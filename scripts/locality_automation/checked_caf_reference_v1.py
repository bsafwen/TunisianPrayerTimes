"""Inactive direct CAF reference preflight. No aliases, source proof or acceptance."""
from pathlib import Path
import hashlib,re

def checked_caf_reference(reference,*,require_xml_bytes=False):
    if not isinstance(reference,dict):raise ValueError('Direct CAF reference dictionary required')
    name=reference.get('file');digest=reference.get('sha256')
    if not isinstance(name,str)or not name.strip():raise ValueError('Direct nonblank file required')
    if not isinstance(digest,str)or re.fullmatch('[0-9a-f]{64}',digest)is None:raise ValueError('Exact lowercase SHA-256 required')
    if require_xml_bytes and('bytes'not in reference or type(reference['bytes'])is not int or reference['bytes']<=0):raise ValueError('XML reference requires positive integer bytes')
    if 'bytes'in reference and(type(reference['bytes'])is not int or reference['bytes']<0):raise ValueError('Optional bytes must be a nonnegative integer')
    path=Path(name)
    if not path.is_file():raise ValueError('Pinned file unavailable')
    data=path.read_bytes()
    if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Pinned SHA-256 mismatch')
    if 'bytes'in reference and reference['bytes']!=len(data):raise ValueError('Pinned byte-size mismatch')
    return path
