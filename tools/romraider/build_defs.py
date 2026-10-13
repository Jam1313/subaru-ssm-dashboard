#!/usr/bin/env python3
"""
Builds the app's SSM parameter database from the RomRaider logger definitions.

Source: RomRaider logger definitions v370 (logger_METRIC_EN_v370.xml, RomRaider project, GPL,
https://www.romraider.com, maintained by the RomRaider community). The original file is kept here
gzipped; Russian names are in ru/*.json.

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


def conversions(el):
    """All conversions, a metric one first: some ecuparams list psi or mmHg before kPa."""
    cs = [[c.get('units', ''), c.get('expr', 'x'), c.get('format', '0'), c.get('storagetype') or '']
          for c in el.iter('conversion')]
    return sorted(cs, key=lambda c: c[0].lower().split(' ')[0] in IMPERIAL)


def address(a):
    return [num(a.text.strip()), int(a.get('length', '1')), int(a.get('bit', '-1'))]


def main():
    root = ET.parse(gzip.open(os.path.join(HERE, 'logger_METRIC_EN_v370.xml.gz'))).getroot()
    ssm = next(p for p in root.iter('protocol') if p.get('id') == 'SSM')
    ru_p, ru_s, ru_e, ru_d = ru('params_ru.json'), ru('switches_ru.json'), ru('ecuparams_ru.json'), ru('dtc_new_ru.json')

    params = []
    for p in ssm.find('parameters'):
        a = p.find('address')
        params.append({
            'id': p.get('id'), 't': int(p.get('target', '1')), 'n': p.get('name'), 'ru': ru_p.get(p.get('id'), ''),
            'b': int(p.get('ecubyteindex', '-1')), 'cb': int(p.get('ecubit', '-1')),
            'a': address(a) if a is not None else None,
            'dep': [r.get('parameter') for r in p.iter('ref')],
            'c': conversions(p),
        })
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
    print('params %d, switches %d, ecuparams %d (%d ROMs), dtc +%d = %d' % (
        len(params), len(switches), len(ecuparams), len({r for e in ecuparams for r in e['r']}), added, len(dtc)))


if __name__ == '__main__':
    main()
