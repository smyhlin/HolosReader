package com.holos.reader.g2p

/**
 * 4-tier on-device Ukrainian Stress Engine:
 * - Tier 0: User overrides (`stress_user.txt`, including multi-word phrase overrides)
 * - Tier 1: 2,886,186-word binary dictionary (`uk_stress.bin` v2 from «Словники України»)
 *   - Unambiguous words resolve in ~0.02 ms via `MappedByteBuffer`
 *   - Ambiguous homographs (36,414 forms) resolved using up to 3-token syntactic lookback
 *     (preposition case government across intervening adjectives, subject pronouns, numerals)
 * - Tier 2: Bundled 1.17 MB ONNX neural accentor (`accentor-lite.onnx`) for OOV words & neutral homographs
 * - Tier 3: Prefix-stripping stem lookup + penultimate-syllable safety fallback
 */
fun interface ContextualHomographResolver {
    fun predictVowelOrdinal(
        contextText: String,
        wordStart: Int,
        wordEnd: Int,
        candidateVowelOrdinals: IntArray,
    ): Int?
}

class UkrainianStressifier(
    private val dict: StressDictionaryLookup?,
    private val overrides: UserStressOverrides? = null,
    private val neuralAccentor: WordAccentor? = null,
    @Volatile var contextualResolver: ContextualHomographResolver? = null,
) {

    companion object {
        // 0-based vowel index for unambiguous high-frequency Ukrainian pronouns
        // Standard Ukrainian orthoepy: вона́, воно́, вони́, її́, його́, мене́, тебе́, себе́, мені́, тобі́, собі́
        private val PRONOUN_VOWEL_INDEX = mapOf(
            "вона" to 1, "воно" to 1, "вони" to 1,
            "її" to 1, "його" to 1, "мене" to 1, "тебе" to 1, "себе" to 1,
            "мені" to 1, "тобі" to 1, "собі" to 1,
            "цього" to 1, "того" to 1, "свого" to 1, "мого" to 1, "твого" to 1,
        )
        const val ACUTE = '\u0301'
        const val GRAVE = '\u0300'
        private const val VOWELS = "аеєиіїоуюяАЕЄИІЇОУЮЯ"
        private val WORD_REGEX = Regex("[А-ЩЬЮЯҐЄІЇа-щьюяґєії'’ʼ\\u0301\\u0300]+")
        private val APOSTROPHE_REGEX = Regex("[’`ʼ]")

        // True monosyllabic clitics only; multi-syllable words (але́, пе́ред, че́рез, бі́ля, се́ред) carry real stress (H20)
        private val UNSTRESSED_WORDS = setOf(
            "в", "у", "з", "із", "зі", "до", "на", "по", "за", "під", "над", "при", "про",
            "без", "від", "для", "між", "крім",
            "і", "й", "та", "а", "бо", "що", "щоб", "як", "чи", "ні", "не", "би", "б", "же", "ж"
        )

        private val GEN_PREPOSITIONS = setOf(
            "біля", "до", "від", "без", "для", "після", "коло", "поряд", "посеред",
            "навколо", "крім", "окрім", "замість", "проти", "серед", "край", "близько",
            "вздовж", "уздовж", "поруч", "усередині", "всередині", "зверху", "знизу", "з"
        )
        private val LOC_PREPOSITIONS = setOf("у", "в", "на", "при", "по")
        private val INS_PREPOSITIONS = setOf("над", "під", "перед", "між", "поміж", "понад", "попід")
        private val DAT_PREPOSITIONS = setOf("завдяки", "назустріч", "всупереч", "подібно")
        private val VERB_CUES = setOf("я", "ти", "він", "вона", "воно", "ми", "ви", "вони", "не")
        private val PLURAL_CUES = setOf(
            "два", "дві", "три", "чотири", "обоє", "обидва", "обидві",
            "всі", "усі", "ці", "ті", "мої", "твої", "свої", "наші", "ваші", "їхні"
        )
        private val NON_NOUN_NEXT = setOf(
            "і", "й", "та", "а", "але", "бо", "що", "щоб", "як", "чи", "не", "у", "в", "на", "до", "від", "з"
        )
    }

    fun stressify(rawText: String): String {
        val text = overrides?.applyPhraseOverrides(rawText) ?: rawText
        val matches = WORD_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return text

        val sb = StringBuilder(text.length + matches.size * 2)
        var cursor = 0
        val recentTokens = ArrayDeque<String>(3)

        for ((idx, m) in matches.withIndex()) {
            val between = text.substring(cursor, m.range.first)
            sb.append(between)
            if (between.any { it == '.' || it == '!' || it == '?' || it == '…' || it == '\n' }) {
                recentTokens.clear()
            }

            val word = m.value
            val normalized = APOSTROPHE_REGEX.replace(word.lowercase(), "'")
            val nextLower = matches.getOrNull(idx + 1)?.let { nm ->
                val gap = text.substring(m.range.last + 1, nm.range.first)
                if (gap.any { it == '.' || it == '!' || it == '?' || it == '…' || it == '\n' }) null
                else APOSTROPHE_REGEX.replace(nm.value.lowercase(), "'")
            }
            sb.append(stressWord(word, normalized, recentTokens.toList(), nextLower, text, m.range.first, m.range.last + 1))

            if (recentTokens.size >= 3) recentTokens.removeFirst()
            recentTokens.addLast(normalized)
            cursor = m.range.last + 1
        }
        sb.append(text.substring(cursor))
        return sb.toString()
    }

    private fun stressWord(
        word: String,
        normalized: String,
        recentTokens: List<String>,
        nextLower: String?,
        fullText: String = word,
        wordStart: Int = 0,
        wordEnd: Int = word.length,
    ): String {
        // Tier 0: User-defined stress / pronunciation overrides ALWAYS win over author marks (H20)
        val baseNormalized = normalized.replace(ACUTE.toString(), "").replace(GRAVE.toString(), "")
        val userOverride = overrides?.lookup(baseNormalized)
        if (userOverride != null) return userOverride

        if (word.indexOf(ACUTE) >= 0 || word.indexOf(GRAVE) >= 0) return word

        val vowelIndices = mutableListOf<Int>()
        for (i in word.indices) {
            if (word[i] in VOWELS) vowelIndices.add(i)
        }
        if (vowelIndices.size <= 1) return word
        if (normalized in UNSTRESSED_WORDS) return word

        // Tier 1: 2.88M-word binary dictionary lookup
        when (val res = dict?.lookup(normalized)) {
            is StressLookupResult.Unambiguous -> {
                val single = pickSingleStress(normalized, res.positions)
                return insertStressMarks(word, single)
            }
            is StressLookupResult.Ambiguous -> {
                val chosen = disambiguateHomograph(word, normalized, vowelIndices, res.variants, recentTokens, nextLower, fullText, wordStart, wordEnd)
                val single = pickSingleStress(normalized, chosen)
                return insertStressMarks(word, single)
            }
            null -> Unit
        }

        // Tier 1b: Dictionary-miss pronoun stress fallback (H20)
        PRONOUN_VOWEL_INDEX[normalized]?.let { vIdx ->
            if (vIdx in vowelIndices.indices) {
                return insertStressMarks(word, intArrayOf(vowelIndices[vIdx] + 1))
            }
        }

        // Tier 2a: Safe prefix-stripping dictionary stem lookup before neural OOV guessing (H20)
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

        // Tier 2b: Bundled 1.17 MB ONNX neural accentor for OOV words
        val neuralPos = neuralAccentor?.predictInsertPosition(normalized)
        if (neuralPos != null && isAfterVowel(word, neuralPos)) {
            return insertStressMarks(word, intArrayOf(neuralPos))
        }

        // Tier 3b: Penultimate-syllable heuristic fallback
        val fallbackInsertPos = vowelIndices[vowelIndices.size - 2] + 1
        return insertStressMarks(word, intArrayOf(fallbackInsertPos))
    }

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
        vowelIndices: List<Int>,
        variants: List<StressVariant>,
        recentTokens: List<String>,
        nextLower: String?,
        fullText: String,
        wordStart: Int,
        wordEnd: Int,
    ): IntArray {
        // Tier 1.5: Optional sentence-level contextual ONNX homograph resolver (aloudreader)
        val resolver = contextualResolver
        if (resolver != null && variants.size > 1) {
            val ordToVariant = LinkedHashMap<Int, StressVariant>()
            for (v in variants) {
                val p0 = v.positions.firstOrNull() ?: continue
                val vOrd = vowelIndices.indexOf(p0 - 1)
                if (vOrd >= 0 && vOrd !in ordToVariant) ordToVariant[vOrd] = v
            }
            if (ordToVariant.size > 1) {
                val predictedOrd = try {
                    resolver.predictVowelOrdinal(fullText, wordStart, wordEnd, ordToVariant.keys.toIntArray())
                } catch (_: Throwable) {
                    null
                }
                if (predictedOrd != null) {
                    ordToVariant[predictedOrd]?.let { return it.positions }
                }
            }
        }

        val prevLower = recentTokens.lastOrNull()

        // 0. Numeral disambiguation (e.g. "со́рок секунд", but NOT when followed by preposition/conjunction)
        if (variants.any { it.hasTag(MorphTag.UPOS_NUM) } && nextLower != null && nextLower !in NON_NOUN_NEXT) {
            variants.firstOrNull { it.hasTag(MorphTag.UPOS_NUM) }?.let { return it.positions }
        }

        // 1. Immediate verb/plural cues
        if (prevLower != null) {
            if (prevLower in VERB_CUES) {
                variants.firstOrNull { it.isVerb() }?.let { return it.positions }
            }
            if (prevLower in PLURAL_CUES) {
                variants.firstOrNull { it.hasTag(MorphTag.NUM_PLUR) }?.let { return it.positions }
            }
        }

        // 2. Up to 3-token lookback for preposition case government across intervening adjectives
        //    (e.g., "біля великого вікна́", "у старому лі́сі")
        for (i in recentTokens.indices.reversed()) {
            val tok = recentTokens[i]
            if (tok in GEN_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_GEN) && it.hasTag(MorphTag.NUM_SING) }
                    ?.let { return it.positions }
                variants.firstOrNull { it.hasTag(MorphTag.CASE_GEN) }
                    ?.let { return it.positions }
                break
            }
            if (tok in LOC_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_LOC) }?.let { return it.positions }
                break
            }
            if (tok in INS_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_INS) }?.let { return it.positions }
                break
            }
            if (tok in DAT_PREPOSITIONS) {
                variants.firstOrNull { it.hasTag(MorphTag.CASE_DAT) }?.let { return it.positions }
                break
            }
        }

        // 3. Neural accentor tie-breaker restricted to valid dictionary variants
        val neuralPos = neuralAccentor?.predictInsertPosition(normalized)
        if (neuralPos != null) {
            variants.firstOrNull { neuralPos in it.positions }?.let { return it.positions }
        }

        // 4. Default to Nominative or first dictionary variant
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
