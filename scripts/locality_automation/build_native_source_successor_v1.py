from pathlib import Path
p=Path(__file__).resolve().parent
s=(p/'review_native_faces_finite_v4.py').read_text(encoding='utf-8')
s=s.replace("                expected_text = diagnostic.get('targetLabelText')\n                own = [v for v in inventory['labeledAreaLeads'] if v['text'] == expected_text] if expected_text else [v for v in inventory['labeledAreaLeads'] if _name_match(v['text'], case['officialName'])]", """                expected_text = diagnostic.get('targetLabelText')
                leads = inventory['labeledAreaLeads']
                if diagnostic.get('targetLabelIndex') is not None:
                    own = [leads[diagnostic['targetLabelIndex']]]
                    if own[0]['text'] != expected_text or own[0]['centerPagePoints'][1] <= 100:
                        raise ValueError('Pinned in-map label differs: '+code)
                else:
                    own = [v for v in leads if v['centerPagePoints'][1]>100 and (v['text']==expected_text if expected_text else _name_match(v['text'],case['officialName']))]""")
s=s.replace("                if not faces:\n", "                if not faces or case.get('useAdministrativeFaces'):\n")
# Rendering is retained even for registration/face holds, before interpreting geometry.
s=s.replace('                original = pypdf.PdfReader(pdf)',"                picture = output / (code + '-original.png')\n                page.get_pixmap(matrix=pymupdf.Matrix(1.8, 1.8), alpha=False).save(picture)\n                original = pypdf.PdfReader(pdf)")
s=s.replace("                picture = output / (code + '-original.png')\n                page.get_pixmap(matrix=pymupdf.Matrix(1.8, 1.8), alpha=False).save(picture)\n                inner =",'                inner =')
compile(s,'review_native_faces_finite_v5.py','exec')
with (p/'review_native_faces_finite_v5.py').open('x',encoding='utf-8')as f:f.write(s)
