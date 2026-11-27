package ua.numeric

enum class YearForm { NOMINATIVE, GENITIVE, LOCATIVE }

internal object CalendarWords {
    private val months = listOf(
        "сі́чня", "лю́того", "бе́резня", "кві́тня", "тра́вня", "че́рвня",
        "ли́пня", "се́рпня", "ве́ресня", "жо́втня", "листопа́да", "гру́дня",
    )
    private val monthIndexes = months.mapIndexed { index, name -> name.withoutStress() to index + 1 }.toMap()
    private val daysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private const val SPACE = "[\\p{Zs}\\t]+"
    private const val OPT_SPACE = "[\\p{Zs}\\t]*"
    private const val DASH = "[-\u2010\u2011\u2012\u2013\u2014\u2212]"
    private const val DATE_CORE = """[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{4}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}"""
    private const val TOKEN_START = """(?<![\p{L}\p{N}./+-])"""
    private const val TOKEN_END = """(?![\p{L}\p{N}/-]|\.[0-9])"""

    private val spaces = Regex(SPACE)
    private val monthPattern = months.joinToString("|") { it.replace("\u0301", "\u0301?") }
    private val datePattern = pattern(
        "($DATE_CORE|[0-9]{1,2}$SPACE(?:$monthPattern)$SPACE[0-9]{4})" +
            "(?:${SPACE}ро\u0301?ку|${OPT_SPACE}(р\\.))?",
    )
    private val yearPattern = pattern(
        "(?:(у|в|з|із|зі|до|від|після)$SPACE)?([0-9]{4})(?:$DASH(й|го|му))?" +
            "(?:$SPACE(рі\u0301?к|ро\u0301?ку|ро\u0301?ці)|${OPT_SPACE}(р\\.))",
    )

    fun date(day: Int, month: Int, year: Int, stress: Boolean): String {
        require(isValidDate(day, month, year)) { "Invalid Gregorian date: $day-$month-$year" }
        return renderDate(day, month, year, stress)
    }

    fun year(year: Int, form: YearForm, stress: Boolean): String {
        require(year in 1000..9999) { "Year must be in 1000..9999" }
        return renderYear(year, form, stress)
    }

    fun dateOrNull(input: String, stress: Boolean): String? =
        datePattern.matchEntire(input.trim().withoutStress())?.let { renderDateMatch(it, stress) }

    fun yearOrNull(input: String, stress: Boolean): String? =
        yearPattern.matchEntire(input.trim().withoutStress())?.let { renderYearMatch(it, stress) }

    fun replaceInText(text: String, format: (String) -> String): String {
        val dates = datePattern.replace(text) { match ->
            val date = renderDateMatch(match, stress = false) ?: return@replace match.value
            format(date) + abbreviationPause(text, match, group = 2)
        }
        return yearPattern.replace(dates) { match ->
            val year = renderYearMatch(match, stress = false) ?: return@replace match.value
            format(year) + abbreviationPause(dates, match, group = 5)
        }
    }

    private fun pattern(core: String): Regex = Regex("$TOKEN_START$core$TOKEN_END", RegexOption.IGNORE_CASE)

    private fun renderDateMatch(match: MatchResult, stress: Boolean): String? {
        val raw = match.groupValues[1]
        val (day, month, year) = when {
            '-' in raw -> {
                val (year, month, day) = raw.split('-')
                Triple(day.toInt(), month.toInt(), year.toInt())
            }
            '.' in raw || '/' in raw -> {
                val (day, month, year) = raw.split('.', '/')
                Triple(day.toInt(), month.toInt(), year.toInt())
            }
            else -> {
                val (day, month, year) = raw.split(spaces)
                Triple(day.toInt(), monthIndexes.getValue(month.withoutStress().lowercase()), year.toInt())
            }
        }
        if (!isValidDate(day, month, year)) return null
        return renderDate(day, month, year, stress)
    }

    private fun renderYearMatch(match: MatchResult, stress: Boolean): String? {
        val (preposition, digits, ending, noun) = match.destructured
        val year = digits.toInt()
        if (year !in 1000..9999) return null

        val prepositionForm = when (preposition.lowercase()) {
            "" -> null
            "у", "в" -> YearForm.LOCATIVE
            else -> YearForm.GENITIVE
        }
        val endingForm = when (ending.lowercase()) {
            "й" -> YearForm.NOMINATIVE
            "го" -> YearForm.GENITIVE
            "му" -> YearForm.LOCATIVE
            else -> null
        }
        val form = when (noun.withoutStress().lowercase()) {
            "рік" -> YearForm.NOMINATIVE
            "року" -> YearForm.GENITIVE
            "році" -> YearForm.LOCATIVE
            else -> prepositionForm ?: endingForm ?: YearForm.GENITIVE
        }
        if (endingForm != null && endingForm != form) return null
        if (prepositionForm != null && prepositionForm != form) return null
        val spoken = renderYear(year, form, stress)
        return if (preposition.isEmpty()) spoken else "$preposition $spoken"
    }

    private fun renderDate(day: Int, month: Int, year: Int, stress: Boolean): String {
        val spokenDay = UaNumericEngine.ordinal(day.toLong(), Case.GENITIVE, stress = stress)
        val spokenMonth = months[month - 1].let { if (stress) it else it.withoutStress() }
        return "$spokenDay $spokenMonth ${renderYear(year, YearForm.GENITIVE, stress)}"
    }

    private fun renderYear(year: Int, form: YearForm, stress: Boolean): String {
        val (case, noun) = when (form) {
            YearForm.NOMINATIVE -> Case.NOMINATIVE to "рі́к"
            YearForm.GENITIVE -> Case.GENITIVE to "ро́ку"
            YearForm.LOCATIVE -> Case.LOCATIVE to "ро́ці"
        }
        val spoken = UaNumericEngine.ordinal(year.toLong(), case, stress = stress)
        return "$spoken ${if (stress) noun else noun.withoutStress()}"
    }

    private fun abbreviationPause(text: String, match: MatchResult, group: Int): String {
        if (match.groups[group] == null) return ""
        var next = match.range.last + 1
        while (next < text.length && text[next].isWhitespace()) next++
        val following = text.getOrNull(next)
        return if (following == null || following.isUpperCase()) "." else ""
    }

    private fun isValidDate(day: Int, month: Int, year: Int): Boolean {
        if (year !in 1..9999 || month !in 1..12) return false
        val leapDay = month == 2 && year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val maximum = if (leapDay) 29 else daysInMonth[month - 1]
        return day in 1..maximum
    }
}
