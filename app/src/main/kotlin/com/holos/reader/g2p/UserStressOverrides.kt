package com.holos.reader.g2p

import java.io.File

/**
 * User-editable pronunciation & stress overrides (`stress_user.txt`), ideal for light novel /
 * fantasy character names, skills, and homographs.
 *
 * Thread-safe via atomic immutable snapshot swaps (`@Volatile`).
 * Supports:
 * - `ка+ва`             -> marks stress on the vowel before `+` (or after `+` if placed before a vowel)
 * - `Сюань=Сюа+нь`      -> replaces whole word with custom stressed spelling
 * - `Сяо Янь=Ся+о Янь`  -> multi-word phrase override applied before word tokenization
 * - `# comment`         -> ignored
 */
class UserStressOverrides {

    @Volatile
    private var exactWordMap: Map<String, String> = emptyMap()

    @Volatile
    private var phraseRules: List<Pair<Regex, String>> = emptyList()

    @Volatile
    private var rawLinesText: String = DEFAULT_TEMPLATE

    fun loadFromFile(file: File) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_TEMPLATE, Charsets.UTF_8)
        }
        loadFromText(file.readText(Charsets.UTF_8))
    }

    fun loadFromText(text: String) {
        val nextWords = LinkedHashMap<String, String>()
        val nextPhrases = ArrayList<Pair<Regex, String>>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if ('=' in line) {
                val (keyRaw, valRaw) = line.split('=', limit = 2)
                val key = normalizeKey(keyRaw.trim())
                val replacement = convertPlusToAcute(valRaw.trim())
                if (key.isNotEmpty() && replacement.isNotEmpty()) {
                    if (key.any { it.isWhitespace() }) {
                        val pattern = Regex("(?iu)(?<![\\p{L}])" + Regex.escape(key) + "(?![\\p{L}])")
                        nextPhrases.add(pattern to replacement)
                    } else {
                        nextWords[key] = replacement
                    }
                }
            } else if ('+' in line) {
                val stressed = convertPlusToAcute(line)
                val base = normalizeKey(line.replace("+", ""))
                if (base.isNotEmpty()) {
                    nextWords[base] = stressed
                }
            }
        }
        // Longer phrases first so specific multi-word rules win over shorter substrings
        nextPhrases.sortByDescending { it.first.pattern.length }
        rawLinesText = text
        exactWordMap = nextWords
        phraseRules = nextPhrases
    }

    fun getRawText(): String = rawLinesText

    fun lookup(wordLower: String): String? = exactWordMap[normalizeKey(wordLower)]

    fun applyPhraseOverrides(text: String): String {
        val rules = phraseRules
        if (rules.isEmpty()) return text
        var result = text
        for ((re, repl) in rules) {
            result = re.replace(result, Regex.escapeReplacement(repl))
        }
        return result
    }

    fun size(): Int = exactWordMap.size + phraseRules.size

    companion object {
        private const val ACUTE = '\u0301'
        private const val VOWELS = "аеєиіїоуюяАЕЄИІЇОУЮЯ"
        private val RE_APOS = Regex("[’`ʼ]")

        private fun normalizeKey(raw: String): String =
            RE_APOS.replace(raw.lowercase(), "'")

        val DEFAULT_TEMPLATE = """
            # Словник власних наголосів та вимови HolosReader
            # Формат 1 (наголос через + після голосної): ка+ва -> ка́ва
            # Формат 2 (повна заміна слова або фрази): Сяо Янь=Ся+о Янь
            # Приклади (розкоментуйте, прибравши #):
            # за+мок
            # рано+бе
            # ісека+й
        """.trimIndent() + "\n"

        /**
         * Converts `+` stress notation into Unicode acute U+0301.
         * Supports both `ка+ва` (after vowel) and `к+ава` (before vowel, moved to next vowel).
         */
        fun convertPlusToAcute(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                if (ch == '+') {
                    if (sb.isNotEmpty() && sb[sb.length - 1] in VOWELS) {
                        sb.append(ACUTE)
                    } else if (i + 1 < input.length && input[i + 1] in VOWELS) {
                        sb.append(input[i + 1]).append(ACUTE)
                        i++
                    }
                } else {
                    sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }
    }
}
