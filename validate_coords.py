#!/usr/bin/env python3
"""Validate gouvernorat coordinates using Google Maps in Chrome via ADB."""
import subprocess
import time
import re
import json

ADB = "/Users/safwen.baroudi/Library/Android/sdk/platform-tools/adb"
DEVICE = "19031FDF6005C6"

# First 10 gouvernorats from the JSON
GOUVERNORATS = [
    ("Tunis", "Tunis, Tunisia", 36.8065, 10.1815),
    ("Zaghouan", "Zaghouan, Tunisia", 36.4029, 10.1429),
    ("Siliana", "Siliana, Tunisia", 36.0849, 9.3708),
    ("Mahdia", "Mahdia, Tunisia", 35.5047, 11.0622),
    ("Gabes", "Gabes, Tunisia", 33.8815, 10.0982),
    ("Sousse", "Sousse, Tunisia", 35.8254, 10.6369),
    ("Ben Arous", "Ben Arous, Tunisia", 36.7533, 10.2282),
    ("Nabeul", "Nabeul, Tunisia", 36.4561, 10.7376),
    ("Tataouine", "Tataouine, Tunisia", 32.9297, 10.4518),
    ("Tozeur", "Tozeur, Tunisia", 33.9197, 8.1335),
]

def adb(*args):
    cmd = [ADB, "-s", DEVICE] + list(args)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    return result.stdout.strip()

def get_chrome_url():
    """Dump UI and extract the Chrome URL bar content."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui_chrome.xml")
    xml = adb("shell", "cat", "/sdcard/ui_chrome.xml")
    # Look for the URL bar text which contains google.com/maps
    matches = re.findall(r'text="([^"]*google\.com/maps[^"]*)"', xml)
    if matches:
        return matches[0]
    # Also try content-desc
    matches = re.findall(r'content-desc="([^"]*google\.com/maps[^"]*)"', xml)
    if matches:
        return matches[0]
    # Try the URL bar resource-id
    matches = re.findall(r'resource-id="com\.android\.chrome:id/url_bar"[^>]*text="([^"]*)"', xml)
    if matches:
        return matches[0]
    # Broader: any text with @ and coordinates pattern
    matches = re.findall(r'text="([^"]*@[\d.]+,[\d.]+[^"]*)"', xml)
    if matches:
        return matches[0]
    return None

def extract_coords_from_url(url):
    """Extract lat,lng from a Google Maps URL like .../@36.8,10.2,12z..."""
    match = re.search(r'@(-?[\d.]+),(-?[\d.]+)', url)
    if match:
        return float(match.group(1)), float(match.group(2))
    # Also try /place/ format or search format
    match = re.search(r'/place/[^/]*/(-?[\d.]+),(-?[\d.]+)', url)
    if match:
        return float(match.group(1)), float(match.group(2))
    return None, None

def open_maps_in_chrome(query):
    """Open Google Maps search in Chrome."""
    url = f"https://www.google.com/maps/search/{query.replace(' ', '+')}"
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-p", "com.android.chrome", "-d", url)

def tap_url_bar():
    """Tap on Chrome URL bar to see the full URL."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui_chrome.xml")
    xml = adb("shell", "cat", "/sdcard/ui_chrome.xml")
    # Find url_bar bounds
    match = re.search(r'resource-id="com\.android\.chrome:id/url_bar"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if match:
        x = (int(match.group(1)) + int(match.group(3))) // 2
        y = (int(match.group(2)) + int(match.group(4))) // 2
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1)

results = []
print(f"{'Gouvernorat':<15} {'Expected Lat':>12} {'Expected Lng':>12} {'Maps Lat':>12} {'Maps Lng':>12} {'Diff (km)':>10}")
print("-" * 80)

for name, query, exp_lat, exp_lng in GOUVERNORATS:
    print(f"Searching for {name}...", end=" ", flush=True)
    
    # Open in Chrome
    open_maps_in_chrome(query)
    
    # Wait for page to load and URL to update with coordinates
    time.sleep(8)
    
    # Try tapping the URL bar to see full URL
    tap_url_bar()
    time.sleep(2)
    
    url = get_chrome_url()
    
    if not url:
        # Try again after more wait
        time.sleep(3)
        url = get_chrome_url()
    
    maps_lat, maps_lng = None, None
    if url:
        maps_lat, maps_lng = extract_coords_from_url(url)
    
    if maps_lat is not None:
        # Haversine distance approximation
        import math
        dlat = math.radians(maps_lat - exp_lat)
        dlng = math.radians(maps_lng - exp_lng)
        a = math.sin(dlat/2)**2 + math.cos(math.radians(exp_lat)) * math.cos(math.radians(maps_lat)) * math.sin(dlng/2)**2
        dist_km = 6371 * 2 * math.asin(math.sqrt(a))
        
        status = "OK" if dist_km < 5 else "MISMATCH"
        print(f"\r{name:<15} {exp_lat:>12.4f} {exp_lng:>12.4f} {maps_lat:>12.4f} {maps_lng:>12.4f} {dist_km:>9.1f}km  {status}")
        results.append((name, exp_lat, exp_lng, maps_lat, maps_lng, dist_km, status))
    else:
        print(f"\r{name:<15} {exp_lat:>12.4f} {exp_lng:>12.4f} {'N/A':>12} {'N/A':>12} {'N/A':>10}  FAILED (url={url})")
        results.append((name, exp_lat, exp_lng, None, None, None, "FAILED"))
    
    # Press back to go back
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)

print("\n\nSummary:")
print("=" * 80)
for name, exp_lat, exp_lng, m_lat, m_lng, dist, status in results:
    if dist is not None:
        print(f"  {name:<15} -> {status} (diff: {dist:.1f} km)")
    else:
        print(f"  {name:<15} -> {status}")
