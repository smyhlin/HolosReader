package com.holos.reader.g2p

/**
 * Splits long EPUB paragraphs into sentence/clause chunks safely below HolosTTS's
 * 1000-frame (~25 s) utterance limit while minimizing latency to first audio.
 */
object SentenceSplitter {

    private const val DEFAULT_MAX_CHARS = 200
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+")
    private val CLAUSE_SPLIT = Regex("(?<=[;:,])\\s+")

    fun split(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= maxChars) return listOf(cleaned)

        val result = mutableListOf<String>()
        val sentences = SENTENCE_SPLIT.split(cleaned)

        for (rawSentence in sentences) {
            val sentence = rawSentence.trim()
            if (sentence.isEmpty()) continue
            if (sentence.length <= maxChars) {
                result.add(sentence)
            } else {
                splitLongSentence(sentence, maxChars, result)
            }
        }
        // Merge ultra-short adjacent sentences (< 45 chars) so HolosTTS never gets unstable 1-word fragments
        if (result.size <= 1) return result
        val merged = ArrayList<String>(result.size)
        for (chunk in result) {
            val prev = merged.lastOrNull()
            if (prev != null && (prev.length < 45 || chunk.length < 28) && prev.length + 1 + chunk.length <= maxChars) {
                merged[merged.lastIndex] = "$prev $chunk"
            } else {
                merged.add(chunk)
            }
        }
        return merged
    }

    private fun splitLongSentence(sentence: String, maxChars: Int, out: MutableList<String>) {
        val clauses = CLAUSE_SPLIT.split(sentence)
        val current = StringBuilder()

        for (rawClause in clauses) {
            val clause = rawClause.trim()
            if (clause.isEmpty()) continue
            if (current.isEmpty()) {
                if (clause.length <= maxChars) {
                    current.append(clause)
                } else {
                    splitByWords(clause, maxChars, out)
                }
            } else if (current.length + 1 + clause.length <= maxChars) {
                current.append(' ').append(clause)
            } else {
                out.add(current.toString())
                current.clear()
                if (clause.length <= maxChars) {
                    current.append(clause)
                } else {
                    splitByWords(clause, maxChars, out)
                }
            }
        }
        if (current.isNotEmpty()) {
            out.add(current.toString())
        }
    }

    private fun splitByWords(text: String, maxChars: Int, out: MutableList<String>) {
        val words = text.split(Regex("\\s+"))
        val current = StringBuilder()
        for (word in words) {
            if (word.isEmpty()) continue
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= maxChars) {
                current.append(' ').append(word)
            } else {
                out.add(current.toString())
                current.clear()
                current.append(word)
            }
        }
        if (current.isNotEmpty()) {
            out.add(current.toString())
        }
    }
}
