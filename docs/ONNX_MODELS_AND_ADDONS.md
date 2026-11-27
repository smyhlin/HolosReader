# HolosReader — ONNX Models & Add-ons Guide

Complete developer and power-user reference for every offline ONNX TTS model and optional add-on in **HolosReader**: exact upstream sources, licenses, precision tradeoffs (INT8 vs FP16 vs FP32), export commands, hosting guidelines, and Android Runtime caveats.

---

## 1. Core Offline TTS Models (1-Tap In-App Download)

All three models download automatically inside HolosReader (first-launch selector, **Menu → TTS Model**, or **Settings → TTS**) with HTTP Range resume (`206 Partial Content` + `Content-Range` verification).

| Model ID | Upstream Source | Precision & Size | Output Rate | Voices | Custom Cloned Voices | Best Hardware |
| :--- | :--- | :--- | :--- | :--- | :---: | :--- |
| `holos_fp32` | [`patriotyk/HolosTTS`](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx) | FP32 (~760 MB) | 24 kHz | 27 Ukrainian | Yes | Flagship 64-bit, 6+ GB RAM |
| `supertonic_3` | [`Supertone/supertonic-3`](https://huggingface.co/Supertone/supertonic-3) | FP32/ONNX (~398 MB) | 44.1 kHz | 10 (`F1`–`F5`, `M1`–`M5`) | No | Mid-range & flagship 64-bit |
| `holos_int8` | [`patriotyk/HolosTTS`](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx-int8) | INT8 (~320 MB) | 24 kHz | 27 Ukrainian | Yes | Entry/older CPUs & battery life |

### Hardware Auto-Recommendation (`DeviceCapabilityScanner.kt`)
- Scans CPU core count, max clock (`/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`), total RAM, 64-bit ABI, and Vulkan / OpenGL ES support.
- Marks the best-fitting model with **`★ Recommended`**: `HIGH` → `holos_fp32`, `MEDIUM` → `supertonic_3`, `ENTRY` → `holos_int8`.
- If SELinux restricts `cpufreq` sysfs reads (`maxCpuMhz == 0`), falls back cleanly to core count, RAM, 64-bit ABI, and Vulkan GPU presence.

---

## 2. Optional ONNX Add-ons (Settings → TTS → Optional ONNX Add-ons)

### A. Ready for 1-Tap In-App Download

| Add-on | Upstream Source | Files Downloaded | Size | What It Does |
| :--- | :--- | :--- | :--- | :--- |
| **FlashSR 48 kHz Upsampler** | [`YatharthS/FlashSR`](https://huggingface.co/YatharthS/FlashSR) | `onnx/model.onnx` | ~487 KB | Neural audio super-resolution (`audio_values` 16 kHz → `reconstruction` 48 kHz). Applies to **HolosTTS INT8 / FP32** only (Supertonic 3 is already 44.1 kHz). |
| **Ukrainian Emotion Pacing** | [`speed-brain-ai/speedbrain-emotion-uk-onnx`](https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx) | `model_quantized.onnx`, `tokenizer.json` | ~580 MB | Multi-label sigmoid 7-emotion Ukrainian classifier (`Joy`, `Fear`, `Anger`, `Sadness`, `Disgust`, `Surprise`, `None`). Dynamically adjusts speed & inter-sentence pauses. Best on **6+ GB RAM** devices. |

### B. Local `.onnx` Export & Import (Until Hosted on a Release Asset)

Upstream Hugging Face repos for these two add-ons publish PyTorch checkpoints rather than pre-exported `.onnx` files.

#### 1. Contextual Ukrainian Stress / Homographs (`uk_stress_contextual.onnx`)
- **Upstream:** [`aloudreader/uk-stress-token-classifier`](https://huggingface.co/aloudreader/uk-stress-token-classifier) (`model.safetensors` + `head.pt`, trained on 100 modern Ukrainian audiobooks).
- **What it fixes:** Sentence-level homographs (*за́мок* vs *замо́к*, *му́ка* vs *мука́*, *доро́га* vs *дорога́*, *пла́чу* vs *плачу́*).
- **Export command:**
  ```bash
  pip install torch transformers huggingface_hub onnx onnxruntime onnxconverter-common

  # Option 1: INT8 (~110 MB) — smallest & fastest on CPU / XNNPACK
  python3 tools/export_stress_onnx.py --precision int8 --out uk_stress_contextual.onnx

  # Option 2: FP16 (~220 MB) — best for Android GPU / NPU via NNAPI
  python3 tools/export_stress_onnx.py --precision fp16 --out uk_stress_contextual.onnx

  # Option 3: FP32 (~440 MB) — maximum numerical precision (high RAM)
  python3 tools/export_stress_onnx.py --precision fp32 --out uk_stress_contextual.onnx
  ```
- **How to install on phone:** Copy `uk_stress_contextual.onnx` to phone storage or Google Drive → open **Settings → TTS → Contextual Stress (Homographs) → Import .onnx**. HolosReader automatically downloads the matching `tokenizer.json` from Hugging Face.
- **Accelerator behavior:** `HolosEngineHolder` applies your **Settings → TTS → ONNX Accelerator** preference (`AUTO`: NNAPI → XNNPACK → CPU) and **CPU Threads** to `ContextualStressOnnx`, `UkrainianEmotionOnnx`, and `FlashSrOnnx`.

#### 2. On-Device WAV Voice Cloner (`voice_encoder.onnx`)
- **Upstream:** [`patriotyk/HolosTTS`](https://huggingface.co/patriotyk/HolosTTS) (`voice_encoder.pt`).
- **Important Upstream Contract Caveat (H27):** `patriotyk/HolosTTS`'s `voice_encoder.pt` is a PyTorch `state_dict` for two log-mel `StyleEncoder` networks, **not** a raw-waveform TorchScript graph. `tools/export_voice_encoder_onnx.py` intentionally fails closed on raw `state_dict` checkpoints rather than emitting an unqualified `.onnx` graph.
- **Audio input requirements:** Uncompressed **16-bit PCM `.wav`** (mono or stereo, 1–15 s, max 16 MB). Produces a 256-float little-endian `.bin` vector in `files/custom_voices/` (excluded from cloud backup and device transfer).

---

## 3. Hosting Custom Exported `.onnx` Files for 1-Tap In-App Download

Do **not** commit `.onnx` binaries directly into git history (GitHub rejects files > 100 MB and bloats clones). Instead, host them on a dedicated **non-latest GitHub Release** (up to **2 GB per asset**, free CDN, full HTTP Range resume) or a Hugging Face repo:

```bash
# 1. Create a dedicated asset-only release (make_latest=false so GitHubUpdater APK checks ignore it!)
gh release create onnx-addons-v1 \
  --title "HolosReader ONNX Add-ons v1" \
  --notes "Pre-exported ONNX add-ons for HolosReader 1-tap download." \
  --latest=false \
  uk_stress_contextual.onnx

# 2. Direct download URL format:
# https://github.com/smyhlin/HolosReader/releases/download/onnx-addons-v1/uk_stress_contextual.onnx
```

Once uploaded, wire the direct HTTPS URL into `OnnxAddonManager.kt` (`CONTEXTUAL_STRESS_ONNX_URL`) and flip `directDownloadAvailable = true` in `OnnxAddonSpec.kt` to turn **Import .onnx** into a 1-tap **Download** button inside the app.
