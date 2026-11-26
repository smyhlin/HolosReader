package com.holos.reader.g2p

import java.text.Normalizer

/**
 * Production Ukrainian Book / Light Novel / LitRPG Text Normalizer & Symbol Verbalizer
 * aligned with `spaces/patriotyk/HolosTTS/app.py` prosody conventions:
 *
 * - Silently strips decorative scene-break dividers (`***`, `~~~`, `───`, `❖ ❖ ❖`)
 * - Converts star ratings (`★★★`, `5★`, `★★★☆☆`) with exact Ukrainian plural grammar
 * - Expands Ukrainian dates (`22.08.2025` -> `двадцять другого серпня дві тисячі двадцять п'ятого року`)
 * - Expands time (`15:30`), ordinals (`1-й`, `2-га`, `3-тє`), Roman chapters (`Розділ IV`)
 * - Expands currencies (`$`, `€`, `₴`, `£`, `¥`, `₩`), percentages (`%`), permille (`‰`), degrees (`°C`)
 * - Expands RPG/math/status symbols (`+50`, `-20`, `±5`, `×3`, `120/150`, `10 → 25`, `≤`, `≥`, `∞`, `✓`, `✗`, `⚔`, `🛡`, `⚡`, `🔥`)
 * - Preserves inline `+` stress notation from patriotyk's Space (`Му+дрого` -> `Му́дрого`)
 * - Applies Space prosody rules: NFKC normalization, dash-to-colon pause (` - ` -> `: `), and terminal punctuation
 */
object UkrainianTextNormalizer {

    private const val ACUTE = '\u0301'

    private val RE_INVISIBLE = Regex("[\u00AD\u200B\u200C\u200D\uFEFF]")
    private val RE_SCENE_BREAK = Regex("^[\\s*~=_\\-─━═❖◆◇●○■□✦✧❦❧•·▪▫★☆]+$")
    private val RE_SPACES = Regex("[\\s\\u00A0]+")

    // Inline `+` stress after a Ukrainian vowel (e.g. "Му+дрого", "Русі+")
    private val RE_INLINE_PLUS_STRESS = Regex("([аеєиіїоуюяАЕЄИІЇОУЮЯ])\\+(?=[а-щьюяґєіїА-ЩЬЮЯҐЄІЇ'’]|$|\\s|[.,!?;:\\-—–])")

    // Star ratings: e.g. ★★★☆☆, ★★★, 5★
    private val RE_MIXED_STARS = Regex("([★⭐✪✦]+)([☆✧]+)")
    private val RE_RUN_STARS = Regex("[★⭐✪✦]{2,}")
    private val RE_NUM_STARS = Regex("(?<!\\d)(\\d+)\\s*[★⭐✪✦]")

    // Dates DD.MM.YYYY (e.g., 22.08.2025)
    private val RE_DATE_FULL = Regex("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)")

    // Roman numerals after chapter/skill/rank/title nouns (supports Latin I/V/X and Cyrillic І/ІІ/ІІІ)
    private val RE_CHAPTER_ROMAN = Regex(
        "(?<![\\p{L}])((?iu:розділ[уаі]?|глав[аиіу]?|том[уаі]?|книг[аиу]?|частин[аиу]?|акт[уаі]?|епізод[уаі]?|" +
            "навичк[аиу]?|вміння|рівень|рівн[яюі]|ранг[уаі]?|ступ[іе]н[ьяюі]?|етап[уаі]?|стаді[яюї]|фаз[аиу]?|" +
            "клас[уаі]?|тір[уаі]?|тир[уаі]?|поверх[уаі]?|кол[оау]|сфер[аиу]?|печатк[аиу]?|форм[аиу]?|версі[яюї]|" +
            "покоління|століття|сцен[аиу]?|ді[яюї]|пункт[уаі]?|арк[аиу]?|квест[уаі]?|ліг[аиу]?|орден[уаі]?|" +
            "легіон[уаі]?|сері[яюї]|модел[ьі]|тип[уаі]?|марк[аиу]?|категорі[яюї]|груп[аиу]?|сектор[уаі]?|" +
            "зон[аиу]?|блок[уаі]?|веж[аіу]|епох[аиу]?|ер[аиу]|вік[уаі]?|корол[ььяюі]|імператор[ауі]?|цар[ььяюі]?|" +
            "княз[ььяюі]?|герцог[ауі]?|пап[аиу]|патріарх[ауі]?|лорд[ауі]?|майст[е]?р[ауі]?|маг[ауі]?|святий))\\s+([IVXLCDMІ]+)(?![\\p{L}])"
    )
    // Standalone uppercase multi-char Roman numerals (II, III, IV, VI, VII, VIII, IX, XI..XX, or Cyrillic ІІ, ІІІ)
    private val RE_STANDALONE_ROMAN = Regex(
        "(?<![\\p{L}])(II|III|IV|VI|VII|VIII|IX|XI|XII|XIII|XIV|XV|XVI|XVII|XVIII|XIX|XX|ІІ|ІІІ)(?![\\p{L}])"
    )
    // Standalone Latin capital I (U+0049) surrounded by Ukrainian context (e.g. "I «...»", "I:", "— I.")
    private val RE_LATIN_I_NUMERAL = Regex(
        """(?<![A-Za-zА-ЩЬЮЯҐЄІЇа-щьюяґєії])I(?=[\s]*[«»"„“”:.,;!?\)\]]|\s+[А-ЩЬЮЯҐЄІЇа-щьюяґєії])"""
    )

    // Ukrainian hyphenated ordinals (supports ASCII hyphen, non-breaking hyphen U+2011, en/em-dash, minus; 0..999999)
    private val RE_ORDINAL = Regex(
        "(?iu)(?<!\\d)(\\d{1,6})[-\u2010\u2011\u2012\u2013\u2014\u2212]" +
            "(ього|ого|того|шого|ьої|ої|тьої|тої|шої|ьому|ому|тому|шому|ьою|ою|тьою|тою|шою|ими|іми|тими|тіми|шими|" +
            "тий|тій|ший|гий|ий|ій|тим|тім|шим|гим|им|ім|тих|тіх|ших|гих|их|іх|" +
            "го|му|ми|ша|га|тя|та|тє|те|ше|ге|ті|ші|гі|ту|тю|шу|гу|й|а|я|е|є|ї|у|ю|і|м|х)(?![\\p{L}])"
    )

    // Time HH:MM
    private val RE_TIME = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")

    // Currencies ($100 or 100$, €50, ₴500, £20, ¥1000)
    private val RE_CURR_PREFIX = Regex("([$€₴£¥₩₿])\\s*(\\d+(?:[.,]\\d+)?)")
    private val RE_CURR_SUFFIX = Regex("(?<!\\d)(\\d+(?:[.,]\\d+)?)\\s*([$€₴£¥₩₿])")

    // RPG stat transitions (10 -> 25, 10 → 25, Lv.5 ⇒ Lv.6)
    private val RE_ARROW_TRANSITION = Regex("\\s*(?:->|=>|→|⇒|➜|➔|⟶)\\s*")

    // HP/MP/XP fractions (120/150)
    private val RE_FRACTION = Regex("(?<!\\d)(\\d+)\\s*/\\s*(\\d+)(?!\\d)")

    // Multipliers (×3, x3, 3×, 3x)
    private val RE_MULT_PREFIX = Regex("(?<!\\p{L})[×xX]\\s*(\\d+(?:[.,]\\d+)?)")
    private val RE_MULT_SUFFIX = Regex("(?<!\\d)(\\d+(?:[.,]\\d+)?)\\s*[×xX](?!\\p{L})")

    // Degrees (°C, °F, °), Percentages (%), Permille (‰), Superscript powers
    private val RE_DEG_C = Regex("(?<!\\d)([+-]?\\d+(?:[.,]\\d+)?)\\s*°\\s*[CcСс]")
    private val RE_DEG_F = Regex("(?<!\\d)([+-]?\\d+(?:[.,]\\d+)?)\\s*°\\s*[Ff]")
    private val RE_DEG = Regex("(?<!\\d)([+-]?\\d+(?:[.,]\\d+)?)\\s*°")
    private val RE_PERCENT = Regex("(\\d+[.,]\\d+|\\d+)\\s*%")
    private val RE_PERMILLE = Regex("(\\d+[.,]\\d+|\\d+)\\s*‰")

    // Signed deltas (+50, -20, ±5)
    private val RE_PLUS_MINUS = Regex("±\\s*(\\d+(?:[.,]\\d+)?)")
    private val RE_POS_DELTA = Regex("(?<=\\s|^|\\(|\\[)\\+(\\d+(?:[.,]\\d+)?)")
    private val RE_NEG_DELTA = Regex("(?<=\\s|^|\\(|\\[)-(\\d+(?:[.,]\\d+)?)")

    private val RE_DECIMAL = Regex("(?<!\\d)(\\d+)[.,](\\d+)(?!\\d)")
    private val RE_RANGE = Regex("(?<!\\d)(\\d+)\\s*[–—-]\\s*(\\d+)(?!\\d)")
    private val RE_HASH_NUM = Regex("#(\\d+)")
    private val RE_INT = Regex("\\d+")
    private val RE_LATIN_WORD = Regex("[A-Za-z]+")
    private val RE_ALL_DASHES = Regex("[\\u1806\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u207B\\u208B\\u2212\\u2E3A\\u2E3B]")

    private val ABBREVIATIONS = listOf(
        Regex("(?iu)(?<![\\p{L}])і т\\.\\s*д\\.") to "і так далі",
        Regex("(?iu)(?<![\\p{L}])і т\\.\\s*п\\.") to "і тому подібне",
        Regex("(?iu)(?<![\\p{L}])т\\.\\s*д\\.") to "так далі",
        Regex("(?iu)(?<![\\p{L}])т\\.\\s*п\\.") to "тому подібне",
        Regex("(?iu)(?<![\\p{L}])н\\.\\s*е\\.") to "нашої ери",
        Regex("(?iu)(?<![\\p{L}])до н\\.\\s*е\\.") to "до нашої ери",
        Regex("(?iu)(?<![\\p{L}])тис\\.(?=\\s|$|,)") to "тисяч",
        Regex("(?iu)(?<![\\p{L}])млн(?![\\p{L}])\\.?") to "мільйонів",
        Regex("(?iu)(?<![\\p{L}])млрд(?![\\p{L}])\\.?") to "мільярдів",
        Regex("(?iu)(?<![\\p{L}])рр\\.(?=\\s|$|,)") to "років",
        Regex("(?<=\\d)\\s*р\\.(?=\\s|$|,)") to " року",
        Regex("(?<=\\d)\\s*ст\\.(?=\\s|$|,)") to " століття",
        Regex("(?<=\\d)\\s*км(?![\\p{L}])\\.?") to " кілометрів",
        Regex("(?<=\\d)\\s*см(?![\\p{L}])\\.?") to " сантиметрів",
        Regex("(?<=\\d)\\s*мм(?![\\p{L}])\\.?") to " міліметрів",
        Regex("(?<=\\d)\\s*кг(?![\\p{L}])\\.?") to " кілограмів",
        Regex("(?<=\\d)\\s*хв(?![\\p{L}])\\.?") to " хвилин",
        Regex("(?<=\\d)\\s*сек(?![\\p{L}])\\.?") to " секунд",
        Regex("(?<=\\d)\\s*с\\.(?=\\s|$|,|;|\\)|!|\\?)") to " секунд",
        Regex("(?iu)(?<![\\p{L}])макс\\.") to "максимального",
        Regex("(?iu)(?<![\\p{L}])мін\\.") to "мінімального",
        Regex("(?iu)(?<![\\p{L}])прибл\\.") to "приблизно",
        Regex("(?<=\\d)\\s*год(?![\\p{L}])\\.?") to " годин",
        // Popular LitRPG / web-novel abbreviations
        Regex("\\bHP\\b") to "ейч-пі",
        Regex("\\bMP\\b") to "ем-пі",
        Regex("\\bSP\\b") to "ес-пі",
        Regex("\\bXP\\b") to "ікс-пі",
        Regex("\\bEXP\\b") to "експа",
        Regex("\\bDPS\\b") to "ді-пі-ес",
        Regex("\\bAOE\\b", RegexOption.IGNORE_CASE) to "аое",
        Regex("\\b(?:LVL|Lv)(?=\\.|\\s|\\d|$)", RegexOption.IGNORE_CASE) to "рівень ",
        Regex("\\bNPC\\b") to "ен-пі-сі",
        Regex("\\bRPG\\b") to "ар-пі-джі",
        Regex("\\bVR\\b") to "ві-ар",
        Regex("\\bAI\\b") to "ей-ай",
    )

    // Standalone Unicode/ASCII symbols frequently used in light novels & system windows
    private val SYMBOL_WORDS = listOf(
        "≠" to " не дорівнює ",
        "≈" to " приблизно ",
        "≤" to " менше або дорівнює ",
        "≥" to " більше або дорівнює ",
        "÷" to " поділити на ",
        "∞" to " нескінченність ",
        "√" to " корінь з ",
        "²" to " в квадраті ",
        "³" to " в кубі ",
        "№" to " номер ",
        "§" to " параграф ",
        "©" to " копірайт ",
        "™" to " торгова марка ",
        "↑" to " вгору ",
        "↓" to " вниз ",
        "←" to " вліво ",
        "⇐" to " вліво ",
        "↔" to " між ",
        "✓" to " виконано ",
        "✔" to " виконано ",
        "☑" to " виконано ",
        "✗" to " провалено ",
        "✘" to " провалено ",
        "☒" to " провалено ",
        "★" to " зірка ",
        "⭐" to " зірка ",
        "✪" to " зірка ",
        "✦" to " зірка ",
        "☆" to " порожня зірка ",
        "✧" to " порожня зірка ",
        "⚔" to " бій ",
        "🛡" to " захист ",
        "⚡" to " блискавка ",
        "🔥" to " вогонь ",
        "❄" to " лід ",
        "💀" to " смерть ",
        "❤" to " серце ",
        "♥" to " серце ",
        "👑" to " корона ",
        "💎" to " діамант ",
        "⚠" to " увага ",
        "&" to " і ",
        "=" to " дорівнює ",
        "<" to " менше ",
        ">" to " більше ",
        "@" to " собачка ",
        // Silent decorative symbols (music notes, bullets, brackets, quotes)
        "♪" to " ", "♫" to " ", "🎵" to " ", "🎶" to " ",
        "•" to " ", "▪" to " ", "❖" to " ", "◆" to " ", "◇" to " ",
        "«" to " ", "»" to " ", "„" to " ", "“" to " ", "”" to " ", "\"" to " ",
        "[" to " ", "]" to " ", "{" to " ", "}" to " ", "【" to " ", "】" to " ",
        "「" to " ", "」" to " ", "『" to " ", "』" to " ", "《" to " ", "》" to " ",
    )


    private val RE_HYPHEN_STRETCH = Regex("""(?<![\p{L}-])([А-ЯІЇЄҐа-яіїєґ]{1,6}(?:-[А-ЯІЇЄҐа-яіїєґ]{1,6}){1,})(?![\p{L}-])""")
    private val RE_LAUGH_REPEAT = Regex("""(?<![\p{L}-])((?:[Хх][аеіо]|[Гг]і|[Мм]у|[Бб]у)(?:-(?:[Хх][аеіо]|[Гг]і|[Мм]у|[Бб]у)){3,})(?![\p{L}-])""")
    private val RE_LONG_LETTER_RUN = Regex("""([А-ЯІЇЄҐа-яіїєґ])\1{3,}""")
    private const val UKR_VOWELS_ALL = "аеєиіїоуюяАЕЄИІЇОУЮЯ"

    /**
     * Collapses hyphen-stretched screams/groans (`А-А-А-АРГХ!` -> `Аааргх!`,
     * `М-м-м-гх-х-х-а-а-а…` -> `Ммгххааа…`), single-letter stutters (`Щ-що?!` -> `Що?!`),
     * and excessive laughter repeats (`Хе-хе-хе-хе` -> `Хе-хе-хе`) so HolosTTS
     * voices them smoothly instead of spelling out isolated letters.
     */
    internal fun normalizeStretchedInterjections(input: String): String {
        var text = input

        // 1. Cap excessive laughter repetitions (4+ identical syllables -> 3)
        text = RE_LAUGH_REPEAT.replace(text) { m ->
            m.value.split('-').take(3).joinToString("-")
        }

        // 2. Hyphen-stretched screams/groans & single-letter stutters (`А-А-А-АРГХ`, `М-м-м-гх-х-х-а-а-а`, `Щ-що`, `Кх-х`)
        text = RE_HYPHEN_STRETCH.replace(text) { m ->
            val parts = m.value.split('-')
            val isStutterWord = parts.size == 2 && parts[0].length == 1 && parts[1].length >= 2 &&
                parts[0][0].lowercaseChar() == parts[1][0].lowercaseChar() &&
                !m.value.equals("з-за", ignoreCase = true)
            val isLongStretch = parts.size >= 3 && parts.count { it.length <= 2 } >= 2
            val isTrailingEcho = parts.size == 2 && parts[1].length <= 2 &&
                parts[0].last().lowercaseChar() == parts[1].first().lowercaseChar()
            when {
                isStutterWord -> parts[0] + parts[1].substring(1)
                isLongStretch || isTrailingEcho -> {
                    val joined = buildString {
                        parts.forEachIndexed { idx, part ->
                            if (idx == 0) {
                                append(part.first())
                                if (part.length > 1) append(part.substring(1).lowercase())
                            } else {
                                append(part.lowercase())
                            }
                        }
                    }
                    capRepeatedLetters(joined)
                }
                else -> m.value
            }
        }

        // 4. Non-hyphenated 4+ letter runs (`Аггггх` -> `Аггх`, `Ааааа` -> `Ааа`)
        text = RE_LONG_LETTER_RUN.replace(text) { m ->
            val ch = m.groupValues[1]
            val cap = if (ch[0] in UKR_VOWELS_ALL) 3 else 2
            ch + ch.lowercase().repeat(cap - 1)
        }
        return text
    }

    private fun capRepeatedLetters(s: String): String {
        if (s.length <= 2) return s
        val out = StringBuilder(s.length)
        var runChar = '\u0000'
        var runCount = 0
        for (ch in s) {
            val lower = ch.lowercaseChar()
            if (lower == runChar) {
                runCount++
                val maxRun = if (ch in UKR_VOWELS_ALL) 3 else 2
                if (runCount <= maxRun) out.append(ch)
            } else {
                runChar = lower
                runCount = 1
                out.append(ch)
            }
        }
        return out.toString()
    }

    fun normalize(raw: String, ensureTerminalPunctuation: Boolean = false): String {
        var text = RE_INVISIBLE.replace(raw, "").trim()
        if (text.isEmpty() || RE_SCENE_BREAK.matches(text)) return ""

        // 1. Preserve patriotyk Space inline `+` stress (e.g. "Му+дрого" -> "Му́дрого") BEFORE math `+`
        text = RE_INLINE_PLUS_STRESS.replace(text, "$1$ACUTE")

        // 1b. Expand '№', '²', '³', '™' BEFORE NFKC (NFKC decomposes №->No, ²->2, ³->3, ™->TM)
        text = text
            .replace("№", " номер ")
            .replace("²", " в квадраті ")
            .replace("³", " в кубі ")
            .replace("™", " торгова марка ")

        // 2. NFKC normalization (matches spaces/patriotyk/HolosTTS/app.py), preserving combining acute
        text = Normalizer.normalize(text, Normalizer.Form.NFKC)
        text = text.replace("...", "…")

        // 2b. Collapse stretched screams/groans & stutters before dash/prosody rules
        text = normalizeStretchedInterjections(text)

        // 3. Star ratings & ranks (★★★☆☆, ★★★, 5★)
        text = RE_MIXED_STARS.replace(text) { m ->
            val filled = m.groupValues[1].length.toLong()
            val total = (m.groupValues[1].length + m.groupValues[2].length).toLong()
            val totalGen = when (total.toInt()) {
                1 -> "однієї"; 2 -> "двох"; 3 -> "трьох"; 4 -> "чотирьох"; 5 -> "п'яти"
                6 -> "шести"; 7 -> "семи"; 8 -> "восьми"; 9 -> "дев'яти"; 10 -> "десяти"
                else -> numberToWords(total)
            }
            " ${numberToWords(filled, feminine = true)} з $totalGen ${pluralForm(total, "зірки", "зірок", "зірок")} "
        }
        text = RE_NUM_STARS.replace(text) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 1L
            " ${numberToWords(n, feminine = true)} ${pluralForm(n, "зірка", "зірки", "зірок")} "
        }
        text = RE_RUN_STARS.replace(text) { m ->
            val n = m.value.length.toLong()
            " ${numberToWords(n, feminine = true)} ${pluralForm(n, "зірка", "зірки", "зірок")} "
        }

        // 4. Full dates DD.MM.YYYY (e.g. 22.08.2025 -> двадцять другого серпня дві тисячі двадцять п'ятого року)
        text = RE_DATE_FULL.replace(text) { m ->
            val day = m.groupValues[1].toIntOrNull()
            val month = m.groupValues[2].toIntOrNull()
            val year = m.groupValues[3].toIntOrNull()
            if (day != null && month != null && year != null && day in 1..31 && month in 1..12) {
                "${dayGenitive(day)} ${MONTHS_GEN[month - 1]} ${yearGenitive(year)} року"
            } else m.value
        }

        // 5. Roman numerals (Latin & Cyrillic І/ІІ/ІІІ) after nouns + standalone II/III/IV..XX + Latin I
        text = RE_CHAPTER_ROMAN.replace(text) { m ->
            val roman = m.groupValues[2].uppercase().replace('І', 'I')
            val num = parseRoman(roman)
            if (num != null && num in 1..3999) "${m.groupValues[1]} $num" else m.value
        }
        text = RE_STANDALONE_ROMAN.replace(text) { m ->
            val roman = m.groupValues[1].replace('І', 'I')
            val num = parseRoman(roman)
            if (num != null && num in 2..3999) num.toString() else m.value
        }
        text = RE_LATIN_I_NUMERAL.replace(text, "1")

        // 6. Ukrainian ordinals (1-й, 2-га, 3-тє, 5-го)
        text = RE_ORDINAL.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val suf = m.groupValues[2].lowercase()
            ordinalToWords(n, suf) ?: "${numberToWords(n.toLong())}-$suf"
        }

        // 7. Time HH:MM
        text = RE_TIME.replace(text) { m ->
            val h = m.groupValues[1].toLongOrNull()
            val min = m.groupValues[2].toLongOrNull()
            if (h != null && min != null && h in 0..23 && min in 0..59) {
                val mWord = if (min == 0L) "годин" else numberToWords(min)
                "${numberToWords(h)} $mWord"
            } else m.value
        }

        // 8. Currencies ($100, 100₴, €50)
        text = RE_CURR_PREFIX.replace(text) { m -> currencyToWords(m.groupValues[2], m.groupValues[1]) }
        text = RE_CURR_SUFFIX.replace(text) { m -> currencyToWords(m.groupValues[1], m.groupValues[2]) }

        // 9. Degrees, percentages, permille
        text = RE_DEG_C.replace(text) { m -> "${signedNumToWords(m.groupValues[1])} градусів Цельсія" }
        text = RE_DEG_F.replace(text) { m -> "${signedNumToWords(m.groupValues[1])} градусів Фаренгейта" }
        text = RE_DEG.replace(text) { m -> "${signedNumToWords(m.groupValues[1])} градусів" }
        text = RE_PERCENT.replace(text) { m -> "${numOrDecimalToWords(m.groupValues[1])} відсотків" }
        text = RE_PERMILLE.replace(text) { m -> "${numOrDecimalToWords(m.groupValues[1])} проміле" }

        // 10. RPG arrows, HP/MP fractions, multipliers, signed deltas
        text = RE_ARROW_TRANSITION.replace(text, " переходить у ")
        text = RE_FRACTION.replace(text) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: 0L
            val b = m.groupValues[2].toLongOrNull() ?: 0L
            "${numberToWords(a)} на ${numberToWords(b)}"
        }
        text = RE_MULT_PREFIX.replace(text) { m -> "на ${numOrDecimalToWords(m.groupValues[1])}" }
        text = RE_MULT_SUFFIX.replace(text) { m -> "${numOrDecimalToWords(m.groupValues[1])} ікс" }
        text = RE_PLUS_MINUS.replace(text) { m -> "плюс-мінус ${numOrDecimalToWords(m.groupValues[1])}" }
        text = RE_POS_DELTA.replace(text) { m -> "плюс ${numOrDecimalToWords(m.groupValues[1])}" }
        text = RE_NEG_DELTA.replace(text) { m -> "мінус ${numOrDecimalToWords(m.groupValues[1])}" }

        // 11. Abbreviations (Ukrainian + LitRPG)
        for ((re, repl) in ABBREVIATIONS) {
            text = re.replace(text, repl)
        }

        // 12. Hash numbers (#12 -> номер дванадцять), ranges, decimals, remaining integers
        text = RE_HASH_NUM.replace(text) { m -> "номер ${numberToWords(m.groupValues[1].toLongOrNull() ?: 0L)}" }
        text = RE_RANGE.replace(text) { m ->
            val a = m.groupValues[1].toLongOrNull()
            val b = m.groupValues[2].toLongOrNull()
            if (a != null && b != null) "${numberToWords(a)} — ${numberToWords(b)}" else m.value
        }
        // Strip English-style thousands commas (10,000 -> 10000) before decimal handling
        text = Regex("(?<!\\d)(\\d{1,3}(?:,\\d{3})+)(?!\\d)").replace(text) { m -> m.groupValues[1].replace(",", "") }
        text = RE_DECIMAL.replace(text) { m -> decimalToWords("${m.groupValues[1]},${m.groupValues[2]}") }
        text = RE_INT.replace(text) { m ->
            val rawNum = m.value
            if (rawNum.length > 12) rawNum.map { digitToWord(it) }.joinToString(" ")
            else numberToWords(rawNum.toLongOrNull() ?: 0L)
        }

        // 13. Popular novel / status symbols -> Ukrainian words
        for ((sym, word) in SYMBOL_WORDS) {
            if (sym in text) text = text.replace(sym, word)
        }
        text = text.replace('*', ' ').replace('#', ' ')

        // 14. Transliterate remaining Latin words
        text = RE_LATIN_WORD.replace(text) { m -> transliterateLatin(m.value) }

        // 15. Upstream patriotyk Space prosody rules: unify dashes & convert ` - ` to `: `
        text = RE_ALL_DASHES.replace(text, "-")
        text = RE_SPACES.replace(text, " ").trim()
        text = text.replace(" - ", ": ")
        text = Regex("\\s+([,.;:!?…])").replace(text, "$1").replace(",.", ".").replace(";.", ".").trim()

        if (ensureTerminalPunctuation && text.isNotEmpty() && text.last() !in ".?!:-…") {
            text += "."
        }
        return text
    }

    private fun signedNumToWords(s: String): String = when {
        s.startsWith("+") -> "плюс ${numOrDecimalToWords(s.substring(1))}"
        s.startsWith("-") -> "мінус ${numOrDecimalToWords(s.substring(1))}"
        else -> numOrDecimalToWords(s)
    }

    private fun numOrDecimalToWords(s: String): String =
        if ('.' in s || ',' in s) decimalToWords(s) else numberToWords(s.toLongOrNull() ?: 0L)

    private fun currencyToWords(numStr: String, sym: String): String {
        val isDec = '.' in numStr || ',' in numStr
        val n = if (isDec) 5L else (numStr.toLongOrNull() ?: 0L)
        val fem = sym == "₴"
        val spokenNum = if (isDec) decimalToWords(numStr) else numberToWords(n, feminine = fem)
        val unit = when (sym) {
            "$" -> pluralForm(n, "долар", "долари", "доларів")
            "€" -> "євро"
            "₴" -> pluralForm(n, "гривня", "гривні", "гривень")
            "£" -> pluralForm(n, "фунт", "фунти", "фунтів")
            "¥" -> pluralForm(n, "єна", "єни", "єн")
            "₩" -> pluralForm(n, "вона", "вони", "вон")
            "₿" -> pluralForm(n, "біткоїн", "біткоїни", "біткоїнів")
            else -> ""
        }
        return "$spokenNum $unit"
    }

    private val MONTHS_GEN = arrayOf(
        "січня", "лютого", "березня", "квітня", "травня", "червня",
        "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"
    )

    private val DAYS_GEN = arrayOf(
        "", "першого", "другого", "третього", "четвертого", "п'ятого",
        "шостого", "сьомого", "восьмого", "дев'ятого", "десятого",
        "одинадцятого", "дванадцятого", "тринадцятого", "чотирнадцятого", "п'ятнадцятого",
        "шістнадцятого", "сімнадцятого", "вісімнадцятого", "дев'ятнадцятого", "двадцятого",
        "двадцять першого", "двадцять другого", "двадцять третього", "двадцять четвертого", "двадцять п'ятого",
        "двадцять шостого", "двадцять сьомого", "двадцять восьмого", "двадцять дев'ятого", "тридцятого",
        "тридцять першого"
    )

    private fun dayGenitive(day: Int): String = DAYS_GEN.getOrElse(day) { numberToWords(day.toLong()) }

    private fun yearGenitive(year: Int): String =
        ordinalToWords(year, "го") ?: numberToWords(year.toLong())

    private fun ordinalToWords(n: Int, suf: String): String? {
        if (n < 0 || n > 999_999) return null
        val slot = when (suf) {
            "й", "ий", "ій", "тий", "тій", "ший", "гий" -> "NOM_M"
            "а", "ша", "га", "тя", "та", "я" -> "NOM_F"
            "е", "є", "тє", "те", "ше", "ге" -> "NOM_N"
            "і", "ті", "ші", "гі" -> "NOM_PL"
            "го", "ого", "ього", "того", "шого" -> "GEN_M"
            "ї", "ої", "ьої", "тої", "тьої", "шої" -> "GEN_F"
            "му", "ому", "ьому", "тому", "шому" -> "DAT_M"
            "у", "ю", "ту", "тю", "шу", "гу" -> "ACC_F"
            "м", "им", "ім", "тим", "тім", "шим", "гим" -> "INS_M"
            "ою", "ьою", "тою", "тьою", "шою" -> "INS_F"
            "х", "их", "іх", "тих", "тіх", "ших", "гих" -> "GEN_PL"
            "ми", "ими", "іми", "тими", "тіми", "шими" -> "INS_PL"
            else -> return null
        }

        fun declineStem(stem: String, soft: Boolean): String = stem + when (slot) {
            "NOM_M" -> if (soft) "ій" else "ий"
            "NOM_F" -> if (soft) "я" else "а"
            "NOM_N" -> if (soft) "є" else "е"
            "NOM_PL" -> "і"
            "GEN_M" -> if (soft) "ього" else "ого"
            "GEN_F" -> if (soft) "ьої" else "ої"
            "DAT_M" -> if (soft) "ьому" else "ому"
            "ACC_F" -> if (soft) "ю" else "у"
            "INS_M" -> if (soft) "ім" else "им"
            "INS_F" -> if (soft) "ьою" else "ою"
            "GEN_PL" -> if (soft) "іх" else "их"
            "INS_PL" -> if (soft) "іми" else "ими"
            else -> "ий"
        }

        if (n == 0) return declineStem("нульов", soft = false)

        val unitStems = mapOf(
            1 to "перш", 2 to "друг", 3 to "трет", 4 to "четверт", 5 to "п'ят",
            6 to "шост", 7 to "сьом", 8 to "восьм", 9 to "дев'ят", 10 to "десят",
            11 to "одинадцят", 12 to "дванадцят", 13 to "тринадцят", 14 to "чотирнадцят",
            15 to "п'ятнадцят", 16 to "шістнадцят", 17 to "сімнадцят", 18 to "вісімнадцят",
            19 to "дев'ятнадцят"
        )
        val tenStems = mapOf(
            20 to "двадцят", 30 to "тридцят", 40 to "сороков", 50 to "п'ятдесят",
            60 to "шістдесят", 70 to "сімдесят", 80 to "вісімдесят", 90 to "дев'яност"
        )
        val hundredStems = mapOf(
            100 to "сот", 200 to "двохсот", 300 to "трьохсот", 400 to "чотирьохсот",
            500 to "п'ятисот", 600 to "шестисот", 700 to "семисот", 800 to "восьмисот",
            900 to "дев'ятисот"
        )

        // Exact thousands (e.g., 1000-го -> тисячного, 2000-го -> двохтисячного)
        if (n % 1000 == 0) {
            val th = n / 1000
            val thPrefix = when (th) {
                1 -> ""
                2 -> "двох"
                3 -> "трьох"
                4 -> "чотирьох"
                5 -> "п'яти"
                6 -> "шести"
                7 -> "семи"
                8 -> "восьми"
                9 -> "дев'яти"
                10 -> "десяти"
                else -> "${numberToWords(th.toLong())} "
            }
            return thPrefix + declineStem("тисячн", soft = false)
        }

        // Exact hundreds (e.g., 100-го -> сотого, 1200-го -> тисяча двохсотого)
        if (n % 100 == 0) {
            val head = n - (n % 1000)
            val hStem = hundredStems[n % 1000] ?: return null
            val tail = declineStem(hStem, soft = false)
            return if (head > 0) "${numberToWords(head.toLong())} $tail" else tail
        }

        val rem100 = n % 100
        val head100 = n - rem100
        val tailWord = when {
            rem100 in 1..19 -> declineStem(unitStems[rem100]!!, soft = rem100 == 3)
            rem100 % 10 == 0 -> declineStem(tenStems[rem100]!!, soft = false)
            else -> {
                val tens = (rem100 / 10) * 10
                val unit = rem100 % 10
                "${numberToWords(tens.toLong())} ${declineStem(unitStems[unit]!!, soft = unit == 3)}"
            }
        }
        return if (head100 > 0) "${numberToWords(head100.toLong())} $tailWord" else tailWord
    }

    private fun parseRoman(s: String): Int? {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0; var prev = 0
        for (i in s.length - 1 downTo 0) {
            val v = map[s[i]] ?: return null
            if (v < prev) total -= v else total += v
            prev = v
        }
        return if (total > 0) total else null
    }

    private fun decimalToWords(numStr: String): String {
        val parts = numStr.split('.', ',')
        if (parts.size != 2) return numStr
        val whole = parts[0].toLongOrNull() ?: 0L
        val frac = parts[1].toLongOrNull() ?: 0L
        val wholeWords = numberToWords(whole, feminine = true)
        val fracWords = numberToWords(frac, feminine = true)
        val wholeLabel = if (whole % 10 == 1L && whole % 100 != 11L) "ціла" else "цілих"
        val fracUnit = when (parts[1].length) {
            1 -> if (frac % 10 == 1L && frac % 100 != 11L) "десята" else "десятих"
            2 -> if (frac % 10 == 1L && frac % 100 != 11L) "сота" else "сотих"
            else -> if (frac % 10 == 1L && frac % 100 != 11L) "тисячна" else "тисячних"
        }
        return "$wholeWords $wholeLabel $fracWords $fracUnit"
    }

    private fun digitToWord(ch: Char): String = when (ch) {
        '0' -> "нуль"; '1' -> "один"; '2' -> "два"; '3' -> "три"; '4' -> "чотири"
        '5' -> "п'ять"; '6' -> "шість"; '7' -> "сім"; '8' -> "вісім"; '9' -> "дев'ять"
        else -> ""
    }

    fun numberToWords(n: Long, feminine: Boolean = false): String {
        if (n == 0L) return "нуль"
        if (n == Long.MIN_VALUE) return "мінус " + "9223372036854775808".map { digitToWord(it) }.joinToString(" ")
        if (n < 0L) return "мінус ${numberToWords(-n, feminine)}"
        if (n >= 1_000_000_000_000L) return n.toString().map { digitToWord(it) }.joinToString(" ")
        val parts = mutableListOf<String>()
        var rem = n
        val billions = rem / 1_000_000_000L
        if (billions > 0) {
            parts.add(tripletToWords(billions.toInt(), false))
            parts.add(pluralForm(billions, "мільярд", "мільярди", "мільярдів"))
            rem %= 1_000_000_000L
        }
        val millions = rem / 1_000_000L
        if (millions > 0) {
            parts.add(tripletToWords(millions.toInt(), false))
            parts.add(pluralForm(millions, "мільйон", "мільйони", "мільйонів"))
            rem %= 1_000_000L
        }
        val thousands = rem / 1_000L
        if (thousands > 0) {
            parts.add(tripletToWords(thousands.toInt(), true))
            parts.add(pluralForm(thousands, "тисяча", "тисячі", "тисяч"))
            rem %= 1_000L
        }
        if (rem > 0) parts.add(tripletToWords(rem.toInt(), feminine))
        return parts.joinToString(" ")
    }

    private fun tripletToWords(n: Int, feminine: Boolean): String {
        val hundreds = arrayOf("", "сто", "двісті", "триста", "чотириста", "п'ятсот", "шістсот", "сімсот", "вісімсот", "дев'ятсот")
        val tens = arrayOf("", "", "двадцять", "тридцять", "сорок", "п'ятдесят", "шістдесят", "сімдесят", "вісімдесят", "дев'яносто")
        val teens = arrayOf("десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять", "п'ятнадцять", "шістнадцять", "сімнадцять", "вісімнадцять", "дев'ятнадцять")
        val unitsM = arrayOf("", "один", "два", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
        val unitsF = arrayOf("", "одна", "дві", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
        val out = mutableListOf<String>()
        val h = n / 100; val t = (n % 100) / 10; val u = n % 10
        if (h > 0) out.add(hundreds[h])
        if (t == 1) out.add(teens[u]) else {
            if (t >= 2) out.add(tens[t])
            if (u > 0) out.add(if (feminine) unitsF[u] else unitsM[u])
        }
        return out.joinToString(" ")
    }

    private fun pluralForm(n: Long, one: String, few: String, many: String): String {
        val mod100 = (n % 100).toInt(); val mod10 = (n % 10).toInt()
        return when {
            mod100 in 11..19 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private fun transliterateLatin(word: String): String {
        val lower = word.lowercase()
        val sb = StringBuilder(lower.length * 2)
        var i = 0
        while (i < lower.length) {
            val rest = lower.substring(i)
            when {
                rest.startsWith("shch") -> { sb.append("щ"); i += 4 }
                rest.startsWith("tion") -> { sb.append("шн"); i += 4 }
                rest.startsWith("sh") -> { sb.append("ш"); i += 2 }
                rest.startsWith("ch") -> { sb.append("ч"); i += 2 }
                rest.startsWith("th") -> { sb.append("з"); i += 2 }
                rest.startsWith("ph") -> { sb.append("ф"); i += 2 }
                rest.startsWith("ck") -> { sb.append("к"); i += 2 }
                rest.startsWith("qu") -> { sb.append("кв"); i += 2 }
                rest.startsWith("ee") || rest.startsWith("ea") -> { sb.append("і"); i += 2 }
                rest.startsWith("oo") -> { sb.append("у"); i += 2 }
                else -> {
                    sb.append(
                        when (lower[i]) {
                            'a' -> "а"; 'b' -> "б"; 'c' -> "к"; 'd' -> "д"; 'e' -> "е"
                            'f' -> "ф"; 'g' -> "ґ"; 'h' -> "х"; 'i' -> "і"; 'j' -> "дж"
                            'k' -> "к"; 'l' -> "л"; 'm' -> "м"; 'n' -> "н"; 'o' -> "о"
                            'p' -> "п"; 'q' -> "к"; 'r' -> "р"; 's' -> "с"; 't' -> "т"
                            'u' -> "у"; 'v' -> "в"; 'w' -> "в"; 'x' -> "кс"; 'y' -> "і"
                            'z' -> "з"
                            else -> ""
                        }
                    )
                    i++
                }
            }
        }
        return sb.toString()
    }
}
