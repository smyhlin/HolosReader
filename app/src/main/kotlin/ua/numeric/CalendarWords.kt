package ua.numeric

enum class YearForm { NOMINATIVE, GENITIVE, LOCATIVE }

internal object CalendarWords {
    private val months = listOf(
        "сі́чня", "лю́того", "бе́резня", "кві́тня", "тра́вня", "че́рвня",
        "ли́пня", "се́рпня", "ве́ресня", "жо́втня", "листопа́да", "гру́дня",
    )
    private val monthIndexes = months.mapIndexed { index, name -> name.withoutStress() to index + 1 }.toMap()
    private val daysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    private val supportedYears = 1000..9999

    private val space = "[\\p{Zs}\\t]+"
    private val namedDate = Regex(
        "([0-9]{1,2})$space(${monthIndexes.keys.joinToString("|")})$space([0-9]{4})(?:$space(?:року|р\\.))?",
        RegexOption.IGNORE_CASE,
    )
    private val numericDate = Regex("([0-9]{1,2})([./])([0-9]{1,2})\\2([0-9]{4})")
    private val isoDate = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
    private val yearPhrase = Regex(
        "(?:(у|в|з|із|зі|до|від)$space)?([0-9]{4})(?:-(й))?$space(рік|року|році)",
        RegexOption.IGNORE_CASE,
    )

    fun date(day: Int, month: Int, year: Int, stress: Boolean): String {
        validateYear(year)
        require(isValidDay(day, month, year)) { "Invalid Gregorian date: $day-$month-$year" }
        val spokenDay = UaNumericEngine.ordinal(day.toLong(), Case.GENITIVE, stress = stress)
        val spokenMonth = months[month - 1].let { if (stress) it else it.withoutStress() }
        return "$spokenDay $spokenMonth ${year(year, YearForm.GENITIVE, stress)}"
    }

    fun year(year: Int, form: YearForm, stress: Boolean): String {
        validateYear(year)
        val (case, noun) = when (form) {
            YearForm.NOMINATIVE -> Case.NOMINATIVE to "рі́к"
            YearForm.GENITIVE -> Case.GENITIVE to "ро́ку"
            YearForm.LOCATIVE -> Case.LOCATIVE to "ро́ці"
        }
        val spoken = UaNumericEngine.ordinal(year.toLong(), case, stress = stress)
        return "$spoken ${if (stress) noun else noun.withoutStress()}"
    }

    fun dateOrNull(input: String, stress: Boolean): String? {
        val text = input.trim().withoutStress()
        isoDate.matchEntire(text)?.let {
            val (year, month, day) = it.destructured
            return validatedDate(day.toInt(), month.toInt(), year.toInt(), stress)
        }
        numericDate.matchEntire(text)?.let {
            val (day, _, month, year) = it.destructured
            return validatedDate(day.toInt(), month.toInt(), year.toInt(), stress)
        }
        namedDate.matchEntire(text)?.let {
            val (day, month, year) = it.destructured
            return validatedDate(day.toInt(), monthIndexes.getValue(month.lowercase()), year.toInt(), stress)
        }
        return null
    }

    fun yearOrNull(input: String, stress: Boolean): String? {
        val match = yearPhrase.matchEntire(input.trim().withoutStress()) ?: return null
        val (preposition, digits, ending, noun) = match.destructured
        val year = digits.toInt()
        if (year !in supportedYears) return null
        val form = when (noun.lowercase()) {
            "рік" -> YearForm.NOMINATIVE
            "року" -> YearForm.GENITIVE
            else -> YearForm.LOCATIVE
        }
        if (ending.isNotEmpty() && form != YearForm.NOMINATIVE) return null
        val compatible = when (preposition.lowercase()) {
            "" -> true
            "у", "в" -> form == YearForm.LOCATIVE
            else -> form == YearForm.GENITIVE
        }
        if (!compatible) return null
        val spoken = year(year, form, stress)
        return if (preposition.isEmpty()) spoken else "$preposition $spoken"
    }

    private fun validatedDate(day: Int, month: Int, year: Int, stress: Boolean): String? {
        if (year !in supportedYears || !isValidDay(day, month, year)) return null
        return date(day, month, year, stress)
    }

    private fun validateYear(year: Int) {
        require(year in supportedYears) { "Year must be in 1000..9999" }
    }

    private fun isValidDay(day: Int, month: Int, year: Int): Boolean {
        if (month !in 1..12) return false
        val leapDay = month == 2 && year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val maximum = if (leapDay) 29 else daysInMonth[month - 1]
        return day in 1..maximum
    }
}
