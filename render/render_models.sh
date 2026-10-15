#!/bin/bash
# Render and pack the SH-format turntables (paint, white, mask passes; 72 frames) of the Sketchfab models.
#   render/render_models.sh WORKDIR [ID ...]      e.g. render/render_models.sh /tmp/frames sk bm
# The .glb files are looked up in $MODELS (default ~/Downloads); output goes to app/src/main/assets/car_<id>.
# PASSES="chameleon white" re-renders only those passes (the mask does not depend on materials other than paint).
set -e
here=$(cd "$(dirname "$0")" && pwd)
work=${1:?work folder}; shift
BLENDER=${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}
MODELS=${MODELS:-$HOME/Downloads}
file_of() {  # (no associative arrays in the macOS bash 3.2)
  case $1 in
    sh|shna) echo subaru_forester_2009.glb ;;
    sg|sgna) echo subaru_forester_mk2_2002-2008.glb ;;
    sk) echo 2020_subaru_forester.glb ;;
    grc) echo subaru_wrx_sti_grc.glb ;;
    bm) echo 2010_subaru_legacy_2.5gt_ts_bm9.glb ;;
    va) echo 2018_subaru_wrx_sti.glb ;;
    gg) echo subaru_wrx_wagon_gg.glb ;;
    gv) echo subaru_impreza_wrx_sti_special_edition.glb ;;
    22b) echo 1998_subaru_impreza_22b_sti.glb ;;
    gc) echo subaru_impreza_coupe_wrx_type_r_sti.glb ;;
    gd) echo generic_toyama_xr_sedan.glb ;;
  esac
}
# the SH model is built along X with the front at -X: its first frame needs the camera on the other side
render_args() { case $1 in sh) echo "--start 145 --tail 0 --paint body_color" ;; shna) echo "--start 145 --tail 0" ;; esac; }
# the atmo SH keeps the car_sh crop, so its frames line up with the turbo ones and its exhaust spots apply;
# the SG5 pair shares one crop (written by the sg pass) for the same reason
pack_args() {
  case $1 in
    shna) echo "--crop 140,102,960,532 --exhausts_from $here/../app/src/main/assets/car_sh/meta.json" ;;
    sg) echo "--save_crop $work/sg.crop" ;;
    sgna) [ -f "$work/sg.crop" ] && echo "--crop $(cat "$work/sg.crop")" ;;
  esac
}
mkdir -p "$work"
ids=${*:-"sg sgna sk grc bm va gg gv 22b gc gd shna"}
for id in $ids; do
  for mode in ${PASSES:-chameleon white mask}; do
    "$BLENDER" -b -P "$here/render_turntable.py" -- --model "$MODELS/$(file_of $id)" --fix "$id" $(render_args $id) \
      --frames 72 --samples ${SAMPLES:-64} --paintmode $mode --out "$work/$id/$mode" > "$work/$id.$mode.log" 2>&1
  done
  out=car_$id; [ $id = sg ] && out=car  # the SG5 keeps its old folder name
  python3 "$here/pack_frames.py" --car "$work/$id/chameleon" --white "$work/$id/white" --mask "$work/$id/mask" \
    --out "$here/../app/src/main/assets/$out" $(pack_args $id) ${PACK_ARGS:-}
done
