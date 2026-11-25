package com.holos.reader.g2p

/**
 * 1:1 Kotlin port of patriotyk/ipa-uk (Wiktionary Module:uk-pronunciation).
 * Converts Ukrainian text with stress marks (acute U+0301 / grave U+0300) into IPA phonemes
 * expected by HolosTTS.
 */
object UkrainianIpa {
    const val ACUTE: Char = '\u0301'
    const val GRAVE: Char = '\u0300'

    private const val VOWEL_NO_I = "aɛɪuɔɐoʊe"
    private const val VOWEL = VOWEL_NO_I + "i"
    private const val VOWEL_C = "[$VOWEL]"
    private const val CONSONANT_NO_W = "bdzʒɡɦmnlrpftskxʃj"
    private const val CONSONANT_NO_W_C = "[$CONSONANT_NO_W]"
    private const val CONSONANT = CONSONANT_NO_W + "ʋβ̞wʍ"
    private const val CONSONANT_C = "[$CONSONANT]"
    private const val PALATALIZABLE = "tdsznlrbpʋfɡmkɦxʃʒ"
    private const val PALATALIZABLE_C = "[$PALATALIZABLE]"
    private const val VOICED_OBSTRUENT = "[bdzʒɡɦ]"

    private val GRAVE_MAP = mapOf(
        'ѐ' to "е$GRAVE",
        'Ѐ' to "Е$GRAVE",
        'ѝ' to "и$GRAVE",
        'Ѝ' to "И$GRAVE",
    )

    private val PERM_SYL_ONSET = setOf(
        "spr", "str", "skr", "spl", "skl",
        "sp", "st", "sk", "sf", "sx",
        "pr", "br", "tr", "dr", "kr", "gr", "ɦr", "fr", "xr",
        "pl", "bl", "kl", "gl", "ɦl", "fl", "xl",
    )

    private val VOICING = listOf(
        "p" to "b",
        "f" to "v",
        "t" to "d",
        "tʲ" to "dʲ",
        "s" to "z",
        "sʲ" to "zʲ",
        "ʃ" to "ʒ",
        "k" to "ɡ",
        "x" to "ɦ",
        "t͡s" to "d͡z",
        "t͡sʲ" to "d͡zʲ",
        "t͡ʃ" to "d͡ʒ",
        "ʃt͡ʃ" to "ʒd͡ʒ",
    ).map { (voiceless, voiced) ->
        Regex("$voiceless($VOICED_OBSTRUENT+)") to voiced
    }

    private val ORTHOGRAPHIC_REPLACEMENTS = listOf(
        Regex("нтськ") to "ньськ",
        Regex("стськ") to "ськ",
        Regex("нтст") to "нст",
        Regex("стч") to "шч",
        Regex("стд") to "зд",
        Regex("стс") to "сː",
        Regex("#зш") to "#шː",
        Regex("зш") to "жш",
        Regex("#зч") to "#шч",
        Regex("зч") to "жч",
        Regex("([бвгґд])\\1") to "$1ː",
        Regex("([^д]+)жж") to "$1жː",
        Regex("([^д]+)зз") to "$1зː",
        Regex("([йклмнпрстфхцчшщ])\\1") to "$1ː",
        Regex("дждж") to "джː",
        Regex("дздз") to "дзː",
    )

    private val PHONETIC_CHARS_3 = listOf(
        "дзь" to "d͡zʲ",
        "тьс" to "t͡sʲː",
    )

    private val PHONETIC_CHARS_2 = listOf(
        "дж" to "d͡ʒ",
        "дз" to "d͡z",
        "дс" to "d͡zs",
        "дш" to "d͡ʒʃ",
        "дч" to "d͡ʒt͡ʃ",
        "дц" to "d͡zt͡s",
        "тс" to "t͡s",
        "тш" to "t͡ʃʃ",
        "тч" to "t͡ʃː",
        "тц" to "t͡sː",
    )

    private val PHONETIC_CHARS_1 = listOf(
        "а" to "a",
        "б" to "b",
        "в" to "ʋ",
        "г" to "ɦ",
        "ґ" to "ɡ",
        "д" to "d",
        "е" to "ɛ",
        "є" to "jɛ",
        "ж" to "ʒ",
        "з" to "z",
        "и" to "ɪ",
        "і" to "i",
        "ї" to "ji",
        "й" to "j",
        "к" to "k",
        "л" to "l",
        "м" to "m",
        "н" to "n",
        "о" to "ɔ",
        "п" to "p",
        "р" to "r",
        "с" to "s",
        "т" to "t",
        "у" to "u",
        "ф" to "f",
        "х" to "x",
        "ц" to "t͡s",
        "ч" to "t͡ʃ",
        "ш" to "ʃ",
        "щ" to "ʃt͡ʃ",
        "ь" to "ʲ",
        "ю" to "ju",
        "я" to "ja",
        ACUTE.toString() to "ˈ",
        GRAVE.toString() to "ˌ",
    )

    private val RE_MULTI_SPACE = Regex("\\s+")
    private val RE_SPLIT_WORD = Regex("[\\s\\-]+")
    private val RE_VOWEL_BEFORE_STRESS = Regex("([aɛiɪuɔ])([ˈˌ])")
    private val RE_IPA_VOWEL = Regex("[aɛiɪuɔ]")
    private val RE_PALATAL_I = Regex("($PALATALIZABLE_C)([ː]?)([ˈˌ⁀]?)i")
    private val RE_PALATAL_J = Regex("($PALATALIZABLE_C)([ː]?)j")
    private val RE_STSJ = Regex("st͡sʲ([ː]?)")
    private val RE_SOFT_1 = Regex("([tdszn])(.)ʲ")
    private val RE_SOFT_2 = Regex("([tdsznl])t͡sʲ")
    private val RE_SOFT_3 = Regex("([tdsznl])d͡zʲ")
    private val RE_SOFT_4 = Regex("t͡s(.)ʲ")
    private val RE_SOFT_5 = Regex("d͡z(.)ʲ")
    private val RE_EXCESSIVE_PAL = Regex("([^aɛiɪuɔ]+)ʲ([^aɛiɪuɔ]+)ʲ([^aɛiɪuɔ]+)ʲ")
    private val RE_UNSTRESSED_A = Regex("([^ˈˌ⁀])a")
    private val RE_UNSTRESSED_U = Regex("([^ˈˌ⁀])u")
    private val RE_UNSTRESSED_O = Regex("ɔ([bdzʒɡɦmnlrpftskxʲʃ͡]+)([ˈˌ⁀][uiʊ])")
    private val RE_UNSTRESSED_E = Regex("([^ˈˌ⁀])[ɛ]")
    private val RE_V_CODA = Regex("($VOWEL_C)ʋ([$CONSONANT_NO_W#])")
    private val RE_V_VOICED = Regex("ʋ([ˈˌ]?[ɔuoʊbdzʒɡɦmnlr])")
    private val RE_V_VOICELESS = Regex("ʋ([pftskxʃ])")
    private val RE_J_CODA = Regex("($VOWEL_C)j([$CONSONANT_NO_W#])")
    private val RE_J_INIT = Regex("#j($CONSONANT_NO_W_C)")
    private val RE_STRESS_1 = Regex("([^#$VOWEL]?[ʲː]*)([ˈˌ])")
    private val RE_STRESS_2 = Regex("([^#$VOWEL]͡)([ˈˌ])")
    private val RE_ONSET = Regex("(.)(ʲ?)($CONSONANT_C)(ʲ?)([ˈˌ])($CONSONANT_C)")
    private val RE_STRESS_4A = Regex("([^#$VOWEL]͡)([ˈˌ])(.ʲ?j)")
    private val RE_STRESS_4B = Regex("([^#$VOWEL]͡)([ˈˌ])(.ʲ?)")
    private val RE_STRESS_5 = Regex("#([^#$VOWEL]+)([ˈˌ])")
    private val RE_STRESS_6 = Regex("#([ui]̯)([ˈˌ])")
    private val RE_CLEAN_LEN = Regex("ʲ?ːʲ")
    private val RE_DARK_L = Regex("l([^ʲ])")

    fun decomposeGrave(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val mapped = GRAVE_MAP[ch]
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Converts stressed Ukrainian text to IPA phonemes.
     * Note: When [checkAccent] is true, monosyllabic words get stressed vowel allophones
     * (matching patriotyk/ipa-uk). For TTS we default [monosyllabicAllophones] = true
     * without throwing on unstressed words.
     */
    fun transcribe(input: String, monosyllabicAllophones: Boolean = true): String {
        val text = RE_MULTI_SPACE.replace(decomposeGrave(input).lowercase(), " ")
        val parts = RE_SPLIT_WORD.split(text)
        val out = ArrayList<String>(parts.size)

        for (rawWord in parts) {
            var phonetic = "#$rawWord#"

            for ((regex, repl) in ORTHOGRAPHIC_REPLACEMENTS) {
                phonetic = regex.replace(phonetic, repl)
            }

            // Remap all Ukrainian apostrophe variants to '%' so consonants stay hard before я/ю/є/ї
            phonetic = phonetic.replace('\'', '%').replace('’', '%').replace('ʼ', '%').replace('`', '%')

            for ((k, v) in PHONETIC_CHARS_3) phonetic = phonetic.replace(k, v)
            for ((k, v) in PHONETIC_CHARS_2) phonetic = phonetic.replace(k, v)
            for ((k, v) in PHONETIC_CHARS_1) phonetic = phonetic.replace(k, v)

            phonetic = RE_VOWEL_BEFORE_STRESS.replace(phonetic, "$2$1")

            if (monosyllabicAllophones) {
                val vowelCount = RE_IPA_VOWEL.findAll(phonetic).count()
                if (vowelCount == 1) {
                    phonetic = RE_IPA_VOWEL.replace(phonetic, "⁀$0")
                }
            }

            phonetic = RE_PALATAL_I.replace(phonetic, "$1ʲ$2$3i")
            phonetic = RE_PALATAL_J.replace(phonetic, "$1ʲ$2")
            phonetic = phonetic.replace("ʲːj", "ʲː")
            phonetic = RE_STSJ.replace(phonetic, "sʲt͡sʲ")

            for ((regex, voiced) in VOICING) {
                phonetic = regex.replace(phonetic) { m -> voiced + m.groupValues[1] }
            }

            phonetic = RE_SOFT_1.replace(phonetic, "$1ʲ$2ʲ")
            phonetic = RE_SOFT_2.replace(phonetic, "$1ʲt͡sʲ")
            phonetic = RE_SOFT_3.replace(phonetic, "$1ʲd͡zʲ")
            phonetic = RE_SOFT_4.replace(phonetic, "t͡sʲ$1ʲ")
            phonetic = RE_SOFT_5.replace(phonetic, "d͡zʲ$1ʲ")
            phonetic = phonetic.replace("d͡zt͡sʲ", "d͡zʲt͡sʲ")
            phonetic = phonetic.replace("t͡sd͡zʲ", "t͡sʲd͡zʲ")

            phonetic = phonetic.replace("ʒt͡sʲ", "zʲt͡sʲ")
            phonetic = phonetic.replace("t͡ʃt͡sʲ", "t͡sʲː")
            phonetic = phonetic.replace("ʃt͡sʲ", "sʲt͡sʲ")
            phonetic = phonetic.replace("ʃsʲ", "sʲː")

            phonetic = phonetic.replace("zʒ", "ʒː")
            phonetic = phonetic.replace("sʃ", "ʃː")
            phonetic = phonetic.replace("zt͡ʃ", "ʒt͡ʃ")
            phonetic = phonetic.replace("zd͡ʒ", "ʒd͡ʒ")
            phonetic = phonetic.replace("t͡ʒ", "d͡ʒ")
            phonetic = phonetic.replace("t͡z", "d͡z")

            phonetic = RE_EXCESSIVE_PAL.replace(phonetic, "$1$2ʲ$3ʲ")

            phonetic = RE_UNSTRESSED_A.replace(phonetic, "$1ɐ")
            phonetic = RE_UNSTRESSED_U.replace(phonetic, "$1ʊ")
            phonetic = RE_UNSTRESSED_O.replace(phonetic, "o$1$2")
            phonetic = RE_UNSTRESSED_E.replace(phonetic, "$1e")

            phonetic = phonetic.replace("⁀", "")

            phonetic = RE_V_CODA.replace(phonetic, "$1u̯$2")
            phonetic = RE_V_VOICED.replace(phonetic, "w$1")
            phonetic = RE_V_VOICELESS.replace(phonetic, "ʍ$1")

            phonetic = RE_J_CODA.replace(phonetic, "$1i̯$2")
            phonetic = RE_J_INIT.replace(phonetic, "#i̯$1")

            phonetic = phonetic.replace("%", "")

            phonetic = RE_STRESS_1.replace(phonetic, "$2$1")
            phonetic = RE_STRESS_2.replace(phonetic, "$2$1")

            phonetic = RE_ONSET.replace(phonetic) { m ->
                val a = m.groupValues[1]
                val aj = m.groupValues[2]
                val b = m.groupValues[3]
                val bj = m.groupValues[4]
                val stress = m.groupValues[5]
                val c = m.groupValues[6]
                val cluster3 = a + b + c
                when {
                    cluster3 in PERM_SYL_ONSET -> "$stress$a$aj$b$bj$c"
                    (b + c in PERM_SYL_ONSET) || (c == "j") -> "$a$aj$stress$b$bj$c"
                    else -> "$a$aj$b$bj$stress$c"
                }
            }

            phonetic = RE_STRESS_4A.replace(phonetic, "$2$1$3")
            phonetic = RE_STRESS_4B.replace(phonetic, "$1$3$2")
            phonetic = RE_STRESS_5.replace(phonetic, "#$2$1")
            phonetic = RE_STRESS_6.replace(phonetic, "#$2$1")

            phonetic = RE_CLEAN_LEN.replace(phonetic, "ʲː")
            phonetic = RE_DARK_L.replace(phonetic, "ɫ$1")

            out.add(phonetic)
        }

        return out.joinToString(" ").replace("#", "")
    }
}
