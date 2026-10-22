#!/usr/bin/env python3
"""
Builds the app's SSM parameter database from the RomRaider logger definitions.

Source: RomRaider logger definitions v370 (logger_METRIC_EN_v370.xml, RomRaider project, GPL,
https://www.romraider.com, maintained by the RomRaider community). The original file is kept here
gzipped; Russian names are in ru/*.json.

Extended parameters of ROMs v370 doesn't know are taken from the TD-D/SubaruDefs fork
(https://github.com/TD-D/SubaruDefs, branches Alpha and Stable, GPL), only for those ROM ids and only where the
parameter id has the same name as in v370: everything v370 has stays exactly as it is.

Writes:
  app/src/main/assets/romraider_ssm.json  standard parameters, switches and per-ROM extended parameters
  app/src/main/assets/ssm_dtc.json        adds the trouble codes the table did not have yet

Run from the repository root:  python3 tools/romraider/build_defs.py
"""
import gzip, json, os, xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')


def ru(name):
    p = os.path.join(HERE, 'ru', name)
    return json.load(open(p, encoding='utf-8')) if os.path.exists(p) else {}


def num(s, default=-1):
    return int(s, 16) if s and s.startswith('0x') else (int(s) if s else default)


IMPERIAL = ('psi', 'mmhg', 'mph', 'f', 'inhg')


# Upstream slips fixed here, by the exact expression: E98's kPa copies the bar offset (0.2) where its own bar, psi
# and mmHg conversions all say 150 mmHg = 19.998 kPa, so MAP read ~20 kPa high.
EXPR_FIX = {'(x*.002542923463176)-.1999836': '(x*.002542923463176)-19.99836'}


def conversions(el):
    """All conversions, a metric one first: some ecuparams list psi or mmHg before kPa."""
    cs = [[c.get('units', ''), c.get('expr', 'x'), c.get('format', '0'), c.get('storagetype') or '']
          for c in el.iter('conversion')]
    for c in cs:
        if c[0].lower().startswith('kpa'):
            c[1] = EXPR_FIX.get(c[1], c[1])
    return sorted(cs, key=lambda c: c[0].lower().split(' ')[0] in IMPERIAL)


def address(a):
    return [num(a.text.strip()), int(a.get('length', '1')), int(a.get('bit', '-1'))]


def main():
    root = ET.parse(gzip.open(os.path.join(HERE, 'logger_METRIC_EN_v370.xml.gz'))).getroot()
    ssm = next(p for p in root.iter('protocol') if p.get('id') == 'SSM')
    ru_p, ru_s, ru_e, ru_d = ru('params_ru.json'), ru('switches_ru.json'), ru('ecuparams_ru.json'), ru('dtc_new_ru.json')

    params = []
    for p in ssm.find('parameters'):
        addrs = p.findall('address')
        a = addrs[0] if addrs else None
        prm = {
            'id': p.get('id'), 't': int(p.get('target', '1')), 'n': p.get('name'), 'ru': ru_p.get(p.get('id'), ''),
            'b': int(p.get('ecubyteindex', '-1')), 'cb': int(p.get('ecubit', '-1')),
            'a': address(a) if a is not None else None,
            'dep': [r.get('parameter') for r in p.iter('ref')],
            'c': conversions(p),
        }
        # several addresses are one value, their bytes in order, as RomRaider's convertor reads them (P148 Steering
        # Angle Sensor: the high byte at 0x8D, the low one at 0x15A): the further ones as [address, length]
        if len(addrs) > 1:
            prm['aa'] = [address(x)[:2] for x in addrs[1:]]
        params.append(prm)
    switches = [{
        'id': s.get('id'), 't': int(s.get('target', '1')), 'n': s.get('name'), 'ru': ru_s.get(s.get('id'), ''),
        'a': num(s.get('byte')), 'bit': int(s.get('bit')), 'b': int(s.get('ecubyteindex', '-1')),
    } for s in ssm.find('switches')]
    ecuparams = []
    for e in ssm.find('ecuparams'):
        roms = {}
        for ecu in e.findall('ecu'):
            for rom in ecu.get('id').split(','):
                roms[rom.strip().upper()] = address(ecu.find('address'))
        tr = ru_e.get(e.get('id'), {})
        ecuparams.append({
            'id': e.get('id'), 't': int(e.get('target', '1')), 'n': e.get('name').rstrip('*').strip(),
            'ru': tr.get('n', ''), 'd': tr.get('d', ''), 'c': conversions(e), 'r': roms,
        })
    # ROMs only the TD-D fork knows (newer USDM/ADM/EDM cars): added, never replacing a v370 address
    known = {r for e in ecuparams for r in e['r']}
    by_id = {e['id']: e for e in ecuparams}
    extra_roms = set()
    for fork in ('logger_METRIC_EN_v290_TDD_Alpha.xml.gz', 'logger_METRIC_EN_TDD_Stable.xml.gz'):
        path = os.path.join(HERE, fork)
        if not os.path.exists(path):
            continue
        froot = ET.parse(gzip.open(path)).getroot()
        fssm = next(p for p in froot.iter('protocol') if p.get('id') == 'SSM')
        for e in fssm.find('ecuparams'):
            base = by_id.get(e.get('id'))
            if base is None or base['n'] != e.get('name').rstrip('*').strip():
                continue
            for ecu in e.findall('ecu'):
                a = address(ecu.find('address'))
                for rom in ecu.get('id').split(','):
                    rom = rom.strip().upper()
                    if len(rom) != 10 or rom in known or rom in base['r'] or not 0 < a[0] <= 0xFFFFFF:
                        continue
                    base['r'][rom] = a
                    extra_roms.add(rom)

    # addresses found by people who drive these cars (tools/romraider/community/*.json): added under ids of their own
    community = os.path.join(HERE, 'community')
    for name in sorted(os.listdir(community)) if os.path.isdir(community) else []:
        c = json.load(open(os.path.join(community, name), encoding='utf-8'))
        for prm in c['params']:
            roms = {}
            for group, addr in prm['r'].items():
                for rom in c['roms'][group]:
                    roms[rom.upper()] = [num(addr.lower()), 4, -1]
            ecuparams.append({
                'id': prm['id'], 't': 1, 'n': prm['n'], 'ru': prm['ru'], 'd': c['source'],
                'c': [[prm['units'], 'x', '0.0', 'float']], 'r': roms,
            })

    out = {
        'source': 'RomRaider logger definitions v%s (logger_METRIC_EN), GPL, https://www.romraider.com' % root.get('version'),
        'params': params, 'switches': switches, 'ecuparams': ecuparams,
    }
    with open(os.path.join(ASSETS, 'romraider_ssm.json'), 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))

    # trouble codes: keep the existing table (it has Russian texts), add what RomRaider knows beyond it
    dtc_path = os.path.join(ASSETS, 'ssm_dtc.json')
    dtc = json.load(open(dtc_path, encoding='utf-8'))
    have = {(int(d['tmp'], 16), int(d['mem'], 16), d['bit']) for d in dtc}
    added = 0
    for d in ssm.find('dtcodes'):
        key = (num(d.get('tmpaddr')), num(d.get('memaddr')), int(d.get('bit')))
        code, _, desc = d.get('name').partition(' ')
        if key in have or code == 'P0000':
            continue
        dtc.append({'code': code, 'desc': desc, 'tmp': '0x%02X' % key[0], 'mem': '0x%02X' % key[1], 'bit': key[2],
                    'ru': ru_d.get(d.get('id'), '')})
        have.add(key)
        added += 1
    with open(dtc_path, 'w', encoding='utf-8') as f:
        json.dump(dtc, f, ensure_ascii=False)
    print('params %d, switches %d, ecuparams %d (%d ROMs, %d from TD-D), dtc +%d = %d' % (
        len(params), len(switches), len(ecuparams), len({r for e in ecuparams for r in e['r']}), len(extra_roms), added, len(dtc)))


if __name__ == '__main__':
    main()
