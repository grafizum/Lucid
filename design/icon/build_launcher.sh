#!/usr/bin/env bash
# Turn the finished 1024 icon render into the adaptive-icon foreground layers.
#
# The launcher art is a single raster file, not a drawing: whatever ships as
# lucid-icon-1024.png is exactly what the home screen shows. This script only
# places that file inside the 108 dp adaptive layer, so no vector redraw can
# drift from it.
#
# The artwork is a full-bleed squircle, and an adaptive layer is 108 dp with
# only the middle 72 dp guaranteed visible. At exactly 72 dp the launcher's
# mask would graze the artwork's own rounded corners (white, in the file), so
# the art is placed at 78 dp instead: its corners always fall outside the mask,
# the paper runs past the edge, and the composition is untouched.
#
#   ./build_launcher.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/lucid-icon-1024.png"
RES="$HERE/../../android/app/src/main/res"

[ -f "$SRC" ] || { echo "missing $SRC" >&2; exit 1; }
command -v magick >/dev/null || { echo "needs ImageMagick (magick)" >&2; exit 1; }

# density : adaptive-layer size in px (108 dp) : artwork size in px (78 dp)
while read -r bucket layer art; do
    out="$RES/mipmap-$bucket/ic_launcher_foreground.png"
    mkdir -p "$(dirname "$out")"
    magick "$SRC" \
        -filter Lanczos -resize "${art}x${art}" \
        -background none -gravity center -extent "${layer}x${layer}" \
        -strip "$out"
    echo "$out  ${layer}px layer, ${art}px art"
done <<'EOF'
mdpi    108 78
hdpi    162 117
xhdpi   216 156
xxhdpi  324 234
xxxhdpi 432 312
EOF
