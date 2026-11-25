package com.holos.reader.ui

import android.content.Context
import com.holos.reader.epub.ReadingStatus
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import java.util.Locale

/**
 * Lightweight zero-restart bilingual localization (Ukrainian & English) with automatic
 * system locale detection. Defaults to Ukrainian when system language is `uk`, English otherwise.
 */
object AppStrings {

    const val LANG_SYSTEM = "SYSTEM"
    const val LANG_UK = "UK"
    const val LANG_EN = "EN"

    fun isEnglish(context: Context): Boolean {
        val pref = ReaderPreferences(context).appLanguage
        return when (pref) {
            LANG_EN -> true
            LANG_UK -> false
            else -> !Locale.getDefault().language.equals("uk", ignoreCase = true)
        }
    }

    fun t(context: Context, uk: String, en: String): String =
        if (isEnglish(context)) en else uk

    fun themeLabel(context: Context, theme: ReaderTheme): String {
        val en = isEnglish(context)
        return when (theme) {
            ReaderTheme.LIGHT -> if (en) "Light" else "Світла"
            ReaderTheme.GRAY -> if (en) "Gray" else "Сіра"
            ReaderTheme.SEPIA -> if (en) "Sepia" else "Сепія"
            ReaderTheme.GREEN -> if (en) "Forest" else "Трава"
            ReaderTheme.DARK -> if (en) "Dark" else "Темна"
            ReaderTheme.CHERRY -> if (en) "Cherry" else "Вишня"
            ReaderTheme.SKY -> if (en) "Sky" else "Небо"
            ReaderTheme.NORD -> "Nord"
            ReaderTheme.AMOLED -> "AMOLED"
        }
    }

    fun statusLabel(context: Context, status: ReadingStatus): String {
        val en = isEnglish(context)
        return when (status) {
            ReadingStatus.UNREAD -> if (en) "Unread" else "Непрочитано"
            ReadingStatus.READING -> if (en) "Reading" else "Читаю"
            ReadingStatus.FINISHED -> if (en) "Finished" else "Прочитано"
        }
    }

    fun languageDisplayName(context: Context, code: String): String {
        val en = isEnglish(context)
        return when (code) {
            LANG_UK -> "Українська"
            LANG_EN -> "English"
            else -> if (en) "System (auto)" else "Системна (авто)"
        }
    }
}
