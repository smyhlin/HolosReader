# HolosReader

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
- **Gradle 8.11.1** (note: this repository intentionally does not bundle `gradlew`)

### Local Build
```bash
gradle :app:assembleDebug
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
| `holos-onnx` | ONNX Runtime bridge (`HolosOnnx.kt`) for neural TTS & accentor inference |
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

---

## License

Released under the [**MIT License**](LICENSE) — free to use, modify, and distribute for personal and commercial purposes.
