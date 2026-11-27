# HolosReader — User Guide

Welcome to **HolosReader**! This guide walks through every reading, listening, and library feature in plain language so you can get the most out of your books and offline audiobooks.

---

## 1. First Launch & Choosing a Voice

When you open HolosReader for the first time, it scans your phone's CPU, RAM, and GPU capabilities and marks the best-fitting offline voice model with a **`★ Recommended`** badge:

- **HolosTTS FP32 (Studio, ~760 MB):** Highest Ukrainian vocal quality, 27 built-in voices, supports custom cloned voices. Best for phones with **6+ GB RAM**.
- **Supertonic 3 (44.1 kHz, ~398 MB):** Crisp studio-grade 44.1 kHz output with 10 styles (`F1`–`F5`, `M1`–`M5`), adjustable quality steps (`2–12`), and an experimental **English Words Pronunciation** toggle for books with mixed Ukrainian/English terms.
- **HolosTTS INT8 (Fast, ~320 MB):** Lightweight quantized Ukrainian model with all 27 voices and custom voice cloning. Best for battery life and entry-level devices.

### Background Downloads
Model and add-on downloads run as a **foreground notification transfer** — you can dismiss the dialog, switch apps, or keep reading while HolosReader downloads in the background. You can install multiple models and switch between them anytime in **Settings → TTS**.

---

## 2. Managing Your Library

- **Importing Books:** Tap **`+`** in the top search bar (or the **`+`** tile at the end of your library) to import a single `.epub` file or scan an entire folder of EPUBs. Batch imports are safely serialized so duplicates are detected automatically.
- **Grid & List Views:** Tap **`⋯`** (View Options) to switch between **Grid** (2, 3, or 4 columns) and **List** views, filter by status (*All / Unread / Reading / Finished*), or sort by *Recent*, *Title*, *Author*, or *Progress*.
- **Instant Search & Scoped Bulk Actions:** Type in the search bar to filter even multi-thousand-book libraries smoothly. Long-press any book to enter **Selection Mode** (Open, Tag, Change Status, Details, Delete). **Select All** applies strictly to the currently displayed search/filter results — hidden books are never accidentally modified or deleted.
- **Relinking Cloud-Restored Books:** If you restored your phone backup or moved your EPUB files, tap a book with a missing file to reattach its EPUB. HolosReader reuses your existing book record, keeps all your bookmarks and reading progress intact, and automatically restores missing embedded covers.

---

## 3. Reading Comfort & Navigation

- **Fullscreen Reading:** HolosReader hides the system status bar by default for distraction-free reading. Tap the center of the page to toggle the top and bottom toolbars.
- **Themes, Brightness & Typography:** Use the bottom quick panels to pick from **9 themes**, adjust screen brightness, tweak font size, margins, line spacing, or enable **Bionic Reading** (bolds word beginnings to guide your eyes). Every typeface in the font picker previews in its own font.
- **Pull-to-Next-Chapter:** At the bottom of a chapter, pull up gently to reveal the floating next-chapter chip and advance smoothly without reaching for buttons.
- **Book-Wide Page Scrubber:** Open the scrubber bar to jump anywhere across the entire book by global page number (`current / total`). Use the **Undo / Redo** buttons to hop back and forth between places you jumped from.
- **Table of Contents (TOC) Quick Search:** Open the TOC sheet to search across thousands of chapters instantly, tap **Clear** to reset your search, or tap **Current** to jump straight back to your active chapter. Non-linear appendices remain directly reachable from the TOC while normal linear navigation skips them cleanly.
- **Illustrations & Tables:** Tap any book illustration to zoom full-screen. Tables (such as LitRPG character sheets and status windows) render cleanly with preserved column alignment and multi-paragraph cells.

---

## 4. Listening & Audio Controls

### Starting & Controlling Playback
- Tap **Play** on the mini-player pill or open the **Audio Sheet** for the full lyrics-style player with word-by-word highlighting, speed ruler, voice picker, and timers.
- **Browse While Listening:** You can freely scroll ahead or back through the chapter text while narration keeps playing! Use the **return-to-narration** control whenever you want to snap the viewport back to the currently spoken paragraph.
- **Smooth Audio Edges:** Play, pause, and seek apply gentle fade-in/fade-out ramps so playback starts and stops cleanly without pops or clicks.
- **A–B Repeat Loop:** Set start (`A`) and end (`B`) paragraphs inside the Audio Sheet to repeat a passage. Changing to a different chapter automatically resets the loop.
- **Sleep Timer:** Choose from **14 duration presets** or **End of Chapter**. When the timer expires, playback stops cleanly even if the screen is off.
- **Lock-Screen & Headset Controls:** Control Play/Pause, Next/Previous paragraph, and seek right from the notification shade, lock screen, or Bluetooth headphones.

### Multi-Voice Adaptive Narration
In **Settings → TTS** (or inside the Audio Sheet), enable **Adaptive Voices** to assign distinct voices to:
1. **Narrator** (standard prose)
2. **Dialogue** (quoted character speech)
3. **System / Status Boxes** (`[Skill Unlocked]`, LitRPG notifications, and framed callouts)

You can mix voices **across different installed TTS models** (for example, a HolosTTS narrator paired with a different speaker or model for `[System]` boxes), and the player displays which adaptive narrator is currently speaking.

### Using HolosReader as Your Android System TTS
Open Android **Settings → System → Languages & input → Text-to-speech output** and select **HolosReader** as your preferred engine. HolosReader advertises only the voices supported by your currently installed model so other apps never receive silent or broken voice selections.

---

## 5. Offline Chapter Audio & Storage Management

For flights, commutes, or maximum battery savings, tap **Offline ›** in the Audio Sheet to pre-render the current chapter to local audio (**0% CPU** during playback).

- **Smart Cache Recipes:** Offline audio is keyed to your exact resolved voice, model, speed, add-ons, and custom stress overrides. Changing settings never replays mismatched audio.
- **Per-Book Storage Manager:** Go to **Settings → Offline Audio Cache → Offline Audio Storage by Book** to see:
  - Total disk space used per book
  - Number of valid cached chapters vs. stale/outdated cache bytes
  - **Delete a single book** while keeping your other offline books untouched
  - **Prune Stale** to reclaim space from old recipes or interrupted renders without touching valid chapters or anything currently playing/rendering
  - **Clear All** with confirmation and exact freed-space reporting

---

## 6. Custom Pronunciation & Voice Cloning

### Teaching Custom Stress (**Settings → TTS → Stress Editor**)
Ukrainian has many fantasy names, borrowed terms, and homographs. Open the **Stress Editor** to add custom words with an acute accent (`́`) or `+` before the stressed vowel (e.g., `за+мок`), test the pronunciation live right inside the editor, and save — changes apply immediately to future narration.

### Contextual Stress Add-on (Homographs)
Install **Contextual Stress (Homographs)** in **Settings → TTS → Optional ONNX Add-ons** (1-tap download). It automatically picks the right stress for words like *за́мок / замо́к* or *му́ка / мука́* based on the surrounding sentence.

### Cloning a Voice on Your Phone
1. Go to **Settings → TTS → Optional ONNX Add-ons** and download **On-Device Voice Cloner** (~97 MB, 1-tap).
2. Prepare a clean **5–15 second uncompressed 16-bit PCM `.wav`** recording (mono or stereo, minimal background noise).
3. Tap **Clone Voice from WAV Clip**, select your `.wav` file, and give your voice a name. It appears immediately in your voice list!
4. You can also import pre-made 256-dim HolosTTS speaker vectors in **`.pt`**, **`.npy`** (little-endian, big-endian, or native float32, shape `(256,)` or `(1, 256)`), or raw **`.bin`** format.

---

## 7. Model Integrity Diagnostics & Verified Updates

- **Verify / Repair Models:** In **Settings → Model Integrity → Verify / Repair Models & Add-ons**, HolosReader runs a background SHA-256 and ONNX header check across all installed models and add-ons. If any file is missing, truncated, or corrupted, tap **Repair Broken** — only broken files are re-downloaded, healthy files stay untouched, and your chosen INT8/FP16 precision is preserved.
- **In-App Updates:** HolosReader checks GitHub Releases automatically when you're on the Library screen (never interrupting active reading) or on demand via **About → Check for updates**. Every APK is verified against the release `SHA256SUMS` manifest before Android's installer is launched.
