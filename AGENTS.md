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
  - **No Gradle Wrapper (`./gradlew`):** The repository does not include `gradlew`. Use system `gradle :app:assembleDebug` (Gradle **8.11.1**).
  - Keep `androidResources { noCompress += listOf("gz", "onnx") }` intact in `app/build.gradle.kts` so bundled G2P dictionaries and `accentor-lite.onnx` remain memory-mappable.
- **APK Signing Consistency:**
  - Both `.github/workflows/build-apk.yml` and `.github/workflows/release.yml` build `assembleDebug`. Do not switch `release.yml` to an unsigned/different-key release APK without handling signing keys, or in-app updates via `GitHubUpdater` will fail with a signature mismatch.

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
