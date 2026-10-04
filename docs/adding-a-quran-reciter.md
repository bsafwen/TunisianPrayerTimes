# Adding a Quran reciter

How to take a folder holding a full recitation and make it a reciter in the app. It is written
for an agent working in this repository on the maintainer's computer, where the Quran media
lives. Follow the steps in order; each one says how to check that it worked.

When you are done:

- the recitation is published to Cloudflare R2, which serves it to every install that needs it;
- if fewer than ten reciters use Google Play, the recitation is also in Google Play's packs for
  the next app bundle;
- the reciter is in the app's reciter menu, with verse highlighting, from the next release.

## How the pieces fit

| Piece | Where | Made by |
|-------|-------|---------|
| Recordings: 114 MP3s, 64 kbps mono at a constant bitrate | a media folder on the computer, never in git | `scripts/quran_assets.py convert` |
| Verse timings | `android-app/app/src/main/assets/quran/audio/<folder>/timings.json`, shipped inside the app | produced separately, then checked here |
| Menu entry | `QuranAudioController.reciters` in `android-app/app/src/main/java/com/tunisianprayertimes/quran/audio/QuranAudioController.kt` | you |
| Packs | `quran/packs.json`, `android-app/quran-assets/manifest.tsv`, `android-app/quran-packs/` | `scripts/quran_assets.py layout` |
| Hosting | R2 bucket `tunisian-quran-assets`, served by the `tunisian-quran-cdn` Worker | `scripts/quran_assets.py publish` |

**Google Play or Cloudflare: `layout` decides, so there is nothing for you to choose.**

- **The first ten reciters, in the order they were added:** 9 Google Play packs each. With the
  page scans that makes 91 of Play's 100 packs. Cloudflare serves these packs as well, for
  installs that did not come from Google Play.
- **Every later reciter:** about 30 MB packs, served only by Cloudflare, to Play installs as well.
- **A reciter never moves** between Google Play and Cloudflare once it has been laid out.

Play packs reach Play users only inside an app bundle that the maintainer builds with
`release.sh` and uploads to the Play Console.

## Before you start

**Inputs**

1. **The original recordings:** one MP3 per surah, 114 in all.
2. **Their verse timings,** in the format described under [Timings file](#timings-file).
3. **The reciter's name in Arabic,** as the app should show it.
4. **The riwaya, which must be Qaloun 'an Nafi' (`قالون عن نافع`).** The app's mushaf pages, verse
   highlights and verse numbering (Madani, later count) are Qaloun's. A recitation that numbers
   verses differently, such as Hafs, cannot be added.

**On the computer**

- **Tools:**
  - Python 3.9 or newer.
  - ffmpeg and ffprobe on the PATH. On Windows, run `winget install Gyan.FFmpeg`, then open a
    new terminal.
  - A JDK and the Android SDK, for the app's tests.
- **The media of everything already laid out:**
  - every existing reciter's converted recordings;
  - the mushaf page scans, as a folder or a zip.

  `layout` rebuilds every pack from local files. Unchanged files give byte-identical archives,
  so nothing that is already published changes.
- **Files that must already be committed:**
  - `quran/packs.json`;
  - `quran/index.json`;
  - `quran/pages/pages.json`;
  - every existing reciter's `timings.json`.

  If `packs.json` does not exist, stop: the first layout is a different task.
- **Cloudflare credentials, for publishing:** `CLOUDFLARE_ACCOUNT_ID` and
  `CLOUDFLARE_API_TOKEN`. The token needs *Workers R2 Storage: Edit*. Ask the maintainer for
  them, keep them in environment variables, and never write them into a file.

**One media folder for all reciters.** Keep every reciter's converted recordings in a subfolder
named exactly like its folder under `quran/audio/`:

```
<media>/
  hosary/001.mp3 … 114.mp3
  <folder>/001.mp3 … 114.mp3      ← the new reciter
  pages/ (or a pages zip passed separately)
```

Every reciter has a `001.mp3`; `layout` tells them apart by the name of the folder they are in.
Keep the original recordings outside `<media>`.

## Steps

### 1. Choose the names

| Name | Rules | Example |
|------|-------|---------|
| `<folder>` | lowercase ASCII letters, digits and `_`, starting with a letter; part of every pack name and asset path | `example` |
| `<id>` | `<folder>-qaloun`; saved in users' settings | `example-qaloun` |

Neither may change after the reciter is released. Check that neither one already appears in
`QuranAudioController.reciters` or under `android-app/app/src/main/assets/quran/audio/`.

### 2. Convert the recordings

Name the originals `001.mp3` … `114.mp3` (the surah number, three digits) first; `convert` keeps
the names. Then:

```bash
python scripts/quran_assets.py convert --from-dir <originals> --to <media>/<folder>
```

**Check:**

- It ends with `✓ 114 recordings in …`.
- `<media>/<folder>` holds exactly `001.mp3` … `114.mp3`.
- Running it again skips the finished files.

**Back up the converted folder: it is now the master copy.** Another ffmpeg version can produce
different bytes, which would mean different packs.

### 3. Add the verse timings

Write the timings to `android-app/app/src/main/assets/quran/audio/<folder>/timings.json`, in the
[format below](#timings-file).

- **Which files to time:** time the converted files. Timings made on the originals also work,
  because conversion shifts the audio by less than 30 ms.
- **`durationMs`:** take it from each converted file, in seconds × 1000, rounded down:

  ```bash
  ffprobe -v error -show_entries format=duration -of csv=p=0 <media>/<folder>/001.mp3
  ```

- **A last verse that runs past the end of the file:** if it overruns by a few hundred
  milliseconds, set its `endMs` to `durationMs`. Published timing data sometimes does this.

**Check:** run the script in [Checking the timings](#checking-the-timings). It must print
`timings.json is valid`.

### 4. Add the reciter to the menu

In `QuranAudioController.kt`, add a line to `reciters`, after the existing ones:

```kotlin
QuranReciter("<id>", "<الاسم بالعربية>", "قالون عن نافع", "quran/audio/<folder>/timings.json"),
```

The list order is the menu order. Al-Husary stays first: he is `DEFAULT_RECITER_ID`.

### 5. Lay out the packs

```bash
python scripts/quran_assets.py layout --from-dir <media> --from-dir <pages folder or zip> \
    --cdn https://tunisian-quran-cdn.baroudi-safwen.workers.dev/
```

If the page scans are inside `<media>`, `--from-dir <media>` alone is enough.

**Check:**

- **Where the reciter went:** the output has one line per reciter. The new one says
  `Google Play and the Worker` (a Play place was free) or `the Worker only`.
- **No warnings:** no line starts with `!`. That warning means a Play pack is over 200 MB, so Play
  would ask before using mobile data. It should not happen at 64 kbps; report it to the
  maintainer if it does.
- **Nothing published has changed.** Run `git diff android-app/app/src/main/assets/quran/packs.json`.
  It may only add the new reciter's packs; every existing pack keeps its `archive.key`. If an
  existing key changed, stop and do not publish: an existing reciter's media, or the page scans,
  differ from what was published (wrong folder, or converted with another ffmpeg). Report it.
- **Modules:** a Play reciter gets `android-app/quran-packs/quran_<folder>_01/build.gradle.kts`
  and so on, up to `_09`. A Worker-only reciter gets no new folders there.

Never edit `packs.json`, `manifest.tsv` or `android-app/quran-packs/` by hand.

### 6. Publish to Cloudflare

Do this whether or not the reciter got a Play place: installs from outside Google Play always
download from Cloudflare.

```bash
export CLOUDFLARE_ACCOUNT_ID=…          # PowerShell: $env:CLOUDFLARE_ACCOUNT_ID="…"
export CLOUDFLARE_API_TOKEN=…           # PowerShell: $env:CLOUDFLARE_API_TOKEN="…"
python scripts/quran_assets.py publish
```

It uploads only the archives that the Worker does not serve yet: after a clean step 5, just the
new reciter's.

**Check:**

- It ends with `✓ Every pack is published`.
- Spot-check one archive with
  `curl -I https://tunisian-quran-cdn.baroudi-safwen.workers.dev/<archive.key from packs.json>`.
  It answers `200`, with the archive's size in bytes.

### 7. Test

```bash
python -m unittest scripts/test_quran_assets.py
cd android-app
./gradlew :app:testDebugUnitTest --tests '*QuranPackLayoutTest*' --tests '*QuranDownloadsTest*' --tests '*PlayOrCdnPackSourceTest*'
./gradlew :app:assembleDebug
```

`QuranPackLayoutTest` checks the committed layout against every reciter's timings:

- every surah is in exactly one pack;
- a Play reciter has at most 9 packs;
- Play packs have modules and Worker-only packs do not;
- the manifest agrees with `packs.json`.

**On a phone or emulator:** install the debug APK. Like the GitHub APK, it downloads from
Cloudflare. Then:

1. Open the Quran tab and pick the reciter from the reciter menu.
2. Play a short surah and a long one. The highlighted verse must follow the recitation.
3. Start from a verse on the page, and check that playback starts at that verse.

**Optional, for a Play reciter:** `android-app/test-asset-packs.sh` installs the app with Play's
packs served locally. It stages every Play pack first, about 1.1 GB per Play reciter, into
`~/.cache/quran-assets`.

### 8. Commit and push

**Commit:**

- `android-app/app/src/main/assets/quran/audio/<folder>/timings.json`;
- `QuranAudioController.kt`;
- `android-app/app/src/main/assets/quran/packs.json`;
- `android-app/quran-assets/manifest.tsv`;
- any new `android-app/quran-packs/*/build.gradle.kts`.

**Never commit media.** `git status` must show no `.mp3`, `.webp` or `.zip` files. `.gitignore`
covers the usual places, and `release.sh` refuses to run while Quran media is staged.

### 9. Release (the maintainer's step)

The reciter appears in the app from the next release, because its timings and menu entry ship
inside the app:

- **GitHub APK:** CI builds it when `release.sh` pushes the release. It downloads the reciter
  from Cloudflare.
- **Google Play:** `./release.sh "…"` builds the bundle, after staging every Play pack from
  Cloudflare. Each Play reciter adds about 1.1 GB to it. The maintainer uploads the bundle to the
  Play Console; Play users see the reciter once that release is live.

Do not run `release.sh` unless you were asked to.

## Timings file

```json
{
  "version": 1,
  "reciterId": "<id>",
  "numbering": "madani-later",
  "surahs": [
    {
      "number": 1,
      "assetPath": "quran/audio/<folder>/001.mp3",
      "durationMs": 52767,
      "timings": [
        {"ayah": 1, "startMs": 1830, "endMs": 6120},
        {"ayah": 2, "startMs": 6120, "endMs": 11870}
      ]
    }
  ]
}
```

The app rejects the whole file if any of these rules is broken (`QuranRecitationTimings.load`):

- **Header:**
  - `version` is `1`;
  - `reciterId` is the `<id>` from step 4;
  - `numbering` is `"madani-later"`.
- **Surahs:** exactly 114 entries, numbered 1 to 114 in order.
- **`assetPath`:** `quran/audio/<folder>/NNN.mp3`.
- **`durationMs`:** the length of the converted file, in milliseconds.
- **`timings`:** one entry per verse.
  - `ayah` runs 1, 2, … N, where N is the number of verses the mushaf has for that surah. The
    mushaf's verses are the `entries` of `quran/index.json`.
  - `startMs` is 0 or more.
  - `endMs` is greater than `startMs` and no more than `durationMs`.
  - A verse never starts before the previous one ends.
- **Untimed audio is fine.** The introduction (isti'adha, basmala) and pauses between verses
  simply show no highlighted verse.

## Checking the timings

Save this outside the repository, for example as `check_timings.py`. Run it from the
repository root:

```bash
python check_timings.py <folder> <id> <media>/<folder>
```

```python
import json, subprocess, sys
from pathlib import Path

folder, reciter_id, converted = sys.argv[1], sys.argv[2], Path(sys.argv[3])
assets = Path('android-app/app/src/main/assets/quran')
index = json.loads((assets / 'index.json').read_text(encoding='utf-8'))
verses = {}
for entry in index['entries']:
    if entry['ayah']:  # ayah 0 is a surah's basmala, which is not a verse
        verses.setdefault(entry['surah'], set()).add(entry['ayah'])
timings = json.loads((assets / 'audio' / folder / 'timings.json').read_text(encoding='utf-8'))
problems = []
if timings.get('version') != 1 or timings.get('numbering') != 'madani-later' or timings.get('reciterId') != reciter_id:
    problems.append(f'version must be 1, numbering "madani-later" and reciterId "{reciter_id}"')
surahs = timings.get('surahs', [])
if [s['number'] for s in surahs] != list(range(1, 115)):
    problems.append('surahs must be numbered 1..114, in order')
for s in surahs:
    n, rows = s['number'], s['timings']
    if s.get('assetPath') != f'quran/audio/{folder}/{n:03d}.mp3':
        problems.append(f'{n}: assetPath must be quran/audio/{folder}/{n:03d}.mp3')
    probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0',
                            str(converted / f'{n:03d}.mp3')], capture_output=True, text=True)
    measured = int(float(probe.stdout) * 1000)
    if abs(s['durationMs'] - measured) > 100:
        problems.append(f'{n}: durationMs is {s["durationMs"]}, the converted file lasts {measured} ms')
    if [r['ayah'] for r in rows] != list(range(1, len(verses[n]) + 1)):
        problems.append(f'{n}: {len(rows)} verses timed, the mushaf has {len(verses[n])}')
    for before, row in zip([None] + rows, rows):
        if not 0 <= row['startMs'] < row['endMs'] <= s['durationMs'] or before and before['endMs'] > row['startMs']:
            problems.append(f'{n}:{row["ayah"]}: bad interval {row["startMs"]}-{row["endMs"]} ms')
print('\n'.join(problems) or 'timings.json is valid')
sys.exit(1 if problems else 0)
```

## When something goes wrong

| Message | Cause | Fix |
|---------|-------|-----|
| `… is 128 kbps stereo; recitations are 64 kbps mono …` (layout) | `layout` was given unconverted files | Step 2; point `layout` at `<media>` |
| `N local files are named 001.mp3: put each reciter's files in a folder named after it` | A reciter's folder is not named like its `quran/audio/` folder, or originals sit inside `<media>` | Rename or move the folders |
| `No local file named 042.mp3` | A surah is missing or misnamed | Name the files `001.mp3` … `114.mp3` |
| `Several recordings share a file name: convert one reciter at a time` (convert) | `--from-dir` holds more than one reciter | Convert each reciter on its own |
| `… lists N surahs, not 114` | `timings.json` is incomplete | Complete it, then check it again |
| `Reciter folder … must be lowercase …` | Bad folder name | Step 1 |
| `Cloudflare refused …` or HTTP 403 (publish) | The token lacks R2 edit rights, or is limited to other IP addresses | Ask the maintainer |
| `… is missing or stale; run layout with the same media` (publish) | The archives in `android-app/quran-assets/build` are from another `layout` run | Run step 5 again |
| The reciter is in the menu but its downloads fail | Its packs were never published, or the app was built before step 5 | Step 6; rebuild the app |
| The highlighted verse is always off by about the same time | The timings were made on another edition of the recording | Time the converted files again |

## Never

- Rename an existing reciter's folder or id, or remove or reorder reciters in the layout. Users
  would lose their downloads, and packs could move between Google Play and Cloudflare.
- Edit generated files (`packs.json`, `manifest.tsv`, `android-app/quran-packs/`) by hand.
- Commit recordings, page scans, zips or credentials.
- Add a recitation that is not Qaloun 'an Nafi'.
