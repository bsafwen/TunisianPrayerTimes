"""
The Gaza Strip's Ministry of Awqaf (plan 9.3, 8.8, A17), daily: its times as the ministry's Quran Radio 98.6 posts
them on Telegram (each evening, for the next day), the footer of palwakf.ps (today's five times), and the ministry's
monthly tables or Ramadan imsakiya when a channel posts them as images.

Public pages only, no login or API key, each saved raw under official/ps/gaza/:
- telegram/<channel>_<search>_YYYYMMDD.html: the Telegram web preview of a search, t.me/s/<channel>?q=<word> (the 20
  latest posts that match): Quran Radio's «مواقيت» (the daily posts), and the ministry's channel palwakfg for «مواقيت»
  (its monthly tables, as post 6088 of 2023) and «إمساكية». A preview is saved when it holds a post that the last
  saved previews of the same search did not (its view counts change every time, so its bytes always do).
- images/<channel>_<post>.<ext>: once each, the photos of the posts that are not a daily text post and name a month,
  a table, Ramadan or the imsakiya, from Telegram's CDN, at the address the preview gives.
- palwakf/palwakf_ar_YYYYMMDD.html: the home page https://palwakf.ps/ar, whose footer shows today's Fajr, Dhuhr, Asr,
  Maghrib and Isha (no sunrise); named after the run's date, while the manifest's fetch time dates the copy.
A source that fails, a Quran Radio preview without posts or a home page without its five times fails the run, after
the rest is saved.
"""

from __future__ import annotations

import datetime as dt
import html as html_lib
import re
import urllib.parse
from pathlib import Path

NAME = "gaza"
CC = "PS"
CADENCE = "daily"
DESCRIPTION = "Gaza Awqaf: Quran Radio and ministry Telegram previews, palwakf.ps footer times, monthly table images"

SITE = "https://palwakf.ps/ar"
# (channel, search word, its file slug, whether the preview must hold posts)
SEARCHES = (
    ("quranradiofm", "مواقيت", "mawaqit", True),
    ("palwakfg", "مواقيت", "mawaqit", False),
    ("palwakfg", "إمساكية", "imsakiya", False),
)
WIDGET = ("fajr", "zohr", "asr", "maghrib", "esha")   # the footer's data-prayer keys
LABELS = ("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")
TABLE_WORDS = ("شهر", "إمساكي", "امساكي", "رمضان", "جدول")
CDN_HOSTS = ("telesco.pe", "cdn-telegram.org")
IMAGE_MAGIC = (b"\xff\xd8\xff", b"\x89PNG", b"RIFF")    # JPEG, PNG, WebP
MAX_IMAGES = 12                                       # new photos fetched in one run
SEEN_PAGES = 3                                        # saved previews of a search that count as seen


def search_url(channel: str, word: str) -> str:
    return f"https://t.me/s/{channel}?q={urllib.parse.quote(word)}"


def plain(fragment: str) -> str:
    """A message's HTML as text: line breaks kept, tags dropped, entities read."""
    fragment = re.sub(r"<br\s*/?>", "\n", fragment, flags=re.I)
    return html_lib.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()


def posts(page: str) -> list[dict]:
    """The preview's posts in page order: channel, id, text and photos, each photo as (its post id, its URL)."""
    out = []
    for block in page.split('data-post="')[1:]:
        head = re.match(r'([A-Za-z0-9_]+)/(\d+)"', block)
        if not head:
            continue
        text = re.search(r'js-message_text[^>]*>(.*?)</div>', block, re.S)
        photos = []
        for tag in re.findall(r"<a\b[^>]*tgme_widget_message_photo_wrap[^>]*>", block, re.S):
            image = re.search(r"background-image:\s*url\(\s*['\"]?([^'\")\s]+)", tag)
            link = re.search(r'href="https?://t\.me/(?:s/)?[A-Za-z0-9_]+/(\d+)', tag)
            if image:
                photos.append((int(link.group(1)) if link else int(head.group(2)), html_lib.unescape(image.group(1))))
        out.append({"channel": head.group(1), "id": int(head.group(2)), "text": plain(text.group(1)) if text else "",
                    "photos": photos})
    return out


def is_daily(text: str) -> bool:
    """A daily text post: at least five of the six times written out ("الفجر: 4:01")."""
    return sum(bool(re.search(label + r"\s*:?\s*\d{1,2}\s*[:.]\s*\d{2}", text)) for label in LABELS) >= 5


def is_cdn(url: str) -> bool:
    p = urllib.parse.urlsplit(url)
    host = (p.hostname or "").lower()
    return p.scheme == "https" and any(host == h or host.endswith("." + h) for h in CDN_HOSTS)


def table_photos(items: list[dict]) -> list[tuple[str, str, str]]:
    """(file name, URL, post link) of the photos that may be a month's table or the imsakiya: those of the posts that
    are not a daily text post and name a month, a table, Ramadan or the imsakiya, on Telegram's CDN. The name is
    images/<channel>_<photo's post id>.<ext>, with _2, _3... for further photos under one id."""
    out = []
    for post in items:
        if not post["photos"] or is_daily(post["text"]) or not any(w in post["text"] for w in TABLE_WORDS):
            continue
        per_id: dict[int, int] = {}
        for pid, url in post["photos"]:
            per_id[pid] = per_id.get(pid, 0) + 1
            ext = Path(urllib.parse.urlsplit(url).path).suffix.lower()
            ext = ext if ext in (".jpg", ".jpeg", ".png", ".webp") else ".jpg"
            suffix = f"_{per_id[pid]}" if per_id[pid] > 1 else ""
            if is_cdn(url):
                channel = post["channel"]
                out.append((f"images/{channel}_{pid}{suffix}{ext}", url, f"https://t.me/{channel}/{pid}"))
    return out


def footer_times(page: str) -> dict[str, str]:
    """The palwakf.ps footer's times: data-prayer key -> the time as shown ("5:13")."""
    return {k: v.strip() for k, v in re.findall(r'data-prayer="(\w+)"\s*>\s*([^<]*?)\s*<', page)}


def seen_ids(folder: Path, prefix: str) -> set[int]:
    """The post ids of the last saved previews of one search (by file time)."""
    files = sorted(folder.glob(prefix + "_*.html"), key=lambda f: f.stat().st_mtime)[-SEEN_PAGES:]
    return {p["id"] for f in files for p in posts(f.read_text(encoding="utf-8", errors="replace"))}


def collect(session, today: dt.date) -> int:
    root = Path(session.root) / CC.lower() / "gaza"
    count, images, problems, handled = 0, 0, [], set()
    for channel, word, slug, expected in SEARCHES:
        url = search_url(channel, word)
        try:
            page = session.fetch(url)
        except Exception as error:  # noqa: BLE001 - one source's failure must not stop the others
            problems.append(f"{channel} {slug}: {type(error).__name__}: {error}")
            continue
        items = posts(page.decode("utf-8", errors="replace"))
        ids = {p["id"] for p in items}
        new = ids - seen_ids(root / "telegram", f"{channel}_{slug}")
        if new or (expected and not items):
            span = f", ids {min(ids)}-{max(ids)}" if ids else ""
            note = f"Telegram preview of {channel}, search {slug}: {len(items)} posts, {len(new)} new{span}"
            if session.save(CC, f"gaza/telegram/{channel}_{slug}_{today:%Y%m%d}.html", page, url, note):
                count += 1
        if expected and not items:
            problems.append(f"{channel} {slug}: no posts in the preview")
        for name, image_url, link in table_photos(items):
            if name in handled or images >= MAX_IMAGES or (root / name).exists():
                continue
            handled.add(name)
            images += 1
            try:
                content = session.fetch(image_url)
            except Exception as error:  # noqa: BLE001
                problems.append(f"{name}: {type(error).__name__}: {error}")
                continue
            if not content.startswith(IMAGE_MAGIC):
                problems.append(f"{name}: not an image ({len(content)} bytes)")
                continue
            if session.save(CC, "gaza/" + name, content, image_url, f"photo of {link}, from Telegram's CDN"):
                count += 1
    try:
        page = session.fetch(SITE)
    except Exception as error:  # noqa: BLE001
        problems.append(f"palwakf.ps: {type(error).__name__}: {error}")
    else:
        times = footer_times(page.decode("utf-8", errors="replace"))
        shown = " ".join(f"{k} {times[k]}" for k in WIDGET if k in times)
        note = f"palwakf.ps home page; footer: {shown}" if shown else "palwakf.ps home page; no footer times"
        if session.save(CC, f"gaza/palwakf/palwakf_ar_{today:%Y%m%d}.html", page, SITE, note, once=True):
            count += 1
        if not all(times.get(k) for k in WIDGET):
            problems.append("palwakf.ps: the footer's five times are missing")
    if problems:
        raise RuntimeError("gaza: " + "; ".join(problems))
    return count
