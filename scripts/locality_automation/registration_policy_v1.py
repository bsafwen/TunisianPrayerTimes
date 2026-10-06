"""Default registration gates plus a pinned, exact-entry, exact-PDF human exception."""
from scripts.locality_automation.run_sealed_boundary_queue import read,checked

def registration_limits(code,pdf,control):
 limits={'fitResidualM':.5,'looMaxM':1.5,'humanException':None}
 ref=control.get('humanRegistrationExceptions',{}).get(code)
 if ref is None:return limits
 proof=read(checked(ref))
 if proof.get('status')!='EXPLICIT_HUMAN_APPROVED_ENTRY_ONLY_REGISTRATION_EXCEPTIONS' or proof.get('humanAnswer')!='Allow both entry-only exceptions':
  raise ValueError('Explicit human registration proof differs')
 matches=[r for r in proof['entries'] if r['officialCode']==code and r['sourcePdf']['sha256']==pdf['sha256']]
 if not matches:return limits
 if len(matches)!=1 or checked(matches[0]['sourcePdf'])!=checked(pdf):raise ValueError('Exception must match the exact original PDF')
 row=matches[0]
 if row['fitResidualLimitM']<.5 or row['looLimitM']<1.5:raise ValueError('Exception limit is inconsistent')
 return {'fitResidualM':row['fitResidualLimitM'],'looMaxM':row['looLimitM'],'humanException':ref}

def require_registration(code,pdf,registration,control):
 limits=registration_limits(code,pdf,control)
 if registration['fitResidualM']>limits['fitResidualM'] or registration['looMaxM']>limits['looMaxM']:
  raise ValueError('Unwaived original registration held: '+code)
 return limits
