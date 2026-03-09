# Qaloon Quran Recitation Model

Fine-tuned Whisper model for recognizing Qaloon an Nafi' Quran recitation.

## Quick Start

### 1. Setup
```bash
cd qaloon-model
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

### 2. Download Audio Data
```bash
# Download Husary (Qaloon) — per-ayah files from EveryAyah.com
python scripts/download_everyayah.py --reciter Husary_Qaloon_128kbps

# Discover Qaloon reciters on MP3Quran.net
python scripts/scrape_mp3quran.py --list-reciters

# Search Archive.org for more recordings
python scripts/download_archive.py --search
```

### 3. Build Qaloon Reference Text
```bash
# Download Hafs baseline text
python scripts/build_qaloon_text.py --download-hafs

# Create Qaloon-Hafs diff template (edit manually)
python scripts/build_qaloon_text.py --init-diffs

# Apply diffs to generate Qaloon text
python scripts/build_qaloon_text.py --apply-diffs

# Verify a surah
python scripts/build_qaloon_text.py --verify --surah 1
```

### 4. Process Audio
```bash
# Normalize all audio to 16kHz WAV
python scripts/normalize_audio.py --input data/raw/husary_qaloon/ --output data/processed/husary_qaloon/

# Segment per-surah recordings into per-ayah clips
python scripts/segment_surah.py --input data/raw/muhaysin_qaloon/ --output data/processed/muhaysin_qaloon/

# Validate segments
python scripts/validate_segments.py --dir data/processed/
```

### 5. Build Training Manifests
```bash
python scripts/build_manifest.py --test-reciter husary_qaloon
python scripts/dataset_stats.py
```

### 6. User Contributions

The Android app has a "Contribute" tab where users record ayah recitations. Recordings are uploaded to Cloudflare R2 via the Worker API. To incorporate them into training:

```bash
# Download all contributions (plain HTTP, no credentials needed)
python scripts/download_contributions.py

# Dry run — see what would be downloaded without downloading
python scripts/download_contributions.py --dry-run

# Rebuild manifest from already-downloaded files
python scripts/download_contributions.py --manifest-only

# Custom output directory
python scripts/download_contributions.py --output data/contributions/
```

This produces `data/manifest_contributions.jsonl`. To include in training:
```bash
cat data/manifest_train.jsonl data/manifest_contributions.jsonl > data/manifest_combined.jsonl
```

**How it works:**
- The script calls `GET /api/contribute/list` to get all contribution metadata (paginated)
- Downloads each WAV via `GET /api/contribute/download?key=...`, skipping files already on disk
- Builds a JSONL manifest using text from R2 metadata (falls back to `quran_qaloon.json`)
- Files are stored locally as `data/contributions/{contributor_id}/{SSS}/{SSSAAA}_{timestamp}.wav`
- Fully resumable — re-running only downloads new files

### 7. Automated Retrain (RunPod)

A single script downloads new contributions, merges manifests, and trains:

```bash
# Full pipeline: download contributions → merge → train
bash scripts/retrain.sh

# Preview what would happen (no downloads, no training)
bash scripts/retrain.sh --dry-run

# Skip download (e.g. contributions already downloaded)
bash scripts/retrain.sh --skip-download
```

**Override defaults via environment variables:**
```bash
# Custom hyperparameters
LEARNING_RATE=5e-7 EPOCHS=10 BATCH_SIZE=8 GRAD_ACCUM=2 bash scripts/retrain.sh

# Use a different model
MODEL_NAME=openai/whisper-medium bash scripts/retrain.sh
```

**What it does:**
1. Downloads new user contributions via the Worker API (incremental, resumable)
2. Merges `manifest_train.jsonl` + `manifest_contributions.jsonl` into a combined manifest
3. Auto-detects the latest checkpoint and resumes training from it
4. Restores the original `manifest_train.jsonl` on exit (even on failure)
5. Prints the conversion command for Android deployment when done

**On RunPod:**
```bash
ssh -p 15146 root@104.255.9.187
cd /workspace/qaloon-model
bash scripts/retrain.sh
```

## Pipeline Order

```
download_everyayah.py ─┐
scrape_mp3quran.py ────┤
download_archive.py ───┘
         │
   build_qaloon_text.py (parallel)
         │
   normalize_audio.py
         │
   segment_surah.py (for per-surah sources, resumable, --workers N)
         │
   validate_segments.py
         │
   split_long_segments.py (split >30s at silence)
         │
   build_manifest.py ──────────────┐
         │                         │
   dataset_stats.py      download_contributions.py
                                   │
                           manifest_contributions.jsonl
                                   │
                              cat → manifest_combined.jsonl
```

## Known Risks for Qaloon Recognition

Full audit of the pipeline for issues that could make the fine-tuned model unable to understand Qaloon recitation.

### CRITICAL — Text Labels

1. **Only 55/6236 ayahs (0.9%) actually differ from Hafs in the Qaloon text.**
   The diff file (`qaloon_hafs_diff.json`) has only 74 entries, 0 verified, and 15 are no-ops (hafs_word == qaloon_word). The real number of farsh al-huruf differences across the Quran is estimated at 500–600. This means ~90% of Qaloon-specific words are **still Hafs text** in our labels — the model will learn Hafs output for those ayahs.
   - **Fix**: Expand and verify the diff file against a printed Qaloon mushaf. Priority: high-frequency surahs (Al-Fatiha, Al-Baqarah, Yasin, Al-Mulk, etc.).

2. **Usul (pronunciation rules) are invisible in text.**
   Qaloon has systematic pronunciation rules that differ from Hafs but produce no text difference:
   - *Mim al-jam'* (صلة ميم الجمع): Qaloon connects plural mim with a short vowel before hamza
   - *Madd munfasil*: Qaloon shortens it to 2 harakaat (Hafs does 4–5)
   - *Ishmam of sirat*: Qaloon reads الصراط with a sound between ص and ز
   - *Tashil/ibdal of hamza*: Systematic hamza softening rules
   
   Since these produce the same text, the model has no signal to learn them. The audio sounds different but the label is identical to Hafs → the model will treat Qaloon pronunciation as noise.
   - **Fix**: Either (a) accept that the model transcribes Qaloon audio to near-Hafs text (may be the pragmatic choice), or (b) encode pronunciation markers in text labels (complex, non-standard).

### HIGH — Segmentation / Alignment

3. **WhisperX alignment uses a general Arabic Whisper model.**
   `segment_surah.py` loads `whisperx.load_model("medium", ...)` which is pre-trained Whisper — trained on Hafs recitation and general Arabic. It transcribes the audio first, then force-aligns. If the Qaloon pronunciation confuses the model (e.g., different vowels, tashil of hamza), the word-level timestamps will be inaccurate, producing mis-cut segments where audio doesn't match the label.
   - **Fix**: Validate segment boundaries manually for a sample of surahs. After the first training round, use the fine-tuned model for re-segmentation.

4. **Alignment doesnt use the Qaloon text at all for boundary detection.**
   WhisperX transcribes freely, then aligns its *own* transcription to timestamps. The actual Qaloon text is only used to estimate proportional timing + silence snapping. The word timestamps from WhisperX are computed but never directly matched to Qaloon ayah text — boundaries are purely heuristic (proportional character count + snap to nearest silence within ±2s).
   - **Fix**: Implement actual forced alignment using the known Qaloon text rather than relying on WhisperX free transcription.

5. **Bismillah handling may shift all ayah boundaries.**
   Surahs typically begin with the Bismillah, but it's not always counted as ayah 1 (except Surah 1). If a reciter includes the Bismillah in audio but the segmenter doesn't account for it, all subsequent ayah boundaries shift by one — every segment gets the wrong label.
   - **Fix**: The segmenter starts ayah numbering at 1 and the manifest skips ayah 0 files. Verify that the reciters' audio Bismillah timing is consistent with the boundary estimation.

### HIGH — Tokenizer / Model Architecture

6. **Whisper tokenizer may not roundtrip Uthmani script characters.**
   The Qaloon text contains:
   - U+0670 SUPERSCRIPT ALEF: 9,835 occurrences (Uthmani script marker)
   - U+06ED SMALL LOW MEEM: 99 occurrences
   - U+06DC SMALL HIGH SEEN: 2 occurrences
   - Various Quran annotation marks (U+06EA–U+06EC)
   
   Whisper's tokenizer was trained on general text, not Uthmani Quran script. These characters may be mapped to `<unk>` tokens or merged incorrectly. If the tokenizer can't represent the target text, the model literally cannot produce correct output.
   - **Fix**: Test tokenizer roundtrip on all unique characters in `quran_qaloon.json`. If any are lost, either (a) normalize text to simplified Arabic before training, or (b) add special tokens to the tokenizer (requires unfreezing embeddings).

7. **`forced_decoder_ids` is set to generic Arabic (`language="ar"`).**
   This tells Whisper "this is Arabic, transcribe it." It biases the decoder toward MSA/general Arabic text patterns. There is no Qaloon-specific language tag. This is acceptable but means the model starts from a Hafs/MSA prior.

8. **Frozen encoder assumes Whisper already understands Qaloon phonetics.**
   With `--freeze-encoder` (default), only the decoder is trained. The encoder's acoustic representations were learned from general Arabic (mostly Hafs recitation, news, YouTube, etc.). If Qaloon's pronunciation rules (ishmam, tashil, madd differences) produce acoustic features significantly different from what the encoder has seen, the encoder won't extract them correctly, and the decoder can't compensate.
   - **Fix (implemented)**: Progressive unfreezing with discriminative LR:
     - Phase 1: `--freeze-encoder` (decoder only, lr=1e-7) — baseline
     - Phase 2: `--freeze-encoder --unfreeze-encoder-layers 4 --encoder-lr 1e-8` — last 4 encoder layers + final LayerNorm
     - Phase 3: `--no-freeze-encoder --learning-rate 1e-8` — full unfreeze (optional)
   - Compare WER across phases to decide optimal configuration.
   - `retrain.sh` supports `UNFREEZE_LAYERS=4 ENCODER_LR=1e-8 bash scripts/retrain.sh`

### MEDIUM — Training Configuration

9. **Previous training diverged catastrophically.**
   Local run: loss went from 8.0 → 8.5 (increasing), gradient norms hit 608K (should be <10), 14 NaN gradient events in rapid succession. Root causes:
   - `lr=1e-6` was too high with `batch_size=1 × grad_accum=16` (effective batch=16)
   - `max_grad_norm=1.0` was insufficient — gradients were 100-600x beyond clipping range
   - The NaNSafeTrainer's gradient check only samples 5 params — NaN can propagate through unchecked params
   - **Fix**: Start with lr=1e-7 or lower. Consider `max_grad_norm=0.1`. Use `weight_decay=0.01`. Monitor gradient norms in first 100 steps.

10. **`save_steps=1500` is too infrequent.**
    With ~26K samples and batch_size=16, that's ~1625 steps/epoch. First checkpoint at step 1500 means nearly a full epoch runs before any save. If training diverges at step 800, all GPU time is wasted.
    - **Fix**: Set `save_steps=500` and `eval_steps=500` for early divergence detection.

11. **No early stopping configured.**
    Training runs for a fixed number of epochs with no early stopping. If the model overfits or diverges mid-training, it continues burning GPU time.
    - **Fix**: Add `EarlyStoppingCallback` with patience=3 based on validation WER.

### LOW — Data Quality

12. **Only 5 reciters available on mp3quran.net for Qaloon.**
    Limited speaker diversity means the model may overfit to these specific voices and fail to generalize to other Qaloon reciters.

13. **Segment duration variance is uncontrolled.**
    Some ayahs are 1 second (short Makki surahs), others are 30+ seconds (long Madani ayahs). The `MAX_AUDIO_SEC=30` cutoff in `train_whisper.py` was truncating audio without adjusting text labels — the model saw partial audio with full text, learning to hallucinate.
    - **Impact**: ~2,600 samples (9.9%) exceeded 30s. Max duration was 169s.
    - **Fix (applied)**:
      - `train_whisper.py` now **drops** samples > 30s instead of silently truncating audio.
      - New `split_long_segments.py` splits long WAVs at silence boundaries into sub-segments ≤ 30s, with proportional text labels saved in `split_texts.json` sidecars.
      - `build_manifest.py` updated to recognize split files (`SSSAAA_NN.wav`) and read their text from sidecars.
    - **Pipeline order**: Run `split_long_segments.py` before `build_manifest.py`.

14. **No data augmentation.**
    No speed perturbation, noise injection, or SpecAugment. The model may overfit to the specific recording conditions of the 5 reciters.

## Directory Structure

```
qaloon-model/
├── scripts/                    # All pipeline scripts
├── data/
│   ├── raw/                    # Downloaded audio (gitignored)
│   ├── processed/              # Normalized WAVs (gitignored)
│   ├── metadata/
│   │   ├── surah_ayah_counts.json
│   │   ├── quran_hafs_uthmani.json
│   │   ├── quran_qaloon.json
│   │   └── qaloon_hafs_diff.json
│   ├── manifest_train.jsonl
│   ├── manifest_val.jsonl
│   └── manifest_test.jsonl
├── requirements.txt
└── .gitignore
```
