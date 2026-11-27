package ua.numeric

import java.math.BigDecimal

/** Ukrainian numeric spell-out for explicit grammatical contexts. */
object UaNumericEngine {
    /** Spells a signed integer, inflecting every cardinal component for the requested case. */
    fun cardinal(
        value: Long,
        case: Case = Case.NOMINATIVE,
        gender: Gender = Gender.MASCULINE,
        animacy: Animacy = Animacy.INANIMATE,
        variant: Variant = Variant.PRIMARY,
        stress: Boolean = false,
    ): String = NumberWords.cardinal(value, case, gender, animacy, variant).render(value, stress)

    /** Spells a signed ordinal, inflecting its final component. */
    fun ordinal(
        value: Long,
        case: Case = Case.NOMINATIVE,
        gender: Gender = Gender.MASCULINE,
        number: NumberForm = NumberForm.SINGULAR,
        animacy: Animacy = Animacy.INANIMATE,
        variant: Variant = Variant.PRIMARY,
        stress: Boolean = false,
    ): String = NumberWords.ordinal(value, case, gender, number, animacy, variant).render(value, stress)

    /** Spells a numeral and a caller-supplied countable noun. */
    fun count(
        value: Long,
        noun: CountedNoun,
        case: Case = Case.NOMINATIVE,
        variant: Variant = Variant.PRIMARY,
        stress: Boolean = false,
    ): String = QuantityWords.count(value, noun, case, variant, stress)

    /** Spells a Gregorian date in the temporal genitive, including року. */
    fun date(day: Int, month: Int, year: Int, stress: Boolean = false): String =
        CalendarWords.date(day, month, year, stress)

    /** Spells an entire supported date token, returning null for invalid or unrecognized input. */
    fun dateOrNull(text: String, stress: Boolean = false): String? = CalendarWords.dateOrNull(text, stress)

    /** Spells a year and its associated noun. Supply a preposition at the call site when needed. */
    fun year(year: Int, form: YearForm = YearForm.GENITIVE, stress: Boolean = false): String =
        CalendarWords.year(year, form, stress)

    /** Spells an entire supported year phrase and retains its preposition, when present. */
    fun yearOrNull(text: String, stress: Boolean = false): String? = CalendarWords.yearOrNull(text, stress)

    /** Spells an exact hryvnia amount, with optional zero kopiykas. */
    fun money(amount: BigDecimal, includeZeroKopiykas: Boolean = false, stress: Boolean = false): String =
        QuantityWords.money(amount, includeZeroKopiykas, stress)

    /** Spells a 24-hour clock reading. Zero-valued minute and second units are omitted. */
    fun time(hour: Int, minute: Int, second: Int = 0, stress: Boolean = false): String =
        QuantityWords.time(hour, minute, second, stress)

    /** Spells a signed common fraction with a positive denominator of at least two. */
    fun fraction(
        numerator: Long,
        denominator: Long,
        case: Case = Case.NOMINATIVE,
        variant: Variant = Variant.PRIMARY,
        stress: Boolean = false,
    ): String = QuantityWords.fraction(numerator, denominator, case, variant, stress)

    /** Spells a decimal fraction using the input BigDecimal's scale to retain trailing zeros. */
    fun decimal(
        amount: BigDecimal,
        case: Case = Case.NOMINATIVE,
        variant: Variant = Variant.PRIMARY,
        stress: Boolean = false,
    ): String = QuantityWords.decimal(amount, case, variant, stress)
}
