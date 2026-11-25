package com.holos.reader.tts

import android.content.Context
import android.content.SharedPreferences

enum class ReaderTheme(val id: Int, val label: String) {
    LIGHT(0, "Світла"),
    GRAY(1, "Сіра"),
    SEPIA(2, "Сепія"),
    GREEN(3, "Зелена"),
    DARK(4, "Темна");

    companion object {
        fun fromId(id: Int): ReaderTheme = entries.firstOrNull { it.id == id } ?: DARK
    }
}

class ReaderPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("holos_reader_prefs", Context.MODE_PRIVATE)

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

    var theme: ReaderTheme
        get() = ReaderTheme.fromId(prefs.getInt("theme", ReaderTheme.DARK.id))
        set(v) = prefs.edit().putInt("theme", v.id).apply()

    var fontSizeSp: Float
        get() = prefs.getFloat("font_sp", 19f).coerceIn(14f, 28f)
        set(v) = prefs.edit().putFloat("font_sp", v.coerceIn(14f, 28f)).apply()

    var useSerif: Boolean
        get() = prefs.getBoolean("serif", false)
        set(v) = prefs.edit().putBoolean("serif", v).apply()

    var bionicReading: Boolean
        get() = prefs.getBoolean("bionic_reading", false)
        set(v) = prefs.edit().putBoolean("bionic_reading", v).apply()

    var autoAdvanceChapter: Boolean
        get() = prefs.getBoolean("auto_chapter", true)
        set(v) = prefs.edit().putBoolean("auto_chapter", v).apply()

    // Library view preferences (Grid/List, columns 2-4, sort, open last book)
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
