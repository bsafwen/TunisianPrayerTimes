"""
Morocco (plan 9.3, 8.7, 8.8, Q5): the Ministry of Habous and Islamic Affairs's prayer table for the current Hijri month,
one page per locality: 191 on 2026-10-08, ids 1-169 and 301-322 (Ceuta 24 and Melilla 40 among them). Monthly, for
fits/ma_habous.

The page is https://www.habous.gov.ma/prieres/horaire_hijri_2.php?ville=<id>, the Arabic monthly page, byte for byte the
index.php?ville=<id> that the research saved and the fit reads (research 2026-10-08). It holds the month's header
(«ربيع الآخر 1448», «شتنبر / أكتوبر 2026»), the table (weekday, Hijri day, Gregorian day, then Fajr, sunrise, Dhuhr,
Asr, Maghrib and Isha) and the selector of every locality, with the page's own marked. Rabat's page (id 1) is read
first: its header names the month, and its selector gives the ids (else the 191 known ids). Pages are saved raw as
<folder>/ville_<id>.html, <folder> being <Hijri year>-<month> (1448-04 = Rabi II 1448) when the page states both, else
the run's Gregorian month (<yyyy>-<mm>). fits/ma_habous/fit.py reads every official/ma/**.html, takes the locality from
the last number of the file name and dates the table by its header, so ids are not zero-padded and no name ever gets a
suffix after the id (the collector never saves twice under one name, which would make common.save append the time).

The site shows the current month only: the task must run at least once in every Hijri month (the README's every two
weeks). A run fetches only the localities without a file for the month (Rabat's page aside), so a later run in the same
month costs one request and a run that stopped half-way resumes there. When Rabat's table differs from the one saved
for the month (a revised table), the month starts a new set in <folder>/r2/ (then r3/ ...). A page is saved only if it
holds the table, is the asked locality's (the one its selector marks) and names Rabat's month; a page naming another
month stops the run, since the month changed during it. Failed localities are asked once more at the end; those still
failing fail the run, and the next run asks them again while the month lasts.
"""

from __future__ import annotations

import datetime as dt
import re
from pathlib import Path

NAME = "ma"
CC = "MA"
CADENCE = "monthly"
DESCRIPTION = "Morocco Habous (habous.gov.ma): the current Hijri month's table of each of its 191 localities"

PAGE = "https://www.habous.gov.ma/prieres/horaire_hijri_2.php?ville={}"
KNOWN_IDS = tuple(str(i) for i in (*range(1, 170), *range(301, 323)))     # the selector of 2026-10-08
GIVE_UP = 5            # failures in a row after which a pass stops asking (the site stopped answering)

# Month names, matched after folding (_fold) and without the article: the first month of each pair (Rabi I, Jumada I)
# with a word of _FIRST, the second with a last word of _SECOND; the others by one word.
_ARTICLE = "ال"
_PAIRS = {"ربيع": 3, "جمادي": 5, "جماد": 5}
_FIRST = ("اول", "اولي")
_SECOND = ("اخر", "اخره", "ثاني", "ثانيه")
_ONE_WORD = {"محرم": 1, "صفر": 2, "رجب": 7, "شعبان": 8, "رمضان": 9, "شوال": 10, "قعده": 11, "حجه": 12}
# Folding: tatweel, the vowel marks (U+064B-U+0652) and the superscript alef dropped; alef with madda or hamza and alef
# wasla read as alef; alef maqsura as yeh; teh marbuta as heh.
_FOLD = {0x0640: None, 0x0670: None, **dict.fromkeys(range(0x064B, 0x0653)),
         **dict.fromkeys((0x0622, 0x0623, 0x0625, 0x0671), 0x0627), 0x0649: 0x064A, 0x0629: 0x0647}
# A run of Arabic letters, marks and spaces, then a Hijri year: «ربيع الآخر 1448».
_ARABIC = f"{chr(0x0621)}-{chr(0x064A)}{chr(0x064B)}-{chr(0x0652)}{chr(0x0670)}"
_HIJRI_HEADER = re.compile(f"([{_ARABIC} ]+?)" + r"\s*(?<!\d)(1[34]\d\d)(?!\d)")


class MonthChanged(Exception):
    """A page names another month than Rabat's, read at the start of the run."""


def ascii_text(text: str) -> str:
    """[text] safe to print on any console (non-ASCII escaped)."""
    return text.encode("ascii", "backslashreplace").decode("ascii")


def reason(error: Exception) -> str:
    return ascii_text(f"{type(error).__name__}: {error}")[:300]


def page_url(vid: str) -> str:
    return PAGE.format(vid)


def file_name(folder: str, vid: str) -> str:
    return f"{folder}/ville_{vid}.html"


# ---------------------------------------------------------------------------------------------- reading a page

_SELECT = re.compile(r"""(?is)<select[^>]*\bname\s*=\s*["']?ville\b[^>]*>(.*?)</select>""")
_OPTION = re.compile(r"(?is)<option\b([^>]*)>([^<]*)")
_VALUE = re.compile(r"""(?i)\bvalue\s*=\s*["']?[^"'\s>]*[?&]ville=(\d+)""")


def _options(page: str) -> list[tuple[str, str, bool]]:
    """(id, name, marked) of each option of the page's locality selector."""
    select = _SELECT.search(page)
    out = []
    for attrs, label in _OPTION.findall(select.group(1)) if select else []:
        value = _VALUE.search(attrs)
        if value:
            out.append((value.group(1), " ".join(label.split()), re.search(r"(?i)\bselected\b", attrs) is not None))
    return out


def localities(page: str) -> dict[str, str]:
    """Id -> name of each locality in the page's selector (value='index.php?ville=N', or horaire_hijri_2.php in
    2024), in its order."""
    out: dict[str, str] = {}
    for vid, name, _ in _options(page):
        out.setdefault(vid, name)
    return out


def marked_id(page: str) -> str | None:
    """The locality the page's selector marks as its own (selected), if it marks one."""
    return next((vid for vid, _, marked in _options(page) if marked), None)


def table_rows(page: str) -> list[list[str]]:
    """The rows of the page's table (id="horaire") as fits/ma_habous/fit.py reads them: 9 cells, the last six times."""
    at = page.find('id="horaire"')
    rows = []
    for tr in re.findall(r"(?s)<tr[^>]*>(.*?)</tr>", page[at:] if at >= 0 else ""):
        cells = [re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"(?s)<td[^>]*>(.*?)</td>", tr)]
        if len(cells) == 9 and all(re.fullmatch(r"\d\d?:\d\d", c) for c in cells[3:]):
            rows.append(cells)
    return rows


def _fold(text: str) -> str:
    return " ".join(text.translate(_FOLD).split())


def hijri_month_number(name: str) -> int | None:
    """1-12 for a Hijri month's name, as Morocco and the Mashriq write it («ربيع الآخر» or «ربيع الثاني», «جمادى
    الأولى», «ذو القعدة» or «ذي القعدة», «المحرم» ...); None for anything else."""
    words = [w[len(_ARTICLE):] if w.startswith(_ARTICLE) and len(w) > 3 else w for w in _fold(name).split()]
    pair = next((n for w, n in _PAIRS.items() if w in words), None)
    if pair:
        return pair if any(w in words for w in _FIRST) else pair + 1 if words[-1] in _SECOND else None
    found = {n for w, n in _ONE_WORD.items() if w in words}
    return found.pop() if len(found) == 1 else None


def hijri_month(page: str) -> tuple[int, int] | None:
    """(year, month) of the Hijri month the page's header names («ربيع الآخر 1448»); None unless it names both."""
    text = " ".join(re.sub(r"<[^>]+>", " | ", page).split())
    for m in _HIJRI_HEADER.finditer(text):
        month = hijri_month_number(m.group(1))
        if month:
            return int(m.group(2)), month
    return None


def month_folder(page: str, today: dt.date) -> str:
    """The folder of the page's month: <Hijri year>-<month> when the page names both, else the run's Gregorian month."""
    hijri = hijri_month(page)
    return f"{hijri[0]}-{hijri[1]:02d}" if hijri else f"{today:%Y-%m}"


def page_problem(page: str, vid: str) -> str | None:
    """Why [page] is not locality [vid]'s monthly table: no table rows, or another locality marked in its selector."""
    if not table_rows(page):
        text = re.sub(r"<[^>]+>", " ", re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", page))
        return "no prayer table: " + ascii_text(" ".join(text.split())[:120])
    marked = marked_id(page)
    if marked is not None and marked != vid:
        return f"the page of locality {marked}"
    return None


def set_folder(root: Path, month: str, vid: str, rows: list[list[str]]) -> str:
    """The folder of [month]'s current set under [root] (official/ma): the month's own, or r2, r3 ... under it, the
    first whose page of locality [vid] is missing or holds these table rows."""
    k = 1
    while True:
        folder = month if k == 1 else f"{month}/r{k}"
        path = root / file_name(folder, vid)
        if not path.exists() or table_rows(path.read_text(encoding="utf-8", errors="replace")) == rows:
            return folder
        k += 1


# ---------------------------------------------------------------------------------------------- the run

def collect(session, today: dt.date) -> int:
    root = Path(session.root) / CC.lower()

    # Rabat's page first (or the next locality while it fails): it names the month and lists the localities.
    probe, tried = None, []
    for vid in KNOWN_IDS[:GIVE_UP]:
        try:
            content = session.fetch(page_url(vid))
            text = content.decode("utf-8", errors="replace")
            problem = page_problem(text, vid)
        except Exception as error:  # noqa: BLE001 - the next locality is tried
            problem = reason(error)
        if problem is None:
            probe = (vid, content, text)
            break
        tried.append(f"{vid}: {problem}")
    if probe is None:
        raise RuntimeError("ma: no locality page could be read: " + "; ".join(tried))

    first, first_content, first_text = probe
    hijri, month = hijri_month(first_text), month_folder(first_text, today)
    folder = set_folder(root, month, first, table_rows(first_text))
    names = localities(first_text)
    ids = list(names) or list(KNOWN_IDS)
    if not names:
        print("ma: no locality list on the page; the 191 known ids are asked")
    elif set(ids) != set(KNOWN_IDS):
        added, removed = sorted(set(ids) - set(KNOWN_IDS), key=int), sorted(set(KNOWN_IDS) - set(ids), key=int)
        print(f"ma: the locality list changed (added {', '.join(added) or 'none'}; removed {', '.join(removed) or 'none'}):"
              " update ma.KNOWN_IDS, and the fit's LOCALITIES")
    todo = [v for v in ids if not (root / file_name(folder, v)).exists()]
    already = len(ids) - len(todo)
    saved = 0

    def keep(vid: str, content: bytes, rows: int) -> None:
        nonlocal saved
        note = f"Habous, locality {vid} ({names.get(vid, '?')}): month {month}, {rows} days"
        if session.save(CC, file_name(folder, vid), content, page_url(vid), note):
            saved += 1

    def ask(vid: str) -> None:
        content = session.fetch(page_url(vid))
        text = content.decode("utf-8", errors="replace")
        problem = page_problem(text, vid)
        if problem:
            raise ValueError(problem)
        other = hijri_month(text)
        if hijri and other and other != hijri:
            raise MonthChanged(month_folder(text, today))
        keep(vid, content, len(table_rows(text)))

    def pass_over(vids: list[str]) -> dict[str, str]:
        """Asks each locality; the reason of each failure. After GIVE_UP failures in a row, the rest is left."""
        failed, streak = {}, 0
        for vid in vids:
            if streak >= GIVE_UP:
                failed[vid] = "not asked: the site stopped answering"
                continue
            try:
                ask(vid)
                streak = 0
            except MonthChanged:
                raise
            except Exception as error:  # noqa: BLE001 - asked once more below
                failed[vid] = reason(error)
                streak += 1
        return failed

    if first in todo:
        todo.remove(first)
        keep(first, first_content, len(table_rows(first_text)))
    try:
        failed = pass_over(todo)
        if failed:
            failed = pass_over(list(failed))
    except MonthChanged as change:
        print(f"ma: {folder}: {saved} saved, then a page named month {change}: the month changed during the run")
        raise RuntimeError(f"ma: the month changed during the run ({month} to {change}); run it again") from None
    print(f"ma: {folder}: {len(ids)} localities, {already} already saved, {saved} saved, {len(failed)} failed")
    if failed:
        raise RuntimeError(f"ma: {len(failed)} of {len(ids)} localities failed for {folder}: "
                           + "; ".join(f"{vid}: {why}" for vid, why in failed.items()))
    return saved
