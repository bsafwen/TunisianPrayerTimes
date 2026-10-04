"""Administrative verification of the unchanged single review timer."""
import json,os,tomllib
from pathlib import Path
from datetime import datetime,timezone
C=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows\work\locality-efficiency-cycles-20261001');p=C/'control.json';c=json.loads(p.read_text(encoding='utf-8'));timer=tomllib.loads((Path(r'C:\Users\barou\.codex\automations')/c['cutoffAutomationId']/'automation.toml').read_text(encoding='utf-8'))
if c['iteration']!=25 or timer['status']!='ACTIVE' or timer['rrule']!='FREQ=DAILY;BYHOUR=21;BYMINUTE=30;BYSECOND=55;COUNT=1':raise ValueError('Exact final-entry timer was not verified')
c.update(cutoffTimerStatus='VERIFIED_ACTIVE',cutoffAutomationStatus='ACTIVE',lastControlUpdateUtc=datetime.now(timezone.utc).isoformat());c.pop('medeninComplete',None);tmp=p.with_suffix('.timer.tmp');tmp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');os.replace(tmp,p);print('Exact same timer verified ACTIVE; deadline retained.')
