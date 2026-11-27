package ua.numeric

import ua.numeric.Case.*
import java.math.BigDecimal

private val hryvnia = CountedNoun(
    Gender.FEMININE,
    NounForms("гри́вня", "гри́вні", "гри́вні", "гри́вню", "гри́внею", "гри́вні"),
    NounForms("гри́вні", "гри́вень", "гри́вням", "гри́вні", "гри́внями", "гри́внях"),
)
private val kopiyka = CountedNoun(
    Gender.FEMININE,
    NounForms("копі́йка", "копі́йки", "копі́йці", "копі́йку", "копі́йкою", "копі́йці"),
    NounForms("копійки́", "копі́йок", "копійка́м", "копійки́", "копійка́ми", "копійка́х"),
    countingPlural = "копі́йки",
)
private val minute = CountedNoun(
    Gender.FEMININE,
    NounForms("хвили́на", "хвили́ни", "хвили́ні", "хвили́ну", "хвили́ною", "хвили́ні"),
    NounForms("хвили́ни", "хвили́н", "хвили́нам", "хвили́ни", "хвили́нами", "хвили́нах"),
)
private val second = CountedNoun(
    Gender.FEMININE,
    NounForms("секу́нда", "секу́нди", "секу́нді", "секу́нду", "секу́ндою", "секу́нді"),
    NounForms("секу́нди", "секу́нд", "секу́ндам", "секу́нди", "секу́ндами", "секу́ндах"),
)
private val wholeSingular = NounForms("ці́ла", "ці́лої", "ці́лій", "ці́лу", "ці́лою", "ці́лій")
private val wholePlural = NounForms("ці́лі", "ці́лих", "ці́лим", "ці́лі", "ці́лими", "ці́лих")
private val minExclusive = BigDecimal.valueOf(Long.MIN_VALUE).subtract(BigDecimal.ONE)
private val maxExclusive = BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE)

private fun Case.isDirect(): Boolean = this == NOMINATIVE || this == ACCUSATIVE

internal object QuantityWords {
    fun count(value: Long, noun: CountedNoun, case: Case, variant: Variant, stress: Boolean): String {
        val ending = quantity(value)
        val nounWord = when {
            value % 1000 == 0L -> noun.plural.genitive
            ending == Quantity.ONE -> noun.singular[case]
            ending == Quantity.FEW && case == ACCUSATIVE && value in -4L..4L &&
                noun.animacy == Animacy.ANIMATE -> noun.plural.accusative
            ending == Quantity.FEW && case.isDirect() -> noun.countingPlural
            ending == Quantity.MANY && case.isDirect() -> noun.plural.genitive
            else -> noun.plural[case]
        }
        return (NumberWords.cardinal(value, case, noun.gender, noun.animacy, variant) + nounWord)
            .render(value, stress)
    }

    fun money(amount: BigDecimal, includeZeroKopiykas: Boolean, stress: Boolean): String {
        val exactAmount = if (amount.signum() == 0) BigDecimal.ZERO else amount
        val whole = checkedWhole(exactAmount)
        require(exactAmount.stripTrailingZeros().scale() <= 2) { "UAH amount must be exact to a kopiyka" }
        val kopiykas = exactAmount.remainder(BigDecimal.ONE).movePointRight(2).abs().intValueExact()
        val majorWords = count(whole, hryvnia, NOMINATIVE, Variant.PRIMARY, stress)
        val minorWords = if (kopiykas > 0 || includeZeroKopiykas) {
            " " + count(kopiykas.toLong(), kopiyka, NOMINATIVE, Variant.PRIMARY, stress)
        } else {
            ""
        }
        return signedSubunit(exactAmount, whole, majorWords + minorWords, stress)
    }

    fun time(hour: Int, minuteOfHour: Int, secondOfMinute: Int, stress: Boolean): String {
        require(hour in 0..23) { "Hour must be in 0..23" }
        require(minuteOfHour in 0..59) { "Minute must be in 0..59" }
        require(secondOfMinute in 0..59) { "Second must be in 0..59" }
        val words = NumberWords.ordinal(
            hour.toLong(), NOMINATIVE, Gender.FEMININE, NumberForm.SINGULAR, Animacy.INANIMATE, Variant.PRIMARY,
        ).toMutableList()
        words += "годи́на"
        if (minuteOfHour > 0) words += count(minuteOfHour.toLong(), minute, NOMINATIVE, Variant.PRIMARY, stress)
        if (secondOfMinute > 0) words += count(secondOfMinute.toLong(), second, NOMINATIVE, Variant.PRIMARY, stress)
        return words.render(0L, stress)
    }

    fun fraction(numerator: Long, denominator: Long, case: Case, variant: Variant, stress: Boolean): String {
        require(denominator >= 2) { "Denominator must be at least 2" }
        val singular = quantity(numerator) == Quantity.ONE
        val number = if (singular) NumberForm.SINGULAR else NumberForm.PLURAL
        val partCase = if (!singular && case.isDirect()) GENITIVE else case
        val top = NumberWords.cardinal(numerator, case, Gender.FEMININE, Animacy.INANIMATE, variant)
        val bottom = NumberWords.ordinal(denominator, partCase, Gender.FEMININE, number, Animacy.INANIMATE, variant)
        return (top + bottom).render(numerator, stress)
    }

    fun decimal(amount: BigDecimal, case: Case, variant: Variant, stress: Boolean): String {
        val places = amount.scale().coerceAtLeast(0)
        require(places <= 18) { "Decimal scale must be at most 18" }
        val whole = checkedWhole(amount)
        val top = NumberWords.cardinal(whole, case, Gender.FEMININE, Animacy.INANIMATE, variant)
        if (places == 0) return top.render(whole, stress)

        val wholeWord = if (quantity(whole) == Quantity.ONE) {
            wholeSingular[case]
        } else {
            wholePlural[if (case.isDirect()) GENITIVE else case]
        }
        val numerator = amount.remainder(BigDecimal.ONE).abs().movePointRight(places).longValueExact()
        val denominator = BigDecimal.TEN.pow(places).longValueExact()
        val parts = fraction(numerator, denominator, case, variant, stress)
        val words = (top + wholeWord + parts).render(whole, stress)
        return signedSubunit(amount, whole, words, stress)
    }

    private fun checkedWhole(amount: BigDecimal): Long {
        require(amount > minExclusive && amount < maxExclusive) { "Whole part must fit in a signed Long" }
        return if (amount.signum() == 0) 0L else amount.toLong()
    }

    private fun signedSubunit(amount: BigDecimal, whole: Long, text: String, stress: Boolean): String {
        return if (whole == 0L && amount.signum() < 0) {
            listOf(text).render(-1L, stress)
        } else {
            text
        }
    }
}
