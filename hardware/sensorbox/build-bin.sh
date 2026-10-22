#!/bin/sh
# Builds the SensorBox firmware for the ESP32-S3 (assets: sensorbox/bin/sensorbox-esp32s3.bin, one image for address 0:
# bootloader, partitions and the program) and writes bin/manifest.json with the sketch's hash, so a unit test fails
# when sensorbox.ino changes and the file was not rebuilt. The AVR boards: build-hex.sh.
# Needs arduino-cli with the esp32 core pinned below (the same as the colour screen's).
set -e
cd "$(dirname "$0")"
CORE=esp32:esp32@3.3.10
URL=https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core install "$CORE" --additional-urls "$URL" >/dev/null
# the USB of the chip itself as Serial, like the colour screen: the app flashes it the same way
FQBN="esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,FlashSize=4M,PartitionScheme=default,PSRAM=disabled"
OUT=$(mktemp -d)
trap 'rm -r "$OUT"' EXIT
mkdir -p bin
arduino-cli compile --fqbn "$FQBN" --output-dir "$OUT" . >/dev/null
# the merged image is padded with 0xFF to the whole flash: only up to the program's end goes into the app
python3 -c "import sys; d=open(sys.argv[1],'rb').read().rstrip(b'\\xff'); n=(len(d)+4095)//4096*4096; open(sys.argv[2],'wb').write(d+b'\\xff'*(n-len(d)))" \
  "$OUT/sensorbox.ino.merged.bin" "bin/sensorbox-esp32s3.bin"
python3 - "$CORE" <<'PY'
import hashlib, json, sys
def sha(p): return hashlib.sha256(open(p, 'rb').read()).hexdigest()
p = "bin/sensorbox-esp32s3.bin"
files = {"esp32s3": {"sha256": sha(p), "bytes": len(open(p, "rb").read())}}
json.dump({"ino_sha256": sha("sensorbox.ino"), "core": sys.argv[1], "files": files}, open("bin/manifest.json", "w"), indent=1)
print(json.dumps(files))
PY
