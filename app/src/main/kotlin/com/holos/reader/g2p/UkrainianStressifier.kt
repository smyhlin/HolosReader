package com.holos.reader.g2p

/**
 * 4-tier on-device Ukrainian Stress Engine:
 * - Tier 0: User overrides (`stress_user.txt`) — highest priority for book character names & homographs
 * - Tier 1: 2,886,186-word binary dictionary (`uk_stress.bin` v2 from «Словники України»)
 *   - Unambiguous words resolve in ~0.02 ms via `MappedByteBuffer`
 *   - Ambiguous homographs (36,414 forms) are resolved using Ukrainian syntactic context
 *     (preposition case government, subject pronouns, negation, numerals) over preserved morphological tags
 * - Tier 2: Bundled 1.17 MB ONNX neural accentor (`accentor-lite.onnx`) for OOV words & neutral homographs
 * - Tier 3: Prefix-stripping stem lookup + penultimate-syllable safety fallback
 */
class UkrainianStressifier(
    private val dict: UkrainianStressDict?,
    private val overrides: UserStressOverrides? = null,
    private val neuralAccentor: WordAccentor? = null,
) {

    companion object {
        // 0-based vowel index for unambiguous high-frequency Ukrainian pronouns
        private val PRONOUN_VOWEL_INDEX = mapOf(
            "вона" to 1, "воно" to 1, "вони" to 1,
            "її" to 1, "його" to 1, "мене" to 1, "тебе" to 1, "себе" to 1,
            "мені" to 1, "тобі" to 1, "собі" to 1,
            "цього" to 1, "того" to 1, "свого" to 1, "мого" to 1, "твого" to 1,
        )
        const val ACUTE = '\u0301'
        const val GRAVE = '\u0300'
        private const val VOWELS = "аеєиіїоуюяАЕЄИІЇОУЮЯ"
        private val WORD_REGEX = Regex("[А-ЩЬЮЯҐЄІЇа-щьюяґєії'’\\u0301\\u0300]+")
        private val APOSTROPHE_REGEX = Regex("[’`ʼ]")

        private val UNSTRESSED_WORDS = setOf(
            "в", "у", "з", "із", "зі", "до", "на", "по", "за", "під", "над", "при", "про",
            "без", "від", "для", "між", "через", "перед", "біля", "крім", "серед",
            "і", "й", "та", "а", "але", "бо", "що", "щоб", "як", "чи", "ні", "не", "би", "б", "же", "ж"
        )

        // Prepositions governing Genitive case (e.g. "біля вікна́", "без голови́", "до руки́", "від стіни́")
        private val GEN_PREPOSITIONS = setOf(
            "біля", "до", "від", "без", "для", "після", "коло", "поряд", "посеред",
            "навколо", "крім", "окрім", "замість", "проти", "серед", "край", "близько",
            "вздовж", "уздовж", "поруч", "усередині", "всередині", "зверху", "знизу", "з"
        )
        // Prepositions governing Locative case ("у лісі", "на стіні", "при вході")
        private val LOC_PREPOSITIONS = setOf("у", "в", "на", "при", "по")
        // Prepositions governing Instrumental case ("під столом", "над головою", "між ними")
        private val INS_PREPOSITIONS = setOf("над", "під", "перед", "між", "поміж", "понад", "попід")
        // Prepositions governing Dative case
        private val DAT_PREPOSITIONS = setOf("завдяки", "назустріч", "всупереч", "подібно")
        // Subject pronouns / negation strongly favoring VERB over NOUN/ADJ
        private val VERB_CUES = setOf("я", "ти", "він", "вона", "воно", "ми", "ви", "вони", "не")
        // Plural numerals (2, 3, 4, many) favoring Number=Plur ("дві ру́ки", "три го́лови", "багато ві́кон")
        private val PLURAL_CUES = setOf(
            "два", "дві", "три", "чотири", "обоє", "обидва", "обидві",
            "всі", "усі", "ці", "ті", "мої", "твої", "свої", "наші", "ваші", "їхні"
        )
    }

    fun stressify(text: String): String {
        val matches = WORD_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return text

        val sb = StringBuilder(text.length + matches.size * 2)
        var cursor = 0
        var prevLower: String? = null

        for ((idx, m) in matches.withIndex()) {
            val between = text.substring(cursor, m.range.first)
            sb.append(between)
            // Sentence boundary resets contextual clue
            if (between.any { it == '.' || it == '!' || it == '?' || it == '…' || it == '\n' }) {
                prevLower = null
            }

            val word = m.value
            val normalized = APOSTROPHE_REGEX.replace(word.lowercase(), "'")
            val nextLower = matches.getOrNull(idx + 1)?.let { nm ->
                val gap = text.substring(m.range.last + 1, nm.range.first)
                if (gap.any { it == '.' || it == '!' || it == '?' || it == '…' || it == '\n' }) null
                else APOSTROPHE_REGEX.replace(nm.value.lowercase(), "'")
            }
            sb.append(stressWord(word, normalized, prevLower, nextLower))
            prevLower = normalized
            cursor = m.range.last + 1
        }
        sb.append(text.substring(cursor))
        return sb.toString()
    }

    private fun stressWord(word: String, normalized: String, prevLower: String?, nextLower: String?): String {
        // Respect existing explicit stress marks
        if (word.indexOf(ACUTE) >= 0 || word.indexOf(GRAVE) >= 0) return word

        // Tier 0: User-defined stress / pronunciation overrides
        val userOverride = overrides?.lookup(normalized)
        if (userOverride != null) return userOverride

        val vowelIndices = mutableListOf<Int>()
        for (i in word.indices) {
            if (word[i] in VOWELS) vowelIndices.add(i)
        }
        if (vowelIndices.size <= 1) return word
        if (normalized in UNSTRESSED_WORDS) return word

        // Unambiguous high-frequency Ukrainian pronouns (prevents rare homographs like во́на [currency] or ї́ї)
        PRONOUN_VOWEL_INDEX[normalized]?.let { vIdx ->
            if (vIdx in vowelIndices.indices) {
                return insertStressMarks(word, intArrayOf(vowelIndices[vIdx] + 1))
            }
        }

        // Tier 1: 2.88M-word binary dictionary lookup
        when (val res = dict?.lookup(normalized)) {
            is StressLookupResult.Unambiguous -> {
                val single = pickSingleStress(normalized, res.positions)
                return insertStressMarks(word, single)
            }
            is StressLookupResult.Ambiguous -> {
                val chosen = disambiguateHomograph(word, normalized, res.variants, prevLower, nextLower)
                val single = pickSingleStress(normalized, chosen)
                return insertStressMarks(word, single)
            }
            null -> Unit
        }

        // Tier 2: Bundled 1.17 MB ONNX neural accentor for OOV words (fantasy/LitRPG names, neologisms)
        val neuralPos = neuralAccentor?.predictInsertPosition(normalized)
        if (neuralPos != null && isAfterVowel(word, neuralPos)) {
            return insertStressMarks(word, intArrayOf(neuralPos))
        }

        // Tier 3a: Prefix-stripping fallback for Ukrainian compounds
        val prefixes = listOf(
            "не", "най", "пере", "проти", "над", "під", "роз", "без",
            "анти", "супер", "мега", "ультра", "архі"
        )
        for (pref in prefixes) {
            if (normalized.startsWith(pref) && normalized.length - pref.length >= 4) {
                val stem = normalized.substring(pref.length)
                val stemPos = when (val r = dict?.lookup(stem)) {
                    is StressLookupResult.Unambiguous -> r.positions
                    is StressLookupResult.Ambiguous -> r.variants.firstOrNull()?.positions
                    null -> null
                }
                if (stemPos != null && stemPos.isNotEmpty()) {
                    return insertStressMarks(word, stemPos.map { it + pref.length }.toIntArray())
                }
            }
        }

        // Tier 3b: Penultimate-syllable heuristic fallback
        val fallbackInsertPos = vowelIndices[vowelIndices.size - 2] + 1
        return insertStressMarks(word, intArrayOf(fallbackInsertPos))
    }

    /**
     * Some dictionary entries store two alternative stress positions (e.g. наза́вжди́, нена́си́тна).
     * Emitting two primary acute marks degrades HolosTTS prosody; pick a single canonical stress.
     */
    private fun pickSingleStress(normalized: String, positions: IntArray): IntArray {
        if (positions.size <= 1) return positions
        val neuralPos = neuralAccentor?.predictInsertPosition(normalized)
        if (neuralPos != null && neuralPos in positions) {
            return intArrayOf(neuralPos)
        }
        return intArrayOf(positions.first())
    }

    private fun disambiguateHomograph(
        word: String,
        normalized: String,
        variants: List<StressVariant>,
        prevLower: String?,
        nextLower: String?,
    ): IntArray {
        // 0. Numeral disambiguation (e.g. "со́рок секунд" vs "соро́к" birds)
        if (variants.any { it.hasTag(MorphTag.UPOS_NUM) } && nextLower != null) {
            variants.firstOrNull { it.hasTag(MorphTag.UPOS_NUM) }?.let { return it.positions }
        }
        if (prevLower != null) {
            // 1. Preposition case government (highest precision for Gen/Loc/Ins/Dat homographs)
            if (prevLower in GEN_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_GEN) && it.hasTag(MorphTag.NUM_SING) }
                    ?.let { return it.positions }
                variants.firstOrNull { it.hasTag(MorphTag.CASE_GEN) }
                    ?.let { return it.positions }
            }
            if (prevLower in LOC_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_LOC) }
                    ?.let { return it.positions }
            }
            if (prevLower in INS_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_INS) }
                    ?.let { return it.positions }
            }
            if (prevLower in DAT_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_DAT) }
                    ?.let { return it.positions }
            }
            // 2. Subject pronoun / negation -> prefer VERB over NOUN/ADJ
            if (prevLower in VERB_CUES) {
                variants.firstOrNull { it.isVerb() }?.let { return it.positions }
            }
            // 3. Plural determiner / numeral -> prefer Number=Plur (e.g. "дві ру́ки", "ці ві́кна")
            if (prevLower in PLURAL_CUES) {
                variants.firstOrNull { it.hasTag(MorphTag.NUM_PLUR) }?.let { return it.positions }
            }
        }

        // 4. If neural accentor is available and predicts one of the valid dictionary variants, use it!
        val neuralPos = neuralAccentor?.predictInsertPosition(normalized)
        if (neuralPos != null) {
            variants.firstOrNull { neuralPos in it.positions }?.let { return it.positions }
        }

        // 5. Default to primary Nominative/Singular or first dictionary variant
        return variants.firstOrNull { it.hasTag(MorphTag.CASE_NOM) }?.positions
            ?: variants.first().positions
    }

    private fun isAfterVowel(word: String, insertPos: Int): Boolean {
        val vowelIdx = insertPos - 1
        return vowelIdx in word.indices && word[vowelIdx] in VOWELS
    }

    private fun insertStressMarks(word: String, insertPositions: IntArray): String {
        val sorted = insertPositions.filter { it in 1..word.length }.distinct().sortedDescending()
        if (sorted.isEmpty()) return word
        val sb = StringBuilder(word)
        for (idx in sorted) {
            sb.insert(idx, ACUTE)
        }
        return sb.toString()
    }
}
