#!/usr/bin/env python3
"""
Read the coords_verification_report.csv and update gouvernorats.json
with Google's coordinates for all ERROR entries (>15 km off).
"""
import csv
import json
from pathlib import Path

CSV_PATH = Path(__file__).parent / "coords_verification_report.csv"
JSON_PATH = Path(__file__).parent / "android-app/app/src/main/assets/gouvernorats.json"

# Read CSV report
fixes = {}
with open(CSV_PATH, encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
        if row["status"] in ("ERROR", "WARNING") and row["google_lat"] and row["google_lng"]:
            deleg_id = int(row["delegation_id"])
            fixes[deleg_id] = {
                "lat": round(float(row["google_lat"]), 4),
                "lng": round(float(row["google_lng"]), 4),
            }

print(f"Found {len(fixes)} entries to fix (ERROR + WARNING).\n")

# Read JSON
with open(JSON_PATH, encoding="utf-8") as f:
    data = json.load(f)

# Apply fixes
fixed_count = 0
for gov in data["gouvernorats"]:
    for deleg in gov["delegations"]:
        did = deleg["id"]
        if did in fixes:
            old_lat, old_lng = deleg["lat"], deleg["lng"]
            new_lat, new_lng = fixes[did]["lat"], fixes[did]["lng"]
            print(
                f"  FIX {gov['nomFr']:15s} / {deleg['nomFr']:25s} (id={did}): "
                f"({old_lat},{old_lng}) -> ({new_lat},{new_lng})"
            )
            deleg["lat"] = new_lat
            deleg["lng"] = new_lng
            fixed_count += 1

# Write updated JSON
with open(JSON_PATH, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")

print(f"\nDone! Fixed {fixed_count} delegations in {JSON_PATH}")
