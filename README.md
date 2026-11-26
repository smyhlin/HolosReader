# HolosReader

> **Language / Мова:** **English** | [Українська](README.uk.md)

[![Latest Release](https://img.shields.io/github/v/release/smyhlin/HolosReader?display_name=tag&sort=semver)](https://github.com/smyhlin/HolosReader/releases/latest)
[![Build APK](https://github.com/smyhlin/HolosReader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/smyhlin/HolosReader/actions/workflows/build-apk.yml)
[![Release APK](https://github.com/smyhlin/HolosReader/actions/workflows/release.yml/badge.svg)](https://github.com/smyhlin/HolosReader/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 10+](https://img.shields.io/badge/Android-10%2B%20(API%2029%E2%80%9335)-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)

**HolosReader** is a fast, fully offline Android EPUB 3 / EPUB 2 reader engineered for massive web novels and light novels (6,000+ chapters) with built-in neural Ukrainian text-to-speech powered by [HolosTTS](https://github.com/patriotyk/HolosTTS). Built purely on Android Framework APIs (`android.useAndroidX=false`) for minimal overhead and instant startup.

---

## Features

### Built for Massive Books (6,000+ Chapters)
- **One-time O(N) SQLite Indexing:** Streams EPUB archives once (`BookDatabase.kt`, `EpubStreamingParser.kt`) so opening a 6,500-chapter book takes milliseconds without re-parsing OPF manifests.
- **Virtualized Paragraph Rendering:** Uses ViewHolder recycling (`ListView`) so only visible paragraphs live in memory — zero jank even in long chapters.
- **Persistent `ZipFile` & LRU Image Cache:** Asynchronous image decoding with display-aware `inSampleSize` (`EpubImageCache.kt`) and full-screen tap-to-zoom illustration viewing.
- **Instant TOC Search:** SQLite `LIKE` filtering across thousands of chapters (`TocSheetController.kt`).

### Full EPUB 3 & EPUB 2 Support
- Inline and block formatting (`<b>`/`<strong>`, `<i>`/`<em>`, `<u>`, `<s>`/`<del>`, `<code>`, `<sup>`/`<sub>`, `<h1>`–`<h6>`, `<blockquote>`, `<ul>`/`<ol>`).
- Styled `<table>` rendering (ideal for LitRPG status windows), `<aside epub:type="footnote">`, `<figure>`/`<figcaption>`, `<img>`, and SVG `<image>`.
- Book cover extraction, bookmarks, reading statistics (`StatisticsActivity.kt`), and optional bionic reading (`BionicTextHelper.kt`).

### Distraction-Free Reader & Overlay Controls
- **Fullscreen by Default:** Hides the system status bar on launch (Android 10–15+), with a subtle chapter header at the top and a tap-to-hide book-wide page counter at the bottom.
- **Book-Wide Page Scrubber:** Scrub and track progress by global book pages (`current / total`) rather than chapters alone.
- **Compact Quick Panels & Overlay Settings Sheet:** Adjust brightness, switch across 9 themes, tweak font size, margins, and line spacing directly over the page, or pull up the 68%-height translucent settings sheet (`ReaderSettingsActivity.kt`).
- **Live Typeface Previews:** Every font option in the font picker renders in its own typeface.

### Offline Neural Ukrainian TTS & Media Controls
- **4-Stage Offline Ukrainian G2P (No Python):**
  1. Custom user stress dictionary (`stress_user.txt` via `StressEditorActivity.kt`)
  2. 2,886,186 word forms from [ukrainian-word-stress](https://github.com/patriotyk/ukrainian-word-stress) + contextual disambiguation for 36,414 homographs
  3. Embedded 1.17 MB ONNX neural accentor (`accentor-lite.onnx` from [egorsmkv/ukrainian-accentor](https://github.com/egorsmkv/ukrainian-accentor)) for out-of-vocabulary words and proper nouns
  4. Native 1:1 Kotlin port of [patriotyk/ipa-uk](https://github.com/patriotyk/ipa-uk)
- **Lyrics-Style Audio Sheet (`AudioSheetController.kt`):** Active paragraph card, dimmed surrounding context, live time scrubber, speed ruler, A-B loop, 14-preset in-sheet sleep timer (`Set Timeout`), offline chapter WAV caching, and voice catalog filtering (`All / Female / Male`) with instant preview.
- **Rich Lock-Screen & Shade Media Player (`ReaderAudioService.kt`):** Displays book cover art, live spoken snippet, chapter subtitle, seekable progress bar, and transport controls.
- **System TTS Integration:** Also registers as a standard Android TTS engine (`HolosTtsService.kt`).

### 3 Optional Offline ONNX TTS Models & Hardware Auto-Recommendation
On first launch (and anytime in **Settings → TTS** or **Menu → TTS Model**), `DeviceCapabilityScanner` scans CPU cores, max clock, RAM, 64-bit ABI, and Vulkan / OpenGL ES support, highlighting the best-fitting model with a **`★ Recommended`** badge:

| Model | Rating | Size | Sample Rate | Highlights |
| --- | --- | --- | --- | --- |
| [**HolosTTS FP32 (Studio)**](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx) | `★★★★★ 4.8/5` | ~760 MB | 24 kHz | Full-precision Ukrainian neural TTS · 27 voices · custom cloned voices · best for flagship 6+ GB RAM devices |
| [**Supertonic 3 (44.1 kHz)**](https://huggingface.co/Supertone/supertonic-3) | `★★★★★ 4.7/5` | ~398 MB | 44.1 kHz | Multilingual flow-matching TTS · 10 voices (`F1`–`F5`, `M1`–`M5`) · configurable quality steps (`2–12`) · **Experimental: English words pronunciation** (`<en>` span routing for embedded English terms) |
| [**HolosTTS INT8 (Fast)**](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx-int8) | `★★★★☆ 4.2/5` | ~320 MB | 24 kHz | Lightweight quantized Ukrainian TTS · 27 voices · custom cloned voices · best for battery life & entry-level CPUs |

### Optional ONNX Add-ons (Settings → TTS)
- **FlashSR 48 kHz Upsampler (~487 KB, one-tap download):** Neural super-resolution ([YatharthS/FlashSR](https://huggingface.co/YatharthS/FlashSR)) that lifts HolosTTS 24 kHz audio to crisp **48 kHz** on CPU.
- **Ukrainian Emotion Pacing (~580 MB, one-tap download):** 7-emotion Ukrainian classifier ([speed-brain-ai/speedbrain-emotion-uk-onnx](https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx)) that dynamically adjusts narration speed and inter-sentence pauses for anger, fear, joy, sadness, disgust, and surprise.
- **Contextual Stress / Homographs (~110 MB, local `.onnx` import):** Sentence-level homograph resolver (*за́мок/замо́к*, *му́ка/мука́*) hooked into `UkrainianStressifier` (Tier 1.5). Export from [aloudreader/uk-stress-token-classifier](https://huggingface.co/aloudreader/uk-stress-token-classifier) using `python3 tools/export_stress_onnx.py` and import in **Settings → TTS** (`tokenizer.json` downloads automatically).
- **On-Device Voice Cloner (~20 MB, local `.onnx` import):** Export `voice_encoder.pt` from [patriotyk/HolosTTS](https://huggingface.co/patriotyk/HolosTTS) using `python3 tools/export_voice_encoder_onnx.py`, import `voice_encoder.onnx` in **Settings → TTS**, and tap **Clone Voice from WAV Clip** to turn any 5–15 s WAV clip into a custom HolosTTS speaker vector.

### Caveats & Limitations
- **FlashSR 48 kHz** applies only to **HolosTTS INT8 / FP32** (24 kHz); **Supertonic 3** already outputs native 44.1 kHz audio.
- **Supertonic 3** uses its own 10 voice styles (`F1`–`F5`, `M1`–`M5`) and does not support HolosTTS 256-dim cloned `.bin` voices. Its **English Words Pronunciation** toggle is experimental and keeps Roman numerals (`II`, `IV`, `X`) in Ukrainian.
- **Ukrainian Emotion Pacing** loads an ~580 MB INT8 XLM-RoBERTa graph and is recommended for devices with **6+ GB RAM**.
- **Contextual Stress & Voice Encoder** require a one-time PC export (`tools/export_stress_onnx.py` / `tools/export_voice_encoder_onnx.py`) because upstream Hugging Face repos publish PyTorch weights rather than pre-exported `.onnx` binaries.
- **On-Device WAV Voice Cloner** requires **uncompressed 16-bit PCM `.wav`** (mono or stereo, 1–15 s; MP3/OGG/M4A are rejected).
- **Hardware Scanner on restricted Android kernels:** If SELinux blocks `/sys/devices/system/cpu/*/cpufreq/cpuinfo_max_freq`, `DeviceCapabilityScanner` gracefully falls back to CPU core count, total RAM, 64-bit ABI, and Vulkan GPU presence.

### Zero-Login In-App Updates
- Checks [GitHub Releases](https://github.com/smyhlin/HolosReader/releases/latest) automatically on launch and on demand via **About → Check for updates** (`GitHubUpdater.kt`, `ApkFileProvider.kt`) — no GitHub account required.

---

## Installation

1. Download the latest `HolosReader-v*.apk` from [**Releases**](https://github.com/smyhlin/HolosReader/releases/latest).
2. Install the APK on any Android 10+ (API 29+) device.
3. Subsequent updates can be installed directly inside the app via **Check for updates**.

---

## Building from Source

### Requirements
- **JDK 17** (Temurin / OpenJDK)
- **Android SDK 35** (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 29`)
- **Gradle 8.11.1** (bundled via `./gradlew`)

### Local Build
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
The compiled APK is written to:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI/CD
- **[`release.yml`](.github/workflows/release.yml):** Automatically bumps `versionName` (`5.1.<run_number>`) and `versionCode` on every push to `main`, builds the APK, and publishes it to GitHub Releases.
- **[`build-apk.yml`](.github/workflows/build-apk.yml):** Builds and uploads the `HolosReader-EPUB3-HolosTTS-apk` workflow artifact.

---

## Project Structure

| Module / Package | Responsibility |
| --- | --- |
| `holos-onnx` | ONNX Runtime engines (`HolosOnnx`, `SupertonicOnnx`, `FlashSrOnnx`, `UkrainianEmotionOnnx`, `ContextualStressOnnx`, `VoiceEncoderOnnx`, `UnigramTokenizer`) |
| `com.holos.reader.epub` | Streaming EPUB parser, SQLite chapter/spine index, LRU image cache |
| `com.holos.reader.g2p` | Offline Ukrainian text normalizer, stress dictionary, ONNX accentor, IPA converter |
| `com.holos.reader.tts` | Prefetching synthesis controller, foreground `MediaSession` service, preferences, voice catalog |
| `com.holos.reader.ui` | Library, fullscreen reader, overlay settings sheet, lyrics audio sheet, pill slider, TOC & stats |
| `com.holos.reader.update` | Zero-login GitHub Releases updater and framework `ContentProvider` APK installer |

For architectural rules, CI pitfalls, and multi-environment sync workflows, see [`AGENTS.md`](AGENTS.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## Credits

- [patriotyk/HolosTTS](https://github.com/patriotyk/HolosTTS) — Ukrainian neural TTS architecture & models
- [patriotyk/ukrainian-word-stress](https://github.com/patriotyk/ukrainian-word-stress) — Ukrainian stress dictionary
- [egorsmkv/ukrainian-accentor](https://github.com/egorsmkv/ukrainian-accentor) — Neural Ukrainian accentor
- [patriotyk/ipa-uk](https://github.com/patriotyk/ipa-uk) — Ukrainian IPA phonemizer
- [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3) — 44.1 kHz multilingual flow-matching ONNX TTS
- [YatharthS/FlashSR](https://huggingface.co/YatharthS/FlashSR) — 487 KB neural 48 kHz audio upsampler
- [speed-brain-ai/speedbrain-emotion-uk-onnx](https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx) — Ukrainian 7-emotion ONNX classifier
- [aloudreader/uk-stress-token-classifier](https://huggingface.co/aloudreader/uk-stress-token-classifier) — Contextual Ukrainian homograph stress model

---

## License

Released under the [**MIT License**](LICENSE) — free to use, modify, and distribute for personal and commercial purposes.
