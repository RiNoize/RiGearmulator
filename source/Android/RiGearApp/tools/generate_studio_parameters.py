#!/usr/bin/env python3
"""Generate Android editor controls from the actual Osirus parameter definitions."""
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[4]
source = root / 'source/axel/osirusJucePlugin/parameterDescriptions_C.json'
text = source.read_text(encoding='utf-8')
# Remove comments only outside JSON strings, retaining strings byte-for-byte.
text = re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*.*?\*/',
              lambda match: match.group(1) or '', text, flags=re.S)
text = re.sub(r',\s*([}\]])', r'\1', text)
data = json.loads(text)
defaults = data.get('parameterdescriptiondefaults', {})
controls, used = [], set()
for item in data['parameterdescriptions']:
    p = dict(defaults, **item)
    if p.get('page') not in (112, 113) or not p.get('isPublic', True):
        continue
    name = p.get('displayName') or p['name']
    key = (p['page'], p['index'])
    if key in used or 'Undefined' in name or name.startswith('SingleName'):
        continue
    used.add(key)
    low = name.lower()
    if any(word in low for word in ('chorus', 'delay', 'reverb', 'phaser', 'distortion', 'eq ', 'eq frequency', 'analog boost', 'bass boost', 'punch')):
        group = 'FX'
    elif 'env ' in low or 'envelope' in low:
        group = 'Envolventes'
    elif 'arp' in low or 'clock tempo' in low:
        group = 'Arpegiador'
    elif any(word in low for word in ('lfo', 'assign', 'velocity', 'aftertouch')):
        group = 'LFO / Mod'
    elif any(word in low for word in ('filter', 'cutoff', 'resonance', 'saturation')):
        group = 'Filtros'
    elif any(word in low for word in ('osc', 'ringmod', 'noise', 'fm ')):
        group = 'Osciladores'
    else:
        group = 'Otros'
    c = {'name': name, 'page': p['page'], 'index': p['index'],
         'min': p.get('min', 0), 'max': p.get('max', 127), 'group': group}
    values = data.get('valuelists', {}).get(p.get('toText', ''))
    if values:
        c['values'] = values
    controls.append(c)
arp = next(p for p in controls if p['page'] == 113 and p['index'] == 1)
assert arp['values'] == ['Off', 'Up', 'Down', 'Up&Down', 'As Played', 'Random', 'Chord']
assert any(p['page'] == 112 and p['index'] == 38 and 'Ring' in p['name'] for p in controls)
assert len(controls) > 100
out = root / 'source/Android/RiGearApp/app/src/main/assets/studio-parameters.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(controls, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print(f'Generated {len(controls)} real Osirus A/B/C controls; 7 arpeggiator values and ringmod mapping verified')
