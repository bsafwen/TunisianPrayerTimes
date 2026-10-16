"""Collect Salat Calendar 'official' yearly tables (Jordan 1-34, UAE 35-94) for the current year.
Read-only, politely paced. Output: one CSV per location id: date,fajr,sunrise,dhuhr,asr,maghrib,isha."""
import json, re, sys, time, urllib.request, html, os
out = sys.argv[1]  # e.g. test-data-local/arab-prototype/salat-calendar-official-2026
ids = [i for i in range(1, 95) if i != 18]
for i in ids:
    path = os.path.join(out, f'{i}.csv')
    if os.path.exists(path):
        continue
    req = urllib.request.Request(f'https://www.salatcalendar.com/index.php/offical/get_year/{i}', data=b'', method='POST',
                                 headers={'User-Agent': 'Mozilla/5.0 (research; prayer-times validation)', 'X-Requested-With': 'XMLHttpRequest'})
    body = urllib.request.urlopen(req, timeout=60).read().decode('utf-8')
    h = json.loads(body)['html']
    rows = []
    for tr in re.findall(r'<tr[^>]*>(.*?)</tr>', h, re.S):
        cells = [html.unescape(re.sub(r'<[^>]+>', '', c)).strip() for c in re.findall(r'<td[^>]*>(.*?)</td>', tr, re.S)]
        if len(cells) >= 8 and re.match(r'\*?\d\d/\d\d/\d{4}', cells[0]):
            d, m, y = cells[0].lstrip('*').split('/')
            rows.append(','.join([f'{y}-{m}-{d}'] + cells[2:8]))
    title = re.search(r'<h\d[^>]*>([^<]*)</h\d>', h)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('date,fajr,sunrise,dhuhr,asr,maghrib,isha\n' + '\n'.join(rows) + '\n')
    print(i, len(rows), flush=True)
    time.sleep(1.5)
