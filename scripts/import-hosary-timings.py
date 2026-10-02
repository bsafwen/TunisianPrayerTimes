#!/usr/bin/env python3
"""Validate MP3Quran's Hosary Qaloun timing data against the original local MP3s.

This importer is offline. Fetch its source JSON with fetch-hosary-timings.py.
It never estimates verse boundaries or alters audio files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--timings", type=Path, default=Path("work/hosary/timings"))
    parser.add_argument("--index", type=Path, default=Path("android-app/app/src/main/assets/quran/index.json"))
    parser.add_argument("--output", type=Path, default=Path("android-app/app/src/main/assets/quran/audio/hosary/timings.json"))
    parser.add_argument("--manifest", type=Path, default=Path("scripts/quran-data/hosary-source-manifest.json"))
    parser.add_argument("--source-verification", type=Path, default=Path("work/hosary/source-audio-verification.json"))
    args = parser.parse_args()

    index = json.loads(args.index.read_text(encoding="utf-8"))
    counts = {surah["number"]: surah["verseCount"] for surah in index["surahs"]}
    assert sorted(counts) == list(range(1, 115)) and sum(counts.values()) == 6214
    source_verification = {}
    if args.source_verification.is_file():
        source_verification = {
            item["surah"]: item
            for item in json.loads(args.source_verification.read_text(encoding="utf-8"))
        }

    chapters = []
    source_manifest = []
    adjustments = []
    for number in range(1, 115):
        filename = f"{number:03d}.mp3"
        audio = args.audio / filename
        source = args.timings / f"{number:03d}.json"
        probe = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(audio)
        ]))
        duration_ms = round(float(probe["format"]["duration"]) * 1000)
        assert any(stream["codec_type"] == "audio" for stream in probe["streams"])
        raw = json.loads(source.read_text(encoding="utf-8"))
        assert [int(row["ayah"]) for row in raw] == list(range(1, counts[number] + 1)), number
        timings = []
        previous_end = 0
        for row in raw:
            ayah, start, end = int(row["ayah"]), int(row["start_time"]), int(row["end_time"])
            assert 0 <= previous_end <= start < end, (number, ayah, start, end)
            if end > duration_ms:
                # MP3Quran's last endpoint slightly exceeds the supplied track for 108/112.
                # A final endpoint may be clipped to duration, never scaled or extrapolated.
                assert ayah == counts[number] and end - duration_ms <= 500, (number, ayah, end)
                adjustments.append({"surah": number, "ayah": ayah, "sourceEndMs": end, "endMs": duration_ms})
                end = duration_ms
            assert start < end <= duration_ms
            timings.append({"ayah": ayah, "startMs": start, "endMs": end})
            previous_end = end

        digest = sha256(audio)
        chapters.append({
            "number": number,
            "assetPath": f"quran/audio/hosary/{filename}",
            "durationMs": duration_ms,
            "sha256": digest,
            "introEndMs": timings[0]["startMs"],
            "timings": timings,
        })
        evidence = source_verification.get(number, {})
        if evidence:
            with audio.open("rb") as handle:
                md5 = hashlib.file_digest(handle, "md5").hexdigest()
            assert evidence["etagMatchesMd5"]
            assert evidence["localMd5"] == evidence["sourceETag"] == md5
            assert evidence["localBytes"] == evidence["remoteBytes"] == audio.stat().st_size
        source_manifest.append({
            "surah": number,
            "file": filename,
            "bytes": audio.stat().st_size,
            "sha256": digest,
            "durationMs": duration_ms,
            "timingSource": f"https://www.mp3quran.net/api/v3/ayat_timing?surah={number}&read=270",
            "timingSourceSha256": sha256(source),
            **({"sourceAudioVerification": evidence} if evidence else {}),
        })

    document = {
        "version": 1,
        "reciterId": "hosary-qaloun",
        "reciterName": "محمود خليل الحصري",
        "riwaya": "قالون عن نافع",
        "numbering": "madani-later",
        "sourceReadId": 270,
        "timingSource": "https://www.mp3quran.net/api/v3/ayat_timing?surah={surah}&read=270",
        "surahs": chapters,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    verified_sources = sum("sourceAudioVerification" in item for item in source_manifest)
    verification_summary = (
        "All 114 local-file MD5 digests equal source HTTP ETags; every source Content-Length equals local file size. Surah 1 additionally verified byte-identical by a complete source download and SHA-256."
        if verified_sources == 114 else
        f"Measured and SHA-256 hashed all 114 local files. Available source ETag/size records verified for {verified_sources} chapters; no other source identity claim is made by this import."
    )
    args.manifest.write_text(json.dumps({
        "version": 1,
        "retrieved": "2026-10-02",
        "reciterId": document["reciterId"],
        "numbering": document["numbering"],
        "sourceReadId": 270,
        "sourceAudioBase": "https://server13.mp3quran.net/husr/Rewayat-Qalon-A-n-Nafi/",
        "verification": verification_summary,
        "endpointAdjustments": adjustments,
        "files": source_manifest,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Validated {len(chapters)} chapters / {sum(len(c['timings']) for c in chapters)} Qaloun verses; {len(adjustments)} final endpoints clipped to track duration.")
    print(f"Wrote {args.output} ({args.output.stat().st_size:,} bytes).")


if __name__ == "__main__":
    main()
