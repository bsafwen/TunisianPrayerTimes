#!/usr/bin/env python3
"""
Verify delegation coordinates using Google Geocoding API.

Usage:
    python3 verify_coords_google.py <GOOGLE_API_KEY>

For each delegation, this script:
1. Forward-geocodes: "<delegation name>, <gouvernorat>, Tunisia"
2. Computes the distance between stored coords and Google's result
3. Flags entries where the distance exceeds a threshold
"""

import json
import math
import sys
import time
import urllib.request
import urllib.parse
import csv
from pathlib import Path

# --- Configuration ---
JSON_PATH = Path(__file__).parent / "android-app/app/src/main/assets/gouvernorats.json"
DISTANCE_WARN_KM = 5    # yellow flag
DISTANCE_ERROR_KM = 15  # red flag
GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
RATE_LIMIT_DELAY = 0.05  # Google allows ~50 QPS for geocoding


def haversine_km(lat1, lon1, lat2, lon2):
    """Great-circle distance between two points in km."""
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2 +
         math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) *
         math.sin(dlon / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def geocode(query, api_key, bounds=None):
    """
    Forward-geocode a query string using Google Geocoding API.
    Returns (lat, lng, formatted_address, place_types) or None on failure.
    Optionally biases results within `bounds` (sw_lat,sw_lng|ne_lat,ne_lng).
    """
    params = {
        "address": query,
        "key": api_key,
        "region": "tn",
        "language": "fr",
    }
    if bounds:
        params["bounds"] = bounds

    url = f"{GEOCODE_URL}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url)

    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())

    if data["status"] == "OK" and data["results"]:
        loc = data["results"][0]["geometry"]["location"]
        addr = data["results"][0]["formatted_address"]
        types = data["results"][0].get("types", [])
        return loc["lat"], loc["lng"], addr, types
    return None


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 verify_coords_google.py <GOOGLE_API_KEY>")
        sys.exit(1)

    api_key = sys.argv[1]

    with open(JSON_PATH, encoding="utf-8") as f:
        data = json.load(f)

    delegations = []
    for g in data["gouvernorats"]:
        for d in g["delegations"]:
            delegations.append((g, d))

    total = len(delegations)
    print(f"Verifying {total} delegations against Google Geocoding API...\n")

    results = []
    errors = []
    warnings = []
    ok_count = 0

    for idx, (gov, deleg) in enumerate(delegations, 1):
        name = deleg["nomFr"]
        gov_name = gov["nomFr"]
        stored_lat = deleg.get("lat", 0)
        stored_lng = deleg.get("lng", 0)

        # Build query — try delegation + gouvernorat + Tunisia
        query = f"{name}, {gov_name}, Tunisie"

        # Tunisia bounding box to bias results
        bounds = "30.2,-0.5|37.5,11.6"

        try:
            result = geocode(query, api_key, bounds=bounds)

            if result is None:
                # Retry with just the delegation name + Tunisia
                query_retry = f"{name}, Tunisie"
                result = geocode(query_retry, api_key, bounds=bounds)

            if result is None:
                msg = f"[{idx:3d}/{total}] ❌ NO RESULT  {gov_name:15s} / {name:25s}"
                print(msg)
                errors.append({
                    "gouvernorat": gov_name,
                    "delegation": name,
                    "delegation_id": deleg["id"],
                    "stored_lat": stored_lat,
                    "stored_lng": stored_lng,
                    "google_lat": None,
                    "google_lng": None,
                    "distance_km": None,
                    "status": "NO_RESULT",
                    "google_address": "",
                })
                continue

            g_lat, g_lng, g_addr, g_types = result
            dist = haversine_km(stored_lat, stored_lng, g_lat, g_lng)

            entry = {
                "gouvernorat": gov_name,
                "delegation": name,
                "delegation_id": deleg["id"],
                "stored_lat": stored_lat,
                "stored_lng": stored_lng,
                "google_lat": round(g_lat, 6),
                "google_lng": round(g_lng, 6),
                "distance_km": round(dist, 2),
                "status": "",
                "google_address": g_addr,
            }

            if dist > DISTANCE_ERROR_KM:
                entry["status"] = "ERROR"
                errors.append(entry)
                icon = "🔴"
            elif dist > DISTANCE_WARN_KM:
                entry["status"] = "WARNING"
                warnings.append(entry)
                icon = "🟡"
            else:
                entry["status"] = "OK"
                ok_count += 1
                icon = "🟢"

            results.append(entry)
            print(
                f"[{idx:3d}/{total}] {icon} {dist:7.2f} km  "
                f"{gov_name:15s} / {name:25s}  "
                f"stored=({stored_lat:.4f},{stored_lng:.4f})  "
                f"google=({g_lat:.4f},{g_lng:.4f})  "
                f"→ {g_addr[:60]}"
            )

        except Exception as e:
            msg = f"[{idx:3d}/{total}] ⚠️  API ERROR  {gov_name:15s} / {name:25s}: {e}"
            print(msg)
            errors.append({
                "gouvernorat": gov_name,
                "delegation": name,
                "delegation_id": deleg["id"],
                "stored_lat": stored_lat,
                "stored_lng": stored_lng,
                "google_lat": None,
                "google_lng": None,
                "distance_km": None,
                "status": "API_ERROR",
                "google_address": str(e),
            })

        sys.stdout.flush()
        time.sleep(RATE_LIMIT_DELAY)

    # --- Summary ---
    print("\n" + "=" * 80)
    print(f"SUMMARY: {total} delegations checked")
    print(f"  🟢 OK (< {DISTANCE_WARN_KM} km):       {ok_count}")
    print(f"  🟡 Warning ({DISTANCE_WARN_KM}-{DISTANCE_ERROR_KM} km): {len(warnings)}")
    print(f"  🔴 Error (> {DISTANCE_ERROR_KM} km):     {len([e for e in errors if e['status'] == 'ERROR'])}")
    print(f"  ❌ No result / API error:  {len([e for e in errors if e['status'] in ('NO_RESULT', 'API_ERROR')])}")
    print("=" * 80)

    if warnings:
        print(f"\n🟡 WARNINGS ({len(warnings)}):")
        for w in sorted(warnings, key=lambda x: x["distance_km"], reverse=True):
            print(
                f"  {w['distance_km']:7.2f} km  {w['gouvernorat']:15s} / {w['delegation']:25s}  "
                f"stored=({w['stored_lat']},{w['stored_lng']})  "
                f"google=({w['google_lat']},{w['google_lng']})"
            )

    if errors:
        print(f"\n🔴 ERRORS ({len(errors)}):")
        for e in sorted(errors, key=lambda x: x["distance_km"] or 9999, reverse=True):
            if e["distance_km"] is not None:
                print(
                    f"  {e['distance_km']:7.2f} km  {e['gouvernorat']:15s} / {e['delegation']:25s}  "
                    f"stored=({e['stored_lat']},{e['stored_lng']})  "
                    f"google=({e['google_lat']},{e['google_lng']})"
                )
            else:
                print(
                    f"  {'N/A':>7s}     {e['gouvernorat']:15s} / {e['delegation']:25s}  "
                    f"[{e['status']}] {e['google_address'][:60]}"
                )

    # --- Export CSV ---
    csv_path = Path(__file__).parent / "coords_verification_report.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "gouvernorat", "delegation", "delegation_id",
            "stored_lat", "stored_lng", "google_lat", "google_lng",
            "distance_km", "status", "google_address",
        ])
        writer.writeheader()
        writer.writerows(results + [e for e in errors if e not in results])

    print(f"\nFull report saved to: {csv_path}")


if __name__ == "__main__":
    main()
