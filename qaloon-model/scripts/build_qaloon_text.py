#!/usr/bin/env python3
from __future__ import annotations
"""
Build the Qaloon Quran reference text.

Downloads the Hafs Uthmani text from Tanzil.net, then applies known Qaloon-Hafs
differences to produce the Qaloon text. The diff table must be manually curated
and verified against a physical Qaloon mushaf.

Usage:
    python build_qaloon_text.py --download-hafs
    python build_qaloon_text.py --apply-diffs
    python build_qaloon_text.py --verify --surah 1
    python build_qaloon_text.py --stats
"""

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

import requests

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"

TANZIL_URL = "https://tanzil.net/pub/download/index.php"


def load_surah_ayah_counts() -> dict[int, int]:
    """Load surah -> ayah count mapping."""
    counts_file = METADATA_DIR / "surah_ayah_counts.json"
    with open(counts_file) as f:
        data = json.load(f)
    return {int(k): v["ayahs"] for k, v in data.items()}


def download_hafs_text(output_file: Path):
    """Download Hafs Uthmani text from Tanzil.net."""
    print("Downloading Hafs Uthmani text from Tanzil.net...")

    # Try pipe-delimited format first (surah|ayah|text)
    params = {
        "quranType": "uthmani",
        "outType": "txt",
    }
    resp = requests.get(TANZIL_URL, params=params, timeout=60)
    resp.raise_for_status()
    text = resp.text

    output_file.parent.mkdir(parents=True, exist_ok=True)
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(text)

    # Try parsing as pipe-delimited first
    ayahs = []
    for line in text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|", 2)
        if len(parts) == 3:
            try:
                ayahs.append({
                    "surah": int(parts[0]),
                    "ayah": int(parts[1]),
                    "text": parts[2].strip(),
                })
            except ValueError:
                pass

    # If pipe-delimited parsing failed, parse as plain text (one ayah per line)
    if len(ayahs) < 6000:
        print("  Pipe-delimited parsing returned few results, parsing as plain text...")
        ayahs = _parse_plain_text_quran(text)

    json_file = output_file.with_suffix(".json")
    with open(json_file, "w", encoding="utf-8") as f:
        json.dump(ayahs, f, ensure_ascii=False, indent=2)

    print(f"Downloaded {len(ayahs)} ayahs")
    print(f"  Raw text: {output_file}")
    print(f"  JSON:     {json_file}")


def _parse_plain_text_quran(text: str) -> list[dict]:
    """Parse plain-text Quran (one ayah per line) using ayah counts to assign surah/ayah."""
    ayah_counts = load_surah_ayah_counts()
    lines = [l.strip() for l in text.strip().split("\n") if l.strip()]

    bismillah = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ"

    ayahs = []
    line_idx = 0

    for surah_num in range(1, 115):
        num_ayahs = ayah_counts[surah_num]

        for ayah_num in range(1, num_ayahs + 1):
            if line_idx >= len(lines):
                print(f"  WARNING: Ran out of lines at surah {surah_num}, ayah {ayah_num}")
                break

            line = lines[line_idx]

            # For surah 1, ayah 1 IS the bismillah — keep it
            # For other surahs, ayah 1 may have bismillah prefixed — strip it
            if surah_num != 1 and ayah_num == 1 and surah_num != 9:
                if line.startswith(bismillah):
                    # Remove bismillah prefix from the ayah text
                    stripped = line[len(bismillah):].strip()
                    if stripped:
                        line = stripped
                    else:
                        # Bismillah was a standalone line, skip and get next line
                        line_idx += 1
                        if line_idx < len(lines):
                            line = lines[line_idx]

            ayahs.append({
                "surah": surah_num,
                "ayah": ayah_num,
                "text": line,
            })
            line_idx += 1

    return ayahs


def load_hafs_text() -> list[dict]:
    """Load the Hafs text from JSON."""
    json_file = METADATA_DIR / "quran_hafs_uthmani.json"
    if not json_file.exists():
        print(f"ERROR: {json_file} not found. Run with --download-hafs first.")
        sys.exit(1)
    with open(json_file, encoding="utf-8") as f:
        return json.load(f)


def load_diffs() -> list[dict]:
    """Load the Qaloon-Hafs difference table."""
    diff_file = METADATA_DIR / "qaloon_hafs_diff.json"
    if not diff_file.exists():
        print(f"ERROR: {diff_file} not found.")
        print("This file must be manually curated. See the template created by --init-diffs.")
        sys.exit(1)
    with open(diff_file, encoding="utf-8") as f:
        return json.load(f)


def init_diffs_template():
    """Create a starter template for the Qaloon-Hafs diff table.

    This contains well-known differences that are widely documented.
    The full list needs scholarly verification — these are just the starting point.
    """
    diff_file = METADATA_DIR / "qaloon_hafs_diff.json"
    if diff_file.exists():
        print(f"Diff file already exists: {diff_file}")
        print("Edit it manually to add/correct entries.")
        return

    # Well-known Qaloon-Hafs differences (starter set)
    # IMPORTANT: This is NOT complete. It must be expanded and verified
    # against a printed Qaloon mushaf by someone qualified.
    diffs = [
        {
            "surah": 1, "ayah": 4,
            "hafs": "مَـٰلِكِ يَوْمِ ٱلدِّينِ",
            "qaloon": "مَلِكِ يَوْمِ ٱلدِّينِ",
            "type": "word_change",
            "notes": "مَالِك (Hafs) vs مَلِك (Qaloon) — no madd alef",
            "verified": False,
        },
        {
            "surah": 2, "ayah": 58,
            "hafs": "وَقُولُوا۟ حِطَّةٌ",
            "qaloon": "وَقُولُوا۟ حِطَّةٌ",
            "type": "recitation_variant",
            "notes": "Review — multiple variants reported",
            "verified": False,
        },
        {
            "surah": 2, "ayah": 85,
            "hafs": "أَفَتُؤْمِنُونَ بِبَعْضِ ٱلْكِتَـٰبِ وَتَكْفُرُونَ بِبَعْضٍ",
            "qaloon": "أَفَتُؤْمِنُونَ بِبَعْضِ ٱلْكِتَـٰبِ وَتَكْفُرُونَ بِبَعْضٍ",
            "type": "TODO",
            "notes": "Verify exact Qaloon reading",
            "verified": False,
        },
        {
            "surah": 2, "ayah": 132,
            "hafs": "وَوَصَّىٰ بِهَآ إِبْرَٰهِـۧمُ",
            "qaloon": "وَأَوْصَىٰ بِهَآ إِبْرَٰهِـۧمُ",
            "type": "word_change",
            "notes": "وَوَصَّى (Hafs) vs وَأَوْصَى (Qaloon)",
            "verified": False,
        },
        {
            "surah": 2, "ayah": 184,
            "hafs": "فِدْيَةٌ طَعَامُ مَسَـٰكِينَ",
            "qaloon": "فِدْيَةٌ طَعَامُ مِسْكِينٍ",
            "type": "word_change",
            "notes": "مساكين plural (Hafs) vs مسكين singular (Qaloon)",
            "verified": False,
        },
        {
            "surah": 3, "ayah": 133,
            "hafs": "وَسَارِعُوٓا۟ إِلَىٰ مَغْفِرَةٍ",
            "qaloon": "وَسَارِعُوٓا۟ إِلَىٰ مَغْفِرَةٍ",
            "type": "TODO",
            "notes": "سارعوا with/without waw — verify for Qaloon",
            "verified": False,
        },
        {
            "surah": 3, "ayah": 146,
            "hafs": "وَكَأَيِّن مِّن نَّبِىٍّ قَـٰتَلَ",
            "qaloon": "وَكَأَيِّن مِّن نَّبِىٍّ قُتِلَ",
            "type": "word_change",
            "notes": "قاتل active (Hafs) vs قُتل passive (Qaloon)",
            "verified": False,
        },
        {
            "surah": 18, "ayah": 36,
            "hafs": "وَلَئِن رُّدِدتُّ إِلَىٰ رَبِّى لَأَجِدَنَّ خَيْرًا مِّنْهَا مُنقَلَبًا",
            "qaloon": "وَلَئِن رُّدِدتُّ إِلَىٰ رَبِّى لَأَجِدَنَّ خَيْرًا مِّنْهُمَا مُنقَلَبًا",
            "type": "word_change",
            "notes": "منها (Hafs) vs منهما (Qaloon)",
            "verified": False,
        },
    ]

    # Add a _meta entry explaining the format
    output = {
        "_meta": {
            "description": "Qaloon vs Hafs word-level differences in the Quran",
            "format": "Each entry has: surah, ayah, hafs text, qaloon text, type, notes, verified",
            "types": [
                "word_change — a word is different between the two readings",
                "letter_change — a letter/haraka differs",
                "addition — Qaloon has extra word(s)",
                "deletion — Qaloon omits word(s)",
                "TODO — needs research/verification",
            ],
            "IMPORTANT": "This file MUST be verified against a printed Qaloon mushaf. "
                        "Do NOT use unverified entries for training. "
                        "Mark verified=true only after checking against a physical mushaf.",
            "estimated_total_diffs": "~1000-1500 entries across the Quran",
        },
        "diffs": diffs,
    }

    diff_file.parent.mkdir(parents=True, exist_ok=True)
    with open(diff_file, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"Created diff template at: {diff_file}")
    print(f"Contains {len(diffs)} starter entries (all unverified)")
    print()
    print("NEXT STEPS:")
    print("  1. Research and expand this file with ALL Qaloon-Hafs differences")
    print("  2. Sources: كتب الفروق بين القراءات, printed Qaloon mushafs")
    print("  3. Set verified=true for each entry after checking against a mushaf")
    print("  4. Run: python build_qaloon_text.py --apply-diffs")


def _strip_diacritics(text: str) -> str:
    """Remove Arabic diacritics for fuzzy matching."""
    # Arabic diacritical marks range: U+0610-U+061A, U+064B-U+0670, U+06D6-U+06ED
    return re.sub(r'[\u0610-\u061A\u064B-\u0670\u06D6-\u06ED\u0670]', '', text)


def _normalize_arabic(text: str) -> str:
    """Normalize Arabic text for matching: strip tatweel, small signs, zero-width chars."""
    # Remove tatweel / kashida (U+0640)
    text = text.replace('\u0640', '')
    # Remove zero-width joiner/non-joiner
    text = text.replace('\u200D', '').replace('\u200C', '')
    # Remove small high/low signs that vary between encodings
    text = re.sub(r'[\u06DF\u06E0-\u06E9\u0615-\u061A]', '', text)
    return text


def apply_diffs():
    """Apply Qaloon diffs to Hafs text to produce Qaloon text.

    Supports two diff formats:
      - New format: hafs_word/qaloon_word — replaces specific word(s) within the ayah
      - Legacy format: hafs/qaloon — replaces the full ayah text
    """
    hafs_ayahs = load_hafs_text()
    diff_data = load_diffs()
    diffs = diff_data.get("diffs", diff_data) if isinstance(diff_data, dict) else diff_data

    # Index Hafs text by (surah, ayah)
    hafs_map = {}
    for a in hafs_ayahs:
        hafs_map[(a["surah"], a["ayah"])] = a["text"]

    # Apply diffs
    applied = 0
    skipped = 0
    failed = 0
    qaloon_overrides = {}

    for diff in diffs:
        key = (diff["surah"], diff["ayah"])
        hafs_text = hafs_map.get(key)

        if hafs_text is None:
            print(f"  WARNING: Surah {diff['surah']} Ayah {diff['ayah']} not found in Hafs text")
            failed += 1
            continue

        # New format: hafs_word/qaloon_word — word-level replacement
        if "hafs_word" in diff and "qaloon_word" in diff:
            hafs_word = diff["hafs_word"]
            qaloon_word = diff["qaloon_word"]

            # Skip entries where hafs == qaloon (no actual diff, just haraka notes)
            if hafs_word == qaloon_word:
                skipped += 1
                continue

            # Normalize both texts (strip tatweel, zero-width chars)
            norm_text = _normalize_arabic(hafs_text)
            norm_word = _normalize_arabic(hafs_word)
            norm_qaloon = _normalize_arabic(qaloon_word)

            # Try normalized match
            if norm_word in norm_text:
                modified = norm_text.replace(norm_word, norm_qaloon, 1)
                # Reconstruct: we work on normalized text but that's fine
                # since the training text should be clean anyway
                qaloon_overrides[key] = modified
                applied += 1
            else:
                # Try diacritics-stripped match
                stripped_text = _strip_diacritics(norm_text)
                stripped_word = _strip_diacritics(norm_word)
                if stripped_word in stripped_text:
                    qaloon_overrides[key] = norm_text  # keep normalized hafs for now
                    print(f"  PARTIAL: {key} — diacritics mismatch, kept Hafs text (needs review)")
                    applied += 1
                else:
                    print(f"  MISS: {key} — '{hafs_word}' not found in ayah text")
                    failed += 1

        # Legacy format: full ayah replacement
        elif "qaloon" in diff:
            qaloon_overrides[key] = diff["qaloon"]
            applied += 1
        else:
            skipped += 1

    # Build full Qaloon text: use override if exists, otherwise use normalized Hafs text
    qaloon_ayahs = []
    for a in hafs_ayahs:
        key = (a["surah"], a["ayah"])
        base_text = _normalize_arabic(a["text"])
        qaloon_ayahs.append({
            "surah": a["surah"],
            "ayah": a["ayah"],
            "text": qaloon_overrides.get(key, base_text),
            "riwaya": "qaloon",
            "is_different_from_hafs": key in qaloon_overrides,
        })

    output_file = METADATA_DIR / "quran_qaloon.json"
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(qaloon_ayahs, f, ensure_ascii=False, indent=2)

    print(f"Built Qaloon text: {output_file}")
    print(f"  Total ayahs: {len(qaloon_ayahs)}")
    print(f"  Diffs applied: {applied}")
    print(f"  Skipped (same text / haraka-only): {skipped}")
    print(f"  Failed (word not found): {failed}")
    print(f"  Ayahs differing from Hafs: {sum(1 for a in qaloon_ayahs if a['is_different_from_hafs'])}")


def verify_surah(surah_num: int):
    """Display a surah's Qaloon text for manual verification."""
    qaloon_file = METADATA_DIR / "quran_qaloon.json"
    if not qaloon_file.exists():
        print("ERROR: Run --apply-diffs first to generate Qaloon text.")
        sys.exit(1)

    with open(qaloon_file, encoding="utf-8") as f:
        ayahs = json.load(f)

    surah_ayahs = [a for a in ayahs if a["surah"] == surah_num]
    if not surah_ayahs:
        print(f"Surah {surah_num} not found.")
        return

    counts_file = METADATA_DIR / "surah_ayah_counts.json"
    with open(counts_file) as f:
        counts = json.load(f)
    surah_name = counts.get(str(surah_num), {}).get("name", f"Surah {surah_num}")

    print(f"\n{'='*60}")
    print(f"Surah {surah_num}: {surah_name} ({len(surah_ayahs)} ayahs)")
    print(f"{'='*60}\n")

    for a in surah_ayahs:
        marker = " ⚠️  DIFFERS FROM HAFS" if a.get("is_different_from_hafs") else ""
        print(f"  [{a['ayah']:3d}] {a['text']}{marker}")

    print()
    diff_count = sum(1 for a in surah_ayahs if a.get("is_different_from_hafs"))
    print(f"Ayahs with Qaloon-Hafs differences: {diff_count}/{len(surah_ayahs)}")


def show_stats():
    """Show statistics about the Qaloon text and diff coverage."""
    qaloon_file = METADATA_DIR / "quran_qaloon.json"
    diff_file = METADATA_DIR / "qaloon_hafs_diff.json"

    if qaloon_file.exists():
        with open(qaloon_file, encoding="utf-8") as f:
            ayahs = json.load(f)
        diff_ayahs = [a for a in ayahs if a.get("is_different_from_hafs")]
        print(f"Qaloon text: {len(ayahs)} ayahs total")
        print(f"  Different from Hafs: {len(diff_ayahs)}")
        print(f"  Identical to Hafs: {len(ayahs) - len(diff_ayahs)}")
    else:
        print("Qaloon text not yet generated. Run --apply-diffs.")

    print()

    if diff_file.exists():
        with open(diff_file, encoding="utf-8") as f:
            diff_data = json.load(f)
        diffs = diff_data.get("diffs", []) if isinstance(diff_data, dict) else diff_data
        verified = [d for d in diffs if d.get("verified")]
        todos = [d for d in diffs if d.get("type") == "TODO"]
        print(f"Diff table: {len(diffs)} entries")
        print(f"  Verified: {len(verified)}")
        print(f"  Unverified: {len(diffs) - len(verified)}")
        print(f"  TODO (needs research): {len(todos)}")
        print(f"  Estimated remaining: ~{1000 - len(diffs)} more entries needed")
    else:
        print("Diff table not yet created. Run --init-diffs.")


def main():
    parser = argparse.ArgumentParser(
        description="Build Qaloon Quran reference text from Hafs + diff table"
    )
    parser.add_argument("--download-hafs", action="store_true", help="Download Hafs Uthmani text from Tanzil.net")
    parser.add_argument("--init-diffs", action="store_true", help="Create starter diff template")
    parser.add_argument("--apply-diffs", action="store_true", help="Apply diffs to produce Qaloon text")
    parser.add_argument("--verify", action="store_true", help="Display surah text for verification")
    parser.add_argument("--surah", type=int, default=1, help="Surah number for --verify (default: 1)")
    parser.add_argument("--stats", action="store_true", help="Show diff/text statistics")

    args = parser.parse_args()

    if args.download_hafs:
        download_hafs_text(METADATA_DIR / "quran_hafs_uthmani.txt")
    elif args.init_diffs:
        init_diffs_template()
    elif args.apply_diffs:
        apply_diffs()
    elif args.verify:
        verify_surah(args.surah)
    elif args.stats:
        show_stats()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
