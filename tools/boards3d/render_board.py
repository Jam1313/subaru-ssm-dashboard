"""
Turntable frames of an Arduino board or a module for the SensorBox screens (assets/boards3d/<id>/board_NNN.webp + meta.json).

    Blender -b -P tools/boards3d/render_board.py -- --model nano.glb --out app/src/main/assets/boards3d/nano
    Blender -b -P tools/boards3d/render_board.py -- --model mega.obj --out ...          (OBJ from threemf_to_obj.py)
    Blender -b -P tools/boards3d/render_board.py -- --build relay4 --out ...            (made here: see BUILDERS)

A board lies flat (its thinnest side up), is centred, scaled to fit and turned about the vertical axis; the camera
looks down at --elev degrees. The modules no free model exists for (MAX31855, 4/8-channel relays, the MOSFET module,
a DC solid-state relay, a 12 V automotive relay, a potentiometer) are built from boxes and cylinders, in millimetres.
"""
import argparse
import json
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ap = argparse.ArgumentParser()
ap.add_argument("--model")
ap.add_argument("--build")
ap.add_argument("--out", required=True)
ap.add_argument("--frames", type=int, default=32)
ap.add_argument("--size", default="520x340")
ap.add_argument("--elev", type=float, default=38.0)
ap.add_argument("--dist", type=float, default=0.0, help="camera distance; 0 = as close as the model fits at every angle")
ap.add_argument("--front", type=float, default=0.0)
ap.add_argument("--samples", type=int, default=24)
ap.add_argument("--tint", default="", help="R,G,B 0..1: one colour for a model that comes without any (the Pro Micro)")
ap.add_argument("--top", default="", help="board id in pins.json: one orthographic view from above (top.webp + top.json)")
ap.add_argument("--markers", action="store_true", help="with --top: a dot on every pin, to check the pin map")
ap.add_argument("--rot", default="", help="x,y,z degrees to turn the model by instead of laying it flat by itself")
a = ap.parse_args(argv)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


# ---------------------------------------------------------------- the building blocks

def mat(name, rgb, rough=0.5, metal=0.0, emit=0.0):
    m = bpy.data.materials.get(name)
    if m:
        return m
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes["Principled BSDF"]
    p.inputs["Base Color"].default_value = (*rgb, 1)
    p.inputs["Roughness"].default_value = rough
    p.inputs["Metallic"].default_value = metal
    if emit:
        p.inputs["Emission Color"].default_value = (*rgb, 1)
        p.inputs["Emission Strength"].default_value = emit
    return m


def hexrgb(h):
    h = h.lstrip("#")
    return tuple((int(h[i:i + 2], 16) / 255) ** 2.2 for i in (0, 2, 4))  # sRGB → linear


def box(size, loc, m, bevel=0.0):
    """A box of [size] (mm) standing on [loc]'s z."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=(loc[0], loc[1], loc[2] + size[2] / 2))
    o = bpy.context.active_object
    o.scale = size
    bpy.ops.object.transform_apply(scale=True)
    if bevel:
        b = o.modifiers.new("b", "BEVEL")
        b.width = bevel
        b.segments = 3
    o.data.materials.append(m)
    return o


def cyl(r, h, loc, m, verts=32, axis="z"):
    rot = {"z": (0, 0, 0), "x": (0, math.pi / 2, 0), "y": (math.pi / 2, 0, 0)}[axis]
    bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=h, vertices=verts, location=loc, rotation=rot)
    o = bpy.context.active_object
    o.data.materials.append(m)
    return o


def label(s, loc, size, m, rot=0.0):
    c = bpy.data.curves.new("t", "FONT")
    c.body = s
    c.size = size
    c.align_x = "CENTER"
    c.align_y = "CENTER"
    c.extrude = 0.05
    o = bpy.data.objects.new("t_" + s, c)
    scene.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = (0, 0, rot)
    o.data.materials.append(m)
    return o


GOLD = None


def colours():
    global GOLD
    GOLD = mat("gold", hexrgb("C9A227"), 0.25, 1.0)
    return {
        "black": mat("black", hexrgb("161616"), 0.45),
        "white": mat("white", hexrgb("EDEDED"), 0.4),
        "silver": mat("silver", hexrgb("C7CBD1"), 0.2, 1.0),
        "solder": mat("solder", hexrgb("D9D9D9"), 0.3, 1.0),
        "relayblue": mat("relayblue", hexrgb("1E5FD0"), 0.35),
        "pcbblue": mat("pcbblue", hexrgb("1B4F9C"), 0.55),
        "pcbred": mat("pcbred", hexrgb("A0202A"), 0.55),
        "pcbpurple": mat("pcbpurple", hexrgb("4B2A7A"), 0.55),
        "termgreen": mat("termgreen", hexrgb("1F8A3A"), 0.5),
        "termblue": mat("termblue", hexrgb("2B5FB0"), 0.5),
        "ledred": mat("ledred", hexrgb("FF2A2A"), 0.2, 0.0, 2.0),
        "ledgreen": mat("ledgreen", hexrgb("2AFF5A"), 0.2, 0.0, 2.0),
        "txt": mat("txt", hexrgb("F2F2F2"), 0.6),
        "txtdark": mat("txtdark", hexrgb("202020"), 0.6),
    }


def header(n, x0, y, c, pitch=2.54, along="x"):
    """A male pin header of [n] pins, the first at x0."""
    for i in range(n):
        x = x0 + i * pitch
        px, py = (x, y) if along == "x" else (y, x)
        box((2.5, 2.5, 2.5), (px, py, 1.6), c["black"])
        box((0.64, 0.64, 8.5), (px, py, 1.6), GOLD)


def terminal(n, x0, y, c, colour="termgreen", pitch=5.0, along="x"):
    """A screw terminal block of [n] ways."""
    w = n * pitch
    sx, sy = (w, 7.5) if along == "x" else (7.5, w)
    cx, cy = (x0 + w / 2 - pitch / 2, y) if along == "x" else (y, x0 + w / 2 - pitch / 2)
    box((sx, sy, 10), (cx, cy, 1.6), c[colour], bevel=0.4)
    for i in range(n):
        px, py = (x0 + i * pitch, y) if along == "x" else (y, x0 + i * pitch)
        cyl(1.6, 1.2, (px, py, 11.9), c["silver"])
        box((2.6, 0.5, 0.3), (px, py, 12.4), c["black"])


def srd_relay(x, y, c, rot=0.0):
    o = box((19, 15.5, 15.5), (x, y, 1.6), c["relayblue"], bevel=0.6)
    label("SRD-05VDC-SL-C", (x, y + 3, 17.15), 1.6, c["txt"], rot)
    label("10A 250VAC", (x, y - 1, 17.15), 1.5, c["txt"], rot)
    label("SONGLE", (x, y - 5, 17.15), 2.0, c["txt"], rot)
    return o


# ---------------------------------------------------------------- the modules

def build_max31855(c):
    box((20, 31, 1.6), (0, 0, 0), c["pcbpurple"], bevel=0.3)
    header(5, -5.08, -13, c)
    box((5, 4, 1.6), (0, -1, 1.6), c["black"])  # SOIC-8
    for i in range(4):
        box((0.4, 1.2, 0.3), (-1.9 + i * 1.27, -3.4, 1.6), c["solder"])
        box((0.4, 1.2, 0.3), (-1.9 + i * 1.27, 1.4, 1.6), c["solder"])
    terminal(2, -2.5, 10.5, c, "termblue")
    box((1.6, 0.8, 0.6), (5, 4, 1.6), c["black"])
    label("MAX31855", (0, 5.0, 1.65), 2.3, c["txt"])
    label("VCC GND SCK CS SO", (0, -9.6, 1.65), 1.35, c["txt"])
    label("T+   T-", (0, 15.5, 1.65), 1.6, c["txt"])


def build_relays(c, n):
    w = 20.0 * n + 12
    box((w, 55, 1.6), (0, 0, 0), c["pcbblue"], bevel=0.3)
    x0 = -w / 2 + 16
    for i in range(n):
        x = x0 + i * 20
        srd_relay(x, 4, c)
        terminal(3, x - 5, 22.5, c, "termblue")
        box((4.5, 6.5, 3.5), (x, -15, 1.6), c["white"])  # optocoupler
        cyl(1.5, 2.2, (x + 6, -15, 2.7), c["ledred"])
        label(f"K{i + 1}", (x - 6, -9.5, 1.65), 2.0, c["txt"])
    header(n + 2, -w / 2 + 4, -24, c)
    label("DC+ DC- IN1..IN%d" % n, (-w / 2 + 4 + (n + 1) * 1.27, -20.5, 1.65), 1.6, c["txt"])
    box((7.5, 2.5, 2.5), (w / 2 - 8, -24, 1.6), c["black"])  # JD-VCC jumper block
    box((5, 2.6, 2.7), (w / 2 - 9.3, -24, 4.1), c["relayblue"])
    label("JD-VCC VCC GND", (w / 2 - 8, -20.5, 1.65), 1.5, c["txt"])


def build_mosfet(c):
    box((33, 24, 1.6), (0, 0, 0), c["pcbred"], bevel=0.3)
    # TO-220 standing up: black body, metal tab with its hole
    box((10, 4.5, 9), (0, 2, 1.6), c["black"])
    box((10, 1.3, 6.5), (0, 4.9, 10.6), c["silver"])
    cyl(1.8, 1.6, (0, 4.9, 14.5), c["black"], axis="y")
    for dx in (-2.54, 0, 2.54):
        box((0.8, 0.5, 4), (dx, 0.2, 1.6), c["silver"])
    label("IRF520", (0, -0.1, 7), 1.6, c["txt"])
    terminal(2, -13, -7, c, "termblue")
    terminal(2, 8, -7, c, "termblue")
    header(3, -2.54, 13, c, along="y")
    cyl(1.2, 1.8, (-11, 8, 2.5), c["ledred"])
    for y, n in ((-2.54, "SIG"), (0, "VCC"), (2.54, "GND")):
        label(n, (10.2, y, 1.65), 1.3, c["txt"], math.pi)


def build_ssr(c):
    box((58, 45, 4), (0, 0, 0), c["black"], bevel=0.8)
    box((44, 45, 19), (0, 0, 4), c["white"], bevel=1.2)
    for x in (-24, 24):
        for y in (-11, 11):
            box((9, 11, 6), (x, y, 4), c["black"])
            cyl(2.6, 1.0, (x, y, 10.5), c["silver"])
            box((3.6, 0.6, 0.4), (x, y, 11.0), c["black"])
    label("SSR-25 DD", (0, 9, 23.05), 4.0, c["txtdark"])
    label("INPUT 3-32VDC", (0, 2, 23.05), 2.4, c["txtdark"])
    label("LOAD 5-60VDC 25A", (0, -3.5, 23.05), 2.4, c["txtdark"])
    for (x, y, n) in ((-15, 11, "3+"), (-15, -11, "4-"), (15, 11, "1"), (15, -11, "2")):
        label(n, (x, y, 23.05), 3.4, c["txtdark"])
    cyl(1.5, 1.0, (14, -15, 23.2), c["ledred"])
    for x in (-27, 27):
        cyl(2.4, 4.2, (x, 0, 2), c["silver"])


def build_relay12(c):
    box((28, 28, 25), (0, 0, 6), c["black"], bevel=1.6)
    box((28, 8, 3), (0, 18, 18), c["black"], bevel=0.8)  # mounting tab
    cyl(2.6, 3.2, (0, 20, 19.5), c["silver"])
    label("12V 40A", (0, 6, 31.05), 4.0, c["txt"])
    label("BOSCH-TYPE", (0, -1, 31.05), 2.4, c["txt"])
    label("86   85", (0, -6.5, 31.05), 3.0, c["txt"])
    label("30   87", (0, -11.5, 31.05), 3.0, c["txt"])
    # blade terminals underneath: 30 in the middle, 85/86 and 87 round it
    for (x, y, ang) in ((0, -7, 0), (-7, 3, math.pi / 2), (7, 3, math.pi / 2), (0, 10, 0), (0, 0, math.pi / 2)):
        b = box((6.3, 0.8, 9), (x, y, -3), c["silver"])
        b.rotation_euler = (0, 0, ang)
    # the coil's diode shown on a little board beside it, as in the wiring picture
    box((10, 6, 1.0), (24, -6, 0), c["pcbblue"])
    cyl(1.3, 5.2, (24, -6, 2.4), c["black"], axis="x")
    box((0.6, 2.7, 2.7), (25.8, -6, 1.05), c["silver"])


def build_pot(c):
    box((16, 16, 1.0), (0, 0, 0), c["termblue"])
    cyl(8.2, 7, (0, 0, 4.5), c["silver"], 48)
    cyl(3.6, 7, (0, 0, 11.5), GOLD, 32)
    cyl(10, 14, (0, 0, 20), c["black"], 48)  # knob
    box((1.2, 6, 0.6), (0, 6, 27), c["white"])  # its marker
    for dx in (-5, 0, 5):
        box((1.6, 0.6, 8), (dx, -9.5, -6.5), c["silver"])
    label("B10K", (0, -4, 1.05), 2.0, c["txt"])


def build_promicro(c):
    """SparkFun Pro Micro 5 V: red board, 12-pin rows on both long sides, the 32U4 in the middle, micro-USB at the end."""
    box((33, 18, 1.6), (0, 0, 0), c["pcbred"], bevel=0.3).name = "PCB"
    top = ["RAW", "GND", "RST", "VCC", "A3", "A2", "A1", "A0", "15", "14", "16", "10"]
    bottom = ["TXO", "RXI", "GND", "GND", "2", "3", "4", "5", "6", "7", "8", "9"]
    for i in range(12):
        x = -13.97 + i * 2.54
        for y, names in ((7.62, top), (-7.62, bottom)):
            box((2.4, 2.4, 2.5), (x, y, -2.5), c["black"])     # headers underneath, as soldered for a breadboard
            box((0.64, 0.64, 9.0), (x, y, -7.4), GOLD)
            cyl(0.95, 0.15, (x, y, 1.68), c["solder"], 16)
            label(names[i], (x, y - 1.9 if y > 0 else y + 1.9, 1.65), 0.85, c["txt"], math.pi / 2)
    box((7, 7, 1.0), (1.5, 0, 1.6), c["black"])                  # ATmega32U4
    label("ATMEGA32U4", (1.5, 0, 2.65), 0.75, c["txt"])
    box((5.6, 7.6, 2.8), (-14.5, 0, 1.6), c["silver"], bevel=0.3)  # micro-USB
    box((3.2, 1.8, 0.4), (8.8, 2.5, 1.6), c["silver"])            # crystal
    box((2.6, 2.6, 1.2), (8.8, -2.5, 1.6), c["black"])           # regulator
    for y in (3.5, -3.5):
        box((1.6, 0.8, 0.6), (-8.5, y, 1.6), c["ledred" if y > 0 else "ledgreen"])
    label("Pro Micro", (12.5, 0, 1.65), 1.1, c["txt"], math.pi / 2)


def build_coil12kit(c):
    """A 12 V automotive relay driven through a transistor: a small key board (input terminal SIG/GND/+12V, BC337,
    its 1 kOhm base resistor, the 1N4007 across the coil) wired to the relay's 85/86."""
    box((26, 22, 1.6), (-16, 0, 0), c["termgreen"], bevel=0.3)            # the key board
    terminal(3, -5, -26, c, "termblue", along="y")                        # SIG, GND, +12V from the left
    for y, n in ((5, "SIG"), (0, "GND"), (-5, "+12V")):
        label(n, (-20.3, y, 1.65), 1.6, c["txt"])
    # BC337 (TO-92): a flat-faced half cylinder with its three legs
    t = cyl(2.4, 4.6, (-12, 5, 3.9), c["black"], 24)
    box((4.8, 0.6, 4.6), (-12, 3.1, 1.6), c["black"])
    label("BC337", (-12, 8.6, 1.65), 1.3, c["txt"])
    # 1 kOhm base resistor (beige, colour bands)
    cyl(0.9, 6, (-16.5, 1.5, 2.6), mat("res", hexrgb("D6B98C"), 0.5), 16, axis="x")
    for dx, col in ((-1.6, "8B4513"), (-0.6, "111111"), (0.4, "EF4444")):
        cyl(0.95, 0.4, (-16.5 + dx, 1.5, 2.6), mat("b" + col, hexrgb(col), 0.5), 16, axis="x")
    label("1k", (-16.5, -0.6, 1.65), 1.3, c["txt"])
    # 1N4007 across the coil: black body, silver band at the +12 V (86) end
    cyl(1.3, 5.2, (-12, -6, 2.9), c["black"], 16, axis="x")
    cyl(1.35, 0.8, (-10, -6, 2.9), c["silver"], 16, axis="x")
    label("1N4007", (-12, -8.6, 1.65), 1.2, c["txt"])
    # the relay itself, as build_relay12 makes it
    box((28, 28, 25), (16, 0, 6), c["black"], bevel=1.6)
    label("12V 40A", (16, 7, 31.05), 4.0, c["txt"])
    label("86   85", (16, -1, 31.05), 3.4, c["txt"])
    label("30   87", (16, -7.5, 31.05), 3.4, c["txt"])
    # wires from the key board to the coil: 86 (+12 V, red), 85 (to the transistor, orange)
    red = mat("wred", hexrgb("EF4444"), 0.4)
    org = mat("worg", hexrgb("F97316"), 0.4)
    box((10, 1.2, 1.2), (-2, -3, 1.6), red)
    box((10, 1.2, 1.2), (-2, 3, 1.6), org)
    label("к 86", (-2, -5, 2.9), 1.3, c["txt"])
    label("к 85", (-2, 5, 2.9), 1.3, c["txt"])


def screen_text(lines, x0, y_top, size, pitch, m, z):
    for i, t in enumerate(lines):
        label(t, (x0, y_top - i * pitch, z), size, m)


def build_oled(c):
    """A 0.96" SSD1306 module, laid with its 4 pins (GND VCC SCL SDA, top down) at the left and the glass upright."""
    box((27.8, 27.3, 1.2), (0, 0, 0), mat("oledpcb", hexrgb("1D3A6E"), 0.5), bevel=0.3)
    for y in (-11.9, 11.9):
        cyl(1.1, 1.3, (-11.6, y, 0.6), c["black"])
    box((19.3, 26.7, 1.4), (2.0, 0, 1.2), c["black"], bevel=0.2)                        # the glass
    lit = mat("oledlit", hexrgb("7FE3FF"), 0.3, 0.0, 3.0)
    label("МАСЛО", (2.0, 7.5, 2.65), 2.2, lit)
    label("97°C", (2.0, 1.5, 2.65), 4.6, lit)
    label("АКБ 14.1V", (2.0, -6.5, 2.65), 2.0, lit)
    header(4, -3.81, -11.2, c, along="y")
    for i, n in enumerate(("GND", "VCC", "SCL", "SDA")):
        label(n, (-8.8, 3.81 - i * 2.54, 1.25), 0.6, c["txt"])


def build_lcd(c, cols, rows):
    """An HD44780 text LCD with the PCF8574 I2C backpack's 4 pins at the left end."""
    w, h = (80, 36) if rows == 2 else (98, 60)
    box((w, h, 1.6), (0, 0, 0), mat("lcdpcb", hexrgb("1F6B3A"), 0.5), bevel=0.3)
    sw, sh = (71, 24) if rows == 2 else (97 - 10, 40)
    box((sw + 2, sh + 2, 7), (2, 0, 1.6), c["black"], bevel=0.4)                         # the bezel
    glow = mat("lcdscreen", hexrgb("2F6FE0"), 0.35, 0.0, 0.8)
    box((sw - 4, sh - 4, 0.3), (2, 0, 8.6), glow)
    txt = mat("lcdtxt", hexrgb("F2F6FF"), 0.4, 0.0, 1.5)
    lines = ["MASLO   97" + chr(176) + "C", "AKB    14.1V"] if rows == 2 else ["MASLO     97" + chr(176) + "C", "DAVL     3.4BAR", "AFR       14.7", "AKB      14.1V"]
    pitch = (sh - 8) / rows
    screen_text(lines, 2, (rows - 1) * pitch / 2, 3.6 if rows == 2 else 3.4, pitch, txt, 8.95)
    # the backpack's pins, sticking out at the left end
    for i, n in enumerate(("GND", "VCC", "SDA", "SCL")):
        y = 3.81 - i * 2.54
        box((2.5, 2.5, 2.5), (-w / 2 - 1, y, 1.6), c["black"])
        box((5.5, 0.64, 0.64), (-w / 2 - 4.5, y, 2.6), GOLD)
        label(n, (-w / 2 + 3, y, 1.65), 1.2, c["txt"])


def build_lcd_back(c, rows):
    """An HD44780 LCD from behind, as it is wired: the PCF8574 backpack over the 16-pin row, its 4 pins out at the end."""
    w, h = (80, 36) if rows == 2 else (98, 60)
    box((w, h, 1.6), (0, 0, 0), mat("lcdpcb", hexrgb("1F6B3A"), 0.5), bevel=0.3)
    for x, y in ((-w / 2 + 2.5, h / 2 - 2.5), (w / 2 - 2.5, h / 2 - 2.5), (-w / 2 + 2.5, -h / 2 + 2.5), (w / 2 - 2.5, -h / 2 + 2.5)):
        cyl(1.6, 1.8, (x, y, 0.8), c["black"])
    # the black blob of the controller and the row of 16 pads the backpack sits on
    cyl(5 if rows == 2 else 6, 1.0, (8, -4, 2.1), c["black"])
    row_y = h / 2 - 4
    x0 = -w / 2 + 8
    for i in range(16):
        cyl(0.9, 0.4, (x0 + i * 2.54, row_y, 1.8), c["solder"])
    # the backpack: 41 x 19 mm, its header at the left end pointing out past the edge
    bx = x0 + 15 * 2.54 / 2
    by = row_y - 6.5
    box((42, 19, 1.6), (bx, by, 7), mat("bppcb", hexrgb("151518"), 0.5), bevel=0.2)
    box((10, 4.5, 1.6), (bx + 4, by - 2, 8.6), c["black"])                 # PCF8574
    label("PCF8574", (bx + 4, by - 2, 10.25), 1.0, c["txt"])
    box((6.5, 6.5, 3.5), (bx - 9, by + 3, 8.6), mat("pot", hexrgb("2F6FE0"), 0.4))  # contrast trimmer
    cyl(1.8, 0.6, (bx - 9, by + 3, 12.4), c["white"])
    label("+", (bx - 9, by + 3, 12.75), 2.2, c["black"])
    box((2.5, 5, 1.2), (bx + 14, by + 5, 8.6), c["black"])                 # LED jumper
    names = ["GND", "VCC", "SDA", "SCL"]
    ex = bx - 21
    pts = []
    for i, n in enumerate(names):
        y = by + 3.81 - i * 2.54
        box((2.5, 2.5, 2.5), (ex - 1.25, y, 7.5), c["black"])
        box((7, 0.64, 0.64), (ex - 6, y, 8.4), GOLD)
        label(n, (ex + 2.6, y, 8.65), 1.15, c["txt"])
        pts.append([n, ex - 9.3, y])
    label("I2C", (bx + 12, by - 6.5, 8.65), 1.4, c["txt"])
    return pts


def build_tm1637(c):
    """The TM1637 4-digit module: black board, four red digits with a colon, pins CLK DIO VCC GND at the left end."""
    box((42, 24, 1.6), (0, 0, 0), mat("tmpcb", hexrgb("15151A"), 0.5), bevel=0.3)
    box((30, 14, 7), (3, 0, 1.6), mat("tmface", hexrgb("0D0D0F"), 0.4))
    red = mat("seg", hexrgb("FF2A2A"), 0.3, 0.0, 3.0)
    dim = mat("segoff", hexrgb("3A0A0A"), 0.5)
    shapes = {"a": (0, 4.2, 3.6, 0.8), "b": (1.9, 2.1, 0.8, 3.4), "c": (1.9, -2.1, 0.8, 3.4), "d": (0, -4.2, 3.6, 0.8),
              "e": (-1.9, -2.1, 0.8, 3.4), "f": (-1.9, 2.1, 0.8, 3.4), "g": (0, 0, 3.6, 0.8)}
    on = [set("bc"), set("abdeg"), set("abcdg"), set("bcfg")]  # 12:34
    for k in range(4):
        cx = -8.3 + k * 7.2 + (1.0 if k >= 2 else 0)
        for seg, (dx, dy, sx, sy) in shapes.items():
            box((sx, sy, 0.3), (cx + dx, dy, 8.6), red if seg in on[k] else dim)
    for y in (1.6, -1.6):
        cyl(0.5, 0.3, (3, y, 8.75), red, 12)
    header(4, -3.81, -18.5, c, along="y")
    for i, n in enumerate(("CLK", "DIO", "VCC", "GND")):
        label(n, (-14.8, 3.81 - i * 2.54, 1.65), 1.2, c["txt"])


def side_header(names, x, c, label_dx=2.6, size=0.75):
    """A pin header standing on the left edge, first pin on top, its names printed beside it."""
    n = len(names)
    y0 = (n - 1) * 2.54 / 2
    header(n, -y0, x, c, along="y")
    for i, nm in enumerate(names):
        label(nm, (x + label_dx, y0 - i * 2.54, 1.65), size, c["txt"])
    return [[nm, x, y0 - i * 2.54] for i, nm in enumerate(names)]


def glass(w, h, x, y, c, z=1.2, t=1.4):
    box((w, h, t), (x, y, z), c["black"], bevel=0.2)
    return z + t + 0.05


def build_oled13(c):
    """1.3" SH1106: like the 0.96" but bigger, its 4 pins VCC GND SCL SDA."""
    box((36, 34, 1.2), (0, 0, 0), mat("oledpcb", hexrgb("1D3A6E"), 0.5), bevel=0.3)
    zt = glass(25, 33, 3.5, 0, c)
    lit = mat("oledlit", hexrgb("7FE3FF"), 0.3, 0.0, 3.0)
    label("МАСЛО", (3.5, 9, zt), 2.8, lit)
    label("97°C", (3.5, 1.5, zt), 6.0, lit)
    label("АКБ 14.1V", (3.5, -8.5, zt), 2.5, lit)
    side_header(["VCC", "GND", "SCL", "SDA"], -14.5, c, 2.4, 0.7)


def build_oled091(c):
    """0.91" 128x32: a narrow strip, 4 pins at its end."""
    box((38, 12, 1.2), (0, 0, 0), mat("oledpcb", hexrgb("1D3A6E"), 0.5), bevel=0.3)
    zt = glass(28, 10, 4.5, 0, c)
    lit = mat("oledlit", hexrgb("7FE3FF"), 0.3, 0.0, 3.0)
    label("МАСЛО  97°C", (4.5, 0, zt), 2.6, lit)
    side_header(["GND", "VCC", "SCL", "SDA"], -16.5, c, 2.6, 0.6)


def build_oled242(c):
    """2.42" SSD1309: a big white OLED, 7 pins (I2C uses GND VCC SCL SDA and RES)."""
    box((70, 40, 1.4), (0, 0, 0), mat("oledpcb242", hexrgb("15306A"), 0.5), bevel=0.3)
    zt = glass(58, 32, 5, 0, c)
    lit = mat("oledwhite", hexrgb("F2F6FF"), 0.3, 0.0, 2.5)
    label("МАСЛО", (5, 9, zt), 4.0, lit)
    label("97°C", (5, -1, zt), 9.0, lit)
    label("АКБ 14.1V", (5, -11, zt), 3.6, lit)
    side_header(["GND", "VCC", "SCL", "SDA", "RES", "DC", "CS"], -32, c, 2.8, 0.8)


def colour_face(w, h, x, y, zt, big=1.0):
    """What our colour layout looks like: grey names, white numbers, cyan and red bars."""
    grey = mat("cgrey", hexrgb("9CA3AF"), 0.4, 0.0, 1.2)
    white = mat("cwhite", hexrgb("FFFFFF"), 0.4, 0.0, 2.0)
    red = mat("cred", hexrgb("FF3030"), 0.4, 0.0, 2.5)
    cyan = mat("ccyan", hexrgb("2FC8FF"), 0.4, 0.0, 2.5)
    rows = [("МАСЛО", "97°C", white, cyan, 0.7), ("ДАВЛ", "3.4", white, cyan, 0.5), ("ВЫХЛОП", "905", red, red, 0.95), ("АКБ", "14.1", white, cyan, 0.6)]
    rh = h / 4
    for i, (n, v, vc, bc, f) in enumerate(rows):
        yy = y + h / 2 - rh * (i + 0.5)
        label(n, (x - w / 2 + w * 0.22, yy + rh * 0.1, zt), rh * 0.3 * big, grey)
        label(v, (x + w * 0.25, yy + rh * 0.1, zt), rh * 0.5 * big, vc)
        box((w * 0.9 * f, rh * 0.08, 0.1), (x - w * 0.45 + w * 0.45 * f, yy - rh * 0.36, zt - 0.05), bc)


def build_oled151(c):
    """1.5" RGB OLED SSD1351 (Waveshare-like): 7 pins VCC GND DIN CLK CS DC RST."""
    box((44, 38, 1.4), (0, 0, 0), mat("pcbblk", hexrgb("1A1A1E"), 0.5), bevel=0.3)
    zt = glass(29, 29, 4, 0, c)
    colour_face(27, 27, 4, 0, zt)
    side_header(["VCC", "GND", "DIN", "CLK", "CS", "DC", "RST"], -19, c, 2.6, 0.7)


def build_ips13(c):
    """1.3" 240x240 ST7789 IPS: 7 pins GND VCC SCL SDA RES DC BLK, no CS."""
    box((40, 30, 1.4), (0, 0, 0), mat("ipsblue", hexrgb("1E4FA0"), 0.5), bevel=0.3)
    zt = glass(25, 25, 5, 0, c)
    colour_face(23, 23, 5, 0, zt)
    side_header(["GND", "VCC", "SCL", "SDA", "RES", "DC", "BLK"], -16.5, c, 2.6, 0.7)


def build_ips20(c):
    """2" 320x240 ST7789 IPS: 8 pins GND VCC SCL SDA RES DC CS BL."""
    box((58, 38, 1.4), (0, 0, 0), mat("ipsred", hexrgb("A0202A"), 0.5), bevel=0.3)
    zt = glass(43, 33, 5, 0, c)
    colour_face(41, 31, 5, 0, zt, 0.9)
    side_header(["GND", "VCC", "SCL", "SDA", "RES", "DC", "CS", "BL"], -25.5, c, 2.6, 0.7)


BUILDERS = {
    "oled13": build_oled13,
    "oled091": build_oled091,
    "oled242": build_oled242,
    "oled151": build_oled151,
    "ips13": build_ips13,
    "ips20": build_ips20,
    "oled": build_oled,
    "lcd1602": lambda c: build_lcd(c, 16, 2),
    "lcd2004": lambda c: build_lcd(c, 20, 4),
    "lcd1602back": lambda c: build_lcd_back(c, 2),
    "lcd2004back": lambda c: build_lcd_back(c, 4),
    "tm1637": build_tm1637,
    "coil12kit": build_coil12kit,
    "promicro": build_promicro,
    "max31855": build_max31855,
    "relay4": lambda c: build_relays(c, 4),
    "relay8": lambda c: build_relays(c, 8),
    "mosfet": build_mosfet,
    "ssr": build_ssr,
    "relay12": build_relay12,
    "pot": build_pot,
}

# ---------------------------------------------------------------- the model, lying flat

if a.build:
    BUILDERS[a.build](colours())
elif a.model.lower().endswith(".obj"):
    bpy.ops.wm.obj_import(filepath=a.model, up_axis="Z", forward_axis="Y")
else:
    bpy.ops.import_scene.gltf(filepath=a.model)

meshes = [o for o in scene.objects if o.type in ("MESH", "FONT")]
if a.tint:
    t = tuple(float(x) for x in a.tint.split(","))
    m = mat("tint", t, 0.5)
    for o in meshes:
        if o.type == "MESH":
            o.data.materials.clear()
            o.data.materials.append(m)

bpy.context.view_layer.update()
root = bpy.data.objects.new("root", None)
scene.collection.objects.link(root)
for o in scene.objects:
    if o.parent is None and o is not root:
        o.parent = root


def bounds():
    bpy.context.view_layer.update()
    pts = [o.matrix_world @ Vector(v) for o in meshes for v in o.bound_box]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return lo, hi


# a board's thinnest side up (glTF exports come in every orientation)
lo, hi = bounds()
ext = hi - lo
thin = min(range(3), key=lambda i: ext[i])
if a.rot:
    root.rotation_euler = tuple(math.radians(float(x)) for x in a.rot.split(","))
elif a.build is None and thin != 2 and ext[thin] < 0.5 * sorted(ext)[1]:
    root.rotation_euler = (math.pi / 2, 0, 0) if thin == 1 else (0, math.pi / 2, 0)
lo, hi = bounds()
# its long side towards the camera
if (hi - lo).y > (hi - lo).x:
    root.rotation_euler.z += math.pi / 2
lo, hi = bounds()


def top_view():
    """The board from straight above, USB at the left, and where each pin of pins.json lands on the image (0..1)."""
    global lo, hi
    cfg = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "pins.json")))[a.top]
    if "points" in cfg:
        # a module's points are in its builder's coordinates: undo the "long side across" turn made above
        root.rotation_euler.z = 0
        lo, hi = bounds()
    pcb = scene.objects[cfg["pcb"]] if cfg.get("pcb") else None
    if pcb is None:  # a module: its whole body
        cfg.setdefault("length_mm", 1.0)
        cfg.setdefault("usb", "-x")
    pts = [pcb.matrix_world @ Vector(v) for v in pcb.bound_box] if pcb else [lo, hi]
    px0, px1 = min(p.x for p in pts), max(p.x for p in pts)
    py0, pz1 = min(p.y for p in pts), max(p.z for p in pts)
    k = (px1 - px0) / cfg["length_mm"] if pcb else cfg.get("k", 1.0)  # model units per millimetre
    usb_minus = cfg["usb"] == "-x"

    def world(u, v):
        return Vector((px0 + u * k if usb_minus else px1 - u * k, py0 + v * k, pz1))

    pins = []
    for n, x, y in cfg.get("points", []):  # a module's pins, where its builder put them
        pins.append((n, Vector((x * cfg.get("unit", 1.0), y * cfg.get("unit", 1.0), pz1)), 0))
    for r in cfg.get("rows", []):
        if "header" in r:
            # pins spread evenly along a header's own body: needs no measure of the board at all
            hp = [scene.objects[r["header"]].matrix_world @ Vector(v) for v in scene.objects[r["header"]].bound_box]
            hx0, hx1 = min(p.x for p in hp), max(p.x for p in hp)
            hy = (min(p.y for p in hp) + max(p.y for p in hp)) / 2
            hz = max(p.z for p in hp)
            n = len(r["pins"])
            xs = [hx0 + (hx1 - hx0) * (i + 0.5) / n for i in range(n)]
            if not usb_minus:
                xs.reverse()
            for i, nm in enumerate(r["pins"]):
                pins.append((nm, Vector((xs[i], hy, hz)), r["side"]))
            continue
        for i, n in enumerate(r["pins"]):
            pins.append((n, world(r["u0"] + i * 2.54, r["v"]), r["side"]))
    for c in cfg.get("columns", []):
        for j, n in enumerate(c["pins"]):
            pins.append((n, world(c["u"], c["v0"] + j * c["dv"]), c["side"]))
    # USB (a module: its pins) to the left: turn it about its centre
    cx, cy = (lo.x + hi.x) / 2, (lo.y + hi.y) / 2
    turn = math.radians(cfg.get("turn", 0 if usb_minus else 180))
    if turn:
        ct, st_ = math.cos(turn), math.sin(turn)
        root.rotation_euler.z += turn
        # a turn about the origin, moved back round the centre
        root.location = (cx - (ct * cx - st_ * cy), cy - (st_ * cx + ct * cy), 0)
        bpy.context.view_layer.update()
        pins = [(n, Vector((cx + ct * (p.x - cx) - st_ * (p.y - cy), cy + st_ * (p.x - cx) + ct * (p.y - cy), p.z)), s) for n, p, s in pins]
    l2, h2 = bounds()
    if "view" in cfg:
        # a long module (a text LCD): only the end with its pins, so they are not lost in a wide picture
        l2.x, h2.x = max(l2.x, cfg["view"][0]), min(h2.x, cfg["view"][1])
        if len(cfg["view"]) == 4:
            l2.y, h2.y = max(l2.y, cfg["view"][2]), min(h2.y, cfg["view"][3])
    dx, dy = h2.x - l2.x, h2.y - l2.y
    w = 1000
    h = int(round(w * dy / dx))
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = dx
    cam.location = ((l2.x + h2.x) / 2, (l2.y + h2.y) / 2, h2.z + 10 * dx)
    cam.data.clip_end = 100 * dx
    for name, loc, energy in (("key", (-1, -1, 3), 3.0), ("fill", (1, 1, 3), 1.5)):
        sun = bpy.data.objects.new(name, bpy.data.lights.new(name, "SUN"))
        sun.data.energy = energy
        sun.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        scene.collection.objects.link(sun)
    world_ = bpy.data.worlds.new("w")
    scene.world = world_
    world_.use_nodes = True
    world_.node_tree.nodes["Background"].inputs[1].default_value = 0.8
    if a.markers:
        dot = mat("dot", (0.1, 1.0, 0.3), 0.3, 0.0, 6.0)
        for n, p, s in pins:
            bpy.ops.mesh.primitive_uv_sphere_add(radius=0.5 * k, location=p + Vector((0, 0, 3 * k)))
            bpy.context.active_object.data.materials.append(dot)
    engines = [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items]
    scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in engines else "BLENDER_EEVEE"
    scene.eevee.taa_render_samples = 32
    scene.render.film_transparent = True
    scene.render.resolution_x, scene.render.resolution_y = w, h
    scene.render.image_settings.file_format = "WEBP"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.quality = 88
    os.makedirs(a.out, exist_ok=True)
    scene.render.filepath = os.path.join(a.out, "top.webp")
    bpy.ops.render.render(write_still=True)
    out = []
    for n, p, s in pins:
        fx = (p.x - l2.x) / dx
        fy = 1 - (p.y - l2.y) / dy
        side = s if s == 0 else (-1 if fy < 0.5 else 1)
        out.append([n, round(fx, 4), round(fy, 4), side])
    pitch = 2.54 * k / dx  # one pin step as a share of the image width
    json.dump({"size": [w, h], "pitch": round(pitch, 5), "pins": out}, open(os.path.join(a.out, "top.json"), "w"), separators=(",", ":"))
    print("done top", a.out, len(out), "pins")


if a.top:
    top_view()
    sys.exit(0)
centre = (lo + hi) / 2
scale = 2.2 / max(hi - lo)
pivot = bpy.data.objects.new("pivot", None)
scene.collection.objects.link(pivot)
root.parent = pivot
pivot.location = -centre * scale
pivot.scale = (scale,) * 3
turn = bpy.data.objects.new("turn", None)
scene.collection.objects.link(turn)
pivot.parent = turn

# ---------------------------------------------------------------- camera, light, render

w, h = (int(x) for x in a.size.split("x"))
el = math.radians(a.elev)
if a.dist <= 0:
    # the model turning sweeps a cylinder: its radius is half the footprint's diagonal; fit that and its height in
    # both directions of the frame (50 mm lens on a 36 mm sensor, fitted to the width), with a little margin
    ext = (hi - lo) * scale
    r = 0.5 * math.hypot(ext.x, ext.y)
    tan_w = 18 / 50
    tan_h = tan_w * h / w
    need_w = r * 1.08 / tan_w
    need_h = (r * math.sin(el) + ext.z * math.cos(el) / 2) * 1.12 / tan_h
    a.dist = max(need_w, need_h) + r * math.cos(el) * 0.6
cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
scene.collection.objects.link(cam)
scene.camera = cam
cam.data.lens = 50
cam.location = (0, -a.dist * math.cos(el), a.dist * math.sin(el))
cam.rotation_euler = (math.pi / 2 - el, 0, 0)
for name, loc, energy in (("key", (-3, -3, 4), 700), ("fill", (4, -2, 2), 300), ("rim", (0, 4, 3), 500), ("top", (0, 0, 5), 250)):
    l = bpy.data.objects.new(name, bpy.data.lights.new(name, "AREA"))
    l.data.energy = energy
    l.data.size = 3
    l.location = loc
    l.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(l)
world = bpy.data.worlds.new("w")
scene.world = world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[1].default_value = 0.7

engines = [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items]
scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in engines else "BLENDER_EEVEE"
scene.eevee.taa_render_samples = a.samples
scene.render.film_transparent = True
scene.render.resolution_x, scene.render.resolution_y = w, h
scene.render.image_settings.file_format = "WEBP"
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.quality = 80
os.makedirs(a.out, exist_ok=True)
for i in range(a.frames):
    turn.rotation_euler = (0, 0, math.radians(a.front) + 2 * math.pi * i / a.frames)
    scene.render.filepath = os.path.join(a.out, "board_%03d.webp" % i)
    bpy.ops.render.render(write_still=True)
json.dump({"frames": a.frames, "size": [w, h]}, open(os.path.join(a.out, "meta.json"), "w"))
print("done", a.out)
