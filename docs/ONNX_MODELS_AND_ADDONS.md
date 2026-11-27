# HolosReader — ONNX Models & Add-ons Guide

This guide covers all offline ONNX TTS models, optional neural add-ons, precision tradeoffs (INT8 vs. FP16 vs. FP32), background downloads, bundle integrity verification & repair, offline cache recipes, and optional developer self-export scripts.

---

## 1. Core Offline TTS Models (1-Tap In-App Download)

All core TTS models download in the background via `ModelDownloadService` (foreground notification transfer) using `VerifiedHttpDownloader` (strict validator / SHA-256 resumable transfers with crash-safe atomic promotion).

| Model ID | Display Name | Size | Sample Rate | Voices | Precision | Upstream Repository |
| --- | --- | --- | --- | --- | --- | --- |
| `holos_fp32` | **HolosTTS FP32 (Studio)** | ~760 MB | 24 kHz | 27 + custom cloned | FP32 | [patriotyk/HolosTTS](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx) |
| `supertonic_3` | **Supertonic 3 (44.1 kHz)** | ~398 MB | 44.1 kHz | 10 (`F1`–`F5`, `M1`–`M5`) | FP32 | [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3) |
| `holos_int8` | **HolosTTS INT8 (Fast)** | ~320 MB | 24 kHz | 27 + custom cloned | INT8 | [patriotyk/HolosTTS](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx-int8) |

### Hardware Auto-Recommendation (`DeviceCapabilityScanner.kt`)
On first launch and in **Settings → TTS**, `DeviceCapabilityScanner` evaluates CPU core count, max clock (with graceful SELinux fallback), total RAM, 64-bit ABI, and Vulkan/GLES availability, marking the best-fitting model with **`★ Recommended`**.

---

## 2. Optional ONNX Add-ons (All 1-Tap In-App Download)

Every optional add-on is directly downloadable in **Settings → TTS → Optional ONNX Add-ons** (no PC or Python required for users). All add-ons are managed through generation-counted `EngineLeasePool` leases (`HolosEngineHolder.kt`) so changing settings or repairing an add-on never closes a native ONNX session while active narration or offline rendering still holds a lease.

| Add-on | Size | Source | Key Behavior |
| --- | --- | --- | --- |
| **FlashSR 48 kHz Upsampler** (`model.onnx`) | ~487 KB | [YatharthS/FlashSR](https://huggingface.co/YatharthS/FlashSR) | Upsamples HolosTTS 24 kHz → 48 kHz with DC-bias removal, preserved original narration dynamics, Android 10 compatibility, and clean between-window cancellation. Not used for Supertonic 3 (already 44.1 kHz). |
| **Ukrainian Emotion Pacing** (`model_quantized.onnx` + `tokenizer.json`) | ~580 MB | [speed-brain-ai/speedbrain-emotion-uk-onnx](https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx) | 7-emotion XLM-RoBERTa classifier (`LiveEmotionPacing.kt`) running in background lookahead off the live synthesis hot path to dynamically modulate tempo and inter-sentence pauses. Recommended for 6+ GB RAM devices. |
| **Contextual Stress / Homographs** (`uk_stress_contextual.onnx` + `tokenizer.json`) | ~111 MB (INT8) / ~221 MB (FP16) | [`onnx-addons-v1`](https://github.com/smyhlin/HolosReader/releases/tag/onnx-addons-v1) | Sentence-level Ukrainian homograph disambiguator (*за́мок/замо́к*, *му́ка/мука́*) hooked into `UkrainianStressifier` (Tier 1.5). Recommends FP16 on 6+ GB RAM + NNAPI/AUTO devices, INT8 otherwise. |
| **On-Device Voice Cloner** (`voice_encoder.onnx`) | ~97 MB | [`onnx-addons-v1`](https://github.com/smyhlin/HolosReader/releases/tag/onnx-addons-v1) | Pre-exported ECAPA-TDNN speaker encoder with baked 24 kHz log-mel frontend (`VoiceEncoderOnnx.kt`). Converts any 5–15 s uncompressed 16-bit PCM `.wav` clip into a 256-dim HolosTTS voice vector on-device. |

### Pre-Built Release Assets (`onnx-addons-v1`, `--latest=false`)
Published on [`smyhlin/HolosReader` release `onnx-addons-v1`](https://github.com/smyhlin/HolosReader/releases/tag/onnx-addons-v1) (marked `make_latest=false` so in-app APK update checks ignore it):

| Asset | Size | SHA-256 |
| --- | --- | --- |
| `uk_stress_contextual_fp16.onnx` | 220.5 MB (231,203,310 B) | `b9f966e92d9176152940f24542038f86bd57921b930992412c7143e7b6a15e9e` |
| `uk_stress_contextual_int8.onnx` | 110.9 MB (116,250,425 B) | `722b7d72f79586694158b1f7141f8753e37c2eb922b98a88d47451d074b761f1` |
| `voice_encoder.onnx` | 97.4 MB (102,163,914 B) | `383d398916ac847152e1dd5a75548d29d3f3c7e519465f03923479a26d06b50b` |

---

## 3. Precision Tradeoffs: INT8 vs. FP16 vs. FP32

| Precision | Size | Best Runtime / EP | Quality & Latency Notes |
| --- | --- | --- | --- |
| **INT8** | ~1× baseline | CPU / XNNPACK | Fastest on ARM64 CPUs with lowest RAM pressure; ideal for 4 GB RAM devices and battery life. |
| **FP16** | ~2× INT8 | NNAPI (GPU/NPU) or modern ARM64 | Higher numerical precision than INT8 at half the footprint of FP32; recommended for Contextual Stress on 6+ GB RAM devices using NNAPI/AUTO. |
| **FP32** | ~4× INT8 | High-end ARM64 CPU / NNAPI | Reference studio quality (`HolosTTS FP32`); highest RAM and storage usage. |

> **Preserving Installed Precision:** When you run **Verify / Repair**, HolosReader preserves your currently installed Contextual Stress precision (INT8 or FP16) rather than forcing a switch to the hardware recommendation.

---

## 4. Integrity Verification, Selective Repair & Offline Cache Recipes

### Bundle Integrity & Selective Repair (`ModelIntegrityChecker.kt`)
- Each model and add-on directory maintains a pure-Kotlin `.verified.json` checksum manifest keyed by **bundle-relative paths** (with backward-compatible lookup for older nested records).
- **Normal readiness** uses fast metadata checks; **Settings → Model Integrity → Verify / Repair Models & Add-ons** runs deep SHA-256 + ONNX header validation off the UI thread, distinguishing `VERIFIED`, `LEGACY_UNVERIFIED`, `CORRUPT`, `MISSING`, and `VARIANT_MISMATCH`.
- For HolosTTS bundles, repair stages and validates a replacement `manifest.json` *before* rebuilding the list of weights to repair.
- Only missing, corrupt, or mismatched files are re-downloaded into `.part` staging files and promoted atomically — healthy files and active playback leases remain untouched.

### Strict Resumable Downloads (`VerifiedHttpDownloader.kt`)
- HTTP 206 resume is permitted **only** when either a strong saved validator (non-weak `ETag` or `Last-Modified`) matches the 206 response or a mandatory `expectedSha256` is supplied. Otherwise `.part` is discarded and a fresh HTTP 200 download is requested so two upstream revisions can never be mixed.

### Offline Chapter Audio Cache (`AudioCacheRecipe.kt` v4 & `OfflineStorageManager.kt`)
- Offline chapter WAVs are keyed by an immutable **v4 `AudioCacheRecipe`** captured from the **actually leased engine** (`engine.modelId`), active add-on revisions, FlashSR revision, all adaptive role voices and custom voice fingerprints, speed, Supertonic options, stress overrides, and EPUB parser version.
- Active playback and offline rendering hold live leases in `OfflineStorageManager`, ensuring stale-cache pruning and per-book cleanup never delete an in-use or freshly published chapter.

---

## 5. Advanced / Developer Self-Export Scripts

Ordinary users do **not** need these scripts because all pre-built add-ons are hosted in `onnx-addons-v1`. Developers who want to export custom checkpoints from source can use the bundled tools:

### Contextual Ukrainian Stress (`tools/export_stress_onnx.py`)
Exports [aloudreader/uk-stress-token-classifier](https://huggingface.co/aloudreader/uk-stress-token-classifier) (`xlm-roberta-base`, 5-class token classifier):

```bash
pip install torch transformers optimum onnx onnxruntime
# INT8 (~111 MB, best for CPU)
python3 tools/export_stress_onnx.py --quantize int8 --output ./uk_stress_contextual.onnx
# FP16 (~221 MB, best for GPU/NNAPI)
python3 tools/export_stress_onnx.py --quantize fp16 --output ./uk_stress_contextual.onnx
```

### On-Device Voice Cloner (`tools/export_voice_encoder_onnx.py`)
Exports a pre-traced TorchScript raw-waveform speaker encoder module (accepts `[1, N]` float32 audio at 24 kHz, outputs `[1, 256]` float32 embedding; intentionally exits with code 2 on raw `state_dict` checkpoints):

```bash
pip install torch onnx onnxruntime
python3 tools/export_voice_encoder_onnx.py --checkpoint voice_encoder_ts.pt --output ./voice_encoder.onnx
```

You can also import custom `.onnx` files directly on-device via **Settings → TTS → Optional ONNX Add-ons → Import `.onnx`**.
