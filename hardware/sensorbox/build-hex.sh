#!/bin/sh
# Builds the SensorBox firmware for every board the app can flash (assets: sensorbox/hex/*.hex) and writes
# hex/manifest.json with the sketch's hash, so a unit test fails when sensorbox.ino changes and the files were not rebuilt.
# Needs arduino-cli (brew install arduino-cli) and the AVR core pinned below.
set -e
cd "$(dirname "$0")"
CORE=arduino:avr@1.8.6
arduino-cli core install "$CORE" >/dev/null
OUT=$(mktemp -d)
for b in "nano arduino:avr:nano:cpu=atmega328" "uno arduino:avr:uno" "32u4 arduino:avr:leonardo" "mega arduino:avr:mega:cpu=atmega2560"; do
  set -- $b
  arduino-cli compile --fqbn "$2" --output-dir "$OUT/$1" . >/dev/null
  cp "$OUT/$1/sensorbox.ino.hex" "hex/sensorbox-$1.hex" # never the *.with_bootloader.hex
done
python3 - "$CORE" <<'PY'
import hashlib, json, sys, os
def sha(p): return hashlib.sha256(open(p, 'rb').read()).hexdigest()
files = {}
for b in ("nano", "uno", "32u4", "mega"):
    p = f"hex/sensorbox-{b}.hex"
    size = sum(int(l[1:3], 16) for l in open(p) if l.startswith(":") and l[7:9] == "00")
    files[b] = {"sha256": sha(p), "bytes": size}
json.dump({"ino_sha256": sha("sensorbox.ino"), "core": sys.argv[1], "files": files}, open("hex/manifest.json", "w"), indent=1)
print(json.dumps(files))
PY
rm -rf "$OUT"
