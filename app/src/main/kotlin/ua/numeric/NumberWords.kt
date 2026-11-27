package ua.numeric

import ua.numeric.Case.*

private data class Group(val value: Int, val scale: Int)

internal enum class Quantity { ONE, FEW, MANY }

internal fun quantity(value: Long): Quantity {
    val ending = kotlin.math.abs((value % 100).toInt())
    if (ending in 11..14) return Quantity.MANY
    return when (ending % 10) {
        1 -> Quantity.ONE
        in 2..4 -> Quantity.FEW
        else -> Quantity.MANY
    }
}

internal fun String.withoutStress(): String = replace("\u0301", "")

internal fun List<String>.render(value: Long, stress: Boolean): String {
    val prefix = if (value < 0) "мі́нус " else ""
    val text = joinToString(" ", prefix)
    return if (stress) text else text.withoutStress()
}

internal object NumberWords {
    fun cardinal(value: Long, case: Case, gender: Gender, animacy: Animacy, variant: Variant): List<String> {
        if (value == 0L) return listOf(cardinalWord(0, case, gender, Animacy.INANIMATE, variant))

        return buildList {
            for ((triplet, scale) in groups(value)) {
                if (scale > 0) {
                    addAll(scaleWords(triplet, scale, case, variant))
                    continue
                }
                val atoms = atoms(triplet)
                for ((index, atom) in atoms.withIndex()) {
                    val useAnimacy = index == atoms.lastIndex && (value in -9L..9L || atom !in 2..4)
                    val wordAnimacy = if (useAnimacy) animacy else Animacy.INANIMATE
                    add(cardinalWord(atom, case, gender, wordAnimacy, variant))
                }
            }
        }
    }

    fun ordinal(
        value: Long,
        case: Case,
        gender: Gender,
        number: NumberForm,
        animacy: Animacy,
        variant: Variant,
    ): List<String> {
        if (value == 0L) return listOf(ordinalWord(0L, case, gender, number, animacy, variant))

        val groups = groups(value)
        return buildList {
            for ((triplet, scale) in groups.dropLast(1)) {
                addAll(scaleWords(triplet, scale, NOMINATIVE, Variant.PRIMARY))
            }

            val (triplet, scale) = groups.last()
            if (scale == 0) {
                val atoms = atoms(triplet)
                for (atom in atoms.dropLast(1)) {
                    add(cardinalWord(atom, NOMINATIVE, Gender.MASCULINE, Animacy.INANIMATE, Variant.PRIMARY))
                }
                add(ordinalWord(atoms.last().toLong(), case, gender, number, animacy, variant))
            } else {
                val suffix = ordinalWord(scaleValues[scale], case, gender, number, animacy, variant)
                add(scalePrefix(triplet) + suffix)
            }
        }
    }

    private fun scaleWords(value: Int, scale: Int, case: Case, variant: Variant): List<String> {
        val scaleValue = scaleValues[scale]
        val gender = if (scale == 1) Gender.FEMININE else Gender.MASCULINE
        val forms = scaleLexicon.getValue(scaleValue)
        val noun = when (quantity(value.toLong())) {
            Quantity.ONE -> forms.singular[case, variant]
            Quantity.FEW -> forms.plural[case, variant]
            Quantity.MANY -> {
                val nounCase = if (case == NOMINATIVE || case == ACCUSATIVE) GENITIVE else case
                forms.plural[nounCase, variant]
            }
        }
        if (value == 1) return listOf(noun)
        return atoms(value).map { cardinalWord(it, case, gender, Animacy.INANIMATE, variant) } + noun
    }

    private fun scalePrefix(value: Int): String {
        if (value == 1) return ""
        // This prefix is attested in the 2019 orthography (§ 38).
        if (value == 530) return "п’ятсоттридцяти"
        return atoms(value).joinToString("") { atom ->
            when (atom) {
                1 -> "одно"
                90, 100 -> cardinalWord(atom, NOMINATIVE, Gender.MASCULINE, Animacy.INANIMATE, Variant.PRIMARY)
                else -> cardinalWord(atom, GENITIVE, Gender.MASCULINE, Animacy.INANIMATE, Variant.PRIMARY)
            }.withoutStress()
        }
    }

    private fun groups(value: Long): List<Group> {
        var remaining = value
        var scale = 0
        return buildList {
            while (remaining != 0L) {
                // Take the magnitude of a single group so Long.MIN_VALUE stays representable.
                val triplet = kotlin.math.abs((remaining % 1000).toInt())
                if (triplet != 0) add(Group(triplet, scale))
                remaining /= 1000
                scale++
            }
        }.asReversed()
    }

    private fun atoms(value: Int): List<Int> = buildList {
        val hundreds = value / 100 * 100
        if (hundreds > 0) add(hundreds)
        val rest = value % 100
        if (rest in 1..19) {
            add(rest)
        } else if (rest >= 20) {
            add(rest / 10 * 10)
            if (rest % 10 != 0) add(rest % 10)
        }
    }
}
