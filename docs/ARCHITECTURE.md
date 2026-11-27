# HolosReader — Architecture & Developer Guide

HolosReader is a zero-AndroidX (`android.useAndroidX=false`), offline-first Android EPUB 3 / EPUB 2 reader and neural Ukrainian TTS application targeting **Android 10–15+ (`minSdk = 29`, `compileSdk = 35`, `targetSdk = 35`)** with **JDK 17** and **Gradle 8.11.1**.

---

## 1. Module & Package Map

| Module / Package | Key Classes & Responsibilities |
| --- | --- |
| **`holos-onnx`** | Pure-Kotlin ONNX Runtime wrappers: `HolosOnnx` (24 kHz Ukrainian TTS), `SupertonicOnnx` (44.1 kHz multilingual flow-matching TTS), `FlashSrOnnx` (24→48 kHz super-resolution with DC-bias removal & dynamics preservation), `UkrainianEmotionOnnx` (7-emotion classifier), `ContextualStressOnnx` (homograph disambiguator), `VoiceEncoderOnnx` (24 kHz speaker encoder), `UnigramTokenizer`. |
| **`com.holos.reader.epub`** | `EpubStreamingParser` (streaming XHTML parser with structural-boundary anchor registration, `TableRowState` cell/nested-table scopes, inline image ordering, fragment-only TOC resolution & literal `#` preservation), `BookDatabase` (one-time O(N) SQLite index, linear spine navigation helpers, serialized batch import, cloud-backup relinking with cover recovery), `EpubImageCache` (persistent `ZipFile` + LRU display-aware decoding), `EpubModels`. |
| **`com.holos.reader.g2p`** | 4-stage offline Ukrainian G2P (no Python): `UserStressOverrides` (`stress_user.txt`), `UkrainianStressDict` (2,886,186 forms + 36,414 homographs), `UkrainianStressifier` (with Tier 1.5 `ContextualHomographResolver`), `NeuralAccentor` (bundled 1.17 MB `accentor-lite.onnx`), `UkrainianTextNormalizer` (numbers, dates, signed percentages/per-mille), `SentenceSplitter`, `UkrainianIpa` (1:1 Kotlin port of `patriotyk/ipa-uk`). |
| **`com.holos.reader.tts`** | `TtsPlaybackController` (live streaming + offline WAV playback, generation-scoped `ScheduledExecutorService` sleep deadline, `PlaybackFocusGate` handshake, `PlaybackGain` edge ramps), `HolosEngineHolder` & `EngineLeasePool` (generation-counted leases for main engine and all 3 ONNX add-ons), `HolosSafeSynthesizer`, `AdaptiveVoiceRouter` & `AdaptiveVoicePlan` (cross-model Narrator/Dialogue/System routing), `LiveEmotionPacing` (off-hot-path lookahead emotion classifier), `AudioCacheRecipe` (v4 resolved immutable snapshot), `OfflineAudioCache` (strict RIFF/PCM16 + exact ordered ID + overflow-safe bounds validation), `OfflineStorageManager` (per-book inventory, lease-protected cleanup & stale pruning), `ModelDownloadService` (foreground notification transfers), `VerifiedHttpDownloader` (strict validator / SHA-256 resume), `ModelIntegrityChecker` (bundle-relative `.verified.json` manifest & selective repair), `CustomVoiceLoader` (`.pt`, `.npy` `<f4`/`>f4`/`=f4` `(256,)`/`(1, 256)`, `.bin`), `ReaderAudioService` (foreground `MediaSession`, audio focus, lock-screen controls), `HolosTtsService` (Android System TTS engine advertising only effective model voices), `ReadingSessionTracker` & `ReadingProgressPolicy` (`NAVIGATION`, `CHECKPOINT`, `CONSUMED_READING`, `AUDIO_CHAPTER_COMPLETE`), `ChapterNarrationModel`, `DeviceCapabilityScanner`, `ReaderPreferences`, `TtsModelSpec`, `OnnxAddonSpec`, `HolosVoiceCatalog`, `SupertonicLanguageSplitter`. |
| **`com.holos.reader.ui`** | `LibraryActivity` (virtualized `GridView`/`ListView` with `BaseAdapter`, 200 ms debounced async search, scoped bulk actions, deferred update prompts), `ReaderActivity` (fullscreen virtualized reader, browse-while-listening, single-paragraph lyric refresh, off-UI checkpoints, unified scrubber & undo/redo), `ChapterEdgeListView` (pull-to-next-chapter gesture), `TocSheetController` (fast SQLite TOC search with Clear & Current actions), `AudioSheetController` (lyrics-style player, speed ruler, A–B loop, sleep timer, offline pre-render), `ReaderSettingsActivity` (68% translucent overlay sheet with preserved scroll position, model integrity UI, per-book offline storage UI), `ModelSelectorDialog`, `StressEditorActivity`, `StatisticsActivity`, `BionicTextHelper`, `HighlightTextView`, `PillSliderView`, `UiTheme`, `AppStrings` (UK/EN). |
| **`com.holos.reader.update`** | `GitHubUpdater` (zero-login GitHub Releases checker + fail-closed `SHA256SUMS` verification + atomic APK staging), `ApkFileProvider` (framework `ContentProvider`). |
| **`com.holos.reader.util`** | `BitmapDecode` (bounded off-UI cover & illustration decoding). |

---

## 2. Core Engineering Invariants

1. **Zero AndroidX:** `android.useAndroidX=false` in `gradle.properties`. Every Activity, View, Adapter, Dialog, Notification, MediaSession, and FileProvider uses pure Android Framework APIs.
2. **Generation-Counted Native Resource Lifetimes (`EngineLeasePool`):** Both the primary TTS engine (`HolosTtsEngine`) and all 3 ONNX add-ons (`FlashSrOnnx`, `UkrainianEmotionOnnx`, `ContextualStressOnnx`) are borrowed via `.use { lease -> ... }`. Retiring a generation marks it retired and closes native ORT sessions off the UI thread only after the last active borrower releases.
3. **Focus-Gated Playback & Independent Sleep Enforcement:** `TtsPlaybackController` awaits `PlaybackFocusGate` grant from `ReaderAudioService` (which promotes foreground first) before `AudioTrack.play()`/PCM writes. Paragraph transitions refresh `MediaSession` metadata without restarting the foreground service. Sleep deadlines run on a generation-scoped `ScheduledExecutorService` (shut down cleanly on reader disposal) that cancels active sessions and pauses/flushes `AudioTrack` even during stalled bootstrap or blocked writes.
4. **Honest Progress & Session Accounting:** `ReadingSessionTracker` accumulates active milliseconds internally across repeated state callbacks and drains only on persistence. Lifecycle saves (`onPause`/`onStop`/`onDestroy`), Statistics entry, and TOC/scrubber jumps emit `ProgressEvent.CHECKPOINT` or `ProgressEvent.NAVIGATION` so unread books are never marked `FINISHED` without organic reading or natural `AUDIO_CHAPTER_COMPLETE` on the final linear spine item.
5. **Uncompressed Assets (`noCompress`):** `app/build.gradle.kts` keeps `gz` and `onnx` uncompressed inside the APK so `AssetFileDescriptor` and direct asset streaming work with zero extraction overhead.

---

## 3. Building, Testing & CI/CD

### Local Commands
```bash
# Run all JVM unit tests + strict Android Lint + Debug APK
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Verify signed Release R8 + ABI-split APKs
./gradlew :app:assembleRelease
```

### Pure JVM Unit Test Guidelines
- Tests in `app/src/test/kotlin/` run on JDK 17 against `android.jar` stubs:
  - Avoid `org.json.JSONObject` in pure-JVM logic (use pure-Kotlin serializers so tests never hit `RuntimeException: Method ... not mocked`).
  - Avoid `com.sun.net.httpserver.HttpServer` (hidden by `android.jar` bootclasspath); use `java.net.ServerSocket` for HTTP mock fixtures.
- Android Lint runs with `abortOnError = true` in CI — zero warnings/errors permitted.

### GitHub Actions Workflows
- **[`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml):** Triggered on pushes/PRs to `dev` and `main`. Validates Python tool syntax, runs all JVM unit tests, runs strict Android Lint, builds Debug APK, and runs a Release R8 + ABI-split smoke build.
- **[`.github/workflows/release.yml`](../.github/workflows/release.yml):** Triggered on pushes to `main`. Auto-bumps `versionName` (`5.2.<run_number>`) and `versionCode`, builds signed ABI-split + universal APKs, generates `SHA256SUMS`, and publishes a GitHub Release for in-app updates.
