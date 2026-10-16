#!/bin/sh
# Builds the colour-screen firmware for every board in boards.h (assets: usbscreen/bin/usbscreen-<id>.bin, each one
# image for address 0: bootloader, partitions and the program) and writes bin/manifest.json with the sources' hash,
# so a unit test fails when the sketch changes and the files were not rebuilt.
# Needs arduino-cli with the esp32 core and «GFX Library for Arduino» at the versions below.
set -e
cd "$(dirname "$0")"
CORE=esp32:esp32@3.3.10
GFX="GFX Library for Arduino@1.6.9"
URL=https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core install "$CORE" --additional-urls "$URL" >/dev/null
arduino-cli lib install "$GFX" >/dev/null
FQBN="esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,FlashSize=4M,PartitionScheme=default,PSRAM=disabled"
BOARDS="LILYGO_AMOLED_191 LILYGO_AMOLED_164 LILYGO_TDISPLAY_S3 LILYGO_T4_S3 WS_AMOLED_143 WS_AMOLED_175 WS_AMOLED_164 WS_AMOLED_18 WS_AMOLED_241 WS_LCD_128"
OUT=$(mktemp -d)
cp screen_board.h "$OUT/screen_board.h.keep"
mkdir -p bin
trap 'cp "$OUT/screen_board.h.keep" screen_board.h; rm -r "$OUT"' EXIT
for b in $BOARDS; do
  id=$(echo "$b" | tr 'A-Z' 'a-z')
  printf '// The board this build is for (see boards.h); build-bin.sh writes this file for each one in turn.\n#define SCREEN_%s\n' "$b" > screen_board.h
  arduino-cli compile --fqbn "$FQBN" --output-dir "$OUT/$id" . >/dev/null
  # the merged image is padded with 0xFF to the whole flash: only up to the program's end goes into the app
  python3 -c "import sys; d=open(sys.argv[1],'rb').read().rstrip(b'\\xff'); n=(len(d)+4095)//4096*4096; open(sys.argv[2],'wb').write(d+b'\\xff'*(n-len(d)))" \
    "$OUT/$id/usbscreen.ino.merged.bin" "bin/usbscreen-$id.bin"
  echo "$id $(wc -c < "bin/usbscreen-$id.bin") bytes"
done
cp "$OUT/screen_board.h.keep" screen_board.h
python3 - "$CORE" "$BOARDS" <<'PY'
import hashlib, json, sys
def sha(p): return hashlib.sha256(open(p, 'rb').read()).hexdigest()
src = hashlib.sha256(open("usbscreen.ino", "rb").read() + open("boards.h", "rb").read()).hexdigest()
files = {}
for b in sys.argv[2].split():
    p = f"bin/usbscreen-{b.lower()}.bin"
    files[b.lower()] = {"sha256": sha(p), "bytes": len(open(p, "rb").read())}
json.dump({"src_sha256": src, "core": sys.argv[1], "files": files}, open("bin/manifest.json", "w"), indent=1)
PY
