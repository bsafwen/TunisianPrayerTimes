#!/usr/bin/env bash
set -euo pipefail
#
# Automated retrain pipeline for RunPod.
#
# Downloads new user contributions, merges manifests, and trains (or resumes).
# Designed to be run as a one-shot command or scheduled via cron.
#
# Usage:
#   bash scripts/retrain.sh                  # full pipeline
#   bash scripts/retrain.sh --skip-download  # skip contribution download
#   bash scripts/retrain.sh --dry-run        # show what would happen
#
# Environment (optional overrides):
#   MODEL_NAME       Whisper model (default: openai/whisper-small)
#   EPOCHS           Training epochs (default: 5)
#   LEARNING_RATE    Learning rate (default: 1e-7)
#   BATCH_SIZE       Per-device batch size (default: 4)
#   GRAD_ACCUM       Gradient accumulation steps (default: 4)
#   OUTPUT_DIR       Model output directory (default: models/whisper-qaloon)
#   UNFREEZE_LAYERS  Unfreeze last N encoder layers (default: 0 = fully frozen)
#   ENCODER_LR       Encoder learning rate (default: LEARNING_RATE / 10)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$ROOT_DIR/data"

# Defaults
MODEL_NAME="${MODEL_NAME:-openai/whisper-small}"
EPOCHS="${EPOCHS:-5}"
LEARNING_RATE="${LEARNING_RATE:-1e-7}"
BATCH_SIZE="${BATCH_SIZE:-4}"
GRAD_ACCUM="${GRAD_ACCUM:-4}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/models/whisper-qaloon}"
UNFREEZE_LAYERS="${UNFREEZE_LAYERS:-0}"
ENCODER_LR="${ENCODER_LR:-}"
SKIP_DOWNLOAD=false
DRY_RUN=false

# Parse flags
for arg in "$@"; do
    case $arg in
        --skip-download) SKIP_DOWNLOAD=true ;;
        --dry-run)       DRY_RUN=true ;;
        *) echo "Unknown flag: $arg"; exit 1 ;;
    esac
done

log() { echo -e "\n=== $1 ==="; }

# ── Step 0: Ensure dependencies are available (RunPod pods lose packages on restart)
if ! command -v ffmpeg &>/dev/null; then
    log "Step 0a: Installing ffmpeg"
    apt-get update -qq && apt-get install -y -qq ffmpeg 2>&1 | tail -1
fi

if ! python -c "import librosa, soundfile, jiwer, transformers" 2>/dev/null; then
    log "Step 0b: Installing Python dependencies"
    pip install -q librosa soundfile jiwer \
        'torch==2.6.0+cu124' 'torchaudio==2.6.0+cu124' 'torchvision==0.21.0+cu124' \
        --index-url https://download.pytorch.org/whl/cu124 2>&1 | tail -3
    pip install -q transformers'>=4.45,<5' datasets accelerate whisperx 2>&1 | tail -3
fi

# ── Step 1: Split long segments (>30s) ─────────────────────────────────────
log "Step 1/6: Splitting long segments"
SEGMENTS_DIR="$DATA_DIR/segments"
if [ -d "$SEGMENTS_DIR" ]; then
    if [ "$DRY_RUN" = true ]; then
        python "$SCRIPT_DIR/split_long_segments.py" --segments-dir "$SEGMENTS_DIR" --dry-run
    else
        python "$SCRIPT_DIR/split_long_segments.py" --segments-dir "$SEGMENTS_DIR"
    fi
else
    echo "  No segments directory found — skipping"
fi

# ── Step 2: Build manifests ────────────────────────────────────────────────
log "Step 2/6: Building training manifests"
if [ -d "$SEGMENTS_DIR" ]; then
    if [ "$DRY_RUN" = false ]; then
        python "$SCRIPT_DIR/build_manifest.py" --processed-dir "$SEGMENTS_DIR" --output-dir "$DATA_DIR"
    else
        echo "  (dry run — skipping manifest build)"
    fi
else
    echo "  No segments directory — skipping manifest build"
fi

# ── Step 3: Download new contributions ─────────────────────────────────────
if [ "$SKIP_DOWNLOAD" = false ]; then
    log "Step 3/6: Downloading user contributions"
    if [ "$DRY_RUN" = true ]; then
        python "$SCRIPT_DIR/download_contributions.py" --dry-run
    else
        python "$SCRIPT_DIR/download_contributions.py"
    fi
else
    log "Step 3/6: Skipping contribution download (--skip-download)"
fi

# ── Step 4: Merge manifests ────────────────────────────────────────────────
log "Step 4/6: Merging manifests"

TRAIN_MANIFEST="$DATA_DIR/manifest_train.jsonl"
CONTRIB_MANIFEST="$DATA_DIR/manifest_contributions.jsonl"
COMBINED_MANIFEST="$DATA_DIR/manifest_combined.jsonl"

if [ ! -f "$TRAIN_MANIFEST" ]; then
    echo "ERROR: $TRAIN_MANIFEST not found. Run build_manifest.py first."
    exit 1
fi

if [ -f "$CONTRIB_MANIFEST" ] && [ -s "$CONTRIB_MANIFEST" ]; then
    CONTRIB_COUNT=$(wc -l < "$CONTRIB_MANIFEST" | tr -d ' ')
    echo "  Contributions: $CONTRIB_COUNT samples"

    if [ "$DRY_RUN" = false ]; then
        cat "$TRAIN_MANIFEST" "$CONTRIB_MANIFEST" > "$COMBINED_MANIFEST"
    fi
else
    echo "  No contributions yet — using base training data only"
    if [ "$DRY_RUN" = false ]; then
        cp "$TRAIN_MANIFEST" "$COMBINED_MANIFEST"
    fi
fi

if [ "$DRY_RUN" = false ]; then
    TOTAL=$(wc -l < "$COMBINED_MANIFEST" | tr -d ' ')
    echo "  Combined manifest: $TOTAL samples → $COMBINED_MANIFEST"
fi

# ── Step 5: Find latest checkpoint (for resume) ───────────────────────────
log "Step 5/6: Checking for existing checkpoints"

RESUME_FLAG=""
if [ -d "$OUTPUT_DIR" ]; then
    LATEST_CKPT=$(find "$OUTPUT_DIR" -maxdepth 1 -name "checkpoint-*" -type d 2>/dev/null | sort -t- -k2 -n | tail -1 || true)
    if [ -n "$LATEST_CKPT" ]; then
        echo "  Resuming from: $LATEST_CKPT"
        RESUME_FLAG="--resume $LATEST_CKPT"
    else
        echo "  No checkpoint found — training from scratch"
    fi
else
    echo "  No previous training — starting fresh"
fi

# ── Step 6: Train ─────────────────────────────────────────────────────────
log "Step 6/6: Training"

TRAIN_CMD="python $SCRIPT_DIR/train_whisper.py \
    --manifest-dir $DATA_DIR \
    --output-dir $OUTPUT_DIR \
    --model $MODEL_NAME \
    --epochs $EPOCHS \
    --batch-size $BATCH_SIZE \
    --gradient-accumulation $GRAD_ACCUM \
    --learning-rate $LEARNING_RATE \
    --freeze-encoder \
    --fp16 \
    $RESUME_FLAG"

# Progressive encoder unfreezing
if [ "$UNFREEZE_LAYERS" -gt 0 ] 2>/dev/null; then
    TRAIN_CMD="$TRAIN_CMD --unfreeze-encoder-layers $UNFREEZE_LAYERS"
    if [ -n "$ENCODER_LR" ]; then
        TRAIN_CMD="$TRAIN_CMD --encoder-lr $ENCODER_LR"
    fi
fi

echo "  Command: $TRAIN_CMD"
echo ""
echo "  Model:         $MODEL_NAME"
echo "  Epochs:        $EPOCHS"
echo "  LR:            $LEARNING_RATE"
echo "  Batch size:    $BATCH_SIZE x $GRAD_ACCUM = $((BATCH_SIZE * GRAD_ACCUM)) effective"
echo "  Output:        $OUTPUT_DIR"
echo ""

if [ "$DRY_RUN" = true ]; then
    echo "(dry run — not training)"
    exit 0
fi

# Override manifest_train.jsonl symlink so train_whisper.py reads the combined file
# (train_whisper.py reads manifest_dir/manifest_train.jsonl)
BACKUP="$DATA_DIR/manifest_train_base.jsonl"
if [ ! -f "$BACKUP" ]; then
    cp "$TRAIN_MANIFEST" "$BACKUP"
fi
cp "$COMBINED_MANIFEST" "$TRAIN_MANIFEST"

# Restore original on exit (success or failure)
trap 'cp "$BACKUP" "$TRAIN_MANIFEST" 2>/dev/null; echo "Restored original manifest_train.jsonl"' EXIT

eval "$TRAIN_CMD"

log "Done"
echo "Model saved to: $OUTPUT_DIR/final"
echo ""
echo "Next steps:"
echo "  # Convert for Android deployment:"
echo "  ct2-whisper-converter --model $OUTPUT_DIR/final --output_dir $OUTPUT_DIR/ct2"
