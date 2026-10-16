"""
A 3MF model (zip with XML meshes and per-object colour groups) as OBJ + MTL, so Blender can import it with its colours.

    python3 -I tools/boards3d/threemf_to_obj.py "Arduino Mega 2560.3mf" out/mega.obj

Each object becomes an OBJ group with its build item's transform applied; each triangle gets the colour its p1 index
(or the object's pindex) picks from the object's colour group.
"""
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = {"c": "http://schemas.microsoft.com/3dmanufacturing/core/2015/02",
      "m": "http://schemas.microsoft.com/3dmanufacturing/material/2015/02"}


def matrix(s):
    if not s:
        return None
    v = [float(x) for x in s.split()]
    # 3MF: m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32 (row vectors)
    return v


def apply(m, p):
    if m is None:
        return p
    x, y, z = p
    return (x * m[0] + y * m[3] + z * m[6] + m[9], x * m[1] + y * m[4] + z * m[7] + m[10], x * m[2] + y * m[5] + z * m[8] + m[11])


def main(src, dst):
    xml = zipfile.ZipFile(src).read("3D/3dmodel.model")
    root = ET.fromstring(xml)
    groups = {}
    for g in root.iter("{%s}colorgroup" % NS["m"]):
        groups[g.get("id")] = [c.get("color") for c in g.findall("m:color", NS)]
    objects = {o.get("id"): o for o in root.iter("{%s}object" % NS["c"])}
    items = [(i.get("objectid"), matrix(i.get("transform"))) for i in root.iter("{%s}item" % NS["c"])]
    colours = {}
    out = [f"mtllib {dst.split('/')[-1].rsplit('.', 1)[0]}.mtl"]
    base = 1
    for oid, m in items:
        o = objects[oid]
        mesh = o.find("c:mesh", NS)
        if mesh is None:
            continue
        name = re.sub(r"[^A-Za-z0-9_]+", "_", o.get("name") or oid)
        out.append(f"o {name}")
        verts = [(float(v.get("x")), float(v.get("y")), float(v.get("z"))) for v in mesh.find("c:vertices", NS)]
        for p in verts:
            x, y, z = apply(m, p)
            out.append(f"v {x:.4f} {y:.4f} {z:.4f}")
        pid, pindex = o.get("pid"), int(o.get("pindex") or 0)
        current = None
        for t in mesh.find("c:triangles", NS):
            gid = t.get("pid") or pid
            idx = int(t.get("p1")) if t.get("p1") is not None else pindex
            col = (groups.get(gid) or ["#808080FF"])[min(idx, len(groups.get(gid) or [0]) - 1)]
            mat = "c" + col.lstrip("#")[:6].upper()
            colours[mat] = col
            if mat != current:
                out.append(f"usemtl {mat}")
                current = mat
            a, b, c = (int(t.get(k)) + base for k in ("v1", "v2", "v3"))
            out.append(f"f {a} {b} {c}")
        base += len(verts)
    open(dst, "w").write("\n".join(out) + "\n")
    mtl = []
    for mat, col in colours.items():
        # MTL colours go to Blender as they are (linear): the 3MF ones are sRGB
        r, g, b = ((int(col[i:i + 2], 16) / 255) ** 2.2 for i in (1, 3, 5))
        mtl += [f"newmtl {mat}", f"Kd {r:.4f} {g:.4f} {b:.4f}", "Ns 60", "illum 2", ""]
    open(dst.rsplit(".", 1)[0] + ".mtl", "w").write("\n".join(mtl))
    print("objects", len(items), "colours", len(colours), "->", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
