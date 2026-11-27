# AGENTS.md — HolosReader Engineering & Sync Guide

Instructions, architectural constraints, and operational caveats for AI agents and maintainers working on **HolosReader** (`smyhlin/HolosReader`).

---

## 1. Hard Technical Constraints

- **No AndroidX (`android.useAndroidX=false`):**
  - Strictly use Android Framework APIs (`android.app.*`, `android.widget.*`, `android.media.session.*`, `android.content.ContentProvider`, etc.).
  - Never add `androidx.*`, `com.google.android.material.*`, or AppCompat dependencies.
- **SDK & Toolchain:**
  - `compileSdk = 35`, `targetSdk = 35`, `minSdk = 29` (Android 10+).
  - Java / Kotlin JVM target: **17**.
  - **Gradle Wrapper (`./gradlew`):** Bundled Gradle **8.11.1** wrapper (`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`).
  - Keep `androidResources { noCompress += listOf("gz", "onnx") }` intact in `app/build.gradle.kts` so bundled G2P dictionaries and `accentor-lite.onnx` remain memory-mappable.
- **Persistent Release Signing & R8 + ABI Splits:**
  - `release.yml` signs `assembleRelease` (`isMinifyEnabled = true`, `isShrinkResources = true`, `-PenableAbiSplits=true`) using the persistent keystore stored in GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; local backup outside git at `/volume/Projects/.secrets/holosreader-release.jks`).
  - Emits slim `arm64-v8a` + `universal` APKs plus `SHA256SUMS`; `GitHubUpdater.kt` automatically picks the device's matching ABI first.

---

## 2. CI/CD & GitHub Actions Caveats

- **Do NOT use `android-actions/setup-android@v3` with default inputs:**
  - On `ubuntu-latest`, `setup-android@v3` defaults to `packages: tools platform-tools`, which fails immediately (`Warning: Failed to find package 'tools'`).
  - `ubuntu-latest` already has Android SDK 35 preinstalled. Use `gradle/actions/setup-gradle@v4` with `gradle-version: "8.11.1"` (matching `build-apk.yml`).
- **Automated Release Versioning (`.github/workflows/release.yml`):**
  - On every push to `main`, `release.yml` stamps `versionName = "5.1.${{ github.run_number }}"` and `versionCode = $((10 + ${{ github.run_number }}))` in `app/build.gradle.kts`, builds `:app:assembleDebug`, and publishes `HolosReader-v5.1.${{ github.run_number }}.apk` as the latest GitHub Release (`make_latest: true`) using `GITHUB_TOKEN` (`permissions: contents: write`).
  - `GitHubUpdater.kt` polls `https://api.github.com/repos/smyhlin/HolosReader/releases/latest` (zero-login) and triggers installation through `ApkFileProvider.kt` (`com.holos.reader.apkprovider`).

---

## 3. Multi-Environment Sync Caveats (`@cloudkey` ↔ Sandbox VM ↔ GitHub)

When syncing changes from an isolated sandbox VM to GitHub via `@cloudkey`:

1. **GitHub Auth Routing:**
   - If the GitHub MCP tool fails with `401 Unauthorized` (`TOOL_AUTHORIZATION_STATUS_NOT_SET`), use `@cloudkey` (`gh` CLI is authenticated as `smyhlin` with `repo` and `workflow` scopes).
   - The `@cloudkey` mirror lives at `/volume/Projects/HolosReader`.
2. **Payload Truncation Limit in `cloudkey:bash` (~10–11 KB):**
   - **Critical:** Passing >10 KB in a single `cloudkey:bash` command or heredoc silently truncates around ~10,873 bytes, corrupting base64/tar streams.
   - **Safe Transfer Protocol:**
     1. On the source VM, package changed files into a `.tar.gz`, base64-encode without line wraps (`base64 -w0`), and split into **≤ 8 KB chunks** (`split -b 8000 -d`).
     2. Record `sha256sum` of both the `.b64` and `.tar.gz` files.
     3. Write each chunk to `/tmp/pXX` on `@cloudkey` (≤ 8 KB per call), concatenate (`cat /tmp/p* > /tmp/patch.b64`), and verify `sha256sum` matches before running `base64 -d` and `tar -xzvf`.
     4. Remove temporary `/tmp/p*`, `/tmp/patch.b64`, and `/tmp/patch.tar.gz` after extraction.
3. **Keep Mirror & VM Clean:**
   - Ensure `git config user.name` and `git config user.email` are set on `@cloudkey` before committing.
   - Never commit `.gradle/`, `build/`, `.idea/`, or temporary `/tmp/` archives into `/volume/Projects/HolosReader`.
   - If files are edited directly on `@cloudkey` (e.g., workflows or docs), pull/sync those commits back to any active VM workspace before the next patch cycle.

---

## 4. Key Architecture Map

- **EPUB & Storage:** `BookDatabase.kt` (SQLite index + `getAllSpineHrefs`), `EpubStreamingParser.kt`, `EpubImageCache.kt`.
- **Reader UI:**
  - `ReaderActivity.kt`: Fullscreen by default (`WindowInsetsController.hide(statusBars())` on API 30+, `FLAG_FULLSCREEN` fallback on API 29), chapter header (`subtleHeaderTv`), tap-to-hide book-wide page counter (`footerCounterTv` + `footerTapHidden`), book-wide page scrubber (`computeBookPageMap`, `jumpToGlobalPage`), compact Light/Font quick panels, live `OnSharedPreferenceChangeListener`.
  - `ReaderSettingsActivity.kt`: Font picker with per-font typeface previews, overlay sheet mode (`EXTRA_AS_SHEET` 68% height via `AppTheme.TranslucentSheet` in `styles.xml`).
  - `PillSliderView.kt`: Custom framework pill slider used in quick settings and scrubbers.
  - `AudioSheetController.kt`: Lyrics-style TTS sheet, speed ruler, A-B loop, in-sheet `Set Timeout` (`sleepPage` with 14 presets).
  - `LibraryActivity.kt` & `UiTheme.kt`: Library UI, startup update check, and manual **Check for updates** action in About dialog.
- **TTS & Media Session:**
  - `ReaderPreferences.kt`: Persistent settings (`fullscreenMode = true`, `footerTapHidden`, `dismissedUpdateTag`, etc.).
  - `ReaderAudioService.kt`: Foreground TTS playback, `MediaSession` with `Icon.createWithResource` actions, cached cover art (`ALBUM_ART` / `DISPLAY_ICON` / `setLargeIcon`), live spoken snippet + chapter subtitle, `METADATA_KEY_DURATION` + `ACTION_SEEK_TO`, `VISIBILITY_PUBLIC`.
- **Auto-Updater:** `GitHubUpdater.kt`, `ApkFileProvider.kt`.

---

## 5. Branching & Release Workflow

- **`dev` (active development branch):** Default branch for all feature work, experiments, and bug fixes. Every push to `dev` runs `.github/workflows/build-apk.yml` and uploads a test APK artifact (`HolosReader-EPUB3-HolosTTS-apk`) without publishing a public GitHub Release.
- **`main` (release branch):** Reserved for tested, ready-to-ship builds. Merging `dev` → `main` triggers `.github/workflows/release.yml`, which auto-stamps `versionName`, generates `SHA256SUMS`, and publishes the APK to GitHub Releases for zero-login in-app updates.
- **Docs-only commits on `main`:** `.github/workflows/release.yml` ignores `**.md`, `LICENSE`, `.gitignore`, `.editorconfig`, `.gitattributes`, `.github/ISSUE_TEMPLATE/**`, and `.github/workflows/build-apk.yml` so documentation updates never cut spurious releases.

---

## 6. Multi-Model TTS & Optional ONNX Add-ons Architecture

- **Unified TTS Engine (`HolosTtsEngine`):**
  - `HolosTtsEngine.Holos` wraps `HolosOnnx` (24 kHz streaming, INT8 or FP32) using the 4-stage Ukrainian G2P pipeline.
  - `HolosTtsEngine.Supertonic` wraps `SupertonicOnnx` (44.1 kHz flow-matching, `F1..F5`/`M1..M5`) + `SupertonicLanguageSplitter` (experimental `<en>` span router).
  - `DeviceCapabilityScanner` classifies `HIGH` (`HOLOS_FP32`), `MEDIUM` (`SUPERTONIC_3`), or `ENTRY` (`HOLOS_INT8`), with SELinux-safe fallback when `/sys/devices/system/cpu/*/cpufreq/cpuinfo_max_freq` is unreadable.
- **Optional ONNX Add-ons (`OnnxAddonSpec.kt`, `OnnxAddonManager.kt`):**
  - `FlashSrOnnx` (`YatharthS/FlashSR/onnx/model.onnx`, `audio_values` [1, N] @ 16 kHz → `reconstruction` [1, 3*N] @ 48 kHz) — applied only to `HolosTtsEngine.Holos`; `TtsPlaybackController` automatically switches `AudioTrack` to `48,000 Hz`.
  - `UkrainianEmotionOnnx` (`speed-brain-ai/speedbrain-emotion-uk-onnx`) + `UnigramTokenizer` (pure-Kotlin Viterbi SentencePiece tokenizer; use `UnigramTokenizer.fromVocab` in JVM unit tests since Android `org.json` is stubbed on JVM).
  - `ContextualStressOnnx` (`aloudreader/uk-stress-token-classifier`, exported via `tools/export_stress_onnx.py`) hooks into `UkrainianStressifier.contextualResolver` as Tier 1.5 (user overrides still take priority; exceptions fall back cleanly).
  - `VoiceEncoderOnnx` (`patriotyk/HolosTTS/voice_encoder.pt`, exported via `tools/export_voice_encoder_onnx.py`) decodes 16-bit PCM WAV (mono/stereo) into L2-normalized 256-float `.bin` speaker vectors.

---

## 7. Wave 1 Overhaul & Qualification Status (H01–H35)

- **Storage & EPUB Core (H01–H09):**
  - Stock Android 10 (API 29 / SQLite 3.22) compatible `INSERT OR IGNORE` + `UPDATE` activity writes, schema-checked `PRAGMA table_info` migrations, SHA-256 content-identity deduplication, `STORED` EPUB `mimetype`, private `custom_voices/` backup exclusion, `EpubSession.borrowZip` reference-counted ZIP lifetime, committed chapter snapshots, viewport vs TTS anchor separation, monotonic session timing, truthful completion, structural `<nav epub:type="toc">` / nested NCX / `#fragment` / BOM & charset decoding.
- **Native ONNX, Audio & System TTS (H10–H17):**
  - `OrtSession.SessionOptions` owned until after dependent sessions close, off-UI engine/add-on retirement, cached effective fallback, non-blocking voice catalog, monotonic sleep timer, safe A–B loop & zero-frame failure surfacing, rotation survival via `configChanges`, truthful `CheckVoiceDataActivity` PASS/FAIL and `HolosTtsService` voice validation, model/add-on-aware locale-neutral offline audio recipe.
- **G2P, Stress, Emotion & Verified Bundles (H18–H28):**
  - Supertonic 3 bilingual English span preservation before Ukrainian transliteration, reference Holos monosyllable phonetics, user stress override precedence, caller-coordinate UTF-16 `UnigramTokenizer` spans, multi-label sigmoid emotion head with neutral/abstain policy, APK-version bundled G2P refresh, bounded 16 MB WAV voice enrollment, and HTTP 206 `Content-Range` verification.
- **UI, Verified APK Handoff & Release Packaging (H29–H35):**
  - `PillSliderView` reverts on `ACTION_CANCEL`, exact ±0.10 line spacing steps, exact ABI token matching (fail-closed) in `GitHubUpdater`, and `-PrequireReleaseSigning=true` fail-closed Gradle gate.
  - **Verification boundary:** All JVM unit tests, Android Lint, debug APK, and signed R8 + ABI-split release APKs are verified in GitHub Actions (`Build HolosReader APK`); stock API 29 & Android 15 16 KiB physical handset checks remain device-stage checks.

---

## 8. Post-Wave-1 Additions & Testing Gotchas (PRs #29–#130)

- **New Core Components (see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for full map):**
  - `ModelDownloadService.kt`: Foreground notification transfer service for non-blocking model & ONNX add-on downloads.
  - `AdaptiveVoicePlan.kt`: Cross-model adaptive narration routing (Narrator / Dialogue / System) across multiple installed TTS models, with live narrator badge in the player.
  - `LiveEmotionPacing.kt`: Off-hot-path lookahead emotion classification so live Holos synthesis never stalls waiting on XLM-RoBERTa inference.
  - `PlaybackGain.kt`: Smooth fade-in/fade-out audio edge ramps and quiet reader comfort controls.
  - `ChapterEdgeListView.kt` & `TocSheetController.kt`: Pull-to-next-chapter gesture with floating pull chip and fast SQLite TOC search with Clear / Current actions.
  - `ModelIntegrityChecker.kt` & `VerifiedHttpDownloader.kt`: Pure-Kotlin bundle-relative `.verified.json` checksum manifest (with legacy nested-record lookup), Holos manifest pre-repair, preserved installed INT8/FP16 precision, and strict strong-validator / SHA-256 HTTP 206 resume.
  - `OfflineStorageManager.kt` & `AudioCacheRecipe.kt` (v4): Per-book offline storage inventory, live playback/render lease protection during cleanup/pruning, and immutable v4 cache recipes keyed to the leased `engine.modelId`.
  - `PlaybackFocusGate` & `EngineLeasePool` (engine + all 3 add-ons): Generation-aware foreground/focus handshake, paragraph-level `MediaSession` refresh without restarting foreground service, and generation-scoped sleep scheduler shut down on reader disposal.
  - Virtualized `LibraryActivity.kt`: Framework `GridView`/`ListView` with `BaseAdapter`, 200 ms debounced async search, search-scoped bulk actions, serialized batch import, and deferred update prompts until Library focus.
- **JVM Unit Test Gotchas (`:app:testDebugUnitTest`):**
  - Avoid `org.json.JSONObject` in pure-JVM production code (throws `Method ... not mocked` against `android.jar` stubs).
  - Avoid `com.sun.net.httpserver.HttpServer` in unit tests (hidden by `android.jar` bootclasspath); use `java.net.ServerSocket`.
