#!/usr/bin/env python3
"""Compile an AndroidManifest.xml (text) into binary AXML.

The build host for this project is an ARM64 Android phone, and Google's
aapt2 is only published for x86_64 Linux, so the manifest is compiled here
instead. Output is verified by re-parsing it with axml_read.py.

Usage: axml_write.py <manifest.xml> <out.axml>
"""
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
FRAMEWORK_IDS = os.path.join(HERE, "framework_ids.txt")

ANDROID_NS = "http://schemas.android.com/apk/res/android"

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP = 0x0180
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

# Resource ids that our own resource table (tools/arsc.py) assigns. The
# manifest is written for aapt2, which resolves @drawable/... itself; the
# on-device build has to use the raw id instead.
DRAWABLE_IDS = {
    "@drawable/ic_launcher": 0x7F010000,
}

NO_INDEX = 0xFFFFFFFF

# Attributes that carry integers / booleans in the framework schema.
INT_ATTRS = {
    "versionCode", "versionCodeMajor", "minSdkVersion", "targetSdkVersion",
    "maxSdkVersion", "compileSdkVersion", "compileSdkVersionCodename",
    "priority", "protectionLevel", "installLocation", "targetActivity",
    "sharedUserId", "process", "lineNumber",
    "launchMode", "windowSoftInputMode", "configChanges", "screenOrientation",
    "screenSize", "smallestScreenSize", "uiMode", "colorMode", "documentLaunchMode",
    "targetSandboxVersion", "targetActivityVersion", "maxAspectRatio",
}
BOOL_ATTRS = {
    "exported", "enabled", "allowBackup", "debuggable", "multiprocess",
    "hasCode", "persistent", "directBootAware", "grantUriPermissions",
    "usesCleartextTraffic", "required", "noHistory", "singleUser",
    "excludeFromRecents", "allowClearUserData", "killAfterRestore",
    "largeHeap", "supportsRtl", "requestLegacyExternalStorage",
    "preserveLegacyExternalStorage",
}
HEX_ATTRS = {"protectionLevel"}


def load_framework_ids(path):
    ids = {}
    if not path:
        return ids
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                m = re.match(r"\s*public static final int (\w+) = (\d+);", line)
                if m:
                    ids.setdefault(m.group(1), int(m.group(2)))
    except OSError:
        pass
    return ids


class Pool:
    """UTF-8 string pool builder."""

    def __init__(self):
        self.items = []      # list of str
        self.index = {}

    def add(self, s):
        if s in self.index:
            return self.index[s]
        idx = len(self.items)
        self.items.append(s)
        self.index[s] = idx
        return idx

    def build(self, style_count=0):
        # UTF-16 pool, exactly like aapt2 writes binary manifests.
        data = bytearray()
        offsets = []
        for s in self.items:
            offsets.append(len(data))
            units = s.encode("utf-16-le")
            n = len(units) // 2
            if n > 0x7FFF:
                data += struct.pack("<H", 0x8000 | (n >> 16))
                data += struct.pack("<H", n & 0xFFFF)
            else:
                data += struct.pack("<H", n)
            data += units
            data += b"\x00\x00"
        header_size = 28
        strings_start = header_size + 4 * len(self.items)
        size = strings_start + len(data)
        # the framework rejects string pools whose size is not 4 byte aligned
        if size % 4:
            pad = 4 - (size % 4)
            data += b"\x00" * pad
            size += pad
        out = bytearray()
        out += struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, size)
        out += struct.pack("<IIIII", len(self.items), style_count, 0, strings_start, 0)
        for off in offsets:
            out += struct.pack("<I", off)
        out += data
        return bytes(out)


def parse_value(raw, attr_name, fw_ids):
    """Return (data_type, data, raw_index_or_-1) for an attribute value."""
    if raw is None:
        return TYPE_INT_BOOLEAN, 0, -1
    v = raw.strip()
    if v in DRAWABLE_IDS:
        return TYPE_REFERENCE, DRAWABLE_IDS[v], -1
    if v.startswith("@0x") or v.startswith("@0X"):
        # raw resource id (e.g. our own drawable in the hand-built resource table)
        try:
            return TYPE_REFERENCE, int(v[1:], 16), -1
        except ValueError:
            pass
    if v.startswith("@android:"):
        # framework resource reference
        name = v.split("/", 1)[1] if "/" in v else v[1:]
        name = name.replace("style.", "")
        rid = fw_ids.get(name)
        if rid is None:
            rid = 0
        return TYPE_REFERENCE, rid, -1
    if attr_name in INT_ATTRS:
        try:
            return TYPE_INT_DEC, int(v, 0), -1
        except ValueError:
            pass
    if attr_name in BOOL_ATTRS:
        if v.lower() in ("true", "false"):
            # aapt2 encodes booleans as 0xFFFFFFFF / 0x00000000
            return TYPE_INT_BOOLEAN, (0xFFFFFFFF if v.lower() == "true" else 0), -1
    if v.lower() == "true":
        return TYPE_INT_BOOLEAN, 0xFFFFFFFF, -1
    if v.lower() == "false":
        return TYPE_INT_BOOLEAN, 0x00000000, -1
    if re.fullmatch(r"0x[0-9a-fA-F]+", v):
        return TYPE_INT_HEX, int(v, 16), -1
    if attr_name in HEX_ATTRS:
        try:
            return TYPE_INT_HEX, int(v, 0), -1
        except ValueError:
            pass
    return TYPE_STRING, raw, -1  # string index resolved by caller


def compile_manifest(xml_path, fw_ids):
    tree = ET.parse(xml_path)
    root = tree.getroot()

    pool = Pool()
    # The resource map is parallel to the string pool from index 0. aapt2 puts
    # the attributes that have a framework resource id first, ordered by
    # ascending id, and leaves attributes without an id (e.g. "package")
    # outside that range. The framework relies on this layout.
    mapped = []
    unmapped = []
    for el in root.iter():
        for a in el.attrib:
            name = a.split("}")[-1]
            if name in mapped or name in unmapped:
                continue
            if fw_ids.get(name, 0) != 0:
                mapped.append(name)
            else:
                unmapped.append(name)
    mapped.sort(key=lambda n: (fw_ids.get(n, 0), n))
    unmapped.sort()
    attr_names = mapped + unmapped
    for name in attr_names:
        pool.add(name)
    n_attr_names = len(mapped)

    # Remaining strings: element names, namespace, values.
    elem_names = []
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag not in elem_names:
            elem_names.append(tag)
    for tag in elem_names:
        pool.add(tag)
    pool.add("android")
    pool.add(ANDROID_NS)

    # attribute string values
    value_index = {}

    def value_idx(s):
        if s not in value_index:
            value_index[s] = pool.add(s)
        return value_index[s]

    for el in root.iter():
        for a, raw in el.attrib.items():
            name = a.split("}")[-1]
            if raw.startswith("@"):
                continue
            if name in INT_ATTRS or name in BOOL_ATTRS or raw.strip().lower() in ("true", "false"):
                continue
            value_idx(raw)

    res_map = [fw_ids.get(name, 0) for name in mapped]

    out = bytearray()
    out += struct.pack("<HHI", RES_XML_TYPE, 8, 0)  # size patched later
    out += pool.build()
    if res_map:
        rm_size = 8 + 4 * len(res_map)
        out += struct.pack("<HHI", RES_XML_RESOURCE_MAP, 8, rm_size)
        for v in res_map:
            out += struct.pack("<I", v)

    def emit_start_ns(prefix_idx, uri_idx):
        out.extend(struct.pack("<HHI", RES_XML_START_NAMESPACE, 16, 24))
        out.extend(struct.pack("<IIII", 1, NO_INDEX, prefix_idx, uri_idx))

    def emit_end_ns(prefix_idx, uri_idx):
        out.extend(struct.pack("<HHI", RES_XML_END_NAMESPACE, 16, 24))
        out.extend(struct.pack("<IIII", 1, NO_INDEX, prefix_idx, uri_idx))

    def emit_start(el):
        tag = el.tag.split("}")[-1]
        name_idx = pool.index[tag]
        attrs = []
        for a, raw in el.attrib.items():
            aname = a.split("}")[-1]
            is_android = a.startswith("{") and a.split("}")[0][1:] == ANDROID_NS
            a_name_idx = pool.index[aname]
            dtype, dval, raw_idx = parse_value(raw, aname, fw_ids)
            if dtype == TYPE_STRING:
                raw_idx = value_idx(dval)
                dval = raw_idx
            elif raw_idx != -1:
                raw_idx = value_idx(raw)
            a_ns_idx = pool.index[ANDROID_NS] if is_android else NO_INDEX
            attrs.append((a_ns_idx, a_name_idx, raw_idx, dtype, dval))
        # aapt2 orders the attributes of an element by their string pool index
        attrs.sort(key=lambda t: t[1])
        out.extend(struct.pack("<HHI", RES_XML_START_ELEMENT, 16, 36 + 20 * len(attrs)))
        out.extend(struct.pack("<II", 1, NO_INDEX))
        out.extend(struct.pack("<II", NO_INDEX, name_idx))
        out.extend(struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0))
        for (ns, nm, raw_idx, dtype, dval) in attrs:
            out.extend(struct.pack("<III", ns, nm, raw_idx if raw_idx != -1 else NO_INDEX))
            out.extend(struct.pack("<HBBI", 8, 0, dtype, dval))

    def emit_end(el):
        tag = el.tag.split("}")[-1]
        out.extend(struct.pack("<HHI", RES_XML_END_ELEMENT, 16, 24))
        out.extend(struct.pack("<IIII", 1, NO_INDEX, NO_INDEX, pool.index[tag]))

    def walk(el):
        emit_start(el)
        for child in el:
            walk(child)
        emit_end(el)

    emit_start_ns(pool.index["android"], pool.index[ANDROID_NS])
    walk(root)
    emit_end_ns(pool.index["android"], pool.index[ANDROID_NS])

    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    xml_path, out_path = sys.argv[1], sys.argv[2]
    fw = load_framework_ids(FRAMEWORK_IDS)
    data = compile_manifest(xml_path, fw)
    with open(out_path, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes, %d framework ids)" % (out_path, len(data), len(fw)))


if __name__ == "__main__":
    main()
