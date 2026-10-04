"""Retain verified TLS using the machine proxy's public CA certificate."""
from pathlib import Path
p=Path(__file__).with_name('refetch_qasr_original_proxy_v1.py')
s=p.read_text(encoding='utf-8').replace("qasr-official-refetch-v1","qasr-official-refetch-v2").replace("timeout=(10,25)","timeout=(10,25),verify=r'C:\\Users\\barou\\.mitmproxy\\mitmproxy-ca-cert.pem'")
exec(compile(s,str(p),'exec'),{'__name__':'__main__','__file__':str(p)})
