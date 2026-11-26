# Contributing to HolosReader

Contributions, bug reports, and feature ideas are welcome.

## Branching Model

- **`dev`** — Primary integration branch for all new features and bug fixes. Pushes and PRs to `dev` run CI APK builds (`build-apk.yml`) without publishing a public Release.
- **`main`** — Release branch. Merging `dev` into `main` automatically builds and publishes a new GitHub Release (`release.yml`) for in-app updates.

## Development Rules

Before submitting changes, please review [`AGENTS.md`](AGENTS.md) for the complete architectural and CI constraints. In short:

1. **No AndroidX:** `android.useAndroidX=false` is strictly enforced. Use standard Android Framework APIs only.
2. **Toolchain:** Target **JDK 17**, **Android SDK 35** (`minSdk 29`), and **Gradle 8.11.1** (`gradle :app:assembleDebug`).
3. **Preserve Asset Compression Settings:** Keep `gz` and `onnx` uncompressed in `app/build.gradle.kts` (`noCompress`).
4. **Formatting:** Follow `.editorconfig` (UTF-8, LF, 4-space indentation for Kotlin/XML, 2-space for YAML/JSON).

## Submitting a Pull Request

1. Fork the repository and create a topic branch from `dev` (e.g., `feat/...` or `fix/...`).
2. Keep commits focused and descriptive (e.g., `feat(ui): ...`, `fix(tts): ...`, `docs: ...`).
3. Verify the APK builds cleanly with `gradle :app:assembleDebug`.
4. Open a pull request targeting **`dev`** describing the change and any visual or behavioral impact.
