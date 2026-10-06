from pathlib import Path
p=Path(__file__).resolve().parent;s=(p/'review_native_faces_finite_v5.py').read_text(encoding='utf-8')
s=s.replace('from shapely import set_precision','from shapely import set_precision, from_wkb')
s=s.replace("                if len(faces) != 1 or not faces[0].is_valid:","""                if case.get('reviewedNativePageFace'):
                    selected = from_wkb(checked(case['reviewedNativePageFace']).read_bytes())
                    matching = [g for g in red_faces.geoms if g.equals_exact(selected,0)]
                    if len(matching)!=1:
                        raise ValueError('Reviewed face is not exactly one original closed red face: '+code)
                    faces=matching
                    method='complete_original_red_face_requires_paired_attribution'
                if len(faces) != 1 or not faces[0].is_valid:""")
compile(s,'review_native_faces_finite_v6.py','exec')
with (p/'review_native_faces_finite_v6.py').open('x',encoding='utf-8')as f:f.write(s)
