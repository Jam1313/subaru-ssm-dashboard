"""
Blender turntable renderer for the Forester SG5 / SH models.

  Blender -b -P render_turntable.py -- --model car.glb --out frames/ [--list] [--frames 90]
      [--paint name1,name2] [--plate name] [--engine EEVEE|CYCLES] [--size 1100x620] [--still]

--list   only prints materials (name, users, face count) so paint/plate materials can be picked.
Body paint gets an "eggplant chameleon" shader: hue shifts with viewing angle
(deep eggplant -> violet -> green -> bronze on grazing angles), metallic flakes + clear coat.
Frames are rendered with a transparent background; the app draws its own backdrop.
"""
import argparse
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ap = argparse.ArgumentParser()
ap.add_argument("--model", required=True)
ap.add_argument("--out", default="frames")
ap.add_argument("--list", action="store_true")
ap.add_argument("--frames", type=int, default=90)
ap.add_argument("--paint", default="")
ap.add_argument("--plate", default="")
ap.add_argument("--plate_image", default=os.path.join(os.path.dirname(__file__), "plate_sg5.png"))
ap.add_argument("--engine", default="EEVEE")
ap.add_argument("--size", default="1100x620")
ap.add_argument("--samples", type=int, default=64)
ap.add_argument("--still", action="store_true", help="render only frame 0 (preview)")
ap.add_argument("--elev", type=float, default=12.0, help="camera elevation, degrees")
ap.add_argument("--envelope", default="", help="xhalf,yhalf,zmin,zmax: drop meshes outside")
ap.add_argument("--start", type=float, default=-35.0, help="first frame camera azimuth, degrees")
ap.add_argument("--fix", default="", help="model-specific clean-up: sg, sh")
ap.add_argument("--wheelz", type=float, default=0.43, help="sg: wheel centre height in model units")
ap.add_argument("--altfront", action="store_true", help="sg: use the alternative front end parked next to the car")
ap.add_argument("--floor", action="store_true", help="add a shadow-catcher floor (Cycles only)")
ap.add_argument("--paintmode", default="chameleon",
                help="chameleon | white (neutral body the app tints to any colour) | mask (body white, rest black)")
args = ap.parse_args(argv)

# ---------- scene ----------
bpy.ops.wm.read_factory_settings(use_empty=True)
ext = os.path.splitext(args.model)[1].lower()
if ext in (".glb", ".gltf"):
    bpy.ops.import_scene.gltf(filepath=args.model)
elif ext == ".fbx":
    bpy.ops.import_scene.fbx(filepath=args.model)
elif ext == ".obj":
    bpy.ops.wm.obj_import(filepath=args.model)
else:
    raise SystemExit("unsupported model format " + ext)

meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]

# This model (BeamNG-style mod) has spare parts laid out around the car: keep only what sits
# inside the car's envelope (median-centred), drop the rest.
if args.envelope:
    ex, ey, ez0, ez1 = (float(v) for v in args.envelope.split(","))
    removed = []
    for o in list(meshes):
        bb = [o.matrix_world @ Vector(c) for c in o.bound_box]
        if (min(p.x for p in bb) < -ex or max(p.x for p in bb) > ex or
                min(p.y for p in bb) < -ey or max(p.y for p in bb) > ey or
                min(p.z for p in bb) < ez0 or max(p.z for p in bb) > ez1):
            removed.append(o.name)
            bpy.data.objects.remove(o, do_unlink=True)
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    print("REMOVED", len(removed), removed[:40])

if args.list:
    stats = {}
    for o in meshes:
        for p in o.data.polygons:
            m = o.material_slots[p.material_index].material if o.material_slots else None
            n = m.name if m else "<none>"
            stats[n] = stats.get(n, 0) + 1
    for n, c in sorted(stats.items(), key=lambda x: -x[1]):
        print("MAT", c, n)
    raise SystemExit(0)


def plate_material(path):
    m = bpy.data.materials.new("PlateKG")
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(path)
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.45
    return m


def fix_sg_model():
    """Clean-up for the szymonpasterczyk 'Subaru Forester MK2 2002-2008' (BeamNG-style) model."""
    objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}

    def bbox(o):
        bb = [o.matrix_world @ Vector(c) for c in o.bound_box]
        return Vector([min(p[i] for p in bb) for i in range(3)]), Vector([max(p[i] for p in bb) for i in range(3)])

    def drop(o):
        bpy.data.objects.remove(o, do_unlink=True)

    # 1) hood is parked 1.68 m to the side
    hood = objs.get("body_froster-material.007")
    if hood:
        hood.matrix_world.translation.x += 1.68
    keep = {hood}
    # optional alternative front end (bumper/grille/fogs) parked ~2.32 m to the left
    if args.altfront:
        alt = [o for o in objs.values() if bbox(o)[1].x < -1.3 and bbox(o)[0].y < -1.6 and o is not hood
               and bbox(o)[1].z < 1.0 and bbox(o)[0].z > 0.1]
        for o in objs.values():  # remove current front bumper parts that the alt set replaces
            mn, mx = bbox(o)
            if o not in alt and o is not hood and mx.y < -1.6 and mn.z < 0.85 and abs((mn.x + mx.x) / 2) < 0.6 \
                    and (mx.x - mn.x) > 1.2:
                print("REPLACED", o.name)
                drop(o)
        objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
        alt = [o for o in objs.values() if bbox(o)[1].x < -1.3 and bbox(o)[0].y < -1.6
               and bbox(o)[1].z < 1.0 and bbox(o)[0].z > 0.1]
        if alt:
            amn = min(bbox(o)[0].x for o in alt)
            amx = max(bbox(o)[1].x for o in alt)
            dx = -(amn + amx) / 2
            for o in alt:
                o.matrix_world.translation.x += dx
                keep.add(o)
                print("ALTFRONT", o.name)
    # 2) roof light bar / pods, floating front plate, stray parts
    for o in list(objs.values()):
        mn, mx = bbox(o)
        c = (mn + mx) / 2
        name = o.name
        floating_plate = mx.y < -2.25 and mn.z > 1.15
        roof_junk = name in ("engine_mesh-material", "plastic_froster-material.012") or (mn.z > 1.44 and len(o.data.polygons) < 100)
        stray = mx.z < 0.15 or mn.z > 1.75 or abs(c.x) > 1.15 or (mx.z < 0.2 and len(o.data.polygons) < 200)
        lower_plate = mx.y < -2.25 and 0.40 < c.z < 0.62 and abs(c.x) < 0.35
        old_hub = name.startswith("wheel_tire__CLR_4_-material.") or name == "rim-material"
        # "Object_N" meshes are the mod's helper geometry (they draw the cracked-glass lines)
        helper = name.startswith("Object_") or any(
            sl.material and "skelet" in sl.material.name.lower() for sl in o.material_slots)  # roll cage
        if (floating_plate or roof_junk or stray or lower_plate or old_hub or helper) and o not in keep:
            if name.startswith(("wheel_tire__CLR_4_-material", "rim-material")) and len(o.data.polygons) > 1000:
                continue  # wheel templates, handled below
            drop(o)
    objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
    # 2b) empty the cabin (seats, dash, cage, harnesses): anything fully inside the cabin volume that isn't
    # body paint or glass. Windows then look tinted instead of showing the mod's race interior.
    for o in list(objs.values()):
        mn, mx = bbox(o)
        mats = " ".join(sl.material.name.lower() for sl in o.material_slots if sl.material)
        inside = mn.x > -0.86 and mx.x < 0.86 and mn.y > -1.15 and mx.y < 1.75 and mn.z > 0.30 and mx.z < 1.47
        if inside and "body_froster" not in mats and "glass" not in mats:
            drop(o)
    objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
    # 3) four wheels from the single tyre + rim templates
    rubber = bpy.data.materials.new("Rubber")
    rubber.use_nodes = True
    rb = rubber.node_tree.nodes["Principled BSDF"]
    rb.inputs["Base Color"].default_value = (0.012, 0.012, 0.014, 1)
    rb.inputs["Roughness"].default_value = 0.85
    tire = objs.get("wheel_tire__CLR_4_-material")
    rim = objs.get("rim-material.001")
    WHEEL_Z = args.wheelz
    centres = [(-0.745, -1.40), (0.745, -1.40), (-0.745, 1.125), (0.745, 1.125)]  # SG wheelbase 2.525 m
    for tmpl in (tire, rim):
        if not tmpl:
            continue
        mn, mx = bbox(tmpl)
        tc = (mn + mx) / 2
        for k, (x, y) in enumerate(centres):
            o = tmpl.copy()
            o.data = tmpl.data
            bpy.context.scene.collection.objects.link(o)
            o.matrix_world.translation += Vector((x - tc.x, y - tc.y, WHEEL_Z - tc.z))
            if x < 0:  # mirror for the left side, in world space
                o.matrix_world = Matrix.Scale(-1, 4, (1, 0, 0)) @ o.matrix_world
            bpy.context.view_layer.update()
            mn2, mx2 = bbox(o)
            o.matrix_world.translation += Vector((x - (mn2.x + mx2.x) / 2, y - (mn2.y + mx2.y) / 2, WHEEL_Z - (mn2.z + mx2.z) / 2))
            if tmpl is tire:
                for slot in o.material_slots:
                    slot.material = rubber
            bpy.context.view_layer.update()
            mn3, mx3 = bbox(o)
            print("WHEEL", "tire" if tmpl is tire else "rim", k, tuple(round(v, 2) for v in mn3), tuple(round(v, 2) for v in mx3))
        drop(tmpl)
    # 4) clean tinted windscreen
    glass = bpy.data.materials.new("CleanGlass")
    glass.use_nodes = True
    g = glass.node_tree.nodes["Principled BSDF"]
    g.inputs["Base Color"].default_value = (0.008, 0.008, 0.012, 1)
    g.inputs["Roughness"].default_value = 0.04
    g.inputs["Coat Weight"].default_value = 1.0
    # windscreen + the window glass object (its texture is the mod's cracked-glass atlas)
    for gname in ("glass_invisible-material.002", "plastic_froster-material.014"):
        ws = bpy.context.scene.objects.get(gname)
        if ws:
            for slot in ws.material_slots:
                slot.material = glass
    # lime-yellow fog lamp lenses -> clear lens
    fog = bpy.data.materials.new("ClearLens")
    fog.use_nodes = True
    fl = fog.node_tree.nodes["Principled BSDF"]
    fl.inputs["Base Color"].default_value = (0.85, 0.87, 0.9, 1)
    fl.inputs["Roughness"].default_value = 0.05
    fl.inputs["Coat Weight"].default_value = 1.0
    for o in bpy.context.scene.objects:
        if o.type == "MESH":
            for slot in o.material_slots:
                if slot.material and "FFB9FF00" in slot.material.name:
                    slot.material = fog
    # 5) blank plates (front + rear); the app draws the user's plate on them using the exported corners
    for n in ("Material__1-material.001", "Material__2-material.001"):
        o = bpy.context.scene.objects.get(n)
        if o:
            drop(o)
    blank = bpy.data.materials.new("PlateBlank")
    blank.use_nodes = True
    bb = blank.node_tree.nodes["Principled BSDF"]
    bb.inputs["Base Color"].default_value = (0.9, 0.9, 0.9, 1)
    bb.inputs["Roughness"].default_value = 0.5
    for name, loc, rx in (("PlateFront", (0, -2.335, 0.51), 90), ("PlateRear", (0.01, 1.93, 0.795), -90)):
        bpy.ops.mesh.primitive_plane_add(size=1, location=loc, rotation=(math.radians(rx), 0, 0))
        pl = bpy.context.active_object
        pl.name = name
        pl.scale = (0.52, 0.113, 1)
        pl.data.materials.append(blank)

def fix_sh_model():
    """Clean-up for niev's 'Subaru Forester 2009' (SH, XT) model: length along X, front at small X."""
    # The model has no window glass, so the light cabin shows through. Make the cabin near-black instead:
    # the windows then read as tinted, like on the SG frames.
    dark = bpy.data.materials.new("DarkCabin")
    dark.use_nodes = True
    dk = dark.node_tree.nodes["Principled BSDF"]
    dk.inputs["Base Color"].default_value = (0.006, 0.006, 0.008, 1)
    dk.inputs["Roughness"].default_value = 0.7
    cabin = {"Interior_1", "Interior_2", "Interior_3", "Leather_1", "Upholstery", "Carpet", "Wood_Trim",
             "LCD_Screen", "Tinted_Glass"}
    for o in bpy.context.scene.objects:
        if o.type == "MESH":
            for slot in o.material_slots:
                if slot.material and slot.material.name in cabin:
                    slot.material = dark
    # blank plates (the app draws the user's plate on them using the exported corners)
    blank = bpy.data.materials.new("PlateBlank")
    blank.use_nodes = True
    bb = blank.node_tree.nodes["Principled BSDF"]
    bb.inputs["Base Color"].default_value = (0.9, 0.9, 0.9, 1)
    bb.inputs["Roughness"].default_value = 0.5
    # rear: flat tailgate recess at z 0.70-0.80; front: lower bumper, centre line y = 1.0625
    for name, loc, ry in (("PlateFront", (1.528, 1.0625, 0.50), -90), ("PlateRear", (5.962, 1.0625, 0.755), 90)):
        bpy.ops.mesh.primitive_plane_add(size=1, location=loc, rotation=(0, math.radians(ry), 0))
        pl = bpy.context.active_object
        pl.name = name
        pl.scale = (0.113, 0.52, 1)
        pl.data.materials.append(blank)

if args.fix == 'sg':
    fix_sg_model()
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
elif args.fix == 'sh':
    fix_sh_model()
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]

# Normalise: put the car on the ground, centred, longest axis = X.
root = bpy.data.objects.new("CarRoot", None)
bpy.context.scene.collection.objects.link(root)
for o in bpy.context.scene.objects:
    if o.parent is None and o is not root:
        o.parent = root
bpy.context.view_layer.update()
pts = [o.matrix_world @ Vector(c) for o in meshes for c in o.bound_box]
mn = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
mx = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
size = mx - mn
centre = (mn + mx) / 2
root.location = Vector((-centre.x, -centre.y, -mn.z))
if size.y > size.x:  # make the car's length run along X
    root.rotation_euler.z = math.radians(90)
length = max(size.x, size.y)
height = size.z

# ---------- materials ----------
def chameleon_material():
    m = bpy.data.materials.new("EggplantChameleon")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    lw = nt.nodes.new("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.55
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    cr = ramp.color_ramp
    cr.interpolation = "B_SPLINE"
    cr.elements[0].position = 0.0
    cr.elements[0].color = (0.10, 0.012, 0.16, 1)    # eggplant (facing the viewer)
    cr.elements[1].position = 0.38
    cr.elements[1].color = (0.30, 0.03, 0.42, 1)     # saturated violet
    e = cr.elements.new(0.62)
    e.color = (0.02, 0.30, 0.20, 1)                  # emerald shift
    e = cr.elements.new(0.85)
    e.color = (0.55, 0.38, 0.05, 1)                  # gold on grazing angles
    nt.links.new(lw.outputs["Facing"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Metallic"].default_value = 0.85
    bsdf.inputs["Roughness"].default_value = 0.32
    bsdf.inputs["Coat Weight"].default_value = 1.0
    bsdf.inputs["Coat Roughness"].default_value = 0.03
    # metallic flake: tiny noise into roughness
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 900.0
    mapr = nt.nodes.new("ShaderNodeMapRange")
    mapr.inputs["To Min"].default_value = 0.22
    mapr.inputs["To Max"].default_value = 0.42
    nt.links.new(noise.outputs["Fac"], mapr.inputs["Value"])
    nt.links.new(mapr.outputs["Result"], bsdf.inputs["Roughness"])
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    return m




def white_material():
    m = bpy.data.materials.new("WhitePaint")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (0.82, 0.82, 0.82, 1)
    b.inputs["Metallic"].default_value = 0.0
    b.inputs["Roughness"].default_value = 0.28
    b.inputs["Coat Weight"].default_value = 1.0
    b.inputs["Coat Roughness"].default_value = 0.03
    return m

def emission(name, rgb):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*rgb, 1)
    em.inputs["Strength"].default_value = 1.0
    nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
    return m

paint_names = [s.strip().lower() for s in args.paint.split(",") if s.strip()]
paint = {"white": white_material, "mask": lambda: emission("MaskBody", (1, 1, 1))}.get(args.paintmode, chameleon_material)()
mask_other = emission("MaskOther", (0, 0, 0)) if args.paintmode == "mask" else None
plate = plate_material(args.plate_image) if args.plate and os.path.exists(args.plate_image) else None
for o in meshes:
    for slot in o.material_slots:
        if not slot.material:
            continue
        n = slot.material.name.lower()
        if any(p in n for p in paint_names):
            slot.material = paint
        elif plate and args.plate.lower() in n:
            slot.material = plate
        elif mask_other:
            slot.material = mask_other
if mask_other:  # plates and anything without a material are not body either
    for o in bpy.context.scene.objects:
        if o.type == "MESH":
            for slot in o.material_slots:
                if slot.material is None or slot.material.name == "PlateBlank":
                    slot.material = mask_other

# ---------- lights / world ----------
scene = bpy.context.scene
world = bpy.data.worlds.new("World")
scene.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
bg.inputs["Color"].default_value = (0.02, 0.012, 0.05, 1)
bg.inputs["Strength"].default_value = 0.6


def area(name, loc, size_xy, energy, color):
    d = bpy.data.lights.new(name, "AREA")
    d.energy = energy
    d.size = size_xy[0]
    d.shape = "RECTANGLE"
    d.size_y = size_xy[1]
    d.color = color
    o = bpy.data.objects.new(name, d)
    o.location = loc
    scene.collection.objects.link(o)
    direction = Vector((0, 0, height * 0.4)) - Vector(loc)
    o.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    return o


L = length
area("Top", (0, 0, height * 3.2), (L * 1.6, L * 0.8), 900 * L * L / 20, (1.0, 0.97, 1.0))
area("KeyL", (-L * 1.2, -L * 1.1, height * 1.4), (L * 0.9, L * 0.4), 450 * L * L / 20, (0.80, 0.70, 1.0))
area("KeyR", (L * 1.2, -L * 1.0, height * 1.2), (L * 0.9, L * 0.4), 380 * L * L / 20, (0.65, 0.85, 1.0))
area("Rim", (0, L * 1.6, height * 1.8), (L * 1.4, L * 0.4), 600 * L * L / 20, (0.75, 0.45, 1.0))

if args.floor:
    bpy.ops.mesh.primitive_plane_add(size=L * 6, location=(0, 0, 0))
    floor = bpy.context.active_object
    floor.is_shadow_catcher = True

# ---------- camera ----------
cam_d = bpy.data.cameras.new("Cam")
cam_d.lens = 55
cam = bpy.data.objects.new("Cam", cam_d)
scene.collection.objects.link(cam)
scene.camera = cam
dist = L * 2.15
elev = math.radians(args.elev)
target = Vector((0, 0, height * 0.42))

# ---------- render settings ----------
w, h = (int(x) for x in args.size.split("x"))
scene.render.resolution_x = w
scene.render.resolution_y = h
scene.render.film_transparent = True
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
if args.paintmode == "mask":  # exact 0/1 values
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
if args.engine.upper() == "CYCLES":
    scene.render.engine = "CYCLES"
    scene.cycles.samples = args.samples
    scene.cycles.use_denoising = True
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for t in ("METAL", "NONE"):
        try:
            prefs.compute_device_type = t
            prefs.get_devices()
            for dv in prefs.devices:
                dv.use = True
            scene.cycles.device = "GPU" if t != "NONE" else "CPU"
            break
        except Exception:
            continue
else:
    scene.render.engine = "BLENDER_EEVEE_NEXT"
    ee = scene.eevee
    ee.taa_render_samples = args.samples
    try:
        ee.use_raytracing = True
        ee.use_shadows = True
    except Exception:
        pass

os.makedirs(args.out, exist_ok=True)
n = 1 if args.still else args.frames
from bpy_extras.object_utils import world_to_camera_view
import json
plates_out = []


def plate_quads():
    """Screen corners (x right, y down, 0..1) of each plate if it faces the camera and isn't hidden."""
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    res = {}
    for name in ("PlateFront", "PlateRear"):
        o = scene.objects.get(name)
        if not o:
            continue
        mw = o.matrix_world
        corners = [mw @ Vector(c) for c in ((-0.5, -0.5, 0), (0.5, -0.5, 0), (0.5, 0.5, 0), (-0.5, 0.5, 0))]
        centre = sum(corners, Vector()) / 4
        normal = (mw.to_3x3() @ Vector((0, 0, 1))).normalized()
        to_cam = (cam.location - centre).normalized()
        if normal.dot(to_cam) < 0.15:
            continue
        ok, loc, nrm, idx, hit, mat = scene.ray_cast(dg, cam.location, (centre - cam.location).normalized())
        if ok and hit.name != name and (loc - cam.location).length < (centre - cam.location).length - 0.02:
            continue
        pts = []
        for c in corners:
            v = world_to_camera_view(scene, cam, c)
            pts.append([round(v.x, 5), round(1 - v.y, 5)])
        # order: top-left, top-right, bottom-right, bottom-left as seen in the image
        pts.sort(key=lambda p: p[1])
        top = sorted(pts[:2]); bot = sorted(pts[2:])
        res["front" if name == "PlateFront" else "rear"] = [top[0], top[1], bot[1], bot[0]]
    return res

for i in range(n):
    # 0 = 3/4 front view, then rotate the camera around the car
    a = math.radians(args.start) + 2 * math.pi * i / args.frames
    cam.location = Vector((math.cos(a) * dist * math.cos(elev), math.sin(a) * dist * math.cos(elev), target.z + dist * math.sin(elev)))
    cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = os.path.join(args.out, "car_%03d.png" % i)
    plates_out.append(plate_quads())
    bpy.ops.render.render(write_still=True)
    print("FRAME", i + 1, "/", n, flush=True)

with open(os.path.join(args.out, "plates.json"), "w") as f:
    json.dump(plates_out, f)
