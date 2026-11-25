package com.holos.reader.tts

import android.content.Context
import android.content.SharedPreferences

enum class ReaderTheme(val id: Int, val label: String) {
    LIGHT(0, "Світла"),
    GRAY(1, "Сіра"),
    SEPIA(2, "Сепія"),
    GREEN(3, "Трава"),
    DARK(4, "Темна"),
    CHERRY(5, "Вишня"),
    SKY(6, "Небо"),
    NORD(7, "Nord"),
    AMOLED(8, "AMOLED");

    companion object {
        fun fromId(id: Int): ReaderTheme = entries.firstOrNull { it.id == id } ?: DARK
    }
}

class ReaderPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("holos_reader_prefs", Context.MODE_PRIVATE)

    // --- TTS & Voices ---
    var selectedVoice: String?
        get() = prefs.getString("voice", null)
        set(v) = prefs.edit().putString("voice", v).apply()

    var adaptiveVoicesEnabled: Boolean
        get() = prefs.getBoolean("adaptive_voices", false)
        set(v) = prefs.edit().putBoolean("adaptive_voices", v).apply()

    var systemVoice: String?
        get() = prefs.getString("voice_system", null)
        set(v) = prefs.edit().putString("voice_system", v).apply()

    var dialogueVoice: String?
        get() = prefs.getString("voice_dialogue", null)
        set(v) = prefs.edit().putString("voice_dialogue", v).apply()

    var speechSpeed: Float
        get() = prefs.getFloat("speed", 1.1f).coerceIn(0.6f, 2.0f)
        set(v) = prefs.edit().putFloat("speed", v.coerceIn(0.6f, 2.0f)).apply()

    var cpuThreads: Int
        get() = prefs.getInt("threads", 4).coerceIn(1, 8)
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, 8)).apply()

    // TTS Highlighting (Granularity: WORD / PARAGRAPH / OFF; Style: HIGHLIGHTER / UNDERLINE / TEXT_COLOR)
    var ttsHighlightGranularity: String
        get() = prefs.getString("tts_hl_gran", "WORD") ?: "WORD"
        set(v) = prefs.edit().putString("tts_hl_gran", v).apply()

    var ttsHighlightStyle: String
        get() = prefs.getString("tts_hl_style", "HIGHLIGHTER") ?: "HIGHLIGHTER"
        set(v) = prefs.edit().putString("tts_hl_style", v).apply()

    var ttsHighlightColor: Int
        get() = prefs.getInt("tts_hl_color", 0xFF4F8BFF.toInt())
        set(v) = prefs.edit().putInt("tts_hl_color", v).apply()

    // --- Font ---
    var fontSizeSp: Float
        get() = prefs.getFloat("font_sp", 19f).coerceIn(14f, 28f)
        set(v) = prefs.edit().putFloat("font_sp", v.coerceIn(14f, 28f)).apply()

    var fontWeight: Int
        get() = prefs.getInt("font_weight", 400).coerceIn(300, 700)
        set(v) = prefs.edit().putInt("font_weight", v.coerceIn(300, 700)).apply()

    // SANS, SERIF, MONO
    var fontFamily: String
        get() = prefs.getString("font_family", if (useSerif) "SERIF" else "SANS") ?: "SANS"
        set(v) {
            prefs.edit().putString("font_family", v).putBoolean("serif", v == "SERIF").apply()
        }

    var useSerif: Boolean
        get() = prefs.getBoolean("serif", false)
        set(v) = prefs.edit().putBoolean("serif", v).putString("font_family", if (v) "SERIF" else "SANS").apply()

    var bionicReading: Boolean
        get() = prefs.getBoolean("bionic_reading", false)
        set(v) = prefs.edit().putBoolean("bionic_reading", v).apply()

    // --- Layout ---
    var paragraphMarginDp: Int
        get() = prefs.getInt("para_margin_dp", 6).coerceIn(2, 24)
        set(v) = prefs.edit().putInt("para_margin_dp", v.coerceIn(2, 24)).apply()

    var lineSpacingMult: Float
        get() = prefs.getFloat("line_spacing", 1.42f).coerceIn(1.1f, 2.0f)
        set(v) = prefs.edit().putFloat("line_spacing", v.coerceIn(1.1f, 2.0f)).apply()

    var letterSpacingEm: Float
        get() = prefs.getFloat("letter_spacing", 0f).coerceIn(-0.02f, 0.12f)
        set(v) = prefs.edit().putFloat("letter_spacing", v.coerceIn(-0.02f, 0.12f)).apply()

    var textIndentDp: Int
        get() = prefs.getInt("text_indent_dp", 0).coerceIn(0, 32)
        set(v) = prefs.edit().putInt("text_indent_dp", v.coerceIn(0, 32)).apply()

    var fullJustification: Boolean
        get() = prefs.getBoolean("full_justify", false)
        set(v) = prefs.edit().putBoolean("full_justify", v).apply()

    var sideMarginDp: Int
        get() = prefs.getInt("side_margin_dp", 18).coerceIn(8, 40)
        set(v) = prefs.edit().putInt("side_margin_dp", v.coerceIn(8, 40)).apply()

    var showHeader: Boolean
        get() = prefs.getBoolean("show_header", true)
        set(v) = prefs.edit().putBoolean("show_header", v).apply()

    var showFooter: Boolean
        get() = prefs.getBoolean("show_footer", true)
        set(v) = prefs.edit().putBoolean("show_footer", v).apply()

    var showProgressBar: Boolean
        get() = prefs.getBoolean("show_progress_bar", false)
        set(v) = prefs.edit().putBoolean("show_progress_bar", v).apply()

    // AUTO, PORTRAIT, LANDSCAPE
    var screenOrientation: String
        get() = prefs.getString("screen_orientation", "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString("screen_orientation", v).apply()

    // --- Theme ---
    var theme: ReaderTheme
        get() = ReaderTheme.fromId(prefs.getInt("theme", ReaderTheme.DARK.id))
        set(v) = prefs.edit().putInt("theme", v.id).apply()

    var invertImagesInDark: Boolean
        get() = prefs.getBoolean("invert_images_dark", false)
        set(v) = prefs.edit().putBoolean("invert_images_dark", v).apply()

    var highlightDialogues: Boolean
        get() = prefs.getBoolean("highlight_dialogues", false)
        set(v) = prefs.edit().putBoolean("highlight_dialogues", v).apply()

    // --- Behavior ---
    var hideScrollbar: Boolean
        get() = prefs.getBoolean("hide_scrollbar", false)
        set(v) = prefs.edit().putBoolean("hide_scrollbar", v).apply()

    var volumeKeyScroll: Boolean
        get() = prefs.getBoolean("vol_key_scroll", false)
        set(v) = prefs.edit().putBoolean("vol_key_scroll", v).apply()

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean("keep_screen_awake", true)
        set(v) = prefs.edit().putBoolean("keep_screen_awake", v).apply()

    var fullscreenMode: Boolean
        get() = prefs.getBoolean("fullscreen_mode", false)
        set(v) = prefs.edit().putBoolean("fullscreen_mode", v).apply()

    var autoScrollSpeed: Int
        get() = prefs.getInt("auto_scroll_speed", 2).coerceIn(1, 5)
        set(v) = prefs.edit().putInt("auto_scroll_speed", v.coerceIn(1, 5)).apply()

    var autoAdvanceChapter: Boolean
        get() = prefs.getBoolean("auto_chapter", true)
        set(v) = prefs.edit().putBoolean("auto_chapter", v).apply()

    // --- Library ---
    var isGridView: Boolean
        get() = prefs.getBoolean("lib_grid", true)
        set(v) = prefs.edit().putBoolean("lib_grid", v).apply()

    var gridColumns: Int
        get() = prefs.getInt("lib_cols", 3).coerceIn(2, 4)
        set(v) = prefs.edit().putInt("lib_cols", v.coerceIn(2, 4)).apply()

    var sortBy: String
        get() = prefs.getString("lib_sort", "RECENT") ?: "RECENT"
        set(v) = prefs.edit().putString("lib_sort", v).apply()

    var statusFilter: String
        get() = prefs.getString("lib_status", "ALL") ?: "ALL"
        set(v) = prefs.edit().putString("lib_status", v).apply()

    var openLastBookOnStart: Boolean
        get() = prefs.getBoolean("open_last", false)
        set(v) = prefs.edit().putBoolean("open_last", v).apply()

    var lastBookId: String?
        get() = prefs.getString("last_book_id", null)
        set(v) = prefs.edit().putString("last_book_id", v).apply()
}
