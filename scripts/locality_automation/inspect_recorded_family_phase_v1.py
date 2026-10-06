"""Show one receipt-derived phase invocation without guessed paths or large JSON dumps.

Read-only navigation; no engine execution, private configuration or acceptance.
The index must come from summarize_family_artifacts_v1.py. Program basenames are
literal: an absent or ambiguous selection fails closed.
"""
import argparse,json,hashlib
from pathlib import Path

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def checked(ref):
 p=Path(ref['file']).resolve(strict=True)
 if hashlib.sha256(p.read_bytes()).hexdigest()!=ref['sha256']:raise ValueError('Receipt pin differs: '+p.name)
 return p
def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--index',type=Path,required=True);p.add_argument('--program',required=True)
 a=p.parse_args()
 if Path(a.program).name!=a.program:raise ValueError('Literal program basename required')
 index=read(a.index)
 if index['status']!='DIAGNOSTIC_RECEIPT_DERIVED_ARTIFACT_INDEX':raise ValueError('Expected recorded artifact index')
 matches=[r for r in index['launches']if Path(r['program']['file']).name==a.program]
 if not matches:raise ValueError('No recorded phase for literal program: '+a.program)
 latest=max(matches,key=lambda r:r.get('startedAtUtc')or'')
 launch=read(checked(latest['launch']));checked(latest['program'])
 result={'launch':latest['launch'],'program':latest['program'],'phase':latest['phase'],'arguments':launch['arguments'],'recordedStatus':latest['status'],'recordedExitCode':latest['exitCode'],'exactExistingArgumentPaths':[r['path']for r in latest['actualPaths']if r['exists']],'qualification':'Literal historical invocation for navigation; not a command authorization or acceptance proof.'}
 text=json.dumps(result,ensure_ascii=False)
 if len(text)>10000:raise ValueError('Selected phase output exceeds bounded navigation limit; use a narrower recorded phase')
 print(text)
if __name__=='__main__':main()
