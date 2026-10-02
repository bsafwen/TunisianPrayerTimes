# Hosary Qaloun recitation

The initial bundled reciter is Mahmoud Khalil al-Hosary, Qaloun from Nafi. The user supplied the original 114 MP3 files in `C:\Users\barou\Desktop\hosary` and requested that every chapter be bundled for offline playback. These originals total 2,243,211,628 bytes (about 2.09 GiB), last about 38 hours 56 minutes, and are included unchanged under `quran/audio/hosary/`.

## Recording identity and timing source

All 114 files identify the album as `Rewayat Qalon An Nafi`, the artist as `Mahmoud Khalil Al-Hussary-Qalon A'n Nafi'`, and the source as `www.mp3quran.net` in their ID3 metadata. Their audio stream is MP3, 128 kbps, mono, 44.1 kHz. They contain no embedded verse chapters.

The [MP3Quran reciter API](https://www.mp3quran.net/api/v3/reciters?language=eng) and [available timed recordings](https://www.mp3quran.net/api/v3/ayat_timing/reads) identify this Qaloun recording as reciter 118 / reading 270, with all 114 chapters at `https://server13.mp3quran.net/husr/Rewayat-Qalon-A-n-Nafi/`. These were checked on 2026-10-02. Every local file's MD5 digest equals the source HTTP ETag, and every source Content-Length equals the local byte count. The complete first-chapter source download was also byte-identical by SHA-256. Per-file local SHA-256, byte count, duration, source ETag, and raw timing response SHA-256 are recorded in `scripts/quran-data/hosary-source-manifest.json`.

The [official verse-timing API](https://www.mp3quran.net/api/v3/ayat_timing?surah=1&read=270) supplies actual start/end milliseconds for the recording. Its 6,214 ayah numbers exactly match the later Madani Qaloun numbering already used by this reader, including every individual chapter's verse count. No Hafs-to-Qaloun verse-number conversion or proportional timing estimation is applied.

Timings start at ayah 1. The opening invocation/basmalah and trailing audio outside the reported verse intervals have no highlighted verse. For example, al-Fatiha's first numbered verse begins at 13,140 ms, after its opening. The five 20 ms gaps between source intervals are preserved. Source end times for the final verses of chapters 108 and 112 exceed the measured local duration by only 282 ms and 367 ms respectively; those two final endpoints are clipped to the actual duration. All other timing values remain unchanged. The importer rejects any overlap, missing/duplicated ayah, mismatched Qaloun count, or larger out-of-range endpoint.

The API also returns polygons for its own SVG page edition. They are deliberately excluded from this timing import: spatial highlights must come from the supplied Qaloun page geometry, not another page layout.

## Rebuild

With Python 3.11 or newer and FFprobe available, run from the repository root:

```powershell
python scripts/fetch-hosary-timings.py
python scripts/import-hosary-timings.py --audio C:\Users\barou\Desktop\hosary
```

The fetcher explicitly uses the required machine proxy `http://127.0.0.1:8888` for every request, including redirects, and disables curl's environment bypass list. The importer is entirely offline. It measures and hashes the originals; it never modifies or transcodes them. Audio copying is separate from timing import.

`quran/audio/hosary/timings.json` uses a reciter identifier separate from its Arabic label and reading, then stores each chapter's asset path, actual duration, SHA-256 and ordered ayah intervals. Additional reciters require their own recording-specific interval data; times must never be shared just because the chapter text is the same.
