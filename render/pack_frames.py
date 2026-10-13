"""
Pack turntable renders from render_turntable.py into an app asset folder (the car_sh format).

  python3 pack_frames.py --car frames/chameleon --white frames/white --mask frames/mask \
      --out ../app/src/main/assets/car_xx [--quality 72] [--mask_scale 0.5] [--margin 8]

  car_NNN.webp    chameleon paint, RGBA
  white_NNN.webp  neutral white body that the app tints to any colour, RGBA
  mask_NNN.webp   body-paint mask (white = paint) on black, RGB; may be smaller than the frames,
                  the app scales it up
  plates.json     per frame: {"front"/"rear": [[x, y] x4]} plate corners (0..1 of the cropped frame)
  meta.json       driveFrame (tail view used while driving), carX (car centre in that frame),
                  exhaustX/Y [, exhaust2X/Y] (tailpipes in that frame)

All frames are cropped to one box: the union of the car's alpha over every frame plus a margin.
--white/--mask may be omitted for a model that only has the paint pass (the SG format).
"""
import argparse
import glob
import json
import os

from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("--car", required=True, help="folder with car_NNN.png, plates.json, render_meta.json")
ap.add_argument("--white", default="")
ap.add_argument("--mask", default="")
ap.add_argument("--out", required=True)
ap.add_argument("--quality", type=int, default=72)
ap.add_argument("--alpha_quality", type=int, default=70)
ap.add_argument("--mask_quality", type=int, default=70)
ap.add_argument("--mask_scale", type=float, default=0.5)
ap.add_argument("--scale", type=float, default=1.0, help="scale every frame (after cropping)")
ap.add_argument("--margin", type=int, default=8)
ap.add_argument("--crop", default="", help="x0,y0,x1,y1 in render pixels instead of the alpha union, e.g. to match "
                "an existing folder (car_sh: 140,102,960,532)")
ap.add_argument("--save_crop", default="", help="write the crop box used to this file (for --crop of a twin)")
ap.add_argument("--exhausts_from", default="", help="meta.json to copy the exhaust spots from (same crop)")
args = ap.parse_args()

frames = sorted(glob.glob(os.path.join(args.car, "car_*.png")))
if not frames:
    raise SystemExit("no car_*.png in " + args.car)
n = len(frames)


def name(folder, i):
    return os.path.join(folder, "car_%03d.png" % i)


# crop box: union of the alpha of every frame of every pass
box = None
for folder in [f for f in (args.car, args.white) if f]:
    for i in range(n):
        b = Image.open(name(folder, i)).getchannel("A").point(lambda a: 255 if a > 4 else 0).getbbox()
        if b:
            box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
W, H = Image.open(frames[0]).size
if args.crop:
    want = tuple(int(v) for v in args.crop.split(","))
    if not (want[0] <= box[0] and want[1] <= box[1] and want[2] >= box[2] and want[3] >= box[3]):
        print("warning: --crop", want, "cuts into the car, alpha box", box)
    box = want
else:
    m = args.margin
    box = (max(0, box[0] - m), max(0, box[1] - m), min(W, box[2] + m), min(H, box[3] + m))
if args.save_crop:
    open(args.save_crop, "w").write(",".join(str(v) for v in box))
cw, ch = box[2] - box[0], box[3] - box[1]
ow, oh = round(cw * args.scale), round(ch * args.scale)
print("crop", box, "->", (ow, oh))
os.makedirs(args.out, exist_ok=True)
for f in glob.glob(os.path.join(args.out, "*_[0-9][0-9][0-9].webp")):
    os.remove(f)


def fit(im):
    im = im.crop(box)
    return im.resize((ow, oh), Image.LANCZOS) if (ow, oh) != (cw, ch) else im


for i in range(n):
    fit(Image.open(name(args.car, i)).convert("RGBA")).save(
        os.path.join(args.out, "car_%03d.webp" % i), "WEBP", quality=args.quality,
        alpha_quality=args.alpha_quality, method=6)
    if args.white:
        fit(Image.open(name(args.white, i)).convert("RGBA")).save(
            os.path.join(args.out, "white_%03d.webp" % i), "WEBP", quality=args.quality,
            alpha_quality=args.alpha_quality, method=6)
    if args.mask:
        mk = Image.open(name(args.mask, i)).convert("RGBA")
        black = Image.new("RGBA", mk.size, (0, 0, 0, 255))
        mk = fit(Image.alpha_composite(black, mk).convert("RGB"))
        if args.mask_scale != 1.0:
            mk = mk.resize((max(1, round(ow * args.mask_scale)), max(1, round(oh * args.mask_scale))), Image.LANCZOS)
        mk.save(os.path.join(args.out, "mask_%03d.webp" % i), "WEBP", quality=args.mask_quality, method=6)


def remap(p):
    return [round((p[0] * W - box[0]) / cw, 5), round((p[1] * H - box[1]) / ch, 5)]


plates = json.load(open(os.path.join(args.car, "plates.json")))
plates = [{k: [remap(p) for p in q] for k, q in fr.items()} for fr in plates]
json.dump(plates, open(os.path.join(args.out, "plates.json"), "w"))

rm_path = os.path.join(args.car, "render_meta.json")
rm = json.load(open(rm_path)) if os.path.exists(rm_path) else {"driveFrame": 43, "exhausts": []}
drive = rm["driveFrame"]
b = Image.open(name(args.car, drive)).getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
meta = {"driveFrame": drive, "carX": round(((b[0] + b[2]) / 2 - box[0]) / cw, 4)}
for k, p in enumerate(rm.get("exhausts", [])[:2]):
    x, y = remap(p)
    meta["exhaust%sX" % ("" if k == 0 else "2")] = round(x, 4)
    meta["exhaust%sY" % ("" if k == 0 else "2")] = round(y, 4)
if args.exhausts_from:
    src = json.load(open(args.exhausts_from))
    meta.update({k: v for k, v in src.items() if k.startswith("exhaust")})
json.dump(meta, open(os.path.join(args.out, "meta.json"), "w"))
total = sum(os.path.getsize(f) for f in glob.glob(os.path.join(args.out, "*")))
print("frames", n, "meta", meta, "size %.2f MB" % (total / 1e6))
