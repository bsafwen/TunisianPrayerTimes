"""Inspect only this batch's Hamima identity/membership fields."""
import json
from pathlib import Path
R=Path(__file__).resolve().parents[2];E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows')
app=json.loads((R/'android-app/app/src/main/assets/neighborhoods.json').read_text(encoding='utf-8'));r=next(r for r in app['features']if r['id']=='osm:relation:7142965');print(json.dumps({'app':r},ensure_ascii=False))
data=json.loads((E/'work/isie-execution-20260926/medenine/525361-source-face-identity-hold-20260928-v1/decision.json').read_text(encoding='utf-8'));print(json.dumps({'identityDecision':{k:v for k,v in data.items()if k!='sourceFace'}},ensure_ascii=False))
m=json.loads((E/'work/locality-progress-dashboard/boundary-map.json').read_text(encoding='utf-8'));r=next(r for r in m['locations']if r['code']=='525361');print(json.dumps({'map':{k:r.get(k)for k in ['datedRosterMember','datedRosterMembershipSource','qualification','explicitIdentityQualification','catalogBindingQualification']}},ensure_ascii=False))
