#!/usr/bin/env python3
from __future__ import annotations
"""
Fine-tune OpenAI Whisper on Qaloon Quran recitation data.

Uses on-the-fly feature computation to avoid filling disk with cached features.

Usage:
    python train_whisper.py --manifest-dir data/ --output-dir models/whisper-qaloon
    python train_whisper.py --manifest-dir data/ --model openai/whisper-small --epochs 10
    python train_whisper.py --resume models/whisper-qaloon/checkpoint-500
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from torch.utils.data import Dataset as TorchDataset
from dataclasses import dataclass
from typing import Any

from transformers import (
    WhisperForConditionalGeneration,
    WhisperProcessor,
    Seq2SeqTrainer,
    Seq2SeqTrainingArguments,
)

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"

MAX_AUDIO_SEC = 30
MAX_LABEL_TOKENS = 448


class NaNSafeTrainer(Seq2SeqTrainer):
    """Trainer that detects NaN in loss/gradients and skips those steps.
    Supports discriminative learning rates for encoder vs decoder."""

    def __init__(self, *args, encoder_lr: float | None = None, **kwargs):
        super().__init__(*args, **kwargs)
        self._nan_count = 0
        self._encoder_lr = encoder_lr

    def create_optimizer(self):
        if self._encoder_lr is not None and self.optimizer is None:
            model = self.model
            decay_params = set()
            for mn, m in model.named_modules():
                if isinstance(m, torch.nn.LayerNorm):
                    for pn in ["weight", "bias"]:
                        decay_params.add(f"{mn}.{pn}" if mn else pn)

            encoder_trainable = [
                (n, p) for n, p in model.named_parameters()
                if p.requires_grad and n.startswith("model.encoder.")
            ]
            decoder_trainable = [
                (n, p) for n, p in model.named_parameters()
                if p.requires_grad and not n.startswith("model.encoder.")
            ]

            optimizer_grouped = []
            if encoder_trainable:
                optimizer_grouped.extend([
                    {"params": [p for n, p in encoder_trainable if n not in decay_params],
                     "lr": self._encoder_lr, "weight_decay": self.args.weight_decay},
                    {"params": [p for n, p in encoder_trainable if n in decay_params],
                     "lr": self._encoder_lr, "weight_decay": 0.0},
                ])
            if decoder_trainable:
                optimizer_grouped.extend([
                    {"params": [p for n, p in decoder_trainable if n not in decay_params],
                     "lr": self.args.learning_rate, "weight_decay": self.args.weight_decay},
                    {"params": [p for n, p in decoder_trainable if n in decay_params],
                     "lr": self.args.learning_rate, "weight_decay": 0.0},
                ])
            # Filter out empty groups
            optimizer_grouped = [g for g in optimizer_grouped if g["params"]]

            self.optimizer = torch.optim.AdamW(
                optimizer_grouped,
                betas=(self.args.adam_beta1, self.args.adam_beta2),
                eps=self.args.adam_epsilon,
            )
        else:
            super().create_optimizer()

    def training_step(self, model, inputs, num_items_in_batch=None):
        loss = super().training_step(model, inputs, num_items_in_batch)

        # Check for NaN/Inf loss
        if torch.isnan(loss) or torch.isinf(loss):
            self._nan_count += 1
            print(f"\n⚠ NaN/Inf loss detected (total: {self._nan_count}), zeroing gradients")
            model.zero_grad(set_to_none=True)
            return torch.tensor(0.0, device=loss.device, requires_grad=True)

        # Lightweight NaN gradient check: sample a few large parameter tensors
        # instead of iterating all parameters (avoids OOM on MPS)
        try:
            grad_sum = torch.zeros(1, device=loss.device)
            checked = 0
            for p in model.parameters():
                if p.grad is not None and p.numel() > 10000:
                    grad_sum += p.grad.sum()
                    checked += 1
                    if checked >= 5:
                        break
            if torch.isnan(grad_sum) or torch.isinf(grad_sum):
                self._nan_count += 1
                print(f"\n⚠ NaN gradient detected (total: {self._nan_count}), zeroing gradients")
                model.zero_grad(set_to_none=True)
                del grad_sum
                return torch.tensor(0.0, device=loss.device, requires_grad=True)
            del grad_sum
        except RuntimeError:
            # If even the check OOMs, just skip it and continue training
            pass

        return loss


class QaloonDataset(TorchDataset):
    """On-the-fly feature extraction dataset for Whisper training."""

    def __init__(self, samples: list[dict], processor: WhisperProcessor):
        self.processor = processor
        # Pre-filter: skip samples that are too long (audio or text)
        # Truncating audio without adjusting text creates a mismatch that
        # teaches the model to hallucinate — so we drop them instead.
        self.samples = []
        skipped_duration = 0
        skipped_tokens = 0
        for s in samples:
            if s.get("duration", 0) > MAX_AUDIO_SEC:
                skipped_duration += 1
                continue
            toks = processor.tokenizer(s["text"]).input_ids
            if len(toks) <= MAX_LABEL_TOKENS:
                self.samples.append(s)
            else:
                skipped_tokens += 1
        if skipped_duration or skipped_tokens:
            print(f"  Filtered: {skipped_duration} samples > {MAX_AUDIO_SEC}s, "
                  f"{skipped_tokens} samples > {MAX_LABEL_TOKENS} tokens "
                  f"({len(self.samples)} kept)")

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        sample = self.samples[idx]

        # Load audio (should be ≤ MAX_AUDIO_SEC after filtering)
        audio, sr = sf.read(sample["audio_path"])

        # Compute mel spectrogram
        input_features = self.processor.feature_extractor(
            audio, sampling_rate=sr, return_tensors="np"
        ).input_features[0]

        # Tokenize text
        labels = self.processor.tokenizer(sample["text"]).input_ids

        return {
            "input_features": input_features,
            "labels": labels,
        }


@dataclass
class DataCollatorSpeechSeq2SeqWithPadding:
    processor: Any
    decoder_start_token_id: int

    def __call__(self, features: list[dict[str, Any]]) -> dict[str, Any]:
        input_features = [{"input_features": f["input_features"]} for f in features]
        batch = self.processor.feature_extractor.pad(input_features, return_tensors="pt")

        label_features = [{"input_ids": f["labels"]} for f in features]
        labels_batch = self.processor.tokenizer.pad(label_features, return_tensors="pt")

        labels = labels_batch["input_ids"].masked_fill(
            labels_batch.attention_mask.ne(1), -100
        )
        if (labels[:, 0] == self.decoder_start_token_id).all().cpu().item():
            labels = labels[:, 1:]

        batch["labels"] = labels
        return batch


def compute_metrics(pred, tokenizer):
    from jiwer import wer as compute_wer

    pred_ids = pred.predictions
    label_ids = pred.label_ids
    label_ids[label_ids == -100] = tokenizer.pad_token_id

    pred_str = tokenizer.batch_decode(pred_ids, skip_special_tokens=True)
    label_str = tokenizer.batch_decode(label_ids, skip_special_tokens=True)

    pairs = [(p, l) for p, l in zip(pred_str, label_str) if l.strip()]
    if not pairs:
        return {"wer": 1.0}
    pred_str, label_str = zip(*pairs)
    return {"wer": round(compute_wer(list(label_str), list(pred_str)), 4)}


def load_manifest(manifest_path: Path) -> list[dict]:
    samples = []
    with open(manifest_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                sample = json.loads(line)
                audio_path = DATA_DIR / sample["audio"]
                if audio_path.exists():
                    sample["audio_path"] = str(audio_path)
                    samples.append(sample)
    return samples


def main():
    parser = argparse.ArgumentParser(description="Fine-tune Whisper on Qaloon data")
    parser.add_argument("--manifest-dir", type=str, default=str(DATA_DIR))
    parser.add_argument("--output-dir", type=str, default=str(SCRIPT_DIR.parent / "models" / "whisper-qaloon"))
    parser.add_argument("--model", type=str, default="openai/whisper-small")
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--gradient-accumulation", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=5e-6)
    parser.add_argument("--warmup-steps", type=int, default=200)
    parser.add_argument("--warmup-ratio", type=float, default=0.1)
    parser.add_argument("--lr-scheduler", type=str, default="cosine")
    parser.add_argument("--resume", type=str, default=None)
    parser.add_argument("--freeze-encoder", action="store_true", default=True,
                        help="Freeze encoder, only train decoder (recommended)")
    parser.add_argument("--no-freeze-encoder", dest="freeze_encoder", action="store_false")
    parser.add_argument("--unfreeze-encoder-layers", type=int, default=0,
                        help="Unfreeze last N encoder layers (overrides --freeze-encoder for those layers)")
    parser.add_argument("--encoder-lr", type=float, default=None,
                        help="Learning rate for unfrozen encoder layers (default: main_lr / 10)")
    parser.add_argument("--fp16", action="store_true")
    args = parser.parse_args()

    manifest_dir = Path(args.manifest_dir)
    output_dir = Path(args.output_dir)

    train_manifest = manifest_dir / "manifest_train.jsonl"
    val_manifest = manifest_dir / "manifest_val.jsonl"

    if not train_manifest.exists():
        print(f"ERROR: {train_manifest} not found")
        sys.exit(1)

    print("Loading manifests...")
    train_samples = load_manifest(train_manifest)
    val_samples = load_manifest(val_manifest) if val_manifest.exists() else []
    print(f"  Train: {len(train_samples)} samples")
    print(f"  Val: {len(val_samples)} samples")

    if len(train_samples) < 10:
        print("ERROR: Need at least 10 training samples.")
        sys.exit(1)

    model_name = args.resume or args.model
    print(f"\nLoading model: {model_name}")

    processor = WhisperProcessor.from_pretrained(args.model)
    model = WhisperForConditionalGeneration.from_pretrained(model_name)
    model = model.float()
    model.config.use_cache = False

    model.generation_config.forced_decoder_ids = processor.get_decoder_prompt_ids(
        language="ar", task="transcribe"
    )
    model.generation_config.suppress_tokens = []

    # Freeze encoder — Whisper already understands Arabic audio;
    # we only need to adapt the decoder for Qaloon-specific text output.
    encoder_lr = None
    if args.freeze_encoder:
        for param in model.model.encoder.parameters():
            param.requires_grad = False

        # Progressive unfreezing: unfreeze last N encoder layers with lower LR
        if args.unfreeze_encoder_layers > 0:
            encoder_layers = model.model.encoder.layers
            n_layers = len(encoder_layers)
            n_unfreeze = min(args.unfreeze_encoder_layers, n_layers)
            for layer in encoder_layers[n_layers - n_unfreeze:]:
                for param in layer.parameters():
                    param.requires_grad = True
            # Also unfreeze encoder layer_norm (final layer norm)
            for param in model.model.encoder.layer_norm.parameters():
                param.requires_grad = True
            encoder_lr = args.encoder_lr or (args.learning_rate / 10)
            print(f"  Unfrozen last {n_unfreeze}/{n_layers} encoder layers (encoder_lr={encoder_lr:.1e})")

        trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
        total = sum(p.numel() for p in model.parameters())
        print(f"  Encoder {'partially ' if args.unfreeze_encoder_layers > 0 else ''}frozen: "
              f"{trainable:,}/{total:,} params trainable ({100*trainable/total:.1f}%)")

    print("Building datasets (on-the-fly processing)...")
    train_ds = QaloonDataset(train_samples, processor)
    val_ds = QaloonDataset(val_samples, processor) if val_samples else None
    print(f"  Train (after filter): {len(train_ds)} samples")
    if val_ds:
        print(f"  Val (after filter): {len(val_ds)} samples")

    data_collator = DataCollatorSpeechSeq2SeqWithPadding(
        processor=processor,
        decoder_start_token_id=model.config.decoder_start_token_id,
    )

    use_fp16 = args.fp16 and torch.cuda.is_available()

    training_args = Seq2SeqTrainingArguments(
        output_dir=str(output_dir),
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.gradient_accumulation,
        learning_rate=args.learning_rate,
        warmup_ratio=args.warmup_ratio,
        lr_scheduler_type=args.lr_scheduler,
        max_grad_norm=1.0,
        num_train_epochs=args.epochs,
        fp16=use_fp16,
        eval_strategy="steps" if val_ds else "no",
        eval_steps=1500 if val_ds else None,
        save_strategy="steps",
        save_steps=1500,
        save_total_limit=3,
        logging_steps=25,
        load_best_model_at_end=True if val_ds else False,
        metric_for_best_model="wer" if val_ds else None,
        greater_is_better=False,
        predict_with_generate=True,
        generation_max_length=225,
        report_to="none",
        push_to_hub=False,
        dataloader_num_workers=4,
        dataloader_pin_memory=True,
        remove_unused_columns=False,
    )

    tokenizer = processor.tokenizer
    trainer = NaNSafeTrainer(
        args=training_args,
        model=model,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        data_collator=data_collator,
        compute_metrics=lambda pred: compute_metrics(pred, tokenizer),
        processing_class=processor,
        encoder_lr=encoder_lr,
    )

    print(f"\nStarting training...")
    print(f"  Model: {args.model}")
    print(f"  Epochs: {args.epochs}")
    print(f"  Batch size: {args.batch_size} (effective: {args.batch_size * args.gradient_accumulation})")
    print(f"  Learning rate: {args.learning_rate}")
    print(f"  Output: {output_dir}")
    print()

    if args.resume:
        trainer.train(resume_from_checkpoint=args.resume)
    else:
        trainer.train()

    # Save final model
    final_dir = output_dir / "final"
    trainer.save_model(str(final_dir))
    processor.save_pretrained(str(final_dir))

    print(f"\nTraining complete! Model saved to: {final_dir}")
    print("\nTo convert for Android deployment:")
    print("  pip install ctranslate2")
    print(f"  ct2-whisper-converter --model {final_dir} --output_dir {output_dir}/ct2")


if __name__ == "__main__":
    main()
