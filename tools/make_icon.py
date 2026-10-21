#!/usr/bin/env python3
"""Generates the DrainScope launcher icon as a PNG.

Pure Python (zlib only) - this phone has no imaging libraries. The icon is
drawn at 2x and box-filtered down, which gives clean edges without PIL.
"""
import math
import os
import struct
import sys
import zlib

OUT_SIZE = 432          # final PNG size (108dp at xxhdpi)
SS = 2                  # supersampling factor
S = OUT_SIZE * SS


def new_canvas(w, h, rgba=(0, 0, 0, 0)):
    return bytearray(w * h * 4)


def put(buf, w, x, y, r, g, b, a):
    if a <= 0:
        return
    i = (y * w + x) * 4
    if a >= 255:
        buf[i] = r
        buf[i + 1] = g
        buf[i + 2] = b
        buf[i + 3] = 255
        return
    # source-over
    da = buf[i + 3]
    f = a / 255.0
    buf[i] = int(r * f + buf[i] * (1 - f))
    buf[i + 1] = int(g * f + buf[i + 1] * (1 - f))
    buf[i + 2] = int(b * f + buf[i + 2] * (1 - f))
    buf[i + 3] = min(255, int(a + da * (1 - f)))


def rounded_rect_coverage(px, py, x0, y0, x1, y1, r, stroke=False, stroke_w=0.0):
    """Returns 1.0 inside the rounded rect, 0.0 outside (no antialiasing)."""
    if px < x0 or px > x1 or py < y0 or py > y1:
        return 0.0
    cx = min(max(px, x0 + r), x1 - r)
    cy = min(max(py, y0 + r), y1 - r)
    if (px - cx) ** 2 + (py - cy) ** 2 > r * r:
        return 0.0
    if stroke:
        # inside the stroke band?
        if (x0 + stroke_w <= px <= x1 - stroke_w) and (y0 + stroke_w <= py <= y1 - stroke_w):
            return 0.0
        if px < x0 + stroke_w or px > x1 - stroke_w or py < y0 + stroke_w or py > y1 - stroke_w:
            return 1.0
        return 0.0
    return 1.0


def draw_background(buf, w, h):
    """Rounded square with a diagonal teal -> blue gradient."""
    radius = w * 0.235
    c1 = (0x0E, 0xA5, 0x8E)   # teal
    c2 = (0x1B, 0x4F, 0x9C)   # deep blue
    for y in range(h):
        for x in range(w):
            px = x + 0.5
            py = y + 0.5
            if not rounded_rect_coverage(px, py, 0, 0, w - 1, h - 1, radius):
                continue
            t = (px / w * 0.45 + py / h * 0.55)
            r = int(c1[0] + (c2[0] - c1[0]) * t)
            g = int(c1[1] + (c2[1] - c1[1]) * t)
            b = int(c1[2] + (c2[2] - c1[2]) * t)
            put(buf, w, x, y, r, g, b, 255)


def draw_battery(buf, w, h):
    """White battery outline with a green level and a white drain arrow."""
    # battery body
    bx0, bx1 = w * 0.255, w * 0.745
    by0, by1 = h * 0.285, h * 0.725
    radius = w * 0.075
    sw = w * 0.058

    # cap
    capx0, capx1 = w * 0.435, w * 0.565
    capy0, capy1 = h * 0.225, h * 0.300

    for y in range(int(h * 0.20), int(h * 0.75)):
        for x in range(int(w * 0.22), int(w * 0.78)):
            px = x + 0.5
            py = y + 0.5
            body = rounded_rect_coverage(px, py, bx0, by0, bx1, by1, radius, stroke=True, stroke_w=sw)
            cap = 0.0
            if capy0 <= py <= capy1:
                if capx0 <= px <= capx1:
                    cap = 1.0
                elif capx0 - sw * 0.35 < px < capx1 + sw * 0.35:
                    cap = 1.0
            if body or cap:
                put(buf, w, x, y, 0xFF, 0xFF, 0xFF, 255)

    # level fill (green) inside the body
    inner_x0, inner_x1 = bx0 + sw, bx1 - sw
    inner_y0, inner_y1 = by0 + sw, by1 - sw
    level_top = inner_y1 - (inner_y1 - inner_y0) * 0.58
    for y in range(int(level_top), int(inner_y1)):
        for x in range(int(inner_x0), int(inner_x1)):
            px = x + 0.5
            py = y + 0.5
            if rounded_rect_coverage(px, py, inner_x0, inner_y0, inner_x1, inner_y1, radius * 0.6):
                t = (py - level_top) / max(1.0, (inner_y1 - level_top))
                r = int(0x4C + (0xFF - 0x4C) * t)
                g = int(0xD9 + (0xC7 - 0xD9) * t)
                b = int(0x64 + (0x3B - 0x64) * t)
                put(buf, w, x, y, r, g, b, 255)

    # drain arrow (white) pointing down, overlapping the level
    ax = w * 0.5
    shaft_w = w * 0.052
    shaft_top = h * 0.345
    shaft_bot = h * 0.545
    head_top = h * 0.505
    head_half = w * 0.105
    for y in range(int(shaft_top), int(h * 0.66)):
        for x in range(int(ax - head_half - 4), int(ax + head_half + 4)):
            px = x + 0.5
            py = y + 0.5
            inside = False
            if shaft_top <= py <= shaft_bot:
                if abs(px - ax) <= shaft_w:
                    inside = True
            elif head_top <= py <= h * 0.655:
                t = (py - head_top) / (h * 0.655 - head_top)
                if abs(px - ax) <= head_half * (1 - t):
                    inside = True
            if inside:
                put(buf, w, x, y, 0xFF, 0xFF, 0xFF, 255)


def downsample(buf, w, h, factor):
    ow, oh = w // factor, h // factor
    out = bytearray(ow * oh * 4)
    n = factor * factor
    for y in range(oh):
        for x in range(ow):
            r = g = b = a = 0
            for dy in range(factor):
                row = (y * factor + dy) * w
                for dx in range(factor):
                    i = (row + x * factor + dx) * 4
                    r += buf[i]
                    g += buf[i + 1]
                    b += buf[i + 2]
                    a += buf[i + 3]
            o = (y * ow + x) * 4
            out[o] = r // n
            out[o + 1] = g // n
            out[o + 2] = b // n
            out[o + 3] = a // n
    return out, ow, oh


def write_png(path, buf, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += buf[y * w * 4:(y + 1) * w * 4]

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "ic_launcher.png"
    buf = new_canvas(S, S)
    draw_background(buf, S, S)
    draw_battery(buf, S, S)
    small, w, h = downsample(buf, S, S, SS)
    size = write_png(out, small, w, h)
    print("wrote %s (%dx%d, %d bytes)" % (out, w, h, size))


if __name__ == "__main__":
    main()
