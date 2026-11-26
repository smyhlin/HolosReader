package com.holos.tts

import org.json.JSONObject
import java.io.File

/**
 * Pure-Kotlin SentencePiece Unigram tokenizer compatible with HuggingFace XLM-RoBERTa
 * `tokenizer.json` files (used by `speedbrain-emotion-uk-onnx` and
 * `aloudreader/uk-stress-token-classifier`).
 */
class UnigramTokenizer private constructor(
    private val pieceToId: HashMap<String, Int>,
    private val pieceScores: HashMap<String, Double>,
    private val maxPieceLen: Int,
    val bosId: Int,
    val padId: Int,
    val eosId: Int,
    val unkId: Int,
) {

    data class TokenSpan(
        val id: Int,
        val charStart: Int,
        val charEnd: Int,
    )

    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val spans: List<TokenSpan>,
    )

    fun encode(text: String, maxLength: Int = 160): Encoded {
        val firstNonWs = text.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs < 0) {
            return Encoded(
                inputIds = longArrayOf(bosId.toLong(), eosId.toLong()),
                attentionMask = longArrayOf(1L, 1L),
                spans = listOf(TokenSpan(bosId, -1, -1), TokenSpan(eosId, -1, -1)),
            )
        }

        val lastNonWs = text.indexOfLast { !it.isWhitespace() }
        val normChars = StringBuilder((lastNonWs - firstNonWs + 1) + 16)
        val normToOrig = ArrayList<Int>((lastNonWs - firstNonWs + 1) + 16)
        var prevSpace = true
        for (i in firstNonWs..lastNonWs) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                if (!prevSpace) {
                    normChars.append(METASPACE)
                    normToOrig.add(i)
                    prevSpace = true
                }
            } else {
                if (prevSpace && normChars.isEmpty()) {
                    normChars.append(METASPACE)
                    normToOrig.add(i)
                }
                normChars.append(ch)
                normToOrig.add(i)
                prevSpace = false
            }
        }

        val norm = normChars.toString()
        val n = norm.length
        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val bestLen = IntArray(n + 1) { 1 }
        val bestId = IntArray(n + 1) { unkId }
        bestScore[0] = 0.0

        for (i in 0 until n) {
            val base = bestScore[i]
            if (base == Double.NEGATIVE_INFINITY) continue
            val maxEnd = minOf(n, i + maxPieceLen)
            var matchedAny = false
            for (end in (i + 1)..maxEnd) {
                val sub = norm.substring(i, end)
                val id = pieceToId[sub]
                if (id != null) {
                    matchedAny = true
                    val score = base + (pieceScores[sub] ?: -12.0)
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        bestLen[end] = end - i
                        bestId[end] = id
                    }
                }
            }
            if (!matchedAny) {
                val score = base - 25.0
                if (score > bestScore[i + 1]) {
                    bestScore[i + 1] = score
                    bestLen[i + 1] = 1
                    bestId[i + 1] = unkId
                }
            }
        }

        val rawTokens = ArrayList<TokenSpan>()
        var pos = n
        while (pos > 0) {
            val len = bestLen[pos].coerceAtLeast(1)
            val start = (pos - len).coerceAtLeast(0)
            val origStart = normToOrig[start]
            val origEnd = normToOrig[pos - 1] + 1
            rawTokens.add(TokenSpan(bestId[pos], origStart, origEnd))
            pos = start
        }
        rawTokens.reverse()

        val maxBody = (maxLength - 2).coerceAtLeast(1)
        val trimmed = if (rawTokens.size > maxBody) rawTokens.subList(0, maxBody) else rawTokens

        val total = trimmed.size + 2
        val ids = LongArray(total)
        val mask = LongArray(total) { 1L }
        val spans = ArrayList<TokenSpan>(total)

        ids[0] = bosId.toLong()
        spans.add(TokenSpan(bosId, -1, -1))
        for (i in trimmed.indices) {
            ids[i + 1] = trimmed[i].id.toLong()
            spans.add(trimmed[i])
        }
        ids[total - 1] = eosId.toLong()
        spans.add(TokenSpan(eosId, -1, -1))

        return Encoded(ids, mask, spans)
    }

    companion object {
        private const val METASPACE = '\u2581'

        fun fromFile(tokenizerJsonFile: File): UnigramTokenizer {
            require(tokenizerJsonFile.exists()) { "Missing tokenizer.json: ${tokenizerJsonFile.path}" }
            return fromJsonString(tokenizerJsonFile.readText(Charsets.UTF_8))
        }

        fun fromJsonString(jsonText: String): UnigramTokenizer {
            val root = JSONObject(jsonText)
            var bosId = 0
            var padId = 1
            var eosId = 2
            var unkId = 3

            val added = root.optJSONArray("added_tokens")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val obj = added.getJSONObject(i)
                    val id = obj.getInt("id")
                    when (obj.optString("content")) {
                        "<s>" -> bosId = id
                        "<pad>" -> padId = id
                        "</s>" -> eosId = id
                        "<unk>" -> unkId = id
                    }
                }
            }

            val model = root.getJSONObject("model")
            val vocabArr = model.getJSONArray("vocab")
            val vocab = ArrayList<Pair<String, Double>>(vocabArr.length())
            for (i in 0 until vocabArr.length()) {
                val entry = vocabArr.getJSONArray(i)
                vocab.add(entry.getString(0) to entry.optDouble(1, -10.0))
            }
            return fromVocab(vocab, bosId = bosId, padId = padId, eosId = eosId, unkId = unkId)
        }

        fun fromVocab(
            vocab: List<Pair<String, Double>>,
            bosId: Int = 0,
            padId: Int = 1,
            eosId: Int = 2,
            unkId: Int = 3,
        ): UnigramTokenizer {
            val pieceToId = HashMap<String, Int>(vocab.size * 4 / 3 + 4)
            val pieceScores = HashMap<String, Double>(vocab.size * 4 / 3 + 4)
            var maxLen = 1
            for (i in vocab.indices) {
                val (piece, score) = vocab[i]
                pieceToId[piece] = i
                pieceScores[piece] = score
                if (piece.length > maxLen && piece.length <= 32) {
                    maxLen = piece.length
                }
            }
            return UnigramTokenizer(
                pieceToId = pieceToId,
                pieceScores = pieceScores,
                maxPieceLen = maxLen,
                bosId = bosId,
                padId = padId,
                eosId = eosId,
                unkId = unkId,
            )
        }
    }
}
