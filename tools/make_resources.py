#!/usr/bin/env python3
"""Generates resources.arsc for the app and copies in the launcher icon.

Usage: make_resources.py <out_dir>
Creates:
  <out_dir>/resources.arsc
  <out_dir>/res/drawable/ic_launcher.png

The icon itself is a normal android resource that is committed to the
repository (app/src/main/res/drawable/ic_launcher.png), so the gradle/aapt2
build and this one use the very same file.
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from arsc import ResourceTable          # noqa: E402

PACKAGE_ID = 0x7F
PACKAGE_NAME = "app.drainscope.battery"
# package id 0x7f, type 1 (drawable), first entry
ICON_RES_ID = 0x7F010000
ICON_PATH = "res/drawable/ic_launcher.png"
ICON_SOURCE = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable",
                            "ic_launcher.png")


def main():
    out_dir = sys.argv[1]
    res_dir = os.path.join(out_dir, "res", "drawable")
    os.makedirs(res_dir, exist_ok=True)

    icon = os.path.join(res_dir, "ic_launcher.png")
    if os.path.exists(ICON_SOURCE):
        shutil.copyfile(ICON_SOURCE, icon)
        print("  icon %s (from the repository)" % icon)
    else:
        from make_icon import (new_canvas, draw_background, draw_battery, downsample,
                               write_png, OUT_SIZE, SS)
        buf = new_canvas(OUT_SIZE * SS, OUT_SIZE * SS)
        draw_background(buf, OUT_SIZE * SS, OUT_SIZE * SS)
        draw_battery(buf, OUT_SIZE * SS, OUT_SIZE * SS)
        small, w, h = downsample(buf, OUT_SIZE * SS, OUT_SIZE * SS, SS)
        size = write_png(icon, small, w, h)
        print("  icon %s (%dx%d, %d bytes)" % (icon, w, h, size))

    table = ResourceTable(PACKAGE_ID, PACKAGE_NAME)
    table.add_file(1, "drawable", "ic_launcher", ICON_PATH)
    data = table.build()
    arsc_path = os.path.join(out_dir, "resources.arsc")
    with open(arsc_path, "wb") as f:
        f.write(data)
    print("  resources.arsc (%d bytes, icon id 0x%08x)" % (len(data), ICON_RES_ID))


if __name__ == "__main__":
    main()
