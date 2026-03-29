#!/usr/bin/env python3
"""Reverse-geocode every delegation's coordinates via Nominatim and compare."""
import json, urllib.request, time, sys

with open('android-app/app/src/main/assets/gouvernorats.json') as f:
    data = json.load(f)

issues = []
idx = 0
total = sum(len(g['delegations']) for g in data['gouvernorats'])

for g in data['gouvernorats']:
    for d in g['delegations']:
        idx += 1
        lat, lng = d.get('lat', 0), d.get('lng', 0)
        name_fr = d['nomFr']
        gov_fr = g['nomFr']
        
        if lat == 0 or lng == 0:
            issues.append(f"MISSING_COORDS: {gov_fr}/{name_fr}")
            continue
        
        url = f"https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lng}&format=json&accept-language=fr&zoom=12"
        req = urllib.request.Request(url, headers={'User-Agent': 'TunisianPrayerTimes-CoordCheck/1.0'})
        
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                result = json.loads(resp.read())
            
            addr = result.get('address', {})
            display = result.get('display_name', '')
            city = addr.get('city', addr.get('town', addr.get('village', addr.get('hamlet', ''))))
            state = addr.get('state', '')
            country = addr.get('country_code', '')
            
            ok = "✅" if country == 'tn' else "❌"
            
            # Check if it's in the right general area
            if country != 'tn':
                issues.append(f"WRONG_COUNTRY: {gov_fr}/{name_fr} ({lat},{lng}) -> {country} - {display[:80]}")
            
            print(f"[{idx}/{total}] {ok} {gov_fr:15s}/{name_fr:25s} -> {city:20s} ({state})")
            sys.stdout.flush()
            
        except Exception as e:
            print(f"[{idx}/{total}] ⚠️  {gov_fr}/{name_fr} error: {e}")
            sys.stdout.flush()
        
        # Nominatim rate limit: 1 req/sec
        time.sleep(1.1)

print(f"\n{'='*60}")
print(f"DONE. Checked {idx} delegations.")
print(f"Issues found: {len(issues)}")
for i in issues:
    print(f"  - {i}")
