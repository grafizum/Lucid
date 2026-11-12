#!/usr/bin/env python3
"""Draw the Lucid mark once and emit every surface that uses it.

The geometry is authored in a 48-unit grid centred on the glass, so the
launcher art, the in-app mark beside the wordmark and the themed silhouette
cannot drift apart. Proportions and colours are read off the reference icon.

    python3 build_mark.py

writes:
    lens-mark.svg             the 1024 icon (squircle plate + magnifier)
    lens-foreground.svg       the adaptive foreground in the 108dp layer
    ../../android/app/src/main/res/drawable/ic_launcher_monochrome.xml

NOTE: no launcher art and no in-app mark comes from this drawing any more.
Both are the rendered icon file now — lucid-icon-1024.png, used exactly as
produced. build_launcher.sh cuts the adaptive layers from it, and build_header.sh
cuts the mark beside the wordmark from it (res/drawable-*/ic_mark_lens.png).
The SVGs below are kept as reference only: writing them back over those assets
would replace the artwork with a redraw of it.
"""
import math

HERE = __file__.rsplit("/", 1)[0]

# ── the mark, in the 48-unit grid ───────────────────────────────────────────
CX = CY = 18.0               # centre of the glass
R_OUT = 12.28                # rim outer radius
R_GLASS = 8.72               # glass radius (the rim is the ring between)
HANDLE_W = 5.25
HANDLE_FROM = 11.9           # the handle starts inside the rim
HANDLE_TO = 25.6

# the two glyphs are monoline strokes with round terminals, not filled
# outlines: that is what makes them read at 54 dp beside the wordmark.
STROKE = 1.22
A_APEX = (14.44, 14.20)
A_FOOT_L = (12.13, 20.30)
A_FOOT_R = (16.97, 20.30)
A_BAR = ((12.85, 18.20), (16.15, 18.20))

W_DOT = ((21.05, 14.72), (21.05, 16.18))
W_BAR = ((18.00, 16.83), (24.10, 16.83))
W_LEG_L = ((22.40, 17.35), (18.40, 21.90))
W_LEG_R = ((19.70, 17.35), (23.65, 21.85))

# ── palette, sampled from the reference ────────────────────────────────────
PAPER_A, PAPER_B = "#FAF4E7", "#ECE1CA"
RIM_HI, RIM_MID, RIM_LOW = "#FDF8EC", "#F2E6CE", "#DBC9A8"
HANDLE_A, HANDLE_B = "#F7EEDB", "#DFCFB1"
GLASS_HI, GLASS_LO = "#332E26", "#211E19"
CREAM = "#F6EFE3"
SAND = "#D2B88E"


def polar(r, deg, cx=CX, cy=CY):
    """Angle in screen degrees clockwise from +x: 0 is right, 90 is down."""
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arc(r, a0, a1, cx=CX, cy=CY):
    """An arc from a0 to a1 degrees, travelling clockwise on screen."""
    x0, y0 = polar(r, a0, cx, cy)
    x1, y1 = polar(r, a1, cx, cy)
    span = (a1 - a0) % 360
    large = 1 if span > 180 else 0
    return f"M{x0:.3f},{y0:.3f} A{r:.3f},{r:.3f} 0 {large},1 {x1:.3f},{y1:.3f}"


def circle(r, cx=CX, cy=CY):
    return (f"M{cx - r:.3f},{cy:.3f} a{r:.3f},{r:.3f} 0 1,0 {2 * r:.3f},0 "
            f"a{r:.3f},{r:.3f} 0 1,0 {-2 * r:.3f},0")


def ring_path():
    return f"{circle(R_OUT)} {circle(R_GLASS)}"


def handle_points():
    fx, fy = polar(HANDLE_FROM, 45)
    tx, ty = polar(HANDLE_TO, 45)
    return fx, fy, tx, ty


def pt(p):
    return f"{p[0]:.3f},{p[1]:.3f}"


# one glyph = a list of open strokes, drawn as round-capped lines
A_STROKES = [
    (A_APEX, A_FOOT_L),
    (A_APEX, A_FOOT_R),
    (A_BAR[0], A_BAR[1]),
]
# the bar is drawn last, over the legs: that is what leaves 文's little
# triangular counter under the bar instead of a 大-shaped peak
W_STROKES = [
    W_DOT,
    W_LEG_L,
    W_LEG_R,
    W_BAR,
]


def strokes_path(strokes):
    return " ".join(f"M{pt(a)} L{pt(b)}" for a, b in strokes)


def svg_strokes(strokes, color, indent="  ", width=STROKE):
    return (f'{indent}<path d="{strokes_path(strokes)}" stroke="{color}"\n'
            f'{indent}      stroke-width="{width}" stroke-linecap="round"'
            f' fill="none"/>')


# ── the 1024 icon ───────────────────────────────────────────────────────────
S = 21.2                     # 48 units -> 1024 canvas
OX, OY = 473.0, 470.0        # the glass centre on the canvas


def P(p):
    return OX + (p[0] - CX) * S, OY + (p[1] - CY) * S


def R(v):
    return v * S


def canvas_strokes(strokes, color, width=STROKE):
    d = " ".join(f"M{pt(P(a))} L{pt(P(b))}" for a, b in strokes)
    return (f'    <path d="{d}" stroke="{color}" stroke-width="{R(width):.2f}"\n'
            f'          stroke-linecap="round" fill="none"/>')


def squircle(size=1024, radius=224):
    """A squircle plate: four corner arcs, iOS-style corner ratio."""
    k = radius
    s = size
    return (f"M{k},0 L{s - k},0 A{k},{k} 0 0,1 {s},{k} "
            f"L{s},{s - k} A{k},{k} 0 0,1 {s - k},{s} "
            f"L{k},{s} A{k},{k} 0 0,1 0,{s - k} L0,{k} A{k},{k} 0 0,1 {k},0 Z")


def icon_svg():
    hx0, hy0, hx1, hy1 = handle_points()
    p0, p1 = P((hx0, hy0)), P((hx1, hy1))
    rx, ry = R(R_GLASS), R(R_GLASS)

    # screen angles: 0 is right, 90 is down, 180 left, 270 up
    gloss = arc(R(7.05), 202, 242, OX, OY)
    gloss2 = arc(R(7.75), 206, 234, OX, OY)
    bevel_dark = arc(R(8.3), 186, 268, OX, OY)
    bevel_light = arc(R(8.26), 12, 78, OX, OY)

    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
<!-- Lucid — generated by design/icon/build_mark.py, do not hand-edit. -->
<defs>
  <linearGradient id="paper" x1="0.1" y1="0" x2="0.9" y2="1">
    <stop offset="0" stop-color="{PAPER_A}"/><stop offset="1" stop-color="{PAPER_B}"/>
  </linearGradient>
  <linearGradient id="rim" x1="0.1" y1="0.02" x2="0.86" y2="0.98">
    <stop offset="0" stop-color="{RIM_HI}"/><stop offset="0.42" stop-color="{RIM_MID}"/>
    <stop offset="1" stop-color="{RIM_LOW}"/>
  </linearGradient>
  <linearGradient id="handle" x1="0" y1="0" x2="0.9" y2="1">
    <stop offset="0" stop-color="{HANDLE_A}"/><stop offset="1" stop-color="{HANDLE_B}"/>
  </linearGradient>
  <radialGradient id="glass" cx="0.36" cy="0.3" r="0.88">
    <stop offset="0" stop-color="{GLASS_HI}"/><stop offset="0.66" stop-color="#282420"/>
    <stop offset="1" stop-color="{GLASS_LO}"/>
  </radialGradient>
  <filter id="lift" x="-30%" y="-30%" width="160%" height="160%">
    <feDropShadow dx="0" dy="18" stdDeviation="34" flood-color="#4A3A22" flood-opacity="0.26"/>
  </filter>
</defs>

<path d="{squircle()}" fill="url(#paper)"/>

<g filter="url(#lift)">
  <path d="M{p0[0]:.2f},{p0[1]:.2f} L{p1[0]:.2f},{p1[1]:.2f}" stroke="url(#handle)"
        stroke-width="{R(HANDLE_W):.2f}" stroke-linecap="round" fill="none"/>
  <path d="{ring_path()}" fill="url(#rim)" fill-rule="evenodd"
        transform="translate({OX},{OY}) scale({S}) translate({-CX},{-CY})"/>
  <circle cx="{OX}" cy="{OY}" r="{rx:.2f}" fill="url(#glass)"/>
  <path d="{bevel_dark}" stroke="#191712" stroke-opacity="0.55" stroke-width="{R(0.62):.2f}"
        fill="none" stroke-linecap="round"/>
  <path d="{bevel_light}" stroke="#FFF8E9" stroke-opacity="0.22" stroke-width="{R(0.4):.2f}"
        fill="none" stroke-linecap="round"/>
  <path d="{gloss}" stroke="#FBF4E4" stroke-opacity="0.11" stroke-width="{R(0.55):.2f}"
        fill="none" stroke-linecap="round"/>
  <path d="{gloss2}" stroke="#FBF4E4" stroke-opacity="0.28" stroke-width="{R(0.32):.2f}"
        fill="none" stroke-linecap="round"/>
{canvas_strokes(A_STROKES, CREAM)}
{canvas_strokes(W_STROKES, SAND)}
</g>
</svg>
'''


# ── the adaptive foreground, in the 108dp layer ─────────────────────────────
FS = 1.30
FOX = FOY = 51.5


def foreground_svg():
    hx0, hy0, hx1, hy1 = handle_points()

    def T():
        return f"translate({FOX},{FOY}) scale({FS}) translate({-CX},{-CY})"

    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
<!-- Lucid — adaptive foreground, generated by build_mark.py. -->
<defs>
  <linearGradient id="rim" x1="0.1" y1="0.02" x2="0.86" y2="0.98">
    <stop offset="0" stop-color="{RIM_HI}"/><stop offset="0.42" stop-color="{RIM_MID}"/>
    <stop offset="1" stop-color="{RIM_LOW}"/>
  </linearGradient>
  <linearGradient id="handle" x1="0" y1="0" x2="0.9" y2="1">
    <stop offset="0" stop-color="{HANDLE_A}"/><stop offset="1" stop-color="{HANDLE_B}"/>
  </linearGradient>
  <radialGradient id="glass" cx="0.36" cy="0.3" r="0.88">
    <stop offset="0" stop-color="{GLASS_HI}"/><stop offset="0.66" stop-color="#282420"/>
    <stop offset="1" stop-color="{GLASS_LO}"/>
  </radialGradient>
  <filter id="lift" x="-40%" y="-40%" width="180%" height="180%">
    <feDropShadow dx="0" dy="2.0" stdDeviation="3.2" flood-color="#4A3A22" flood-opacity="0.34"/>
  </filter>
</defs>
<g filter="url(#lift)" transform="{T()}">
  <path d="M{hx0:.3f},{hy0:.3f} L{hx1:.3f},{hy1:.3f}" stroke="url(#handle)"
        stroke-width="{HANDLE_W}" stroke-linecap="round" fill="none"/>
  <path d="{ring_path()}" fill="url(#rim)" fill-rule="evenodd"/>
  <circle cx="{CX}" cy="{CY}" r="{R_GLASS}" fill="url(#glass)"/>
  <path d="{arc(8.3, 186, 268)}" stroke="#191712" stroke-opacity="0.55" stroke-width="0.62"
        fill="none" stroke-linecap="round"/>
  <path d="{arc(8.26, 16, 74)}" stroke="#FFF8E9" stroke-opacity="0.32" stroke-width="0.40"
        fill="none" stroke-linecap="round"/>
  <path d="{arc(7.05, 202, 242)}" stroke="#FBF4E4" stroke-opacity="0.15" stroke-width="0.55"
        fill="none" stroke-linecap="round"/>
  <path d="{arc(7.75, 206, 234)}" stroke="#FBF4E4" stroke-opacity="0.28" stroke-width="0.32"
        fill="none" stroke-linecap="round"/>
{svg_strokes(A_STROKES, CREAM)}
{svg_strokes(W_STROKES, SAND)}
</g>
</svg>
'''


# ── the themed (Android 13+) layer: the silhouette, tinted by the system ────
def monochrome_xml():
    hx0, hy0, hx1, hy1 = handle_points()
    tx = 51.5 - CX * FS
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Themed-icon layer, generated by design/icon/build_mark.py: the magnifier in
     silhouette, so the system can tint it from the wallpaper. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <group
        android:scaleX="{FS}"
        android:scaleY="{FS}"
        android:translateX="{tx:.2f}"
        android:translateY="{tx:.2f}">
        <path
            android:pathData="M{hx0:.3f},{hy0:.3f} L{hx1:.3f},{hy1:.3f}"
            android:strokeColor="#FFFFFF"
            android:strokeWidth="{HANDLE_W}"
            android:strokeLineCap="round" />
        <path
            android:fillColor="#FFFFFF"
            android:fillType="evenOdd"
            android:pathData="{ring_path()}" />
    </group>
</vector>
'''


open(f"{HERE}/lens-mark.svg", "w").write(icon_svg())
open(f"{HERE}/lens-foreground.svg", "w").write(foreground_svg())
open(f"{HERE}/../../android/app/src/main/res/drawable/ic_launcher_monochrome.xml", "w").write(monochrome_xml())
print("wrote lens-mark.svg, lens-foreground.svg, ic_launcher_monochrome.xml")
