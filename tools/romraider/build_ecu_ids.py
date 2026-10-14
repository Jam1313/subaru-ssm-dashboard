#!/usr/bin/env python3
"""
Builds app/src/main/assets/ecu_ids.json: ECU id (the 5 bytes the init answer calls ROM ID) -> the car it is
from, e.g. "JDM Forester XT 2003 AT". Shown next to the ROM id so a driver (and whoever reads a sent log) can see
which ECU the car has.

Source: ecuid_to_rom_TDD_Alpha.json, extracted from every ECUFlash and RomRaider ROM definition of the TD-D fork
of SubaruDefs (https://github.com/TD-D/SubaruDefs, branch Alpha, GPL): <romid> blocks with year, market, model,
submodel and transmission. Patched ROMs (MerpMod and the like) are skipped in favour of the stock definition.

Run from the repository root:  python3 tools/romraider/build_ecu_ids.py
"""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def year(y):
    y = (y or '').strip()
    if len(y) == 2 and y.isdigit():
        return ('19' if int(y) >= 85 else '20') + y
    return y if len(y) == 4 and y.isdigit() else ''


def main():
    src = json.load(open(os.path.join(HERE, 'ecuid_to_rom_TDD_Alpha.json'), encoding='utf-8'))
    out = {}
    for ecu, roms in src.items():
        if len(ecu) != 10 or ecu == '1234567890':
            continue
        stock = [r for r in roms if '.' not in r.get('xmlid', '')] or roms
        r = stock[0]
        text = ' '.join(x for x in [r.get('market', ''), r.get('model', ''), r.get('submodel', ''), year(r.get('year')),
                                    r.get('transmission', '')] if x and x.strip())
        if text:
            out[ecu.upper()] = text
    path = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'ecu_ids.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'source': 'SubaruDefs, TD-D fork (github.com/TD-D/SubaruDefs), GPL', 'ids': out}, f, ensure_ascii=False,
                  separators=(',', ':'))
    print('%d ECU ids' % len(out))


if __name__ == '__main__':
    main()
