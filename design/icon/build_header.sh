#!/usr/bin/env bash
# Cut the in-app mark — the one beside the "Lucid" wordmark — from the app icon.
#
# It is the icon, not a drawing of it: the same file, cropped to the plate the
# artwork already has, so the header and the home screen can never disagree.
# The plate is a rounded square of radius 224/1024; masking to that silhouette
# drops the white the artwork was rendered on, and with it the drop shadow that
# was cast onto that white (which would read as a pale halo on the app's paper).
#
#   ./build_header.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/lucid-icon-1024.png"
RES="$HERE/../../android/app/src/main/res"

[ -f "$SRC" ] || { echo "missing $SRC" >&2; exit 1; }
command -v magick >/dev/null || { echo "needs ImageMagick (magick)" >&2; exit 1; }

mask=$(mktemp --suffix=.png)
trap 'rm -f "$mask"' EXIT

magick -size 1024x1024 xc:black -fill white -draw "roundrectangle 0,0 1023,1023 224,224" "$mask"

# the header draws it at 56 dp
while read -r bucket px; do
    out="$RES/drawable-$bucket/ic_mark_lens.png"
    mkdir -p "$(dirname "$out")"
    magick "$SRC" "$mask" -alpha off -compose CopyOpacity -composite \
        -filter Lanczos -resize "${px}x${px}" -strip "$out"
    echo "$out  ${px}px"
done <<'EOF'
mdpi    56
hdpi    84
xhdpi   112
xxhdpi  168
xxxhdpi 224
EOF
