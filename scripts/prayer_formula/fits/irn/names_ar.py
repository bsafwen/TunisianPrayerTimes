"""
Arabic names for IRN's 358 entries (plan 6.5: every zone needs an Arabic `nameAr`). The entry's municipality name
(`kommune_name`, its Norwegian form where IRN gives a Sami one first) is written in Arabic by, in order: the overrides
below (towns with an established Arabic spelling), the place catalogue's Arabic label of a catalogue row of the same
name in that municipality (android-app/.../places/europe-cities.tsv, from GeoNames and Wikidata), else a rule-based
transliteration of the Norwegian spelling. The catalogue's labels may replace these in M5 (as for bosnia_iz).
"""

from __future__ import annotations

import re
import unicodedata

# The Norwegian name where IRN writes the Sami one first (or several names).
NORWEGIAN = {
    "Guovdageaidnu - Kautokeino": "Kautokeino", "Kárásjohka - Karasjok": "Karasjok", "Raarvihke - Røyrvik": "Røyrvik",
    "Snåase - Snåsa": "Snåsa", "Unjárga-Nesseby": "Nesseby", "Deatnu-Tana": "Tana", "Loabák - Lavangen": "Lavangen",
    "Gáivuotna - Kåfjord - Kaivuono": "Kåfjord", "Porsanger - Porsángu - Porsanki": "Porsanger",
    "Fauske - Fuosko": "Fauske", "Sortland - Suortá": "Sortland", "Storfjord - Omasvuotna - Omasvuono": "Storfjord",
    "Hamarøy - Hábmer": "Hamarøy", "Snåase": "Snåsa", "Gáivuotna - Kåfjord": "Kåfjord",
}

OVERRIDES = {
    "Oslo": "أوسلو", "Bergen": "بيرغن", "Trondheim": "تروندهايم", "Stavanger": "ستافانغر", "Kristiansand": "كريستيانساند",
    "Drammen": "درامن", "Fredrikstad": "فريدريكستاد", "Sandnes": "ساندنس", "Tromsø": "ترومسو", "Sarpsborg": "ساربسبورغ",
    "Skien": "شيين", "Ålesund": "أولسوند", "Bodø": "بودو", "Haugesund": "هاوغسوند", "Tønsberg": "تونسبيرغ", "Moss": "موس",
    "Porsgrunn": "بورسغرون", "Arendal": "أرندال", "Hamar": "هامار", "Larvik": "لارفيك", "Halden": "هالدن",
    "Lillehammer": "ليلهامر", "Molde": "مولده", "Harstad": "هارستاد", "Gjøvik": "يوفيك", "Kongsberg": "كونغسبرغ",
    "Narvik": "نارفيك", "Alta": "ألتا", "Asker": "أسكر", "Bærum": "بيروم", "Lillestrøm": "ليلستروم", "Svalbard": "سفالبارد",
    "Hammerfest": "هامرفست", "Vadsø": "فادسو", "Kirkenes": "كيركينس", "Sør-Varanger": "سور فارانغر", "Kautokeino": "كاوتوكينو",
    "Karasjok": "كاراسيوك", "Steinkjer": "ستاينشير", "Levanger": "ليفانغر", "Verdal": "فيردال", "Namsos": "نامسوس",
    "Elverum": "إلفيروم", "Kongsvinger": "كونغسفينغر", "Horten": "هورتن", "Sandefjord": "ساندفيورد", "Grimstad": "غريمستاد",
    "Mandal": "ماندال", "Egersund": "إيغرسوند", "Eigersund": "إيغرسوند", "Kristiansund": "كريستيانسوند", "Førde": "فورده",
    "Stord": "ستورد", "Voss": "فوس", "Os": "أوس", "Nordkapp": "نوردكاب", "Vardø": "فاردو", "Røros": "روروس",
}

_SAMI = set("áčđŋšŧžÁČĐŊŠŦŽ")

# Norway's counties (2024), to tell apart two entries whose Arabic names coincide (the two Herøy, the two Våler,
# Sola and Sula, Ås and Os, Ørland and Aurland).
COUNTIES = {
    "Oslo": "أوسلو", "Rogaland": "روغالاند", "Møre og Romsdal": "مور ورومسدال", "Nordland": "نوردلاند",
    "Østfold": "أوستفولد", "Akershus": "أكيرسهوس", "Buskerud": "بوسكيرود", "Innlandet": "إينلانديت",
    "Vestfold": "فيستفولد", "Telemark": "تيلمارك", "Agder": "أغدر", "Vestland": "فيستلاند", "Trøndelag": "تروندلاغ",
    "Troms": "ترومس", "Finnmark": "فينمارك", "Svalbard": "سفالبارد",
}


def county(fylke_name: str) -> str:
    """The Arabic county name of IRN's `fylke_name` ("15 - Møre og Romsdal")."""
    name = fylke_name.split(" - ", 1)[-1].strip()
    return COUNTIES.get(name, transliterate(name))


def main_name(location_name: str, kommune_name: str) -> str:
    """The Norwegian municipality name an entry stands for."""
    name = (kommune_name or location_name).strip().rstrip("-").strip()
    for key, value in NORWEGIAN.items():
        if name.startswith(key):
            return value
    for sep in (" / ", " - ", " ("):
        if sep in name:
            parts = [p.strip(" -") for p in name.split(sep)]
            plain = [p for p in parts if p and not (set(p) & _SAMI)]
            name = plain[0] if plain else parts[0]
    return name.strip()


# ---- transliteration -------------------------------------------------------------------------------------------------

_VOWELS = "aeiouyæøå"


def _translit_word(w: str) -> str:
    w = w.lower()
    out = []
    i = 0
    n = len(w)

    def nxt(k=1):
        return w[i + k] if i + k < n else ""

    def front(k=1):
        s = w[i + k:i + k + 2]
        return s[:1] in ("i", "y") or s in ("ei", "øy")
    while i < n:
        c = w[i]
        initial = i == 0
        final = i == n - 1
        if c in _VOWELS:
            two = w[i:i + 2]
            if two in ("ei", "ai"):
                out.append("أي" if initial else "اي")
                i += 2
                continue
            if two == "au":
                out.append("أو" if initial else "او")
                i += 2
                continue
            if two in ("øy", "oy"):
                out.append("أوي" if initial else "وي")
                i += 2
                continue
            if two == "aa":
                out.append("أو" if initial else "و")
                i += 2
                continue
            if c in "aæ":
                out.append("أ" if initial else "ا")
            elif c in "ou" or c in "øå":
                out.append("أو" if initial else "و")
            elif c in "iy":
                out.append("إي" if initial else "ي")
            elif c == "e":
                if initial:
                    out.append("إي")
                elif final:
                    out.append("ه")
                else:
                    # a stressed first-syllable e is written, an unstressed one before a consonant mostly not
                    prev_v = any(ch in _VOWELS for ch in w[:i])
                    out.append("" if prev_v else "ي")
            i += 1
            continue
        three = w[i:i + 3]
        two = w[i:i + 2]
        if three == "skj":
            out.append("ش")
            i += 3
            continue
        if two in ("sj", "kj", "tj"):
            out.append("ش")
            i += 2
            continue
        if two == "sk" and front(2):
            out.append("ش")
            i += 2
            continue
        if two in ("gj", "hj", "lj") and initial:
            out.append("ي")
            i += 2
            continue
        if two == "hv":
            out.append("ف")
            i += 2
            continue
        if two in ("ng",):
            out.append("نغ")
            i += 2
            continue
        if two in ("ch", "ck"):
            out.append("ك")
            i += 2
            continue
        if two == "th":
            out.append("ت")
            i += 2
            continue
        if two == "ph":
            out.append("ف")
            i += 2
            continue
        if two == "dt":
            out.append("ت")
            i += 2
            continue
        if i + 1 < n and w[i + 1] == c:          # double consonant
            i += 1
            continue
        m = {"b": "ب", "c": "س" if nxt() in ("e", "i", "y") else "ك", "d": "د", "f": "ف",
             "g": "ي" if front() else "غ", "h": "ه", "j": "ي", "k": "ش" if front() and initial else "ك", "l": "ل",
             "m": "م", "n": "ن", "p": "ب", "q": "ك", "r": "ر", "s": "س", "t": "ت", "v": "ف", "w": "و", "x": "كس",
             "z": "ز"}.get(c)
        if m is not None:
            out.append(m)
        i += 1
    return "".join(out)


def transliterate(name: str) -> str:
    words = re.split(r"[\s\-]+", name.strip())
    return " ".join(_translit_word(unicodedata.normalize("NFC", w)) for w in words if w)


def arabic(location_name: str, kommune_name: str, catalogue: dict[str, str] | None = None) -> str:
    name = main_name(location_name, kommune_name)
    if name in OVERRIDES:
        return OVERRIDES[name]
    if catalogue:
        label = catalogue.get(name)
        if label and "بلدية" not in label:
            return label
    return transliterate(name)
