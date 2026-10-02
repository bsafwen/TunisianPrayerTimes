#!/usr/bin/env python3
"""Offline integrity/search checks for the bundled Qaloun reader data."""
from collections import defaultdict
import json
from pathlib import Path
import unicodedata


def normalize(text):
    value = unicodedata.normalize("NFKC", text)
    value = "".join(c for c in value if not unicodedata.category(c).startswith("M") and c not in "ـۥۦ")
    value = value.translate(str.maketrans("أإآٱىئؤةیکے", "ااااييوهيكي"))
    return " ".join("".join(c if c.isalnum() else " " for c in value).split())


def main():
    base = Path(__file__).resolve().parents[1] / "android-app/app/src/main/assets/quran"
    data = json.loads((base / "index.json").read_text(encoding="utf-8"))
    manifest = json.loads((base / "pages/pages.json").read_text(encoding="utf-8"))
    assert len(manifest["pages"]) == 621
    assert [s["number"] for s in data["surahs"]] == list(range(1, 115))
    assert sum(s["verseCount"] for s in data["surahs"]) == 6214
    assert set(e["page"] for e in data["entries"]) == set(range(1, 604))
    assert data["surahs"][0]["page"] == data["surahs"][1]["page"] == 1
    assert [s["page"] for s in data["surahs"][-3:]] == [603, 603, 603]
    for image in manifest["pages"]:
        assert (base / "pages" / image["file"]).is_file(), image
    pages = defaultdict(list)
    verses = defaultdict(list)
    for entry in data["entries"]:
        assert entry["text"].strip()
        assert isinstance(entry["ayah"], int)
        pages[(entry["surah"], entry["page"])].append(entry)
        verses[(entry["surah"], entry["ayah"])].append(entry)
    assert len([key for key in verses if key[1] > 0]) == 6214
    assert len([key for key in verses if key[1] == 0]) == 113
    for chapter in data["surahs"]:
        number = chapter["number"]
        first = 1 if number == 9 else 0
        assert [ayah for surah, ayah in verses if surah == number] == list(range(first, chapter["verseCount"] + 1))
    for reference, fragments in verses.items():
        assert [e["isFirstFragment"] for e in fragments] == [True] + [False] * (len(fragments) - 1), reference
        assert [e["isLastFragment"] for e in fragments] == [False] * (len(fragments) - 1) + [True], reference
        assert [e["page"] for e in fragments] == sorted(set(e["page"] for e in fragments)), reference
    assert {key: [e["page"] for e in fragments] for key, fragments in verses.items() if len(fragments) > 1} == {
        (20, 86): [316, 317],
    }
    for reference, page in {(4, 44): 84, (24, 36): 353, (24, 42): 354, (87, 15): 591}.items():
        assert [entry["page"] for entry in verses[reference]] == [page], reference
    def search(query):
        query = normalize(query)
        return {key for key, entries in pages.items() if any(query in normalize(" ".join(e.get(field, e["text"]) for e in entries)) for field in ("text", "searchText"))}
    checks = [
        ("بسم الله الرحمن الرحيم", (1, 1)),
        ("الحمد لله رب العالمين", (1, 1)),
        ("مَلِكِ يَوْمِ الدِّينِ", (1, 1)),
        ("الكتاب", (2, 1)),
        ("الصلاة", (2, 1)),
        ("السماوات", (2, 5)),
        ("إن الذين كفروا", (2, 2)),
        ("الله لا إله إلا هو الحي القيوم لا تأخذه سنة", (2, 41)),
        ("لا إكراه في الدين", (2, 41)),
        ("قل هو الله أحد", (112, 603)),
        ("من الجنة والناس", (114, 603)),
        ("وكفى بالله نصيرا", (4, 84)),
        ("رجال لا تلهيهم", (24, 353)),
        ("يقلب الله الليل والنهار", (24, 354)),
        ("وذكر اسم ربه فصلى", (87, 591)),
    ]
    for query, destination in checks:
        assert destination in search(query), (query, destination)
    assert (1, 1) not in search("مالك يوم الدين"), "Search aliases must not replace the Qaloun reading"
    assert not search("كلمات غير موجودة إطلاقا في المصحف")
    print(f"PASS: 621 image files, 114 chapters, 6214 numbered Qaloun verses, 113 basmalah headings, 1 cross-page verse, 4 verified scan-page corrections, 603 indexed Quran pages, {len(checks)} navigation/search cases; reading distinction preserved.")


if __name__ == "__main__":
    main()
