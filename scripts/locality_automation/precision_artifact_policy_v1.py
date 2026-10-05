"""Exact human-authorized source/app-grid hairline decisions; default bounds stay."""
from pathlib import Path
from math import isfinite
from scripts.locality_automation.run_sealed_boundary_queue import read,checked

def require_precision_artifact(code,patch,control,source_outside_m2,decoded_outside_m2):
 if any(not isfinite(v) or v<0 for v in [source_outside_m2,decoded_outside_m2]):raise ValueError('Artifact areas must be finite and nonnegative')
 ref=control.get('humanPrecisionArtifactExceptions',{}).get(code)
 if not ref:raise ValueError('Unapproved source body exceeds 10cm rounding bound: '+code)
 proof=read(checked(ref));auth=proof['authorization'];entry=proof['entry']
 if proof['status']!='EXPLICIT_DIRECT_USER_APPROVED_CODE_PDF_GRID_ARTIFACT'or auth['sourceRole']!='direct-user-instruction'or auth['action']!='entry-only-grid-hairline-artifact'or auth['officialCode']!=code or not auth['userStatement'].strip():raise ValueError('Direct exact artifact approval differs')
 for k in ['sourcePdf','rawSourceGeometry','geometry']:
  if patch[k]!=entry[k]:raise ValueError('Approved exact PDF/raw/grid shape differs: '+code)
  checked(entry[k])
 review=read(checked(proof['review']))
 if any(entry[k]!=review[k]for k in ['sourcePdf','rawSourceGeometry','geometry']):raise ValueError('Pinned reviewed shape differs')
 if auth['maxSourceOutsideDecoded10cmM2']!=.01 or auth['maxDecodedOutsideSource10cmM2']!=0:raise ValueError('Only the reviewed narrow decision is supported')
 if source_outside_m2>auth['maxSourceOutsideDecoded10cmM2']or decoded_outside_m2>auth['maxDecodedOutsideSource10cmM2']:raise ValueError('Approved artifact domain bound exceeded')
 return {'policy':'exact-human-approved-pdf-grid-hairline-artifact','proof':ref,'sourceOutsideDecoded10cmM2':source_outside_m2,'decodedOutsideSource10cmM2':decoded_outside_m2,'sourceOutsideLimitM2':.01,'decodedOutsideLimitM2':0,'ordinaryHausdorffLimitMUnchanged':.1,'transfersToOtherSources':False}
