# HolosReader

> **Language / Мова:** **English** | [Українська](README.uk.md)

[![Latest Release](https://img.shields.io/github/v/release/smyhlin/HolosReader?display_name=tag&sort=semver)](https://github.com/smyhlin/HolosReader/releases/latest)
[![Build APK](https://github.com/smyhlin/HolosReader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/smyhlin/HolosReader/actions/workflows/build-apk.yml)
[![Release APK](https://img.shields.io/github/v/release/smyhlin/HolosReader?label=Release%20APK&color=2ea44f)](https://github.com/smyhlin/HolosReader/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/smyhlin/HolosReader/releases/latest)

**HolosReader** is a fast, distraction-free Android EPUB reader that turns your favorite books, web novels, and 6,000-chapter light novels into **lifelike Ukrainian audiobooks — 100% offline**.

No accounts, no subscriptions, no cloud tracking, and no robotic system voices. Download your voice model once, import your EPUB files, and read or listen anywhere — even in airplane mode.

[Quick Start](#quick-start) · [What Makes It Great](#what-makes-it-great) · [Voices & Add-ons](#voices--optional-add-ons) · [User Guide](docs/USER_GUIDE.md) · [FAQ](docs/FAQ.md) · [For Developers](#for-developers)

---

## Quick Start

1. **Install the app** — Download the latest `HolosReader-v*.apk` from [**GitHub Releases**](https://github.com/smyhlin/HolosReader/releases/latest) and install it on any Android 10+ phone or tablet.
2. **Download a voice** — On first launch, HolosReader checks your device's hardware and marks the best-fitting voice model with a **`★ Recommended`** badge. Tap **Download** — transfers run in the notification shade so you can keep using your phone.
3. **Import & enjoy** — Tap **`+`** in the Library to import a single EPUB or an entire folder of books, open a chapter, and tap **Play**.

> **Privacy first:** After downloading your chosen voice model, HolosReader works completely offline. Your books, bookmarks, reading stats, and custom voices never leave your device.

---

## What Makes It Great

### Built for Massive Books (6,000+ Chapters)
- **Opens in a blink:** Even giant multi-thousand-chapter web novels open almost instantly after a one-time import.
- **Butter-smooth reading:** Virtualized scrolling keeps long chapters fluid and battery-friendly, with tap-to-zoom illustrations, styled tables (great for LitRPG status windows), footnotes, and rich EPUB 3 / EPUB 2 formatting.
- **Effortless navigation:** Pull up at the end of a chapter to jump smoothly to the next one, scrub across the entire book by page count with **Undo / Redo**, or search thousands of chapters instantly in the **Table of Contents** (with quick **Clear** and **Current Chapter** buttons).
- **Comfortable day or night:** Fullscreen reading by default, **9 color themes** (light, sepia, dark, AMOLED, and more), live typeface previews, brightness & typography quick panels, and optional **Bionic Reading** mode.

### Natural Offline Ukrainian Audiobooks
- **Accurate Ukrainian pronunciation out of the box:** Built-in offline stress dictionary (2.88M+ word forms), neural fallback for rare/fantasy names, natural number/percentage/date reading, and a **Custom Stress Editor** so you can teach HolosReader how to pronounce any character name or term.
- **Multi-Voice Adaptive Narration:** Assign different voices to the **Narrator**, **Dialogue**, and **`[System]` / status boxes** — you can even mix voices across different installed TTS models!
- **Browse freely while listening:** Scroll ahead or look back at an earlier scene without interrupting narration, then tap to jump right back to the live spoken paragraph.
- **Gentle, polished playback:** Smooth fade-in and fade-out edges eliminate harsh clicks on play, pause, or seek; lyrics-style Audio Sheet with word-by-word highlighting, speed ruler, **A–B repeat loop**, and a **14-preset Sleep Timer** (including *End of Chapter*).
- **Zero-CPU Offline Chapter Cache:** Pre-render chapters to local audio for ultra-low battery listening on long trips, with a **Per-Book Offline Audio Storage** manager to delete individual books or prune stale caches safely.
- **Full lock-screen & headset controls:** Cover art, live spoken line, chapter title, seek bar, and Bluetooth/headset play/pause/skip.
- **Works in other apps too:** HolosReader also registers as a standard **Android System TTS engine** so you can use its voices system-wide.

### Fast Library & Hassle-Free Maintenance
- **Scales to thousands of books:** Virtualized Grid (2–4 columns) and List views with instant debounced search, status filters (*Unread / Reading / Finished*), sorting, and bulk actions scoped strictly to your displayed search results.
- **Reading Statistics:** Track daily reading & listening time, streaks, pages read, and book completion.
- **Safe Cloud-Backup Relinking:** Restored your phone or moved your EPUB folder? Reattach your EPUBs while preserving reading progress, bookmarks, and automatically restoring embedded book covers.
- **Model Health Check & 1-Tap Repair:** Built-in **Verify / Repair** diagnostics check model integrity and re-download only missing or damaged files — never wiping healthy files or switching your chosen precision.
- **Verified In-App Updates:** Checks GitHub Releases automatically (waiting until you're on the Library screen so it never interrupts reading) and verifies every APK with SHA-256 before installing.

---

## Voices & Optional Add-ons

All models and optional add-ons can be downloaded with **1 tap** inside **Settings → TTS** (running in the background notification shade).

### Core Offline Voice Models

| Model | Best For | Size | Audio Quality | Voices |
| --- | --- | --- | --- | --- |
| [**HolosTTS FP32 (Studio)**](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx) | Best Ukrainian quality on 6+ GB RAM phones | ~760 MB | 24 kHz (48 kHz with FlashSR) | 27 built-in + custom cloned voices |
| [**Supertonic 3 (44.1 kHz)**](https://huggingface.co/Supertone/supertonic-3) | Crisp studio audio & Ukrainian + English words | ~398 MB | 44.1 kHz native | 10 styles (`F1`–`F5`, `M1`–`M5`), adjustable quality steps (`2–12`) |
| [**HolosTTS INT8 (Fast)**](https://huggingface.co/patriotyk/HolosTTS/tree/main/onnx-int8) | Battery life & budget/older phones | ~320 MB | 24 kHz (48 kHz with FlashSR) | 27 built-in + custom cloned voices |

### 1-Tap Optional Add-ons (**Settings → TTS → Optional ONNX Add-ons**)

| Add-on | What It Does | Size | Notes |
| --- | --- | --- | --- |
| **FlashSR 48 kHz Upscaler** | Lifts HolosTTS 24 kHz audio to crisp, studio-like **48 kHz** with clean dynamics | ~487 KB | Works with HolosTTS INT8 & FP32 (Supertonic 3 is already 44.1 kHz) |
| **Ukrainian Emotion Pacing** | Detects 7 emotions ahead of time to vary tempo & pauses in dialogue and action scenes | ~580 MB | Runs in background lookahead so live speech never stalls (6+ GB RAM recommended) |
| **Contextual Stress (Homographs)** | Resolves context-dependent Ukrainian words like *за́мок/замо́к* and *му́ка/мука́* automatically | ~111 MB (INT8) / ~221 MB (FP16) | 1-tap download from [`onnx-addons-v1`](https://github.com/smyhlin/HolosReader/releases/tag/onnx-addons-v1) |
| **On-Device Voice Cloner** | Clone any voice directly on your phone from a 5–15 s uncompressed 16-bit PCM `.wav` clip | ~97 MB | 1-tap download from [`onnx-addons-v1`](https://github.com/smyhlin/HolosReader/releases/tag/onnx-addons-v1); also supports importing `.pt`, `.npy`, and `.bin` voices |

---

## Documentation

- 📖 **[User Guide](docs/USER_GUIDE.md)** — Everyday walkthrough of reading gestures, listening while browsing, multi-voice roles, voice cloning, custom stress dictionary, offline audio storage, and relinking books.
- ❓ **[FAQ & Troubleshooting](docs/FAQ.md)** — Quick answers to common questions about models, storage, voice cloning, pronunciation, and updates.
- 🧠 **[ONNX Models & Add-ons Guide](docs/ONNX_MODELS_AND_ADDONS.md)** — Precision tradeoffs (INT8 vs FP16 vs FP32), integrity verification & repair, offline cache recipes, and optional developer self-export scripts.
- 🏗️ **[Architecture & Developer Guide](docs/ARCHITECTURE.md)** — Module map, G2P & TTS pipelines, concurrency/lease model, testing, and CI/CD.

---

## For Developers

HolosReader is built purely on **Android Framework APIs** (`android.useAndroidX=false`, `minSdk 29`, `compileSdk 35`, **JDK 17**, **Gradle 8.11.1**) for minimal APK size and instant startup.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

See **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)**, **[`CONTRIBUTING.md`](CONTRIBUTING.md)**, and **[`AGENTS.md`](AGENTS.md)** for full engineering details and contribution guidelines.

---

## Credits

- [patriotyk/HolosTTS](https://github.com/patriotyk/HolosTTS) — Ukrainian neural TTS architecture & voice models
- [patriotyk/ukrainian-word-stress](https://github.com/patriotyk/ukrainian-word-stress) — Ukrainian stress dictionary
- [egorsmkv/ukrainian-accentor](https://github.com/egorsmkv/ukrainian-accentor) — Neural Ukrainian accentor
- [patriotyk/ipa-uk](https://github.com/patriotyk/ipa-uk) — Ukrainian IPA phonemizer
- [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3) — 44.1 kHz multilingual flow-matching ONNX TTS
- [YatharthS/FlashSR](https://huggingface.co/YatharthS/FlashSR) — Neural 48 kHz audio upsampler
- [speed-brain-ai/speedbrain-emotion-uk-onnx](https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx) — Ukrainian 7-emotion ONNX classifier
- [aloudreader/uk-stress-token-classifier](https://huggingface.co/aloudreader/uk-stress-token-classifier) — Contextual Ukrainian homograph stress model

---

## License

Released under the [**MIT License**](LICENSE) — free to use, modify, and distribute for personal and commercial purposes.
