#!/usr/bin/env python3
"""Build the offline Qaloun search index without changing the supplied page images.

See docs/quran-reader-sources.md for inputs, provenance, and page-map verification.
This script performs no network requests. Its inputs must already exist locally.
"""
from __future__ import annotations

import argparse
from bisect import bisect_right
from collections import Counter
from difflib import SequenceMatcher
import html
import json
from pathlib import Path
import re
import unicodedata
import xml.etree.ElementTree as ET


def normalized(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = "".join(c for c in text if not unicodedata.category(c).startswith("M") and c not in "ـۥۦ")
    return text.translate(str.maketrans("أإآٱىئؤةیک", "ااااييوهيك"))


def skeleton(text: str) -> str:
    # Used for alignment only; never for user search or displayed text.
    return normalized(text).replace("ا", "").replace("صلوه", "صله").replace("زكوه", "زكه").replace("حيوه", "حيه")


def spelling_alias(source: str, imlai: str) -> str:
    """Accept an imlai alias only for orthography, not a different riwaya reading."""
    original, alias = normalized(source), normalized(imlai)
    if original == alias:
        return imlai
    if "ٰ" in source and skeleton(original) == skeleton(alias):
        return imlai
    if any(c in source for c in "ۥۦۧ") and original.replace("ۥ", "و").replace("ۦ", "ي").replace("ۧ", "ي") == alias:
        return imlai
    if any(word in original for word in ("صلوه", "زكوه", "حيوه", "مشكوه", "نجوه", "غدوه")):
        if original.replace("وه", "اه") == alias:
            return imlai
    return source


def build(args: argparse.Namespace) -> None:
    source_chapters = json.loads(args.chapter_map.read_text(encoding="utf-8"))["surahs"]
    starts = [(int(p.attrib["sura"]), int(p.attrib["aya"])) for p in ET.parse(args.metadata).getroot().find("pages")]
    assert len(starts) == 604
    simple: dict[int, list[tuple[int, str, int]]] = {n: [] for n in range(1, 115)}
    for line in args.imlai.read_text(encoding="utf-8-sig").splitlines():
        match = re.match(r"^(\d+)\|(\d+)\|(.+)$", line)
        if not match:
            continue
        chapter, verse, text = int(match[1]), int(match[2]), match[3]
        page = max(1, bisect_right(starts, (chapter, verse)) - 1)
        words = text.split()
        if verse == 1 and chapter != 9:
            assert words[:4] == ["بسم", "الله", "الرحمن", "الرحيم"]
            words = words[4:]
        simple[chapter].extend((verse, word, page) for word in words)

    names = json.loads(args.names.read_text(encoding="utf-8"))
    chapters, entries, audit = [], [], []
    total_verses = 0
    for number in range(1, 115):
        source = (args.source / f"surah-{number:03}.html").read_text(encoding="utf-8")
        verses = [(int(n), html.unescape(t)) for t, n in re.findall(r"<p>([^<]+) ﴿(\d+)﴾</p>", source)]
        assert verses and [n for n, _ in verses] == list(range(1, len(verses) + 1)), number
        total_verses += len(verses)
        words = [(verse, word) for verse, text in verses for word in text.split() if any(c.isalpha() for c in word)]
        reference = simple[number]
        matcher = SequenceMatcher(None, [skeleton(w) for _, w in words], [skeleton(w) for _, w, _ in reference], autojunk=False)
        locations: list[int | None] = [None] * len(words)
        aliases = [word for _, word in words]
        ambiguous = []
        boundary_replacements = []
        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag != "equal" and len({p for _, _, p in reference[j1:j2]}) > 1:
                boundary_replacements.append({"qaloon": " ".join(w for _, w in words[i1:i2]), "imlai": [(w, p) for _, w, p in reference[j1:j2]]})
            if tag == "equal" or (tag == "replace" and i2 - i1 == j2 - j1):
                for i, j in zip(range(i1, i2), range(j1, j2)):
                    locations[i] = reference[j][2]
                    aliases[i] = spelling_alias(words[i][1], reference[j][1])
            elif tag == "delete" or tag == "replace":
                before = reference[j1][2] if j1 < j2 else reference[max(0, j1 - 1)][2]
                after = reference[j2 - 1][2] if j1 < j2 else reference[min(len(reference) - 1, j2)][2]
                if before != after:
                    ambiguous.append({"tag": tag, "qaloon": [w for _, w in words[i1:i2]], "pages": [before, after]})
                for i in range(i1, i2):
                    locations[i] = before
                if i2 - i1 == 1 and tag == "replace":
                    joined = " ".join(w for _, w, _ in reference[j1:j2])
                    if skeleton(words[i1][1]) == skeleton(joined).replace(" ", ""):
                        aliases[i1] = joined
        assert not ambiguous, (number, ambiguous)
        assert all(p is not None for p in locations)
        assert locations == sorted(locations), number
        assert locations[0] == source_chapters[number - 1]["sourcePage"], (number, locations[0])
        chapters.append({"number": number, "name": names[number - 1]["name"], "page": locations[0], "verseCount": len(verses)})
        if number != 9:
            entries.append({"surah": number, "page": locations[0], "text": "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"})
        # Keep true Qaloun verse fragments. Splitting at page boundaries makes every
        # word-search destination unambiguous even when the verse straddles pages.
        start = 0
        while start < len(words):
            end = start + 1
            while end < len(words) and words[end][0] == words[start][0] and locations[end] == locations[start]:
                end += 1
            entry = {"surah": number, "page": locations[start], "text": " ".join(w for _, w in words[start:end])}
            alias = " ".join(aliases[start:end])
            if normalized(alias) != normalized(entry["text"]):
                entry["searchText"] = alias
            entries.append(entry)
            start = end
        audit.append({"surah": number, "verses": len(verses), "sourceWords": len(words), "referenceWords": len(reference), "equalWordRatio": round(matcher.ratio(), 5), "startPage": locations[0], "endPage": locations[-1], "boundaryReplacements": boundary_replacements})

    assert total_verses == 6214, total_verses
    assert set(e["page"] for e in entries) == set(range(1, 604))
    output = {
        "version": 1,
        "edition": "Quran_Qaloun.pdf",
        "quranPages": 603,
        "totalPages": 621,
        "textSource": "https://quranpedia.net/surah/7/{surah}",
        "pageBoundarySource": "https://tanzil.net/res/text/metadata/quran-data.xml",
        "surahs": chapters,
        "entries": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    args.audit.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"surahs": len(chapters), "verses": total_verses, "entries": len(entries), "pages": len(set(e["page"] for e in entries)), "lowestAlignment": min(a["equalWordRatio"] for a in audit), "bytes": args.output.stat().st_size}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Directory with downloaded surah-NNN.html Qaloun pages")
    parser.add_argument("--imlai", type=Path, required=True, help="Tanzil simple-clean txt-2 text")
    parser.add_argument("--metadata", type=Path, required=True, help="Tanzil quran-data.xml")
    parser.add_argument("--chapter-map", type=Path, required=True, help="Visually checked supplied-image chapter map")
    parser.add_argument("--names", type=Path, default=Path("qaloon-app/app/src/main/assets/quran_qaloon.json"))
    parser.add_argument("--output", type=Path, default=Path("android-app/app/src/main/assets/quran/index.json"))
    parser.add_argument("--audit", type=Path, required=True)
    build(parser.parse_args())
