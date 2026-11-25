package com.holos.reader.g2p

import java.io.File

/**
 * User-editable pronunciation & stress overrides (`stress_user.txt`), ideal for light novel /
 * fantasy character names, skills, and homographs.
 *
 * Supported line formats:
 * - `ка+ва`             -> marks stress on the vowel before `+` (or after `+` if placed before a vowel)
 * - `Сюань=Сюа+нь`      -> replaces whole word with custom stressed spelling
 * - `HP=е+йч пі+`       -> custom multi-word pronunciation override
 * - `# comment`         -> ignored
 */
class UserStressOverrides {

    private val exactWordMap = LinkedHashMap<String, String>()
    private var rawLinesText: String = DEFAULT_TEMPLATE

    fun loadFromFile(file: File) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_TEMPLATE, Charsets.UTF_8)
        }
        loadFromText(file.readText(Charsets.UTF_8))
    }

    fun loadFromText(text: String) {
        rawLinesText = text
        exactWordMap.clear()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if ('=' in line) {
                val (keyRaw, valRaw) = line.split('=', limit = 2)
                val key = keyRaw.trim().lowercase()
                val replacement = convertPlusToAcute(valRaw.trim())
                if (key.isNotEmpty() && replacement.isNotEmpty()) {
                    exactWordMap[key] = replacement
                }
            } else if ('+' in line) {
                val stressed = convertPlusToAcute(line)
                val base = line.replace("+", "").lowercase()
                if (base.isNotEmpty()) {
                    exactWordMap[base] = stressed
                }
            }
        }
    }

    fun getRawText(): String = rawLinesText

    fun lookup(wordLower: String): String? = exactWordMap[wordLower]

    fun size(): Int = exactWordMap.size

    companion object {
        private const val ACUTE = '\u0301'
        private const val VOWELS = "аеєиіїоуюяАЕЄИІЇОУЮЯ"

        val DEFAULT_TEMPLATE = """
            # Словник власних наголосів та вимови HolosReader
            # Формат 1 (наголос через + після голосної): ка+ва -> ка́ва
            # Формат 2 (повна заміна): Сяо Янь=Ся́о Янь
            # Приклади:
            за+мок
            рано+бе
            ісека+й
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
