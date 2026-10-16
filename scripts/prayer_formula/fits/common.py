"""
Shared helpers for the authority fits (plan 9.4, milestones AM2 and M3).

- Scoring always goes through the independent reference engine (prayer_engine_reference.py), so a fitted
  profile scores exactly as the Kotlin engine computes it (the two agree to the millisecond, 9.1).
- Search loops may use faster special-purpose code; the final numbers are re-scored here.
- The output is an official-zone asset (android-app/app/src/main/assets/official-zones/<authority>.json,
  schema 1, read by multiplatform/shared/.../place/OfficialZones.kt) and FitStats records (plan 6.3, 9.5).

Standard library only. Official tables stay in test-data-local/ (D13, A7); nothing here copies them.
"""

from __future__ import annotations

import datetime
import json
import math
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import prayer_engine_reference as ref  # noqa: E402

ROOT = HERE.parents[2]
LOCAL_DATA = ROOT.parent.parent.parent / "test-data-local" if (ROOT.parent.parent.parent / "test-data-local").exists() else ROOT / "test-data-local"
ASSETS = ROOT / "android-app" / "app" / "src" / "main" / "assets" / "official-zones"
EVENTS = ("FAJR", "SUNRISE", "DHUHR", "ASR", "MAGHRIB", "ISHA")


def e5(x: float) -> int:
    return int(round(x * 1e5))


def point(lat: float, lng: float) -> dict:
    return {"latE5": e5(lat), "lngE5": e5(lng)}


def method_json(**fields) -> dict:
    """A full PrayerMethod JSON with the defaults of the Kotlin type, overridden by [fields]."""
    m = {
        "id": "fit", "fajr": {"type": "angle", "degrees": 18.0}, "isha": {"type": "angle", "degrees": 18.0},
        "ishaLatest": None, "ishaEarliest": None, "maghribAngle": None, "offsets": {}, "dhuhrAfterTransitMin": 0.0,
        "asrShadow": 1, "asr": {"type": "shadow"}, "elevationShifts": [], "seasonalOffsets": {}, "takvimMeans": [],
        "asrSun": "GEOMETRIC", "sunModel": "MEEUS", "twilightLatitudeScale": 1.0, "highLatitude": {"type": "angle_based"},
        "polar": "MIN_DAY_NIGHT_5H", "minDayNightHours": None,
        "rounding": {e: "NEAREST" for e in EVENTS},
        "horizon": {"sunrise": {"type": "standard"}, "sunset": {"type": "standard"}}, "dipOnTwilight": False,
        "sunEvaluation": {}, "ramadanOffsets": {}, "ramadanDays": "PLACE_CALENDAR", "revisions": [],
        "maghribTerrain": None, "jumua": {"type": "at_dhuhr"}, "evidence": {"kind": "FROM_TABLES", "source": "fit"}, "fit": None,
    }
    m.update(fields)
    return m


def zone_json(zone_id: str, lat: float, lng: float, **fields) -> dict:
    z = {"authority": "fit", "id": zone_id, "point": point(lat, lng), "morningPoint": None, "eveningPoint": None,
         "elevationM": None, "eveningElevationM": None, "pressureHpa": None, "temperatureC": None,
         "sunriseAltitudeDeg": None, "sunsetAltitudeDeg": None, "offsets": {}, "baseZoneId": None,
         "offsetRule": "PUBLISHED", "terrainClass": None, "switchDays": {}}
    z.update(fields)
    return z


def utc_offset(zone_id: str, day: datetime.date, hour: int = 12, minute: int = 0) -> int:
    """The zone's UTC offset in seconds at that local time on [day], summer time included (tzdata through zoneinfo)."""
    tz = ZoneInfo(zone_id)
    return int(datetime.datetime(day.year, day.month, day.day, hour, minute, tzinfo=tz).utcoffset().total_seconds())


def local_to_epoch(zone_id: str, day: datetime.date, hhmm: str) -> int:
    """A published clock time "H:MM" or "HH:MM" on [day] in the zone, as epoch seconds (hours of 24 and more run into the next days)."""
    h, m = (int(x) for x in hhmm.strip().split(":"))
    extra, h = divmod(h, 24)
    d = day + datetime.timedelta(days=extra)
    local = datetime.datetime(d.year, d.month, d.day, h, m)
    return int((local - datetime.datetime(1970, 1, 1)).total_seconds()) - utc_offset(zone_id, d, h, m)


def standard_offset(zone_id: str, day: datetime.date) -> int:
    """The zone's standard UTC offset in seconds: the table below, else tzdata's offset minus its summer time."""
    fixed = {
        "Asia/Riyadh": 3, "Asia/Muscat": 4, "Asia/Qatar": 3, "Asia/Amman": 3, "Africa/Cairo": 2, "Asia/Dubai": 4,
        "Asia/Aden": 3, "Africa/Algiers": 1, "Africa/Casablanca": 1, "Africa/Tripoli": 2, "Asia/Baghdad": 3,
        "Asia/Kuwait": 3, "Asia/Bahrain": 3, "Africa/Khartoum": 2, "Africa/Mogadishu": 3, "Africa/Djibouti": 3,
        "Indian/Comoro": 3, "Africa/Nouakchott": 0, "Asia/Damascus": 3, "Asia/Beirut": 2, "Asia/Gaza": 2, "Asia/Hebron": 2,
        "Europe/Paris": 1, "Europe/Vienna": 1, "Europe/Sarajevo": 1, "Europe/Zagreb": 1, "Europe/Ljubljana": 1,
        "Europe/Luxembourg": 1, "Indian/Reunion": 4, "Europe/Moscow": 3, "Europe/Dublin": 0, "Europe/Oslo": 1,
        "Europe/Belgrade": 1, "Europe/Tirane": 1, "Europe/Skopje": 1, "Europe/Sofia": 2, "Europe/Istanbul": 3,
        "America/Martinique": -4, "America/Cayenne": -3,
    }
    if zone_id in fixed:
        return fixed[zone_id] * 3600
    tz = ZoneInfo(zone_id)
    noon = datetime.datetime(day.year, day.month, day.day, 12, tzinfo=tz)
    return int((noon.utcoffset() - noon.dst()).total_seconds())


def case(method: dict, lat: float, lng: float, zone_id: str, day: datetime.date, *, zone: dict | None = None,
         base_zone: dict | None = None, window: tuple[datetime.date, datetime.date] | None = None,
         elevation_m: int | None = None, country: str | None = None, ramadan: bool = False) -> dict:
    """A case in the reference's format (README, the cross-check subsection); clock settings read at the day's real offset."""
    std = standard_offset(zone_id, day)
    actual = utc_offset(zone_id, day)
    midnight = int((datetime.datetime(day.year, day.month, day.day) - datetime.datetime(1970, 1, 1)).total_seconds()) - utc_offset(zone_id, day, 0)
    return {
        "id": f"{lat},{lng},{day}",
        "place": {"latE5": e5(lat), "lngE5": e5(lng), "elevationM": elevation_m, "zoneId": zone_id, "countryCode": country},
        "date": day.isoformat(),
        "method": method,
        "ramadanDay": ramadan,
        "zone": zone,
        "baseZone": base_zone,
        "offsetWindow": None if window is None else {"from": window[0].isoformat(), "to": window[1].isoformat()},
        "adjustments": {},
        "zoneFacts": {"standardOffsetSeconds": std, "utcOffsetSeconds": actual, "localMidnightEpochSeconds": midnight,
                      "ishaLatestEpochSeconds": None, "ishaEarliestEpochSeconds": None},
    }


def compute(c: dict) -> dict[str, tuple[float | None, int | None, str]]:
    """The reference's day: per event, (unrounded epoch seconds, rounded epoch seconds, status)."""
    day = ref.CaseEngine(c).run()
    out = {}
    for e in EVENTS:
        minute = day.minutes.get(e)
        out[e] = (day.unrounded.get(e), None if minute is None else int(round(minute)), day.status(e))
    return out


def local_minutes(epoch_seconds: float, offset_seconds: int) -> float:
    """Minutes after local midnight of an instant in a fixed offset (may exceed 1440 or be negative across days)."""
    t = epoch_seconds + offset_seconds
    return (t % 86400) / 60.0


@dataclass
class Score:
    """Exact and within-1 counts per event, with the days per calendar month."""
    exact: dict = field(default_factory=lambda: defaultdict(int))
    within1: dict = field(default_factory=lambda: defaultdict(int))
    total: dict = field(default_factory=lambda: defaultdict(int))
    months: dict = field(default_factory=lambda: defaultdict(lambda: [0] * 12))
    worst: int = 0
    misses: list = field(default_factory=list)

    def add(self, event: str, day: datetime.date, computed_minute: int, published_minute: int):
        diff = computed_minute - published_minute
        self.total[event] += 1
        self.months[event][day.month - 1] += 1
        if diff == 0:
            self.exact[event] += 1
        if abs(diff) <= 1:
            self.within1[event] += 1
        else:
            self.misses.append((event, day.isoformat(), diff))
        self.worst = max(self.worst, abs(diff))

    def shares(self) -> dict:
        return {e: (self.exact[e] / self.total[e] if self.total[e] else 0.0) for e in EVENTS}

    def within1_shares(self) -> dict:
        return {e: (self.within1[e] / self.total[e] if self.total[e] else 0.0) for e in EVENTS}


def fit_stats(fitted_on: list[dict], held_out: list[dict], score: Score, ramadan_days: int = 0, quirk_days: int = 0,
              regions: list[str] | None = None) -> dict:
    """A FitStats record (plan 6.3) from the held-out score."""
    stats = {
        "fittedOn": fitted_on, "heldOut": held_out,
        "exact": {e: round(v, 4) for e, v in score.shares().items()},
        "within1": {e: round(v, 4) for e, v in score.within1_shares().items()},
        "daysPerMonth": {e: score.months[e] for e in EVENTS},
        "heldOutRamadanDays": ramadan_days, "quirkDays": quirk_days, "maxDiffMin": score.worst,
    }
    if regions:
        stats["regions"] = sorted(regions)
    return stats


def sample(source: str, start: datetime.date, end: datetime.date, days: int, zones: int, month_split: bool = False) -> dict:
    return {"source": source, "from": start.isoformat(), "to": end.isoformat(), "days": days, "zones": zones, "monthSplit": month_split}


def write_asset(authority: str, method_id: str, country: str, mapping: str, zones: list[dict], *, fit: dict | None = None,
                source: str = "", by_admin1: bool = False, named_first: bool = False, terrain_class_km: float | None = None,
                hijri_month_starts: dict[int, list[datetime.date]] | None = None) -> Path:
    """Writes the asset (schema 1) with LF line endings; zones are dicts in the asset's zone format.
    Every zone needs "id", "nameAr" (Arabic, never empty) and "point". [hijri_month_starts]: Algeria's 12 month
    starts per Hijri year (plan 6.2, 8.8)."""
    asset = {"schema": 1, "authority": authority, "methodId": method_id, "country": country, "mapping": mapping}
    if by_admin1:
        asset["byAdmin1"] = True
    if named_first:
        asset["namedFirst"] = True
    if terrain_class_km is not None:
        asset["terrainClassKm"] = terrain_class_km
    asset["source"] = source
    if fit is not None:
        asset["fit"] = fit
    if hijri_month_starts:
        asset["hijriMonthStarts"] = {str(y): [d.isoformat() for d in starts] for y, starts in sorted(hijri_month_starts.items())}
    asset["zones"] = [{k: v for k, v in z.items() if v not in (None, {}, [], "PUBLISHED", False, 0) or k in ("id", "nameAr", "point")}
                      for z in zones]
    ASSETS.mkdir(parents=True, exist_ok=True)
    path = ASSETS / f"{authority}.json"
    path.write_text(json.dumps(asset, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    write_index()
    return path


def write_index() -> Path:
    """The assets' manifest, official-zones/index.tsv (authority, method, its own and its zones' countries), which the
    app reads to parse an asset only when its method serves a place (OfficialZoneAssets); OfficialZoneAssetsTest
    checks it against the assets."""
    rows = []
    for path in sorted(ASSETS.glob("*.json")):
        asset = json.loads(path.read_text(encoding="utf-8"))
        countries = {c.strip() for c in asset["country"].split(",") if c.strip()}
        countries |= {z["country"] for z in asset["zones"] if z.get("country")}
        rows.append(f"{asset['authority']}\t{asset['methodId']}\t{','.join(sorted(countries))}")
    out = ASSETS / "index.tsv"
    out.write_text("authority\tmethodId\tcountries\n" + "".join(row + "\n" for row in rows), encoding="utf-8", newline="\n")
    return out
