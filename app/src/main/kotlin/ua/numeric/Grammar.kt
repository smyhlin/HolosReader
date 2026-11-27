package ua.numeric

enum class Case { NOMINATIVE, GENITIVE, DATIVE, ACCUSATIVE, INSTRUMENTAL, LOCATIVE }
enum class Gender { MASCULINE, FEMININE, NEUTER }
enum class Animacy { INANIMATE, ANIMATE }
enum class NumberForm { SINGULAR, PLURAL }

/** Selects the second dictionary form where a parallel form is available. */
enum class Variant { PRIMARY, ALTERNATIVE }

/** Singular or plural case forms of a noun, optionally with written stress marks. */
data class NounForms(
    val nominative: String,
    val genitive: String,
    val dative: String,
    val accusative: String,
    val instrumental: String,
    val locative: String,
) {
    operator fun get(case: Case): String = when (case) {
        Case.NOMINATIVE -> nominative
        Case.GENITIVE -> genitive
        Case.DATIVE -> dative
        Case.ACCUSATIVE -> accusative
        Case.INSTRUMENTAL -> instrumental
        Case.LOCATIVE -> locative
    }
}

/** A countable noun with both numbers. countingPlural overrides the direct form after 2–4. */
data class CountedNoun(
    val gender: Gender,
    val singular: NounForms,
    val plural: NounForms,
    val animacy: Animacy = Animacy.INANIMATE,
    val countingPlural: String = plural.nominative,
)
