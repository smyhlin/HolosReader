package com.holos.reader.tts

/**
 * Extendable multilingual span router for Supertonic 3.
 *
 * When [englishPronunciationEnabled] is active (Experimental), detects embedded English
 * words and phrases inside Ukrainian prose (e.g., LitRPG skills, UI terms, names like
 * "Shadow Step", "Level Up", "HP") and splits the utterance into `(text, lang)` spans so
 * Supertonic 3 renders them with native `<en>…</en>` pronunciation while keeping the
 * surrounding Ukrainian prose in `<uk>…</uk>`.
 */
object SupertonicLanguageSplitter {

    data class LanguageSpan(
        val text: String,
        val lang: String,
    )

    private val LATIN_WORD_RE = Regex("[A-Za-z][A-Za-z0-9'’-]*")
    private val CYRILLIC_CHAR_RE = Regex("[\\p{IsCyrillic}]")
    private val ROMAN_NUMERALS = setOf(
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XX",
    )

    fun split(text: String, englishPronunciationEnabled: Boolean): List<LanguageSpan> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (!englishPronunciationEnabled) {
            return listOf(LanguageSpan(clean, "uk"))
        }

        val tokens = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val classified = tokens.map { tok -> tok to classifyToken(tok) }

        val merged = ArrayList<LanguageSpan>()
        var currentLang = classified.first().second
        val buf = StringBuilder()

        for ((tok, lang) in classified) {
            val effectiveLang = if (lang == "neutral") currentLang else lang
            if (buf.isEmpty()) {
                currentLang = effectiveLang
                buf.append(tok)
            } else if (effectiveLang == currentLang) {
                buf.append(' ').append(tok)
            } else {
                val piece = buf.toString().trim()
                if (piece.isNotEmpty()) merged.add(LanguageSpan(piece, currentLang))
                buf.clear()
                currentLang = effectiveLang
                buf.append(tok)
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) merged.add(LanguageSpan(tail, currentLang))

        return merged.map { span ->
            if (span.lang == "neutral") span.copy(lang = "uk") else span
        }
    }

    private fun classifyToken(token: String): String {
        val stripped = token.trim { !it.isLetterOrDigit() }
        if (stripped.isEmpty()) return "neutral"
        if (CYRILLIC_CHAR_RE.containsMatchIn(stripped)) return "uk"
        if (stripped in ROMAN_NUMERALS) return "uk"
        if (LATIN_WORD_RE.matches(stripped) && stripped.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            if (stripped.length == 1 && stripped != "a" && stripped != "A") return "uk"
            return "en"
        }
        return "neutral"
    }
}
