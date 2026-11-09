#!/usr/bin/env bash
# Render the app icons from the jk mark (clients/web/.../web/jk-logo.svg).
#
# shared/core's resources: jumpkick.png (256 px, the Linux notification and desktop-entry icon) and
# jumpkick.ico (16 to 256 px, PNG-compressed, the Windows Start-menu shortcut's icon).
# The dashboard's web root: icon-192.png and icon-512.png (the installed web app's icons, the mark
# as drawn) and icon-maskable-512.png (the borderless mark on a full-bleed canvas of its own panel
# colour, so a platform's circle or squircle mask never cuts into it).
# Run after changing the logo and commit the output; the build does not render them.
#
# Needs ImageMagick with its librsvg delegate (`magick -list format` lists RSVG) and python3.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
svg="$root/clients/web/src/main/resources/web/jk-logo.svg"
out="$root/shared/core/src/main/resources/cc/jumpkick/desktop"
web="$root/clients/web/src/main/resources/web"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

render() {
  magick -background none -density 1536 "$svg" -resize "$1x$1" -strip "png32:$2"
}

mkdir -p "$out"
sizes=(16 20 24 32 40 48 64 256)
for s in "${sizes[@]}"; do
  render "$s" "$work/$s.png"
done
cp "$work/256.png" "$out/jumpkick.png"

# An ICO is a directory of images; every entry here is a whole PNG file, which Windows reads at
# every size.
python3 -I - "$out/jumpkick.ico" "${sizes[@]/#/$work/}" <<'PY'
import struct, sys
out, pngs = sys.argv[1], [p + ".png" for p in sys.argv[2:]]
blobs = [open(p, "rb").read() for p in pngs]
offset = 6 + 16 * len(blobs)
head = struct.pack("<HHH", 0, 1, len(blobs))
entries = b""
for blob in blobs:
    width, height = struct.unpack(">II", blob[16:24])
    entries += struct.pack("<BBBBHHII", width % 256, height % 256, 0, 0, 1, 32, len(blob), offset)
    offset += len(blob)
with open(out, "wb") as f:
    f.write(head + entries + b"".join(blobs))
PY
render 192 "$web/icon-192.png"
render 512 "$web/icon-512.png"
# The maskable mark drops the panel's border, which a mask would cut into, and keeps its glyphs
# inside the 80% safe circle.
grep -v 'stroke="#2a3742"' "$svg" >"$work/borderless.svg"
magick -background none -density 1536 "$work/borderless.svg" -resize 448x448 -strip "png32:$work/maskable-mark.png"
magick -size 512x512 xc:'#0f141b' "$work/maskable-mark.png" -gravity center -composite -strip \
  "png32:$web/icon-maskable-512.png"
echo "rendered $out/jumpkick.{png,ico} and $web/icon-{192,512,maskable-512}.png"
