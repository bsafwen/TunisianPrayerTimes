# Qaloon Quran Recitation Recognition Model — Build Plan

> **Goal:** Build an on-device speech-to-text model that accurately transcribes Qaloon riwaya Quran recitation, then use it in an Android app to detect word/letter-level mistakes in real time.
>
> **Approach:** Fine-tune OpenAI's Whisper (open-source, MIT license) on Qaloon-specific audio data. This is the right call over building from scratch — Whisper already understands Arabic phonetics, attention-based alignment, and audio feature extraction. We'd need 10,000+ hours and months of GPU time to replicate that from zero. Instead, we specialize it in ~50-200 hours of Qaloon audio.

---

## Phase 0 — Environment & Tooling Setup

**Duration:** 1-2 days

### Tasks
- [ ] Set up a Python environment (3.10+) with:
  ```
  pip install torch torchaudio transformers datasets evaluate jiwer
  pip install openai-whisper whisper-normalizer
  pip install librosa soundfile pydub
  ```
- [ ] Verify GPU access (local NVIDIA GPU, or rent a cloud GPU — RunPod/Vast.ai/Lambda, NOT a managed API; we control the machine)
  - Minimum: 1x A100 40GB (fine-tuning `whisper-small`) 
  - Recommended: 1x A100 80GB (fine-tuning `whisper-medium`)
- [ ] Clone Whisper repo and verify baseline Arabic inference:
  ```bash
  git clone https://github.com/openai/whisper.git
  whisper test_arabic.wav --model medium --language ar
  ```
- [ ] Set up experiment tracking (Weights & Biases free tier, or plain TensorBoard)

### Deliverable
A working development environment that can load Whisper and transcribe Arabic audio.

---

## Phase 1 — Data Collection

**Duration:** 1-3 weeks (can be parallelized with Phase 0)

This is the most critical phase. Model quality = data quality.

### 1.1 Primary Source: Existing Qaloon Recordings

| Source | Reciter | Format | Estimated Hours |
|--------|---------|--------|-----------------|
| [EveryAyah.com](https://everyayah.com/) | Qaari (Qaloon) | MP3, per-ayah | ~15-20h |
| [Quran.com audio](https://quran.com/) | Multiple Qaloon reciters | MP3 | ~10-15h |
| [Archive.org](https://archive.org/) | Various Qaloon mushaf recordings | MP3/OGG | ~20-40h |
| Tunisian/Libyan radio recordings | Local imams (Qaloon is standard in Tunisia/Libya) | Various | ~10-20h |

**Target: 50-200 hours of labeled Qaloon audio.**

### 1.2 Download & Organize Audio

```
data/
├── raw/                    # Original downloads
│   ├── everyayah_qaloon/
│   ├── qurancom_qaloon/
│   └── archive_qaloon/
├── processed/              # Cleaned, split, normalized
│   ├── train/
│   ├── val/
│   └── test/
└── metadata/
    ├── transcripts.json    # Ayah-level transcripts
    └── splits.json         # Train/val/test split info
```

### 1.3 Qaloon Reference Text

We need the **exact Uthmani text for Qaloon riwaya** (not Hafs). Key differences from Hafs exist in ~1,000+ words across the Quran.

- Source the Qaloon mushaf text from [Tanzil.net](https://tanzil.net/download/) or digitized Qaloon mushafs
- Store as JSON with full tashkeel (diacritics):
  ```json
  {
    "surah": 1, "ayah": 4,
    "text": "مَلِكِ يَوْمِ ٱلدِّينِ",
    "riwaya": "qaloon"
  }
  ```
- **Verify every ayah** against a printed Qaloon mushaf — transcription errors here will poison the model
- Document all Qaloon-Hafs differences in a separate reference file

### 1.4 Alignment: Audio ↔ Text

Each audio clip must be paired with its exact transcript.

- **EveryAyah files** are already per-ayah → direct pairing via filename (surah_ayah.mp3)
- **Longer recordings** → use forced alignment with `whisperx` or `ctc-segmentation` to split at ayah boundaries
- For each sample, produce:
  ```json
  {
    "audio_path": "processed/train/001_004.wav",
    "text": "مَلِكِ يَوْمِ ٱلدِّينِ",
    "surah": 1, "ayah": 4,
    "duration_sec": 3.2,
    "reciter": "qaari"
  }
  ```

### Deliverable
A HuggingFace `Dataset` (or equivalent) with 50-200h of `(audio, transcript)` pairs, split 90/5/5 train/val/test.

---

## Phase 2 — Data Preprocessing

**Duration:** 3-5 days

### 2.1 Audio Normalization
```python
# Target format for Whisper:
# - 16kHz sample rate
# - Mono channel
# - 16-bit PCM WAV
# - Normalized volume (peak at -1dB)

import librosa
import soundfile as sf

def normalize_audio(input_path, output_path):
    audio, sr = librosa.load(input_path, sr=16000, mono=True)
    audio = librosa.util.normalize(audio)
    sf.write(output_path, audio, 16000, subtype='PCM_16')
```

### 2.2 Segment Length
- Whisper works best with segments ≤ 30 seconds
- Most ayahs are under 30s → no splitting needed
- Long ayahs (Al-Baqarah 282, etc.) → split at natural pause points using silence detection
- Very short ayahs (< 1s) → optionally concatenate 2-3 consecutive ayahs into one sample

### 2.3 Text Normalization
- Keep full tashkeel in ground truth (this is what the model learns to output)
- Normalize Unicode: use NFC normalization, consistent alef/hamza forms
- Handle Quran-specific characters: ۝ (ayah marker), ۞ (hizb marker) — strip these from transcript

### 2.4 Data Augmentation (Optional, for robustness)
- **Speed perturbation:** 0.9x and 1.1x playback speed (simulates faster/slower reciters)
- **Room impulse response:** Simulate mosque reverb
- **Background noise:** Low-level ambient noise (fan, AC) at SNR 20-30dB
- Do NOT augment with pitch shift — this would create unnatural Arabic phonetics

### Deliverable
A clean, normalized dataset ready for training with consistent audio format and verified transcripts.

---

## Phase 3 — Model Fine-Tuning

**Duration:** 1-2 weeks (GPU time: 2-5 days depending on model size)

### 3.1 Model Selection

| Model | Parameters | VRAM Needed | Arabic WER (baseline) | Recommended |
|-------|-----------|-------------|----------------------|-------------|
| `whisper-small` | 244M | ~12GB | ~15-20% | For prototyping |
| `whisper-medium` | 769M | ~24GB | ~10-15% | **Best balance** |
| `whisper-large-v3` | 1.5B | ~40GB | ~8-12% | If you have the GPU |

**Start with `whisper-small` for fast iteration, then scale to `whisper-medium` for production.**

### 3.2 Fine-Tuning Script (HuggingFace Transformers)

```python
from transformers import WhisperForConditionalGeneration, WhisperProcessor
from transformers import Seq2SeqTrainer, Seq2SeqTrainingArguments

model_name = "openai/whisper-small"
model = WhisperForConditionalGeneration.from_pretrained(model_name)
processor = WhisperProcessor.from_pretrained(model_name)

# Force Arabic language and transcribe task
model.config.forced_decoder_ids = processor.get_decoder_prompt_ids(
    language="ar", task="transcribe"
)

training_args = Seq2SeqTrainingArguments(
    output_dir="./whisper-qaloon-small",
    per_device_train_batch_size=16,       # Adjust for VRAM
    gradient_accumulation_steps=2,
    learning_rate=1e-5,                    # Low LR — we're fine-tuning, not training
    warmup_steps=500,
    max_steps=5000,                        # Adjust based on dataset size
    eval_strategy="steps",
    eval_steps=500,
    save_steps=500,
    fp16=True,
    predict_with_generate=True,
    generation_max_length=225,
    logging_steps=25,
    load_best_model_at_end=True,
    metric_for_best_model="wer",
    greater_is_better=False,
)

# Data collator, dataset, and trainer setup here...
trainer = Seq2SeqTrainer(
    model=model,
    args=training_args,
    train_dataset=train_dataset,
    eval_dataset=val_dataset,
    data_collator=data_collator,
    compute_metrics=compute_metrics,
    tokenizer=processor.feature_extractor,
)

trainer.train()
```

### 3.3 Key Hyperparameters to Tune

| Parameter | Start Value | Notes |
|-----------|------------|-------|
| Learning rate | 1e-5 | Too high → catastrophic forgetting of Arabic knowledge |
| Batch size | 16-32 | Maximize for your VRAM |
| Max steps | 3,000-10,000 | Monitor val loss — stop when it plateaus |
| Warmup | 500 | Stabilizes early training |
| Weight decay | 0.01 | Prevents overfitting |
| Dropout | 0.1 (default) | Keep as-is |

### 3.4 What the Model Learns

We're NOT teaching Whisper Arabic from scratch. We're teaching it:
1. **Quranic vocabulary** — Classical Arabic words it may not have seen enough of
2. **Qaloon-specific pronunciations** — Where Qaloon differs from standard Arabic or Hafs
3. **Recitation style** — Melodic, elongated vowels, pauses between ayahs
4. **Tashkeel output** — To output full diacritics (critical for detecting mistakes)

### Deliverable
A fine-tuned Whisper model with val WER < 10% on Qaloon recitation.

---

## Phase 4 — Evaluation & Iteration

**Duration:** 1 week

### 4.1 Metrics

| Metric | Target | What It Measures |
|--------|--------|-----------------|
| **WER** (Word Error Rate) | < 10% | Overall transcription accuracy |
| **CER** (Character Error Rate) | < 5% | Letter-level accuracy (critical for our use case) |
| **Tashkeel accuracy** | > 90% | Diacritics correctness |
| **Qaloon-specific WER** | < 5% | Accuracy on words where Qaloon differs from Hafs |

### 4.2 Error Analysis

Build a confusion matrix of common errors:
```
Expected: مَلِكِ  →  Got: مَالِكِ    (Qaloon/Hafs confusion — BAD)
Expected: يَوْمِ  →  Got: يَوْمِ     (Correct — GOOD)
Expected: نَسْتَعِينُ → Got: نَسْتَعِين (Missing final damma — MINOR)
```

Categorize errors:
1. **Critical:** Wrong word / Qaloon-Hafs confusion → need more training data for these cases
2. **Medium:** Missing/wrong tashkeel → can be handled with post-processing rules
3. **Low:** Minor Unicode differences → normalize in post-processing

### 4.3 Targeted Improvement

- Collect more data for high-error ayahs
- Oversample ayahs with Qaloon-Hafs differences (these are the most important to get right)
- Add a post-processing layer for systematic tashkeel corrections

### Deliverable
A validated model with documented performance across all surahs, and a known error profile.

---

## Phase 5 — Model Optimization for Mobile

**Duration:** 1-2 weeks

### 5.1 Quantization

Reduce model size and inference speed for Android deployment:

```python
import torch
from transformers import WhisperForConditionalGeneration

model = WhisperForConditionalGeneration.from_pretrained("./whisper-qaloon-small")

# Dynamic quantization (CPU)
quantized_model = torch.quantization.quantize_dynamic(
    model, {torch.nn.Linear}, dtype=torch.qint8
)

# OR: Export to ONNX + quantize
from optimum.onnxruntime import ORTModelForSpeechSeq2Seq
ort_model = ORTModelForSpeechSeq2Seq.from_pretrained(
    "./whisper-qaloon-small", export=True
)
```

| Format | Size (whisper-small) | Speed | Accuracy Loss |
|--------|---------------------|-------|---------------|
| PyTorch FP32 | ~950MB | Baseline | None |
| PyTorch INT8 | ~250MB | 2-3x faster | < 1% WER |
| ONNX INT8 | ~200MB | 3-4x faster | < 1% WER |
| whisper.cpp GGML Q5 | ~180MB | 4-5x faster | ~1-2% WER |

### 5.2 whisper.cpp Conversion (Recommended for Android)

[whisper.cpp](https://github.com/ggerganov/whisper.cpp) is the gold standard for on-device Whisper:

```bash
# Convert fine-tuned model to whisper.cpp format
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp

# Convert HF model → ggml
python models/convert-h5-to-ggml.py ../whisper-qaloon-small/ . ./models/

# Quantize to Q5_1 (best size/quality tradeoff)
./quantize models/ggml-model.bin models/ggml-model-q5_1.bin q5_1

# Test
./main -m models/ggml-model-q5_1.bin -l ar -f test_recitation.wav
```

### 5.3 Android Integration via JNI

whisper.cpp has an [official Android example](https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android):
- C++ inference via JNI
- ~200ms latency per 5s chunk on modern phones (Snapdragon 8 Gen 2)
- Model loaded once into memory, reused across inferences

### Deliverable
A quantized model file (< 250MB) that runs on Android with < 500ms latency per ayah.

---

## Phase 6 — Android App Integration

**Duration:** 2-3 weeks

### 6.1 App Architecture

```
┌─────────────────────────────────────────┐
│              Android App                 │
├─────────────┬───────────────────────────┤
│  UI Layer   │  Jetpack Compose          │
│  (Kotlin)   │  - Surah/Ayah selector    │
│             │  - Live text display       │
│             │  - Error highlights (red)  │
│             │  - Audio playback button   │
├─────────────┼───────────────────────────┤
│  Logic      │  Kotlin                   │
│  Layer      │  - Text comparison engine  │
│             │  - Arabic text normalizer  │
│             │  - Recording manager       │
├─────────────┼───────────────────────────┤
│  ML Layer   │  whisper.cpp (C++ / JNI)  │
│             │  - Model loading           │
│             │  - Audio → text inference  │
│             │  - Streaming support       │
├─────────────┼───────────────────────────┤
│  Data       │  Room DB                  │
│  Layer      │  - Qaloon Quran text       │
│             │  - User progress tracking  │
│             │  - Recitation history      │
└─────────────┴───────────────────────────┘
```

### 6.2 Core Flow

```
1. User selects Surah Al-Fatiha, Ayah 4
2. App displays: مَلِكِ يَوْمِ ٱلدِّينِ
3. User presses 🎤 and recites
4. Audio captured in 5-second chunks
5. Each chunk → whisper.cpp → Arabic text
6. Compare output against reference:
   - User said: "مَالِكِ يَوْمِ ٱلدِّينِ"  (Hafs reading)
   - Expected:  "مَلِكِ يَوْمِ ٱلدِّينِ"   (Qaloon reading)
   - Result: ❌ مَالِكِ → should be مَلِكِ
7. Highlight مالك in red, show correct word
8. Optional: play correct audio clip for that word
```

### 6.3 Comparison Algorithm

```kotlin
fun compareRecitation(
    spoken: String,
    reference: String
): List<WordCorrection> {
    val spokenWords = arabicTokenize(spoken)
    val refWords = arabicTokenize(reference)
    
    // Levenshtein-based word alignment
    val alignment = alignWords(spokenWords, refWords)
    
    return alignment.filter { it.type != MatchType.EXACT }
        .map { WordCorrection(
            position = it.refIndex,
            spoken = it.spokenWord,
            expected = it.refWord,
            type = it.type  // SUBSTITUTION, INSERTION, DELETION
        )}
}
```

### Deliverable
A working Android app that records recitation, transcribes it, and highlights word-level errors against Qaloon text.

---

## Phase 7 — Testing & Refinement

**Duration:** Ongoing

### 7.1 Testing Strategy

- **Unit tests:** Arabic text normalization, comparison algorithm
- **Integration tests:** Full pipeline (audio → transcription → comparison)
- **User testing:** Record 10+ people reciting surahs, measure false positive/negative rates
- **Edge cases:** 
  - Very fast recitation
  - Very slow recitation with long pauses
  - Background noise (mosque, home)
  - Multiple ayahs without pause

### 7.2 Feedback Loop

```
User reports false correction
        ↓
Log the audio + ASR output + expected text
        ↓
Add to training set with correct label
        ↓
Periodic re-training (monthly)
        ↓
Push updated model via app update
```

---

## Resource Summary

| Resource | Estimated Cost | Notes |
|----------|---------------|-------|
| GPU rental (A100, ~100h) | $150-300 | RunPod/Vast.ai at $1.5-3/h |
| Storage (audio data) | Negligible | ~50GB |
| Development time | 6-10 weeks | Two people, focused effort |
| Ongoing GPU for retraining | ~$50/month | Optional, as needed |

**Total estimated cost: $200-400** (excluding our time)

---

## Milestone Checklist

| # | Milestone | Status |
|---|-----------|--------|
| 0 | Dev environment ready | ⬜ |
| 1 | 50+ hours of Qaloon audio collected & labeled | ⬜ |
| 2 | Data preprocessed and verified | ⬜ |
| 3 | First fine-tuned model (whisper-small) trained | ⬜ |
| 4 | WER < 15% on test set | ⬜ |
| 5 | Model optimized, WER < 10% | ⬜ |
| 6 | Quantized model running on Android | ⬜ |
| 7 | App MVP with comparison working | ⬜ |
| 8 | User testing with 10+ people | ⬜ |
| 9 | Public release | ⬜ |

---

## File Structure (Final Project)

```
qaloon-model/
├── data/
│   ├── raw/                     # Downloaded audio
│   ├── processed/               # Cleaned, normalized audio
│   ├── quran_qaloon.json        # Qaloon Quran text with tashkeel
│   └── qaloon_hafs_diff.json    # Words that differ between riwayat
├── training/
│   ├── prepare_dataset.py       # Audio → HuggingFace Dataset
│   ├── train.py                 # Fine-tuning script
│   ├── evaluate.py              # WER/CER evaluation
│   └── configs/
│       ├── small.yaml
│       └── medium.yaml
├── export/
│   ├── convert_ggml.py          # → whisper.cpp format
│   ├── quantize.sh              # Quantization script
│   └── benchmark.py             # Speed/accuracy benchmarks
├── android-app/                 # The Android app
│   ├── app/src/main/
│   │   ├── cpp/                 # whisper.cpp JNI bindings
│   │   ├── java/.../            
│   │   │   ├── ml/              # Model inference wrapper
│   │   │   ├── comparison/      # Text comparison engine
│   │   │   ├── data/            # Quran database
│   │   │   └── ui/              # Compose UI
│   │   └── assets/
│   │       └── ggml-model-q5.bin
│   └── build.gradle.kts
└── README.md
```

---

## Let's Start

**Phase 0 and Phase 1 can run in parallel.** The immediate next steps are:

1. **Set up the Python environment** and verify Whisper works on a sample Arabic audio file
2. **Start downloading Qaloon audio** from EveryAyah.com (scripted bulk download)
3. **Source the Qaloon Quran text** and verify against a physical mushaf

We build this one phase at a time. Each phase produces a concrete deliverable we can test before moving on.
