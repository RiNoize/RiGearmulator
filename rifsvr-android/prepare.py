#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Apply small, auditable corrections to the versioned source bundle before building.
from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'build.py'
s=p.read_text()
old="'-source','8','-target','8','-bootclasspath',androidjar"
assert old in s
s=s.replace(old,"'--release','8','-classpath',androidjar")
p.write_text(s)
p=root/'src/smoke.cpp'
s=p.read_text()
s=s.replace('if(peak<1e-7)return 5;', 'if(peak<1e-7)return 5; if(synth.stats()[8]!=0)return 7;')
s=s.replace('if(peak<1e-7)return 6;', 'if(peak<1e-7)return 6; if(synth.stats()[8]!=0)return 7;')
s=s.replace('synth.render(out,256);}', 'synth.render(out,256);if(synth.stats()[8]!=0)return 7;}')
p.write_text(s)
