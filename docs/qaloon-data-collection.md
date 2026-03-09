# Qaloon Data Collection Plan

> **Goal:** Collect 100+ hours of labeled Qaloon Quran recitation audio with per-ayah alignment.
> Every audio file must be paired with its exact Qaloon Uthmani text transcript.

---

## Available Complete Qaloon Recitations

These are full mushaf recordings (30 juz), freely available online, specifically in **Qaloon an Nafi'** riwaya:

### Tier 1 — Per-Ayah Recordings (Already Segmented)

These are the highest-value sources because each file = one ayah = one transcript line. No forced alignment needed.

| # | Reciter | Source | Format | Est. Hours | Segmentation |
|---|---------|--------|--------|------------|--------------|
| 1 | **Mahmoud Khalil Al-Husary** (Qaloon) | EveryAyah.com / QuranicAudio.com | MP3, per-ayah | ~18-22h | ✅ Per-ayah |
| 2 | **Qaari (Qaloon)** | EveryAyah.com | MP3, per-ayah | ~18-22h | ✅ Per-ayah |

### Tier 2 — Per-Surah Recordings (Need Splitting)

Full surah recordings — high quality, but need to be split into ayah-level segments via forced alignment.

| # | Reciter | Source | Format | Est. Hours | Segmentation |
|---|---------|--------|--------|------------|--------------|
| 3 | **Muhammad Salim Muhaysin** | MP3Quran.net / Archive.org | MP3, per-surah | ~20-25h | ⬜ Per-surah |
| 4 | **Ahmad Naina** | MP3Quran.net / Islamway.net | MP3, per-surah | ~25-30h | ⬜ Per-surah |
| 5 | **Abdurrahman Al-Hudhayfi** (Qaloon) | Archive.org / Islamway.net | MP3, per-surah | ~20-25h | ⬜ Per-surah |
| 6 | **Qaloon readers from Tunisian radio** | Archive.org | MP3, per-surah/juz | ~10-15h | ⬜ Mixed |

### Tier 3 — Additional Sources (Backup)

| # | Reciter | Source | Notes |
|---|---------|--------|-------|
| 7 | **Ali Al-Hudhayfi** (Qaloon) | Various | Less commonly found, verify riwaya |
| 8 | **Abdel Hakim Abdel Latif** | Archive.org | Verify completeness |
| 9 | Various Libyan/Tunisian imams | YouTube (yt-dlp) | Variable quality, taraweeh recordings |

**Estimated total: 110-140 hours across 6-9 reciters**

---

## Download Sources & URLs

### EveryAyah.com

The best source — files are named by surah and ayah number.

```
# Pattern: https://everyayah.com/data/{reciter_folder}/{surah_padded}{ayah_padded}.mp3
# Example: https://everyayah.com/data/Husary_Qaloon_128kbps/001001.mp3
#                                                              ^^^~~~
#                                                              surah 001, ayah 001
```

**Known Qaloon folders on EveryAyah:**
- `Husary_Qaloon_128kbps` — Al-Husary, Qaloon riwaya
- Check for additional reciters at: `https://everyayah.com/data/status.php`

### MP3Quran.net

```
# Reciters page: https://mp3quran.net/ar/qaloon
# Lists all available Qaloon reciters with per-surah downloads
# Pattern varies per reciter — inspect page for direct links
```

### QuranicAudio.com

```
# API endpoint: https://quranicaudio.com/api/audio_files
# Filter by reciter + riwaya
# Has per-ayah downloads for some reciters
```

### Archive.org

```
# Search: "quran qaloon" OR "قالون" 
# https://archive.org/search?query=quran+qaloon
# Bulk download with: ia download <identifier>
```

---

## Phase 1A: Download Per-Ayah Audio (Week 1, Days 1-3)

### Step 1: Download Al-Husary (Qaloon) from EveryAyah

```bash
mkdir -p data/raw/husary_qaloon

# Quran has 6236 ayahs across 114 surahs
# Surah ayah counts: Al-Fatiha=7, Al-Baqarah=286, ... An-Nas=6

# Download script
python3 scripts/download_everyayah.py \
    --reciter "Husary_Qaloon_128kbps" \
    --output data/raw/husary_qaloon/ \
    --format mp3
```

**download_everyayah.py** logic:
```python
"""
Download all ayah recordings from EveryAyah.com for a given reciter.

For each surah (1-114), for each ayah (1-N):
  - URL: https://everyayah.com/data/{reciter}/{surah:03d}{ayah:03d}.mp3
  - Save to: {output}/{surah:03d}/{surah:03d}{ayah:03d}.mp3
  - Include bismillah files: {surah:03d}000.mp3 (ayah 0 = bismillah)
  - Retry on failure, skip if already downloaded
  - Respect rate limiting (0.5s delay between requests)

Total files: ~6,236 ayahs + 114 bismillahs = ~6,350 files
Expected size: ~2-4 GB per reciter
"""
```

### Step 2: Download second per-ayah reciter (if available)

Check EveryAyah status page for additional Qaloon reciters and repeat.

### Step 3: Verify Downloads

```bash
# Count files — should be ~6350 per reciter
find data/raw/husary_qaloon -name "*.mp3" | wc -l

# Check for zero-byte / corrupt files
find data/raw/husary_qaloon -name "*.mp3" -size 0 -print
find data/raw/husary_qaloon -name "*.mp3" -exec ffprobe {} \; 2>&1 | grep "Invalid"

# Spot-check: play random ayahs to confirm they're Qaloon (not Hafs)
# Key test ayahs where Qaloon differs:
#   Al-Fatiha 4: مَلِكِ (not مَالِكِ)
#   Al-Baqarah 184: فِدْيَةٌ طَعَامُ مِسْكِينٍ (singular in Qaloon)
```

---

## Phase 1B: Download Per-Surah Audio (Week 1, Days 2-5)

### Step 1: Scrape MP3Quran.net Qaloon page

```bash
# List available Qaloon reciters
python3 scripts/scrape_mp3quran.py --riwaya qaloon --list-reciters

# Download each reciter's full mushaf (per-surah MP3s)
python3 scripts/scrape_mp3quran.py \
    --reciter "muhammad_salim_muhaysin" \
    --output data/raw/muhaysin_qaloon/

python3 scripts/scrape_mp3quran.py \
    --reciter "ahmad_naina" \
    --output data/raw/naina_qaloon/
```

### Step 2: Download from Archive.org

```bash
# Install Internet Archive CLI
pip install internetarchive

# Search for Qaloon recordings
ia search "quran qaloon" --fields identifier,title | head -20

# Download specific collections
ia download <identifier> --destdir data/raw/archive_qaloon/ --glob "*.mp3"
```

### Step 3: Verify Surah-Level Downloads

```bash
# Should have 114 files per reciter (one per surah)
find data/raw/muhaysin_qaloon -name "*.mp3" | wc -l

# Check total duration (should be ~18-30 hours per reciter)
ffprobe -show_entries format=duration data/raw/muhaysin_qaloon/*.mp3 2>/dev/null \
    | grep duration | awk -F= '{s+=$2} END {printf "%.1f hours\n", s/3600}'
```

---

## Phase 2: Qaloon Reference Text (Week 1, Days 1-3, parallel with downloads)

### Step 1: Source Qaloon Uthmani Text

The transcript for every ayah must be the **exact Qaloon reading**, NOT Hafs.

**Primary source options:**
1. **Tanzil.net** — Download Uthmani text, then manually patch the ~1,000+ Qaloon-Hafs differences
2. **King Fahd Complex Qaloon Mushaf** — Digital text if available
3. **Manually digitized Qaloon text** — From open-source Islamic text projects

```bash
# Download Tanzil Uthmani text as baseline
curl -o data/quran_uthmani_hafs.txt "https://tanzil.net/pub/download/index.php?quranType=uthmani&outType=txt"
```

### Step 2: Build Qaloon-Hafs Difference Table

This is critical. We need a verified list of every word that differs between Qaloon and Hafs.

```json
// qaloon_hafs_diff.json — Example entries
[
  {
    "surah": 1, "ayah": 4,
    "hafs": "مَـٰلِكِ",
    "qaloon": "مَلِكِ",
    "type": "word_change",
    "notes": "No madd in Qaloon"
  },
  {
    "surah": 2, "ayah": 132,
    "hafs": "وَوَصَّىٰ",
    "qaloon": "وَأَوْصَىٰ",
    "type": "word_change",
    "notes": "Different verb form"
  }
  // ... ~1000+ entries
]
```

**Sources for the diff list:**
- Published Qaloon-Hafs comparison books (كتب الفروق بين القراءات)
- Academic papers on Qiraat differences
- Cross-reference multiple sources for accuracy

### Step 3: Generate Qaloon Quran Text

```python
"""
apply_qaloon_diffs.py

1. Load Hafs Uthmani text (Tanzil)
2. Load qaloon_hafs_diff.json
3. For each diff entry, replace the Hafs word with the Qaloon word
4. Output: quran_qaloon.json with all 6236 ayahs in Qaloon reading
5. IMPORTANT: Have a Qaloon scholar verify a sample of ayahs
"""
```

### Step 4: Verification

- [ ] Randomly sample 100 ayahs, compare against physical Qaloon mushaf
- [ ] Specifically verify ALL entries in the diff table
- [ ] Have someone who reads Qaloon review the full text of at least 5 surahs

---

## Phase 3: Segment Per-Surah Audio into Per-Ayah (Week 2)

The per-surah recordings need to be split into individual ayah segments.

### Approach: Forced Alignment

Use an existing ASR model (Whisper base, unmodified) to find approximate timestamps, then refine with silence detection.

```python
"""
segment_surah.py

Input:  surah_002.mp3 (full Al-Baqarah recording, ~2.5 hours)
Output: 286 individual WAV files, one per ayah

Algorithm:
1. Run Whisper with word-level timestamps on the full surah audio
2. Match Whisper output words to known ayah text using dynamic programming
3. Identify ayah boundaries (end of ayah N → start of ayah N+1)
4. Refine boundaries using silence detection (ayah gaps are usually 0.5-2s of silence)
5. Cut audio at refined boundaries
6. Validate: check that segment count matches expected ayah count for the surah

Alternative approach:
- Use `whisperx` (forced alignment mode) which aligns transcript to audio at word level
- This is more accurate than vanilla Whisper timestamps
"""
```

### Validation

```bash
# For each reciter, after segmentation:
# 1. Count segments per surah — must match expected ayah count
python3 scripts/validate_segments.py --reciter muhaysin_qaloon

# 2. Spot-check 20 random ayahs per reciter — play and verify
python3 scripts/spot_check.py --reciter muhaysin_qaloon --count 20

# 3. Check segment durations — flag outliers
#    Typical ayah: 2-15 seconds
#    Flag: < 0.5s (likely cut wrong) or > 60s (likely merged ayahs)
python3 scripts/check_durations.py --reciter muhaysin_qaloon
```

---

## Phase 4: Build Final Dataset (Week 2-3)

### Step 1: Audio Normalization

```bash
# Convert all audio to consistent format:
#   16kHz, mono, 16-bit PCM WAV, normalized volume
python3 scripts/normalize_audio.py \
    --input data/raw/ \
    --output data/processed/ \
    --sample-rate 16000
```

### Step 2: Create Manifest File

```json
// data/manifest.jsonl — one line per sample
{"audio": "processed/husary_qaloon/002/002001.wav", "text": "الٓمٓ", "surah": 2, "ayah": 1, "reciter": "husary", "duration": 2.1}
{"audio": "processed/husary_qaloon/002/002002.wav", "text": "ذَٰلِكَ ٱلْكِتَابُ لَا رَيْبَ ۛ فِيهِ ۛ هُدًى لِّلْمُتَّقِينَ", "surah": 2, "ayah": 2, "reciter": "husary", "duration": 5.8}
```

### Step 3: Train/Val/Test Split

```
Split strategy (by reciter, NOT random):
├── Train: Husary + Muhaysin + Naina + Hudhayfi  (~90 hours)
├── Val:   Random 5% of train reciters' ayahs     (~5 hours)  
└── Test:  Hold out 1 full reciter entirely         (~20 hours)
                                                     
Why split by reciter for test?
→ Tests generalization to unseen voices
→ If we split randomly, the model memorizes voice patterns, not Arabic
```

### Step 4: Dataset Statistics

Generate and review:
```
Total samples:      ~25,000-37,000 (6236 ayahs × 4-6 reciters)
Total duration:     100-140 hours
Avg sample length:  4.2 seconds
Min sample length:  0.8 seconds
Max sample length:  45 seconds
Unique reciters:    4-6
```

---

## File Structure After Collection

```
data/
├── raw/
│   ├── husary_qaloon/           # Per-ayah MP3s from EveryAyah
│   │   ├── 001/
│   │   │   ├── 001001.mp3       # Al-Fatiha, ayah 1
│   │   │   ├── 001002.mp3
│   │   │   └── ...
│   │   ├── 002/
│   │   └── ...
│   ├── muhaysin_qaloon/         # Per-surah MP3s
│   │   ├── 001.mp3
│   │   ├── 002.mp3
│   │   └── ...
│   ├── naina_qaloon/
│   └── hudhayfi_qaloon/
├── processed/
│   ├── husary_qaloon/           # Normalized per-ayah WAVs
│   ├── muhaysin_qaloon/         # Segmented + normalized per-ayah WAVs
│   ├── naina_qaloon/
│   └── hudhayfi_qaloon/
├── metadata/
│   ├── quran_qaloon.json        # Full Qaloon Quran text
│   ├── qaloon_hafs_diff.json    # Difference table
│   ├── surah_ayah_counts.json   # Reference: ayah counts per surah
│   └── reciters.json            # Reciter metadata
├── manifest_train.jsonl
├── manifest_val.jsonl
├── manifest_test.jsonl
└── stats.json                   # Dataset statistics
```

---

## Scripts to Build

| Script | Purpose | Priority |
|--------|---------|----------|
| `scripts/download_everyayah.py` | Bulk download per-ayah audio | 🔴 Day 1 |
| `scripts/scrape_mp3quran.py` | Download per-surah recordings | 🔴 Day 1 |
| `scripts/download_archive.py` | Fetch from Archive.org | 🟡 Day 2 |
| `scripts/build_qaloon_text.py` | Generate Qaloon reference text | 🔴 Day 1 |
| `scripts/normalize_audio.py` | Convert to 16kHz WAV | 🟡 Day 3 |
| `scripts/segment_surah.py` | Split per-surah → per-ayah | 🔴 Day 4 |
| `scripts/validate_segments.py` | Verify segment counts & quality | 🟡 Day 5 |
| `scripts/build_manifest.py` | Create train/val/test JSONL | 🟡 Day 6 |
| `scripts/dataset_stats.py` | Generate statistics report | 🟢 Day 7 |

---

## Immediate Next Steps

1. **Create `scripts/download_everyayah.py`** — Start downloading Husary Qaloon tonight
2. **Verify EveryAyah reciter list** — Hit their status page, confirm exact folder names for Qaloon reciters
3. **Start the Qaloon-Hafs diff table** — This is scholarly work that takes time; start early
4. **Scout MP3Quran.net** — List all available Qaloon reciters with direct download links

The downloads can run overnight. By morning we'll have our first 20+ hours of labeled audio.
