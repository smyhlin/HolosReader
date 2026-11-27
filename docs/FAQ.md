# HolosReader — Frequently Asked Questions & Troubleshooting

### Which TTS model should I pick for my phone?
On first launch (and in **Settings → TTS**), HolosReader marks the best match for your device with **`★ Recommended`**:
- **HolosTTS FP32 (Studio, ~760 MB):** Best overall Ukrainian voice quality and custom voice cloning; recommended for **6+ GB RAM** devices.
- **Supertonic 3 (44.1 kHz, ~398 MB):** Crisp 44.1 kHz audio with 10 styles (`F1`–`F5`, `M1`–`M5`) and an experimental **English Words Pronunciation** toggle for Ukrainian books with English terms.
- **HolosTTS INT8 (Fast, ~320 MB):** Fastest synthesis and best battery life on mid-range or older phones.

---

### Can I switch apps or lock my screen while models are downloading?
**Yes.** Model and add-on downloads run as a background foreground transfer in the Android notification shade. You can dismiss the progress dialog, switch apps, or keep reading while they finish.

---

### Can I scroll ahead or look back in a chapter without stopping narration?
**Yes.** You can browse freely anywhere in the chapter while narration continues uninterrupted. Whenever you want to jump back to the live spoken paragraph, tap the return-to-narration control.

---

### What is Adaptive Multi-Voice Narration? Can I mix voices from different models?
Adaptive Narration automatically detects **Narrator** prose, **Dialogue**, and **`[System]` / LitRPG status boxes**, letting you assign a different voice to each role. If you have more than one TTS model installed (for example, HolosTTS + Supertonic 3), you can even assign roles across different installed models.

---

### Why does FlashSR 48 kHz only apply to HolosTTS and not Supertonic 3?
HolosTTS generates **24 kHz** audio, which FlashSR upsamples cleanly to **48 kHz**. Supertonic 3 already generates native **44.1 kHz** audio, so it doesn't need upsampling.

---

### Why did a Ukrainian word have the wrong stress, and how do I fix it?
1. For context-dependent homographs (like *за́мок* vs. *замо́к*), install the 1-tap **Contextual Stress (Homographs)** add-on in **Settings → TTS → Optional ONNX Add-ons**.
2. For character names, fantasy terms, or specific words, open **Settings → TTS → Stress Editor**, add the word with `+` before the stressed vowel (e.g., `за+мок`), preview it live, and save.

---

### How do I clone a voice on my phone? Why was my audio file rejected?
1. Download **On-Device Voice Cloner** (~97 MB, 1-tap) in **Settings → TTS → Optional ONNX Add-ons**.
2. Use an **uncompressed 16-bit PCM `.wav`** clip (**5–15 seconds**, mono or stereo, clean speech with little background noise). Compressed formats like `.mp3`, `.ogg`, or `.m4a` are rejected — convert them to 16-bit PCM `.wav` first.
3. Custom cloned voices work with **HolosTTS INT8** and **HolosTTS FP32** (Supertonic 3 uses its own 10 built-in styles).
4. You can also import existing 256-dim HolosTTS voice files in **`.pt`**, **`.npy`** (`<f4`, `>f4`, or `=f4`, shape `(256,)` or `(1, 256)`), or raw 1024-byte **`.bin`** format.

---

### How do I free up offline audio storage without deleting my favorite book?
Open **Settings → Offline Audio Cache → Offline Audio Storage by Book**. From there you can:
- Tap any individual book to delete only its offline audio.
- Tap **Prune Stale** to remove outdated recipes or incomplete renders while keeping all valid current chapters intact.
- Tap **Clear All** (with confirmation) to wipe all stored offline audio. Files currently playing or rendering are automatically protected.

---

### A model download got interrupted or sounds broken. Do I have to delete everything?
**No.** Open **Settings → Model Integrity → Verify / Repair Models & Add-ons**. HolosReader verifies every file's checksum and ONNX header in the background and lets you tap **Repair Broken** to re-download **only** missing or corrupted files — healthy files are preserved and your installed INT8/FP16 precision is never overwritten.

---

### I restored my phone or moved my EPUB folder — will I lose my bookmarks or covers?
**No.** Tap a book whose file path changed and reattach its `.epub` file. HolosReader reuses the existing book ID, preserves all bookmarks, tags, reading stats, and reading position, and automatically restores missing embedded covers.

---

### Why didn't jumping to the last chapter via TOC mark my book "Finished"?
By design! Pure navigation (TOC jumps, book-wide scrubber jumps, or closing the reader right after jumping) saves a **checkpoint** without falsely marking an unread book as **Finished**. A book is only marked **Finished** when you actually read or listen through the end of its final linear chapter.

---

### Does HolosReader work as a system-wide TTS engine in other Android apps?
**Yes.** Go to Android **Settings → System → Languages & input → Text-to-speech output** and choose **HolosReader**. Only voices supported by your currently installed model are advertised so other apps always get working voices.

---

### Does HolosReader collect any data or require an internet connection?
**No.** Internet is used only when you choose to download voice models, optional ONNX add-ons, or verified GitHub Releases updates. Everything else — EPUB parsing, Ukrainian stress/phonemization, neural TTS, emotion pacing, and voice cloning — runs 100% locally on your device.
