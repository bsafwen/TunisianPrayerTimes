"""
Diyanet's prayer-times site, namazvakitleri.diyanet.gov.tr (plan 6.5 `DISTRICT`, 7.5, M3): how its lists and tables
are requested, and a reader for the copies `collect.py` saves under test-data-local/official/tr/ and official/at/.

Requests (read on 2026-10-16; plain GETs, no account):
- A country's provinces: /tr-TR/home/GetRegList?ChangeType=country&CountryId=<id>&Culture=tr-TR, JSON: `StateList`
  (SehirID, SehirAdi, SehirAdiEn) and, for a country listed as one state (Austria: «AVUSTURYA», 657), its entries
  in `StateRegionList` at once.
- A province's entries: /tr-TR/home/GetRegList?ChangeType=state&CountryId=<id>&StateId=<SehirID>&Culture=tr-TR,
  JSON: `StateRegionList` (IlceID, IlceAdi, IlceAdiEn, IlceUrl).
- An entry's tables: its IlceUrl (/tr-TR/<IlceID>/<slug>-icin-namaz-vakti), an HTML page with three tables: the week
  and 31 days from the day of the request («Haftalık», «Aylık»), and the whole next calendar year («Yıllık»: 2027
  when read in October 2026; the current year's past months are not served). Each row: Gregorian date, Hijri date,
  İmsak, Güneş, Öğle, İkindi, Akşam, Yatsı, in the country's clock time.
Countries: TÜRKİYE 2, AVUSTURYA 35. The site's firewall answers some requests (robots.txt) with a block page
(«İsteğiniz güvenlik kurallarına takılmıştır»): `is_block_page` spots it.

Saved copies (collect.py): official/<cc>/lists/country_<id>.json, official/tr/lists/state_<SehirID>.json and
official/<cc>/pages/<IlceID>.html.gz, each page gzip-compressed as served (gunzip gives the page byte for byte; the
manifest note holds the page's own size and SHA-256). Standard library only.
"""

from __future__ import annotations

import datetime
import gzip
import html
import json
import re
from pathlib import Path

BASE = "https://namazvakitleri.diyanet.gov.tr"
COUNTRY_IDS = {"TR": 2, "AT": 35}
MONTHS = {"Ocak": 1, "Şubat": 2, "Mart": 3, "Nisan": 4, "Mayıs": 5, "Haziran": 6, "Temmuz": 7, "Ağustos": 8,
          "Eylül": 9, "Ekim": 10, "Kasım": 11, "Aralık": 12}
BLOCK_MARK = "güvenlik kurallarına takılmıştır"


def country_list_url(cc: str) -> str:
    return f"{BASE}/tr-TR/home/GetRegList?ChangeType=country&CountryId={COUNTRY_IDS[cc]}&Culture=tr-TR"


def state_list_url(cc: str, state_id: str) -> str:
    return f"{BASE}/tr-TR/home/GetRegList?ChangeType=state&CountryId={COUNTRY_IDS[cc]}&StateId={state_id}&Culture=tr-TR"


def is_block_page(text: str) -> bool:
    return BLOCK_MARK in text


def _cells(row: str) -> list[str]:
    return [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<td[^>]*>(.*?)</td>", row, flags=re.S)]


def parse_page(text: str) -> dict:
    """The page's entry id (its `ilceId`), its title and its tables: {"id", "title", "tables": [(caption, {date: six
    "HH:MM"})], "rows": {date: six "HH:MM"}, "conflicts": [date]} (rows merged over the tables; a date two tables
    give differently is listed in conflicts and keeps the later table's row)."""
    m = re.search(r"ilceId\s*=\s*\"?(\d+)", text)
    title = re.search(r"<title>(.*?)</title>", text, flags=re.S)
    tables, rows, conflicts = [], {}, []
    for tm in re.finditer(r"<table.*?</table>", text, flags=re.S):
        block = tm.group(0)
        cap = re.search(r"<caption[^>]*>(.*?)</caption>", block, flags=re.S)
        caption = html.unescape(re.sub(r"<[^>]+>", "", cap.group(1))).strip() if cap else ""
        table = {}
        for r in re.findall(r"<tr.*?</tr>", block, flags=re.S):
            cells = _cells(r)
            if len(cells) < 8:
                continue
            parts = cells[0].split()
            if len(parts) < 3 or parts[1] not in MONTHS:
                continue
            day = datetime.date(int(parts[2]), MONTHS[parts[1]], int(parts[0]))
            times = tuple(cells[2:8])
            if not all(re.fullmatch(r"\d{1,2}:\d{2}", x) for x in times):
                continue
            table[day] = times
        tables.append((caption, table))
        for day, times in table.items():
            if day in rows and rows[day] != times:
                conflicts.append(day)
            rows[day] = times
    return {"id": m.group(1) if m else None, "title": html.unescape(title.group(1)).strip() if title else "",
            "tables": tables, "rows": rows, "conflicts": conflicts}


def read_text(path: Path) -> str:
    data = path.read_bytes()
    if path.suffix == ".gz":
        data = gzip.decompress(data)
    return data.decode("utf-8", errors="replace")


def official_folder(local_data: Path, cc: str) -> Path:
    return local_data / "official" / cc.lower()


def _latest(paths: list[Path]) -> Path | None:
    """Of a name and its same-day copies (name-HHMMSS.ext, collectors/common.py), the one written last."""
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def load_entries(local_data: Path, cc: str) -> list[dict]:
    """The country's entries from the saved lists: {"id", "name", "nameEn", "url", "stateId", "stateName"}."""
    lists = official_folder(local_data, cc) / "lists"
    country = _latest(sorted(lists.glob(f"country_{COUNTRY_IDS[cc]}*.json")))
    if country is None:
        return []
    top = json.loads(country.read_text(encoding="utf-8"))
    states = {s["SehirID"]: s["SehirAdi"] for s in top.get("StateList") or []}
    out, seen = [], set()

    def add(region_list, state_id):
        for e in region_list or []:
            if e["IlceID"] in seen:
                continue
            seen.add(e["IlceID"])
            out.append({"id": e["IlceID"], "name": e["IlceAdi"], "nameEn": e.get("IlceAdiEn") or e["IlceAdi"],
                        "url": e["IlceUrl"], "stateId": state_id, "stateName": states.get(state_id, "")})

    if top.get("StateRegionList") and len(states) == 1:
        add(top["StateRegionList"], next(iter(states)))
    for state_id in states:
        path = _latest(sorted(lists.glob(f"state_{state_id}.json")) + sorted(lists.glob(f"state_{state_id}-*.json")))
        if path is not None:
            add(json.loads(path.read_text(encoding="utf-8")).get("StateRegionList"), state_id)
    return out


def page_files(local_data: Path, cc: str, entry_id: str) -> list[Path]:
    pages = official_folder(local_data, cc) / "pages"
    return sorted(pages.glob(f"{entry_id}.html.gz")) + sorted(pages.glob(f"{entry_id}-*.html.gz"))


def load_rows(local_data: Path, cc: str, entry_id: str) -> tuple[dict, list]:
    """An entry's rows over all its saved pages ({date: six "HH:MM"}) and the dates two copies give differently."""
    rows, conflicts = {}, []
    for path in sorted(page_files(local_data, cc, entry_id), key=lambda p: p.stat().st_mtime):
        page = parse_page(read_text(path))
        conflicts += page["conflicts"]
        for day, times in page["rows"].items():
            if day in rows and rows[day] != times:
                conflicts.append(day)
            rows[day] = times
    return rows, sorted(set(conflicts))
