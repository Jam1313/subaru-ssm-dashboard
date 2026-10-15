"""
Turntable frames of an engine model for the «3D мотор» tab, plus where each sensor is on every frame.

    Blender -b -P tools/engine3d/render_engine.py -- --glb model.glb --out app/src/main/assets/engine_ej \
        --frames 72 --size 900x640 --anchors tools/engine3d/ej257_anchors.json [--hide name,name]
    Blender -b -P tools/engine3d/render_engine.py -- --glb model.glb --list      (objects and their centres)

The model is centred, scaled to fit and turned about the vertical axis; the camera looks down at --elev degrees.
Anchors (JSON: {"coolant": [x, y, z], ...} in the model's own coordinates after import, or {"coolant": "ObjectName"}
for an object's centre) come out per frame in anchors.json as [x, y, visible] with x, y in 0..1 of the image and
visible = 0 when the point is behind a part (a ray from the camera hits something else first).
"""
import argparse
import json
import math
import os
import sys

import bpy
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ap = argparse.ArgumentParser()
ap.add_argument("--glb", required=True)
ap.add_argument("--out")
ap.add_argument("--frames", type=int, default=72)
ap.add_argument("--size", default="900x640")
ap.add_argument("--elev", type=float, default=24.0)
ap.add_argument("--anchors")
ap.add_argument("--hide", default="")
ap.add_argument("--list", action="store_true")
ap.add_argument("--samples", type=int, default=32)
ap.add_argument("--dist", type=float, default=4.4)
a = ap.parse_args(argv)

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=a.glb)
scene = bpy.context.scene
meshes = [o for o in scene.objects if o.type == "MESH"]

if a.list:
    for o in sorted(meshes, key=lambda o: o.name):
        c = sum((o.matrix_world @ Vector(v) for v in o.bound_box), Vector()) / 8
        print("OBJ", o.name, "centre", [round(x, 3) for x in c], "verts", len(o.data.vertices))
    sys.exit(0)

hide = {h.strip().lower() for h in a.hide.split(",") if h.strip()}
for o in meshes:
    if any(h in o.name.lower() for h in hide):
        o.hide_render = True

# everything under one pivot, centred and scaled so the largest side is 2 units
pts = [o.matrix_world @ Vector(v) for o in meshes if not o.hide_render for v in o.bound_box]
lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
centre = (lo + hi) / 2
scale = 2.0 / max(hi - lo)
pivot = bpy.data.objects.new("pivot", None)
scene.collection.objects.link(pivot)
for o in scene.objects:
    if o.parent is None and o is not pivot:
        o.parent = pivot
pivot.location = -centre * scale
pivot.scale = (scale, scale, scale)
turn = bpy.data.objects.new("turn", None)
scene.collection.objects.link(turn)
pivot.parent = turn

# anchors in model coordinates (or an object's centre), as empties under the pivot so they turn with it
anchors = {}
if a.anchors:
    for k, v in json.load(open(a.anchors)).items():
        if isinstance(v, str):
            o = scene.objects[v]
            p = sum((o.matrix_world @ Vector(c) for c in o.bound_box), Vector()) / 8
            p = (p + centre * scale) / scale  # back to model space under the pivot
        else:
            p = Vector(v)
        e = bpy.data.objects.new("anchor_" + k, None)
        scene.collection.objects.link(e)
        e.parent = pivot
        e.location = p
        anchors[k] = e

# camera, lights, world
w, h = (int(x) for x in a.size.split("x"))
cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
scene.collection.objects.link(cam)
scene.camera = cam
cam.data.lens = 50
dist = a.dist
el = math.radians(a.elev)
cam.location = (0, -dist * math.cos(el), dist * math.sin(el))
cam.rotation_euler = (math.pi / 2 - el, 0, 0)
for name, loc, energy in (("key", (-3, -3, 4), 900), ("fill", (4, -2, 2), 350), ("rim", (0, 4, 3), 600)):
    l = bpy.data.objects.new(name, bpy.data.lights.new(name, "AREA"))
    l.data.energy = energy
    l.data.size = 3
    l.location = loc
    l.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(l)
world = bpy.data.worlds.new("w")
scene.world = world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[1].default_value = 0.6

scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE"
scene.eevee.taa_render_samples = a.samples
scene.render.film_transparent = True
scene.render.resolution_x, scene.render.resolution_y = w, h
scene.render.image_settings.file_format = "WEBP"
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.quality = 82
os.makedirs(a.out, exist_ok=True)

out = {"frames": a.frames, "size": [w, h], "elev": a.elev, "anchors": {k: [] for k in anchors}}
depsgraph = bpy.context.evaluated_depsgraph_get()
for i in range(a.frames):
    turn.rotation_euler = (0, 0, 2 * math.pi * i / a.frames)
    bpy.context.view_layer.update()
    depsgraph = bpy.context.evaluated_depsgraph_get()
    for k, e in anchors.items():
        p = e.matrix_world.translation
        v = world_to_camera_view(scene, cam, p)
        # behind a part: something nearer than the point along the camera ray
        d = p - cam.location
        hit, loc, *_ = scene.ray_cast(depsgraph, cam.location, d.normalized(), distance=d.length - 0.03)
        out["anchors"][k].append([round(v.x, 4), round(1 - v.y, 4), 0 if hit else 1])
    scene.render.filepath = os.path.join(a.out, "engine_%03d.webp" % i)
    bpy.ops.render.render(write_still=True)
    print("frame", i + 1, "/", a.frames, flush=True)

json.dump(out, open(os.path.join(a.out, "anchors.json"), "w"), separators=(",", ":"))
print("done", a.out)
