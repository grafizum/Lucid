#!/usr/bin/env python3
"""Trace a glyph bitmap into absolute path data, fitted into a target box.

Used to build the Lucid launcher icon: the letters are typeset with real fonts,
rasterised, traced with potrace, and then scaled into the icon's 108-unit grid.
Produces plain absolute M/L/C/Z path data, which Android vector drawables accept.

    trace_glyph.py glyph.svg --box 30,41,18,22
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile


NUM = re.compile(r"-?\d*\.?\d+(?:e-?\d+)?")


def potrace_path(png_path):
    """Run potrace and return (path_d, viewbox_height)."""
    # potrace only reads 1-bit bitmaps, so threshold the render first.
    with tempfile.TemporaryDirectory() as tmp:
        bitmap = os.path.join(tmp, "glyph.pbm")
        subprocess.run(
            ["magick", png_path, "-threshold", "50%", "pbm:" + bitmap],
            check=True,
        )
        svg = subprocess.run(
            ["potrace", bitmap, "-b", "svg", "-o", "-", "-a", "1.0", "-O", "0.2", "-t", "4"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    height = float(re.search(r'viewBox="0 0 [\d.]+ ([\d.]+)"', svg).group(1))
    d = re.search(r'<path d="([^"]+)"', svg).group(1)
    return d, height


def parse(d):
    """Turn potrace's M/l/c/m/z data into absolute subpaths of segments."""
    # potrace glues commands to numbers ("M747", "c-273", "-300z"), so split
    # letters out rather than trusting whitespace.
    tokens = re.findall(r"[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?", d)
    i = 0
    cx = cy = sx = sy = 0.0
    subpaths, current = [], []
    cmd = None

    def num():
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
        kind = cmd
        if kind in ("M", "m"):
            x, y = num(), num()
            if kind == "m":
                x, y = cx + x, cy + y
            if current:
                subpaths.append(current)
            current = [("M", x, y)]
            cx, cy, sx, sy = x, y, x, y
            cmd = "l" if kind == "m" else "L"
        elif kind in ("l", "L"):
            x, y = num(), num()
            if kind == "l":
                x, y = cx + x, cy + y
            current.append(("L", x, y))
            cx, cy = x, y
        elif kind in ("c", "C"):
            pts = [num() for _ in range(6)]
            if kind == "c":
                pts = [
                    pts[0] + cx, pts[1] + cy,
                    pts[2] + cx, pts[3] + cy,
                    pts[4] + cx, pts[5] + cy,
                ]
            current.append(("C", *pts))
            cx, cy = pts[4], pts[5]
        elif kind in ("z", "Z"):
            current.append(("Z",))
            cx, cy = sx, sy
            cmd = None
        else:
            raise SystemExit("unexpected path command: %r" % kind)

    if current:
        subpaths.append(current)
    return subpaths


def bounds(subpaths, height):
    xs, ys = [], []
    for sub in subpaths:
        for seg in sub:
            if seg[0] == "Z":
                continue
            for j in range(1, len(seg), 2):
                xs.append(seg[j] / 10.0)
                ys.append(height - seg[j + 1] / 10.0)
    return min(xs), min(ys), max(xs), max(ys)


def fit(subpaths, height, box):
    """Scale the traced ink into box=(x, y, w, h), bottom-aligned and centred."""
    x0, y0, x1, y1 = bounds(subpaths, height)
    bx, by, bw, bh = box
    scale = min(bw / (x1 - x0), bh / (y1 - y0))
    dx = bx + (bw - (x1 - x0) * scale) / 2 - x0 * scale
    dy = by + bh - y1 * scale

    out = []
    for sub in subpaths:
        parts = []
        for seg in sub:
            if seg[0] == "Z":
                parts.append("Z")
                continue
            nums = []
            for j in range(1, len(seg), 2):
                px = seg[j] / 10.0 * scale + dx
                py = (height - seg[j + 1] / 10.0) * scale + dy
                nums.append("%.2f" % px)
                nums.append("%.2f" % py)
            parts.append(seg[0] + ",".join(nums))
        out.append("".join(parts))
    return " ".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("png")
    ap.add_argument("--box", required=True, help="x,y,w,h in the 108-unit icon grid")
    ap.add_argument("--suffix", default="")
    args = ap.parse_args()

    box = [float(v) for v in args.box.split(",")]
    d, height = potrace_path(args.png)
    path = fit(parse(d), height, box)
    print("%s%s" % (path, args.suffix))


if __name__ == "__main__":
    sys.exit(main())
