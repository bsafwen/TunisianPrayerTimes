"""Synchronous, serial-specific Google Maps query and untouched PNG capture."""
import argparse
import json
from pathlib import Path
import subprocess
import time
from urllib.parse import quote

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--query', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--camera', nargs=2, type=float)
    a = p.parse_args()
    adb = Path(r'C:\Users\barou\AppData\Local\Android\Sdk\platform-tools\adb.exe')
    command = [str(adb), '-s', a.serial]
    uri = ('geo:' + ','.join(map(str, a.camera)) + '?z=14') if a.camera else 'geo:0,0?q=' + quote(a.query)
    result = subprocess.run(command + ['shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', uri, '-p', 'com.google.android.apps.maps'], capture_output=True, check=True, timeout=20)
    time.sleep(7)
    screen = subprocess.run(command + ['exec-out', 'screencap', '-p'], capture_output=True, check=True, timeout=20).stdout
    with a.output.open('xb') as f:
        f.write(screen)
    print(json.dumps({'serial': a.serial, 'query': a.query, 'screenshot': str(a.output), 'bytes': len(screen), 'hostHttpUsed': False}, ensure_ascii=False))
