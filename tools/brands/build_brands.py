#!/usr/bin/env python3
"""
Builds the offline car make tables and brand logos for the "other car" mode:

  app/src/main/assets/brands/wmi.json    WMI (VIN chars 1-3) -> brand slug, 2-char fallbacks, regions, makes
  app/src/main/assets/brands/logos.json  bundled logo slugs + pinned remote URLs for the rest
  app/src/main/assets/brands/logos/<slug>.webp

Sources and licenses:
  - NHTSA vPIC API, https://vpic.nhtsa.dot.gov/api/ (US government work, public domain):
    GetWMIsForManufacturer/<q> and GetMakeForManufacturer/<q> (partial name search, one query per letter/digit).
  - Wikipedia "Vehicle identification number" (en) and "Идентификационный номер транспортного средства" (ru),
    text CC BY-SA 4.0: used only to check the hand-made supplement below and to settle vPIC WMIs whose
    manufacturer has several makes. No text from it is shipped.
  - Logos: https://github.com/filippofilip95/car-logos-dataset (MIT, metadata and processing),
    pinned to LOGOS_COMMIT. The logos themselves are trademarks of their respective owners.

Downloads go to a separate cache dir (--dl) and are treated as data only. Run with `python3 -I`.
Needs Pillow (with WebP) for the logos; without it only the tables are written.

  python3 -I tools/brands/build_brands.py --dl /path/to/brands-dl [--refresh] [--offline]
"""

import argparse
import collections
import json
import os
import re
import string
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "brands")

VPIC = "https://vpic.nhtsa.dot.gov/api/vehicles/"
WIKI_EN = "https://en.wikipedia.org/w/index.php?title=Vehicle_identification_number&action=raw"
WIKI_RU = ("https://ru.wikipedia.org/w/index.php?title="
           + urllib.parse.quote("Идентификационный_номер_транспортного_средства") + "&action=raw")
LOGOS_REPO = "filippofilip95/car-logos-dataset"
LOGOS_COMMIT = "bb2d661f28ce617dba8a51bdfc2069a3381a23b0"  # 2025-09-12
LOGOS_RAW = "https://raw.githubusercontent.com/%s/%s/logos/" % (LOGOS_REPO, LOGOS_COMMIT)
UA = "subaru-ssm-dashboard brands builder (offline table generator)"

# vPIC vehicle types worth knowing in a car app; trailers, motorcycles, golf carts are left out
TYPES_3 = {"Passenger Car", "Multipurpose Passenger Vehicle (MPV)", "Truck", "Bus", "Incomplete Vehicle"}
TYPES_6 = {"Passenger Car", "Multipurpose Passenger Vehicle (MPV)", "Truck"}  # small makers (xx9 + chars 12-14)

# vPIC make name -> display name, where plain title case is wrong
NAMES = {
    "BMW": "BMW", "GMC": "GMC", "MINI": "Mini", "MERCEDES-BENZ": "Mercedes-Benz", "MERCEDES BENZ": "Mercedes-Benz",
    "ROLLS-ROYCE": "Rolls-Royce", "ROLLS ROYCE": "Rolls-Royce", "RAM": "Ram", "KIA": "Kia", "MG": "MG", "DS": "DS",
    "BYD": "BYD", "JAC": "JAC", "UAZ": "UAZ", "GAZ": "GAZ", "KAMAZ": "KAMAZ", "ZAZ": "ZAZ", "SEAT": "SEAT",
    "FIAT": "Fiat", "CITROEN": "Citroën", "SKODA": "Škoda", "LAND ROVER": "Land Rover", "ALFA ROMEO": "Alfa Romeo",
    "MCLAREN": "McLaren", "DELOREAN": "DeLorean", "SSANGYONG": "SsangYong", "AMC": "AMC", "VPG": "VPG",
    "AM GENERAL": "AM General", "LEVC": "LEVC", "FAW": "FAW", "BAIC": "BAIC", "GAC": "GAC", "SAIC": "SAIC",
    "DFSK": "DFSK", "JMC": "JMC", "MAZ": "MAZ", "ZIL": "ZIL", "PAZ": "PAZ", "KAVZ": "KAvZ", "LIAZ": "LiAZ",
    "SMART": "Smart", "LYNK & CO": "Lynk & Co", "HUMMER": "Hummer", "SRT": "SRT", "IC BUS": "IC Bus",
    "TVR": "TVR", "DAF": "DAF", "MAN": "MAN", "UD": "UD", "XPENG": "XPeng", "NIO": "NIO", "VINFAST": "VinFast",
    "GEO": "Geo", "AUDI": "Audi", "INEOS": "Ineos", "KTM": "KTM", "BXR": "BXR",
}

# vPIC/other make names that are one brand for us
SAME = {"MERCEDES BENZ": "MERCEDES-BENZ", "ROLLS ROYCE": "ROLLS-ROYCE", "LANDROVER": "LAND ROVER",
        "SCION": "SCION", "STERLING TRUCK": "STERLING"}

# brand origin country (ISO 3166-1 alpha-2); others take the most common vPIC country of their WMIs
COUNTRY = {
    "subaru": "JP", "toyota": "JP", "lexus": "JP", "nissan": "JP", "infiniti": "JP", "datsun": "JP", "honda": "JP",
    "acura": "JP", "mazda": "JP", "mitsubishi": "JP", "suzuki": "JP", "isuzu": "JP", "daihatsu": "JP", "scion": "JP",
    "hino": "JP", "hyundai": "KR", "kia": "KR", "genesis": "KR", "daewoo": "KR", "ssangyong": "KR",
    "volkswagen": "DE", "audi": "DE", "bmw": "DE", "mercedes-benz": "DE", "porsche": "DE", "opel": "DE",
    "smart": "DE", "maybach": "DE", "man": "DE", "renault": "FR", "peugeot": "FR", "citroen": "FR", "ds": "FR",
    "bugatti": "FR", "alpine": "FR", "skoda": "CZ", "seat": "ES", "cupra": "ES", "fiat": "IT", "alfa-romeo": "IT",
    "lancia": "IT", "ferrari": "IT", "lamborghini": "IT", "maserati": "IT", "iveco": "IT", "volvo": "SE",
    "saab": "SE", "scania": "SE", "polestar": "SE", "land-rover": "GB", "jaguar": "GB", "mini": "GB",
    "bentley": "GB", "rolls-royce": "GB", "aston-martin": "GB", "mclaren": "GB", "lotus": "GB", "mg": "GB",
    "vauxhall": "GB", "ford": "US", "chevrolet": "US", "cadillac": "US", "buick": "US", "gmc": "US",
    "chrysler": "US", "dodge": "US", "jeep": "US", "ram": "US", "lincoln": "US", "tesla": "US", "hummer": "US",
    "pontiac": "US", "saturn": "US", "oldsmobile": "US", "mercury": "US", "plymouth": "US", "rivian": "US",
    "lucid": "US", "lada": "RU", "gaz": "RU", "uaz": "RU", "kamaz": "RU", "moskvich": "RU", "zil": "RU",
    "paz": "RU", "kavz": "RU", "liaz": "RU", "ural": "RU", "zaz": "UA", "maz": "BY", "chery": "CN",
    "geely": "CN", "haval": "CN", "great-wall": "CN", "changan": "CN", "byd": "CN", "jac": "CN", "lifan": "CN",
    "dongfeng": "CN", "faw": "CN", "baic": "CN", "gac": "CN", "exeed": "CN", "omoda": "CN", "zeekr": "CN",
    "li-auto": "CN", "wuling": "CN", "zotye": "CN", "haima": "CN", "brilliance": "CN", "foton": "CN",
    "ravon": "UZ", "tata": "IN", "mahindra": "IN", "proton": "MY", "perodua": "MY", "vinfast": "VN",
}

# vPIC country names -> ISO codes (only those that can be the most common one of a make)
ISO = {
    "UNITED STATES (USA)": "US", "MEXICO": "MX", "CANADA": "CA", "CHINA": "CN", "JAPAN": "JP", "GERMANY": "DE",
    "UNITED KINGDOM (UK)": "GB", "ENGLAND": "GB", "ITALY": "IT", "HONG KONG": "HK", "SOUTH KOREA": "KR",
    "TAIWAN": "TW", "AUSTRALIA": "AU", "FRANCE": "FR", "SOUTH AFRICA": "ZA", "VIETNAM": "VN", "BRAZIL": "BR",
    "SWEDEN": "SE", "SPAIN": "ES", "THAILAND": "TH", "TURKEY": "TR", "ARGENTINA": "AR", "NEW ZEALAND": "NZ",
    "INDIA": "IN", "BELGIUM": "BE", "DENMARK": "DK", "NETHERLANDS": "NL", "RUSSIA": "RU", "POLAND": "PL",
    "SWITZERLAND": "CH", "NORWAY": "NO", "AUSTRIA": "AT", "CZECH REPUBLIC": "CZ", "UKRAINE": "UA",
    "FINLAND": "FI", "MALAYSIA": "MY", "INDONESIA": "ID", "PHILIPPINES": "PH", "CROATIA": "HR",
}

# Hand-made supplement: WMIs vPIC lacks or names oddly, mostly Russian/CIS assembly and Chinese makers.
# (wmi, slug, display name, keywords one of which must appear in the Wikipedia row for that WMI)
SUPPLEMENT = [
    ("XTA", "lada", "Lada", ["avtovaz", "автоваз"]),
    ("X7D", "lada", "Lada", ["рослада"]),
    ("Z0X", "lada", "Lada", ["лада"]),
    ("XTH", "gaz", "GAZ", ["gaz", "газ"]),
    ("X96", "gaz", "GAZ", ["gaz", "газ"]),
    ("X78", "gaz", "GAZ", ["газ"]),
    ("XTT", "uaz", "UAZ", ["uaz", "уаз"]),
    ("XU1", "uaz", "UAZ", ["uaz"]),
    ("XTC", "kamaz", "KAMAZ", ["kamaz", "камаз"]),
    ("XTB", "moskvich", "Moskvich", ["azlk", "москвич"]),
    ("XTZ", "zil", "ZIL", ["зил"]),
    ("X1M", "paz", "PAZ", ["paz", "паз"]),
    ("X1E", "kavz", "KAvZ", ["kavz", "кавз"]),
    ("XTY", "liaz", "LiAZ", ["liaz", "лиаз"]),
    ("X1P", "ural", "Ural", ["урал"]),
    ("XTM", "maz", "MAZ", ["маз"]),
    ("Y3M", "maz", "MAZ", ["маз"]),
    ("XTE", "zaz", "ZAZ", ["zaz", "заз"]),
    ("Y6D", "zaz", "ZAZ", ["zaz", "заз"]),
    ("X7L", "renault", "Renault", ["renault"]),
    ("XW7", "toyota", "Toyota", ["toyota", "тойота"]),
    ("Z8N", "nissan", "Nissan", ["nissan", "ниссан"]),
    ("X9F", "ford", "Ford", ["ford", "форд"]),
    ("Z6F", "ford", "Ford", ["ford", "форд"]),
    ("XUU", "chevrolet", "Chevrolet", ["chevrolet"]),
    ("X9L", "chevrolet", "Chevrolet", ["gm-avtovaz", "gm-автоваз"]),  # Chevrolet Niva
    ("X4X", "bmw", "BMW", ["bmw"]),
    ("Y6U", "skoda", "Škoda", ["škoda"]),
    ("Y4K", "geely", "Geely", ["belgee", "белджи"]),
    ("X9X", "great-wall", "Great Wall", ["great wall"]),
    ("XLB", "volvo", "Volvo", ["volvo"]),
    ("Z9M", "mercedes-benz", "Mercedes-Benz", ["mercedes"]),
    ("X8U", "scania", "Scania", ["скания"]),
    ("L6T", "geely", "Geely", ["geely"]),
    ("LB3", "geely", "Geely", ["geely"]),
    ("LC0", "byd", "BYD", ["byd"]),
    ("LGX", "byd", "BYD", ["byd"]),
    ("LPE", "byd", "BYD", ["byd"]),
    ("LGW", "great-wall", "Great Wall", ["great wall"]),  # Haval, Tank, Wey and the pickups alike
    ("LVV", "chery", "Chery", ["chery"]),  # Chery and its sub-brands (Omoda, Jaecoo, ...)
    ("LVM", "chery", "Chery", ["chery"]),
    ("PRH", "chery", "Chery", ["chery"]),
    ("LS5", "changan", "Changan", ["changan"]),  # Changan Suzuki also used it, China only, until 2018
    ("LJ1", "jac", "JAC", ["jac"]),
    ("LLV", "lifan", "Lifan", ["lifan"]),
    ("LGJ", "dongfeng", "Dongfeng", ["dongfeng"]),
    ("LGG", "dongfeng", "Dongfeng", ["dongfeng"]),
    ("LFP", "faw", "FAW", ["faw"]),
    ("LFB", "faw", "FAW", ["faw"]),
    ("LNB", "baic", "BAIC", ["baic"]),
    ("LMG", "gac", "GAC", ["trumpchi"]),
    ("LZW", "wuling", "Wuling", ["wuling"]),
    ("LJ8", "zotye", "Zotye", ["zotye"]),
    ("LH1", "haima", "Haima", ["haima"]),
    ("LVG", "toyota", "Toyota", ["toyota"]),
    ("LFM", "toyota", "Toyota", ["toyota"]),
    ("LTV", "toyota", "Toyota", ["toyota"]),
    ("LHG", "honda", "Honda", ["honda"]),
    ("LVH", "honda", "Honda", ["honda"]),
    ("LUC", "honda", "Honda", ["honda"]),
    ("LBV", "bmw", "BMW", ["bmw"]),
    ("LE4", "mercedes-benz", "Mercedes-Benz", ["benz"]),
    ("LBE", "hyundai", "Hyundai", ["hyundai"]),
    ("LJD", "kia", "Kia", ["kia"]),
    ("LVR", "mazda", "Mazda", ["mazda"]),
    ("LVY", "volvo", "Volvo", ["volvo"]),
    ("LYV", "volvo", "Volvo", ["volvo"]),
    ("LRW", "tesla", "Tesla", ["tesla"]),
    ("UU1", "dacia", "Dacia", ["dacia"]),
    ("TMB", "skoda", "Škoda", ["škoda"]),
    ("VSS", "seat", "SEAT", ["seat"]),
    ("U5Y", "kia", "Kia", ["kia"]),
    ("U6Y", "kia", "Kia", ["kia"]),
    ("TMA", "hyundai", "Hyundai", ["hyundai"]),
    ("TMC", "hyundai", "Hyundai", ["hyundai"]),
    ("NLH", "hyundai", "Hyundai", ["hyundai"]),
    ("MAL", "hyundai", "Hyundai", ["hyundai"]),
    ("KMT", "genesis", "Genesis", ["genesis"]),
    ("KMU", "genesis", "Genesis", ["genesis"]),
    ("KPT", "ssangyong", "SsangYong", ["kg mobility"]),  # KG Mobility since 2023
    ("KPA", "ssangyong", "SsangYong", ["kg mobility"]),
    ("KPB", "ssangyong", "SsangYong", ["kg mobility"]),
    ("MMR", "subaru", "Subaru", ["subaru"]),
    ("PLP", "subaru", "Subaru", ["subaru"]),
    ("VF3", "peugeot", "Peugeot", ["peugeot"]),
    ("VF7", "citroen", "Citroën", ["citroën"]),
    ("VR1", "ds", "DS", ["ds automobiles"]),
    ("VR3", "peugeot", "Peugeot", ["peugeot"]),
    ("VR7", "citroen", "Citroën", ["citroën"]),
    ("VS7", "citroen", "Citroën", ["citroën"]),
    ("VS8", "peugeot", "Peugeot", ["peugeot"]),
    ("SJN", "nissan", "Nissan", ["nissan"]),
    ("SAL", "land-rover", "Land Rover", ["land rover"]),
    ("SAJ", "jaguar", "Jaguar", ["jaguar"]),
    ("SKA", "vauxhall", "Vauxhall", ["vauxhall"]),
    ("VNK", "toyota", "Toyota", ["toyota"]),
    ("SB1", "toyota", "Toyota", ["toyota"]),
    ("NMT", "toyota", "Toyota", ["toyota"]),
    ("MR0", "toyota", "Toyota", ["toyota"]),
    ("MHF", "toyota", "Toyota", ["toyota"]),
    ("MA3", "suzuki", "Suzuki", ["suzuki"]),
    ("TSM", "suzuki", "Suzuki", ["suzuki"]),
    ("MMT", "mitsubishi", "Mitsubishi", ["mitsubishi"]),
    ("ML3", "mitsubishi", "Mitsubishi", ["mitsubishi"]),
    ("XMC", "mitsubishi", "Mitsubishi", ["mitsubishi"]),
]
# Left out on purpose, the WMI is shared by several makes or not confirmed by Wikipedia:
#   XW8 (VW Group Rus, Kaluga: VW, Škoda, Audi), Z94 (Hyundai Rus: Hyundai Solaris and Kia Rio),
#   XWE/XWF/X4A (Avtotor: Kia, Hyundai, Chevrolet, Chery...), XWB (UzDaewoo/GM Uzbekistan: Daewoo, Chevrolet, Ravon),
#   XUF (GM Russia: Chevrolet, Opel, Cadillac), XTK/XWK (IzhAvto: Izh, Kia, Lada), Z8T (PCMA Rus: Peugeot,
#   Citroën, Mitsubishi), Y9Z (ZAZ: Lada, Renault), X7M (TagAZ), XWW (Asia Avto), LSJ (SAIC MG/Roewe),
#   LFV/LSV (FAW-VW, SAIC-VW: VW, Audi, Škoda), Haval Russia (Tula), Exeed, Omoda, Tank, Li Auto, Zeekr: no source.

# 2-char fallbacks: only where every known WMI of the prefix is one make (checked against the table at run time)
# (JH is left out: Honda, Acura and Hino trucks; JA: Mitsubishi and Isuzu; KN: Kia, Renault Samsung, Ford)
PREFIX2 = {"JF": "subaru", "JT": "toyota", "JN": "nissan", "JM": "mazda", "JS": "suzuki",
           "WP": "porsche", "YV": "volvo", "WV": "volkswagen", "WB": "bmw"}
# a prefix whose own WMIs include a sub-brand that is in the WMI table already still counts as one maker
PREFIX2_GROUP = {"subaru": {"saab"}, "toyota": {"lexus", "scion"}, "nissan": {"infiniti", "datsun", "ud"},
                 "volkswagen": set(), "bmw": {"mini", "rolls-royce"}}

REGION = {}
for c in "ABC":
    REGION[c] = "AF"
for c in "HJKLMNPR":
    REGION[c] = "AS"
for c in "ESTUVWXYZ":
    REGION[c] = "EU"
for c in "123457":
    REGION[c] = "NA"
REGION["6"] = "OC"
for c in "89":
    REGION[c] = "SA"

# logos to ship inside the APK, by our slug (most common in Russia/CIS)
BUNDLE = [
    "lada", "toyota", "kia", "hyundai", "renault", "volkswagen", "skoda", "nissan", "mitsubishi", "mazda", "honda",
    "subaru", "lexus", "bmw", "mercedes-benz", "audi", "ford", "chevrolet", "daewoo", "opel", "peugeot", "citroen",
    "suzuki", "infiniti", "volvo", "land-rover", "porsche", "jeep", "chery", "geely", "haval", "great-wall",
    "changan", "byd", "exeed", "omoda", "tank", "jac", "li-auto", "zeekr", "gaz", "uaz", "datsun", "ravon",
    "ssangyong", "genesis", "cadillac", "dodge", "chrysler", "mini", "fiat", "seat", "isuzu", "daihatsu", "acura",
    "tesla", "jaguar", "alfa-romeo", "lifan", "dongfeng", "faw", "baic", "gac", "dacia", "kamaz", "zaz", "maz",
    "moskvich", "lincoln", "gmc", "ram", "smart", "ds", "cupra", "jetour", "hongqi",
]
# our slug -> dataset slug, where they differ
DATASET_SLUG = {"baic": "baic-motor", "gac": "gac-group", "saic": "saic-motor", "lynk-co": "lynk-and-co"}
LOGO_H = 128
LOGO_MAX_W = 384


def log(*a):
    print(*a, file=sys.stderr)


def slugify(name):
    s = unicodedata.normalize("NFKD", name)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower().replace("&", " ")
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def display(make):
    make = make.strip()
    if make in NAMES:
        return NAMES[make]
    words = []
    for w in re.split(r"(\s+|-)", make.lower()):
        if len(w) <= 3 and w.isalpha() and not re.search(r"[aeiouy]", w):
            words.append(w.upper())
        else:
            words.append(w[:1].upper() + w[1:])
    return "".join(words)


class Fetcher:
    def __init__(self, dl, refresh, offline):
        self.dl, self.refresh, self.offline = dl, refresh, offline
        self.last = 0.0

    def get(self, url, name, binary=False):
        path = os.path.join(self.dl, name)
        if os.path.exists(path) and not self.refresh:
            with open(path, "rb") as f:
                data = f.read()
            return data if binary else data.decode("utf-8")
        if self.offline:
            raise FileNotFoundError(path)
        wait = 0.6 - (time.time() - self.last)  # polite: well under two requests a second
        if wait > 0:
            time.sleep(wait)
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    data = r.read()
                break
            except Exception as e:
                if attempt == 3:
                    raise
                log("  retry", url, e)
                time.sleep(5 * (attempt + 1))
        self.last = time.time()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
        return data if binary else data.decode("utf-8")


# ---------------------------------------------------------------- Wikipedia (checks only)

def wiki_table(text):
    """WMI -> lowercased row text, from the wikitable rows of the article."""
    def clean(s):
        s = re.sub(r"<ref[^>]*/>", "", s)
        s = re.sub(r"<ref.*?</ref>", "", s, flags=re.S)
        s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
        s = re.sub(r"(rowspan|colspan|style)\s*=\s*\"?[^|\"]*\"?\s*\|", "", s)
        return s.strip()

    out = {}
    for block in text.split("\n|-"):
        cells = []
        for line in block.split("\n"):
            line = line.strip()
            if not line.startswith("|") or line.startswith("|}") or line.startswith("|+"):
                continue
            cells += [clean(c) for c in line[1:].split("||")]
        cells = [c for c in cells if c]
        if not cells:
            continue
        key = cells[0].replace("В", "B").replace("Х", "X").replace("Т", "T")  # Cyrillic look-alikes in ru
        if re.fullmatch(r"[A-Z0-9]{3}", key):
            out[key] = (out.get(key, "") + " " + " ".join(cells[1:])).lower()
    return out


# ---------------------------------------------------------------- vPIC

def vpic(fx):
    rows, makes = {}, collections.defaultdict(set)
    for q in string.ascii_lowercase + string.digits:
        w = json.loads(fx.get(VPIC + "GetWMIsForManufacturer/%s?format=json" % q, "vpic/wmi_%s.json" % q))
        for r in w["Results"]:
            rows[r["WMI"]] = r
        m = json.loads(fx.get(VPIC + "GetMakeForManufacturer/%s?format=json" % q, "vpic/make_%s.json" % q))
        for r in m["Results"]:
            if r.get("Make_Name"):
                makes[r["Mfr_Name"]].add(SAME.get(r["Make_Name"].strip(), r["Make_Name"].strip()))
        log("  vPIC %s: %d WMIs, %d manufacturers" % (q, len(rows), len(makes)))
    return rows, makes


def decoded_makes(fx, wmi):
    """The makes vPIC lists for this one WMI (DecodeWMI), for manufacturers with several makes."""
    d = json.loads(fx.get(VPIC + "DecodeWMI/%s?format=json" % wmi, "vpic/decode/%s.json" % wmi))
    out = set()
    for r in d.get("Results") or []:
        for m in (r.get("Make") or "").split(","):
            if m.strip():
                out.add(SAME.get(m.strip().upper(), m.strip().upper()))
    return sorted(out)


def pick_make(fx, wmi, row, makes, wiki):
    cands = sorted(makes.get(row["Name"], ()))
    if len(cands) == 1:
        return cands[0], "one make"
    if not cands:
        return None, "no make"
    if len(wmi) == 3:
        cands = decoded_makes(fx, wmi) or cands
        if len(cands) == 1:
            return cands[0], "decode"
        text = wiki.get(wmi)
        if text and "all brands" not in text:
            hit = [c for c in cands if re.search(r"\b%s\b" % re.escape(c.lower()), text)]
            if len(hit) == 1:
                return hit[0], "wikipedia"
    name = row["Name"].upper()
    hit = [c for c in cands if re.search(r"\b%s\b" % re.escape(c), name)]
    if len(hit) == 1:
        return hit[0], "name"
    return None, "ambiguous: " + ", ".join(cands[:6])


# ---------------------------------------------------------------- logos

def make_logo(png, dst):
    from io import BytesIO
    from PIL import Image, ImageDraw

    im = Image.open(BytesIO(png))
    im.load()
    im = im.convert("RGBA")
    w, h = im.size
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    if all(im.getpixel(c)[3] > 250 and min(im.getpixel(c)[:3]) > 235 for c in corners):
        for c in corners:  # a white box around the logo: clear what touches the edges
            ImageDraw.floodfill(im, c, (255, 255, 255, 0), thresh=40)
    box = im.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
    if box:
        im = im.crop(box)
    w, h = im.size
    scale = min(LOGO_H / h, LOGO_MAX_W / w)
    im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    im.save(dst, "WEBP", quality=88, method=6)


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dl", required=True, help="download cache dir (untrusted data, kept apart from this script)")
    ap.add_argument("--refresh", action="store_true", help="download again even if cached")
    ap.add_argument("--offline", action="store_true", help="use the cache only")
    a = ap.parse_args()
    fx = Fetcher(os.path.abspath(a.dl), a.refresh, a.offline)

    log("Wikipedia")
    wiki_en = wiki_table(fx.get(WIKI_EN, "wiki/en.txt"))
    wiki_ru = wiki_table(fx.get(WIKI_RU, "wiki/ru.txt"))
    log("  en %d, ru %d WMIs" % (len(wiki_en), len(wiki_ru)))

    log("vPIC")
    rows, mfr_makes = vpic(fx)

    wmi, why = {}, collections.Counter()
    make_names, make_countries = {}, collections.defaultdict(collections.Counter)
    skipped = []
    for code, row in sorted(rows.items()):
        code = code.strip().upper()
        if len(code) == 3:
            if row["VehicleType"] not in TYPES_3:
                continue
        elif len(code) == 6 and code[2] == "9":
            if row["VehicleType"] not in TYPES_6:
                continue
        else:
            continue
        if re.search(r"[^A-Z0-9]|[IOQ]", code):
            continue
        make, how = pick_make(fx, code, row, mfr_makes, wiki_en)
        why[how.split(":")[0]] += 1
        if not make:
            if len(code) == 3:
                skipped.append("%s %s (%s)" % (code, row["Name"], how))
            continue
        slug = slugify(display(make))
        if not slug:
            continue
        wmi[code] = slug
        make_names.setdefault(slug, display(make))
        if row.get("Country") in ISO:
            make_countries[slug][ISO[row["Country"]]] += 1
    log("  vPIC picks:", dict(why))
    for s in skipped:
        log("  skipped", s)

    log("Supplement")
    for code, slug, name, keys in SUPPLEMENT:
        rows_text = " ".join(t for t in (wiki_en.get(code), wiki_ru.get(code)) if t)
        if not rows_text:
            log("  NOT IN WIKIPEDIA, left out:", code, slug)
            continue
        if not any(k in rows_text for k in keys):
            log("  WIKIPEDIA DISAGREES, left out:", code, slug, "|", rows_text[:120])
            continue
        if code in wmi and wmi[code] != slug:
            log("  override vPIC %s: %s -> %s" % (code, wmi[code], slug))
        wmi[code] = slug
        make_names[slug] = name

    prefix2 = {}
    for p, slug in PREFIX2.items():
        own = {s for c, s in wmi.items() if len(c) == 3 and c.startswith(p)}
        extra = own - {slug} - PREFIX2_GROUP.get(slug, set())
        if slug not in own or extra:
            log("  prefix %s left out: %s" % (p, sorted(own)))
            continue
        prefix2[p] = slug

    makes = {}
    for slug in sorted(set(wmi.values())):
        country = COUNTRY.get(slug)
        if not country and make_countries[slug]:
            country = make_countries[slug].most_common(1)[0][0]
        makes[slug] = {"name": make_names[slug]} | ({"country": country} if country else {})

    os.makedirs(OUT, exist_ok=True)
    table = {"wmi": dict(sorted(wmi.items())), "prefix2": prefix2, "region": REGION, "makes": makes}
    with open(os.path.join(OUT, "wmi.json"), "w", encoding="utf-8") as f:
        json.dump(table, f, ensure_ascii=False, separators=(",", ":"))
    log("wmi.json: %d WMIs (%d small makers), %d makes, %d prefixes" % (
        len(wmi), sum(1 for c in wmi if len(c) == 6), len(makes), len(prefix2)))

    log("Logos")
    data = json.loads(fx.get(LOGOS_RAW + "data.json", "logos/data.json"))
    by_ds = {d["slug"]: d for d in data}
    ds_of = lambda slug: DATASET_SLUG.get(slug, slug)
    try:
        from PIL import features
        webp = features.check("webp")
    except ImportError:
        webp = False
    logo_dir = os.path.join(OUT, "logos")
    os.makedirs(logo_dir, exist_ok=True)
    bundled, missing, total = [], [], 0
    if webp:
        for slug in BUNDLE:
            d = by_ds.get(ds_of(slug))
            if not d:
                missing.append(slug)
                continue
            png = fx.get(LOGOS_RAW + "thumb/%s.png" % d["slug"], "logos/thumb/%s.png" % d["slug"], binary=True)
            dst = os.path.join(logo_dir, slug + ".webp")
            make_logo(png, dst)
            total += os.path.getsize(dst)
            bundled.append(slug)
    else:
        log("  Pillow with WebP not available: no logos bundled")
    for name in os.listdir(logo_dir):  # drop logos no longer in the list
        if name.endswith(".webp") and name[:-5] not in bundled:
            os.remove(os.path.join(logo_dir, name))
    ours = {ds_of(s): s for s in set(makes) | set(BUNDLE)}
    remote = {}
    for d in data:
        slug = ours.get(d["slug"], d["slug"])
        if slug not in bundled:
            remote[slug] = d["slug"] + ".png"
    logos = {"source": "https://github.com/%s/tree/%s" % (LOGOS_REPO, LOGOS_COMMIT),
             "bundled": sorted(bundled), "remoteBase": LOGOS_RAW + "thumb/", "remote": dict(sorted(remote.items()))}
    with open(os.path.join(OUT, "logos.json"), "w", encoding="utf-8") as f:
        json.dump(logos, f, ensure_ascii=False, separators=(",", ":"))
    log("logos: %d bundled, %.1f KB; %d remote; missing in the dataset: %s" % (
        len(bundled), total / 1024, len(remote), ", ".join(missing) or "-"))
    no_wmi = [s for s in BUNDLE if s not in makes]
    if no_wmi:
        log("bundled brands without a WMI:", ", ".join(no_wmi))


if __name__ == "__main__":
    main()
