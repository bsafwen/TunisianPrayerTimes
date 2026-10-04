"""Perform one observed Maps tap and capture its untouched screen and visible text."""
import argparse, json, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--tap', nargs=2, type=int)
    p.add_argument('--swipe', nargs=4, type=int)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    base = [r'C:\Users\barou\AppData\Local\Android\Sdk\platform-tools\adb.exe', '-s', a.serial]
    def run(parts):
        return subprocess.run(base + parts, capture_output=True, check=True, timeout=20).stdout
    if a.tap:
        run(['shell', 'input', 'tap', *map(str, a.tap)])
        time.sleep(5)
    if a.swipe:
        run(['shell', 'input', 'swipe', *map(str, a.swipe), '700'])
        time.sleep(3)
    with a.output.open('xb') as f:
        f.write(run(['exec-out', 'screencap', '-p']))
    run(['shell', 'uiautomator', 'dump', '/sdcard/djerba-current.xml'])
    xml = run(['exec-out', 'cat', '/sdcard/djerba-current.xml'])
    a.output.with_suffix('.xml').write_bytes(xml)
    print(json.dumps([(v.get('text'), v.get('content-desc'), v.get('bounds')) for v in ET.fromstring(xml).iter('node') if v.get('text') or v.get('content-desc')], ensure_ascii=False))
