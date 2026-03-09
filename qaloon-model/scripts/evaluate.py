#!/usr/bin/env python3
from __future__ import annotations
"""
Run inference on trained Whisper-Qaloon model to evaluate quality.

Usage:
    python evaluate.py --model models/whisper-qaloon/final --audio data/segments/ali_alhuthaifi/001/001001.wav
    python evaluate.py --model models/whisper-qaloon/final --manifest data/manifest_test.jsonl --samples 20
"""

import argparse
import json
import random
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from transformers import WhisperForConditionalGeneration, WhisperProcessor

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"


def transcribe(model, processor, audio_path: str, device: str) -> str:
    """Transcribe a single audio file."""
    audio, sr = sf.read(audio_path)

    # Truncate to 30 seconds if needed
    if len(audio) > sr * 30:
        audio = audio[:sr * 30]

    input_features = processor.feature_extractor(
        audio, sampling_rate=sr, return_tensors="pt"
    ).input_features.to(device)

    with torch.no_grad():
        predicted_ids = model.generate(input_features)

    transcription = processor.batch_decode(predicted_ids, skip_special_tokens=True)[0]
    return transcription


def compute_wer(reference: str, hypothesis: str) -> float:
    """Compute word error rate between reference and hypothesis."""
    from jiwer import wer
    if not reference.strip():
        return 1.0 if hypothesis.strip() else 0.0
    return wer(reference, hypothesis)


def main():
    parser = argparse.ArgumentParser(description="Evaluate Whisper-Qaloon model")
    parser.add_argument(
        "--model", type=str,
        default=str(SCRIPT_DIR.parent / "models" / "whisper-qaloon" / "final"),
        help="Path to trained model directory",
    )
    parser.add_argument("--audio", type=str, help="Single audio file to transcribe")
    parser.add_argument("--manifest", type=str, help="JSONL manifest for batch evaluation")
    parser.add_argument("--samples", type=int, default=20, help="Number of samples to evaluate")
    parser.add_argument("--compare-base", action="store_true", help="Also run base whisper-small for comparison")
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")

    # Load model
    print(f"Loading model: {args.model}")
    processor = WhisperProcessor.from_pretrained(args.model)
    model = WhisperForConditionalGeneration.from_pretrained(args.model).float().to(device)
    model.eval()

    if args.audio:
        # Single file transcription
        result = transcribe(model, processor, args.audio, device)
        print(f"\nTranscription: {result}")
        return

    # Batch evaluation from manifest
    manifest_path = Path(args.manifest) if args.manifest else DATA_DIR / "manifest_test.jsonl"
    if not manifest_path.exists():
        print(f"ERROR: {manifest_path} not found")
        sys.exit(1)

    samples = []
    with open(manifest_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                s = json.loads(line)
                audio_path = DATA_DIR / s["audio"]
                if audio_path.exists():
                    s["audio_path"] = str(audio_path)
                    samples.append(s)

    random.seed(42)
    if len(samples) > args.samples:
        samples = random.sample(samples, args.samples)

    print(f"\nEvaluating {len(samples)} samples...\n")

    # Optionally load base model for comparison
    base_model = None
    if args.compare_base:
        print("Loading base model: openai/whisper-small")
        base_processor = WhisperProcessor.from_pretrained("openai/whisper-small")
        base_model = WhisperForConditionalGeneration.from_pretrained("openai/whisper-small").float().to(device)
        base_model.eval()

    wers = []
    base_wers = []

    for i, sample in enumerate(samples):
        ref_text = sample["text"]
        hyp = transcribe(model, processor, sample["audio_path"], device)
        w = compute_wer(ref_text, hyp)
        wers.append(w)

        surah = sample.get("surah", "?")
        ayah = sample.get("ayah", "?")
        reciter = sample.get("reciter", "?")

        print(f"[{i+1}/{len(samples)}] {reciter} {surah}:{ayah}")
        print(f"  REF: {ref_text[:80]}...")
        print(f"  HYP: {hyp[:80]}...")
        print(f"  WER: {w:.2%}")

        if base_model:
            base_hyp = transcribe(base_model, base_processor, sample["audio_path"], device)
            bw = compute_wer(ref_text, base_hyp)
            base_wers.append(bw)
            print(f"  BASE: {base_hyp[:80]}...")
            print(f"  BASE WER: {bw:.2%}")
        print()

    avg_wer = np.mean(wers)
    print(f"{'='*60}")
    print(f"  Fine-tuned WER: {avg_wer:.2%} (avg over {len(samples)} samples)")
    if base_wers:
        avg_base = np.mean(base_wers)
        print(f"  Base model WER: {avg_base:.2%}")
        print(f"  Improvement: {avg_base - avg_wer:.2%} absolute")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
