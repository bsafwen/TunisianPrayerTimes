from pathlib import Path
p=Path(__file__).resolve().parent;s=(p/'review_native_faces_finite_v6.py').read_text(encoding='utf-8')
s=s.replace("own[0]['centerPagePoints'][1] <= 100", "(own[0]['centerPagePoints'][1] <= 100 and not case.get('reviewedInMapLabel'))")
compile(s,'review_native_faces_finite_v7.py','exec')
with(p/'review_native_faces_finite_v7.py').open('x',encoding='utf-8')as f:f.write(s)
