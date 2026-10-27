#!/usr/bin/env python3
"""Check the shipped raw parameter metadata against the pinned upstream catalogue."""
from pathlib import Path
import json, re
root=Path(__file__).resolve().parents[3]
source=(root/'waldi/xt/xtJucePlugin/parameterDescriptions_xt.json').read_text()
source=re.sub(r'//[^\n]*','',source)
source=re.sub(r',\s*([}\]])',r'\1',source)
data=json.loads(source)
defaults=data['parameterdescriptiondefaults']
params={p['name']:{**defaults,**p} for p in data['parameterdescriptions']}
java=(root/'Android/RiXeniaApp/app/src/main/java/com/rinoize/rixenia/XtParameters.java').read_text()
rows=re.findall(r'PARAMETERS\[(\d+)\]=new Param\((\d+),(\d+),(\d+),"([^"]+)",(true|false)\)',java)
assert len(rows)>=90
for slot,idx,lo,hi,name,bipolar in rows:
    p=params[name]
    assert (int(slot),int(idx),int(lo),int(hi),bipolar=='true')==(p['index'],p['index'],p['min'],p['max'],p['isBipolar']),name
print(f'Editor catalogue: {len(rows)} indices/ranges/bipolar flags match pinned XT descriptions')
