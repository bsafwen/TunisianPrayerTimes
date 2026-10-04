"""Immutable generic successors: existing formulas and gates remain unchanged."""
from pathlib import Path
p=Path(__file__).resolve().parent
s=(p/'review_djerba_native_faces_v2.py').read_text(encoding='utf-8')
s=s.replace('finite Djerba','finite reviewed').replace("control['approvedCycle23AcceptancePool']","control['approvedCycle'+str(control['iteration'])+'AcceptancePool']").replace('Djerba source scope differs','Current finite source scope differs')
a=s.index('        code = case');b=s.index('\n    put(output /')+1
body=s[a:b]
s=s[:a]+'        try:\n'+''.join('    '+line if line.strip() else line for line in body.splitlines(keepends=True))+'''        except Exception as exc:
            rows.append({'officialCode':case['officialCode'],'status':'HOLD_ORIGINAL_SOURCE_FACTS','error':str(exc),'sourcePdf':case['sourcePdf'],'credit':0})
            print(json.dumps(rows[-1],ensure_ascii=False),flush=True)
'''+s[b:]
compile(s,'review_native_faces_finite_v4.py','exec')
with (p/'review_native_faces_finite_v4.py').open('x',encoding='utf-8')as f:f.write(s)
# The existing target-only audit now accepts an explicit finite family slug.
s=(p/'audit_source_batch_minimum_v1.py').read_text(encoding='utf-8')
s=s.replace('djerba', 'medenine').replace("c['approvedCycle23AcceptancePool']","c['approvedCycle'+str(c['iteration'])+'AcceptancePool']")
compile(s,'audit_medenine_batch_minimum_v1.py','exec')
if not (p/'audit_medenine_batch_minimum_v1.py').exists():
 with (p/'audit_medenine_batch_minimum_v1.py').open('x',encoding='utf-8')as f:f.write(s)
