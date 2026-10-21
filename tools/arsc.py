#!/usr/bin/env python3
"""Read and write Android binary resource tables (resources.arsc).

The build host is an ARM64 phone and Google's aapt2 is x86_64-only, so the
app's own resource table is produced here. Only what the app needs is
supported: one package, string/drawable style file resources.

Layout follows AOSP frameworks/base/libs/androidfw/ResourceTypes.h.
"""
import struct

RES_TABLE_TYPE = 0x0002
RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

FLAG_COMPLEX = 0x0001
FLAG_PUBLIC = 0x0002

UTF8_FLAG = 1 << 8


# --------------------------------------------------------------------- pool

def build_string_pool(strings, utf8=True):
    """Builds a ResStringPool chunk."""
    data = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(data))
        if utf8:
            b = s.encode("utf-8")
            u16 = len(s.encode("utf-16-le")) // 2
            data += _len8(u16) + _len8(len(b)) + b + b"\x00"
        else:
            b = s.encode("utf-16-le")
            n = len(b) // 2
            if n > 0x7FFF:
                data += struct.pack("<H", 0x8000 | (n >> 16)) + struct.pack("<H", n & 0xFFFF)
            else:
                data += struct.pack("<H", n)
            data += b + b"\x00\x00"
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(data)
    # chunk sizes must be a multiple of 4 (the framework's chunk iterator
    # rejects unaligned chunks)
    if size % 4:
        pad = 4 - (size % 4)
        data += b"\x00" * pad
        size += pad
    out = bytearray()
    out += struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, size)
    out += struct.pack("<IIIII", len(strings), 0, UTF8_FLAG if utf8 else 0, strings_start, 0)
    for off in offsets:
        out += struct.pack("<I", off)
    out += data
    return bytes(out)


def _len8(n):
    if n > 0x7F:
        return bytes([(n >> 8) | 0x80, n & 0xFF])
    return bytes([n])


def read_string_pool(data, off):
    (stype, hsize, size) = struct.unpack_from("<HHI", data, off)
    count, style_count, flags, str_start, style_start = struct.unpack_from("<IIIII", data, off + 8)
    utf8 = bool(flags & UTF8_FLAG)
    offsets = struct.unpack_from("<%dI" % count, data, off + 28)
    strings = []
    for o in offsets:
        p = off + str_start + o
        if utf8:
            n = data[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p]
                p += 1
            m = data[p]
            p += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | data[p]
                p += 1
            strings.append(data[p:p + m].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            strings.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
    return strings, size, utf8


# ------------------------------------------------------------------ config

def default_config(size=64):
    """A ResTable_config with only the mandatory fields set."""
    cfg = bytearray(size)
    struct.pack_into("<I", cfg, 0, size)      # size
    return bytes(cfg)


# ------------------------------------------------------------------ writer

class ResourceTable:
    """Builds a resource table with file resources (drawable/mipmap style)."""

    def __init__(self, package_id=0x7F, package_name="app.drainscope.battery"):
        self.package_id = package_id
        self.package_name = package_name
        self.types = {}          # type_id -> (type_name, [(key_name, file_path)])
        self.values = []         # global value strings (file paths)

    def add_file(self, type_id, type_name, key_name, file_path):
        entry = self.types.setdefault(type_id, (type_name, []))
        entry[1].append((key_name, file_path))
        self.values.append(file_path)

    def build(self):
        value_strings = list(self.values)
        value_index = {s: i for i, s in enumerate(value_strings)}

        type_names = []
        key_names = []
        key_index = {}
        for type_id, (type_name, entries) in sorted(self.types.items()):
            if type_name not in type_names:
                type_names.append(type_name)
            for key_name, _ in entries:
                if key_name not in key_names:
                    key_index[key_name] = len(key_names)
                    key_names.append(key_name)

        global_pool = build_string_pool(value_strings, utf8=True)
        type_pool = build_string_pool(type_names, utf8=True)
        key_pool = build_string_pool(key_names, utf8=True)

        # package chunk
        pkg_header_size = 0x120
        type_strings_off = pkg_header_size
        key_strings_off = type_strings_off + len(type_pool)
        body_off = key_strings_off + len(key_pool)

        type_chunks = bytearray()
        for type_id, (type_name, entries) in sorted(self.types.items()):
            # every type chunk is preceded by its type spec chunk
            type_chunks += self._type_spec_chunk(type_id, len(entries))
            type_chunks += self._type_chunk(type_id, type_name, entries, value_index, key_index)

        pkg_size = body_off + len(type_chunks)
        pkg = bytearray()
        pkg += struct.pack("<HHI", RES_TABLE_PACKAGE_TYPE, pkg_header_size, pkg_size)
        pkg += struct.pack("<I", self.package_id)
        name_bytes = self.package_name.encode("utf-16-le")
        pkg += name_bytes + b"\x00" * (256 - len(name_bytes))
        pkg += struct.pack("<I", type_strings_off)
        pkg += struct.pack("<I", 0)                       # lastPublicType
        pkg += struct.pack("<I", key_strings_off)
        pkg += struct.pack("<I", 0)                       # lastPublicKey
        pkg += struct.pack("<I", 0)                       # typeIdOffset
        pkg += type_pool
        pkg += key_pool
        pkg += type_chunks

        out = bytearray()
        out += struct.pack("<HHI", RES_TABLE_TYPE, 12, 12 + len(global_pool) + len(pkg))
        out += struct.pack("<I", 1)                       # packageCount
        out += global_pool
        out += pkg
        struct.pack_into("<I", out, 4, len(out))
        return bytes(out)

    def _type_spec_chunk(self, type_id, entry_count):
        """ResTable_typeSpec: header + one 4 byte spec entry per resource."""
        header_size = 16
        size = header_size + 4 * entry_count
        chunk = bytearray()
        chunk += struct.pack("<HHI", RES_TABLE_TYPE_SPEC_TYPE, header_size, size)
        chunk += struct.pack("<BBH", type_id, 0, 0)
        chunk += struct.pack("<I", entry_count)
        for i in range(entry_count):
            # ResTable_typeSpecEntry: entryFlags, entryResId
            chunk += struct.pack("<HH", 0, i)
        return bytes(chunk)

    def _type_chunk(self, type_id, type_name, entries, value_index, key_index):
        cfg = default_config(64)
        # header: chunk header (8) + id/flags/reserved (4) + entryCount (4)
        #         + entriesStart (4) + ResTable_config (64)
        header_size = 8 + 4 + 4 + 4 + len(cfg)
        entry_count = len(entries)
        # the offset array follows the header, the entry data after that
        entries_start = header_size + 4 * entry_count

        entry_data = bytearray()
        entry_offsets = []
        for key_name, file_path in entries:
            # offsets are relative to the start of the entry data
            entry_offsets.append(len(entry_data))
            # ResTable_entry: size, flags, key (index into the key string pool)
            entry_data += struct.pack("<HHI", 8, 0, key_index[key_name])
            # Res_value: size, res0, dataType, data
            entry_data += struct.pack("<HBBI", 8, 0, TYPE_STRING, value_index[file_path])

        chunk = bytearray()
        chunk += struct.pack("<HHI", RES_TABLE_TYPE_TYPE, header_size,
                             entries_start + len(entry_data))
        chunk += struct.pack("<BBH", type_id, 0, 0)
        chunk += struct.pack("<I", entry_count)
        chunk += struct.pack("<I", entries_start)
        chunk += cfg
        for off in entry_offsets:
            chunk += struct.pack("<I", off)
        chunk += entry_data
        return bytes(chunk)


# ------------------------------------------------------------------ reader

def dump(path_or_bytes, label=""):
    if isinstance(path_or_bytes, (bytes, bytearray)):
        data = bytes(path_or_bytes)
    else:
        with open(path_or_bytes, "rb") as f:
            data = f.read()
    (stype, hsize, size) = struct.unpack_from("<HHI", data, 0)
    print("%s table: type=0x%04x headerSize=%d size=%d packages=%d" % (
        label, stype, hsize, size, struct.unpack_from("<I", data, 8)[0]))
    off = hsize
    values = []
    while off < len(data):
        (ctype, chsize, csize) = struct.unpack_from("<HHI", data, off)
        if ctype == RES_STRING_POOL_TYPE:
            strings, size, utf8 = read_string_pool(data, off)
            print("  global pool: %d strings utf8=%s" % (len(strings), utf8))
            values = strings
        elif ctype == RES_TABLE_PACKAGE_TYPE:
            pkg_id = struct.unpack_from("<I", data, off + 8)[0]
            name = data[off + 12:off + 12 + 256].decode("utf-16-le", "replace").split("\x00")[0]
            type_off = struct.unpack_from("<I", data, off + 12 + 256)[0]
            key_off = struct.unpack_from("<I", data, off + 12 + 256 + 8)[0]
            print("  package id=0x%02x name=%s typeStrings@%d keyStrings@%d" % (pkg_id, name, type_off, key_off))
            tnames, _, _ = read_string_pool(data, off + type_off)
            knames, _, _ = read_string_pool(data, off + key_off)
            print("    types=%s (%d keys)" % (tnames, len(knames)))
            # walk the type chunks that follow the two pools
            _, tsize, _ = read_string_pool(data, off + type_off)
            _, ksize, _ = read_string_pool(data, off + key_off)
            toff = off + key_off + ksize
            while toff < off + csize:
                (tt, th, ts) = struct.unpack_from("<HHI", data, toff)
                if tt == RES_TABLE_TYPE_TYPE:
                    tid = data[toff + 8]
                    flags = data[toff + 9]
                    ecount = struct.unpack_from("<I", data, toff + 12)[0]
                    estart = struct.unpack_from("<I", data, toff + 16)[0]
                    cfg_size = struct.unpack_from("<I", data, toff + 20)[0]
                    hdr = struct.unpack_from("<H", data, toff + 2)[0]
                    print("    type id=%d flags=%d entries=%d entriesStart=%d configSize=%d headerSize=%d" % (
                        tid, flags, ecount, estart, cfg_size, hdr))
                    # the offset array follows the header, the entries after it
                    arr = toff + hdr
                    for i in range(min(ecount, 3)):
                        eoff = struct.unpack_from("<I", data, arr + 4 * i)[0]
                        epos = estart + eoff
                        esize, eflags, key = struct.unpack_from("<HHI", data, toff + epos)
                        vsize, vres0, vtype, vdata = struct.unpack_from("<HBBI", data, toff + epos + 8)
                        val = values[vdata] if vtype == TYPE_STRING and vdata < len(values) else vdata
                        print("      [%d] key=%s flags=0x%x entrySize=%d type=0x%02x value=%s" % (
                            i, knames[key] if key < len(knames) else key, eflags, esize, vtype, val))
                toff += ts
        off += csize


if __name__ == "__main__":
    import sys
    dump(sys.argv[1], sys.argv[1].split("/")[-1])
