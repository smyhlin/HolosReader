package com.holos.reader.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import kotlin.math.ceil

/**
 * Bionic Reading fixation formatter + phonetic-weighted word timeline for 1:1 TTS word highlighting.
 */
object BionicTextHelper {

    private val RE_WORD = Regex("""[\p{L}\p{N}\p{M}]+(?:['’ʼ-][\p{L}\p{N}\p{M}]+)*""")
    private const val VOWELS = "аеєиіїоуюяАЕЄИІЇОУЮЯaeiouyAEIOUY"

    data class WordSpan(
        val start: Int,
        val end: Int,
        val startFraction: Float,
        val endFraction: Float,
    )

    /**
     * Applies Bionic Reading fixation bolding to the leading portion of each word (>= 2 chars),
     * preserving all existing EPUB and highlight spans.
     */
    fun applyBionic(ssb: SpannableStringBuilder) {
        val text = ssb.toString()
        for (m in RE_WORD.findAll(text)) {
            val s = m.range.first
            val len = m.range.last - s + 1
            if (len < 2) continue
            val boldLen = when {
                len <= 3 -> 1
                len <= 5 -> 2
                len <= 8 -> 3
                len <= 12 -> 4
                else -> ceil(len * 0.42).toInt().coerceAtLeast(5)
            }.coerceAtMost(len)
            ssb.setSpan(StyleSpan(Typeface.BOLD), s, s + boldLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Builds a normalized [0f..1f] speaking-time map for every word in [text], weighting by
     * syllable/character spoken duration + Ukrainian punctuation pauses (commas, dashes, sentence stops).
     */
    fun buildWordTimeline(text: String): List<WordSpan> {
        val matches = RE_WORD.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val weights = FloatArray(matches.size)
        var totalWeight = 0f

        for (i in matches.indices) {
            val m = matches[i]
            val w = m.value
            var vowelCount = 0
            var digitCount = 0
            for (ch in w) {
                if (ch in VOWELS) vowelCount++
                else if (ch.isDigit()) digitCount++
            }
            // Spoken duration scales with syllables + consonants + expanded digits
            var weight = 1.4f + vowelCount * 1.35f + (w.length - vowelCount) * 0.35f + digitCount * 2.2f

            // Add realistic prosody pause weight for punctuation immediately following this word
            val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            if (m.range.last + 1 < nextStart) {
                val gap = text.substring(m.range.last + 1, nextStart)
                when {
                    '.' in gap || '!' in gap || '?' in gap || '…' in gap -> weight += 5.2f
                    ';' in gap || ':' in gap || '—' in gap || '–' in gap -> weight += 3.4f
                    ',' in gap -> weight += 2.4f
                }
            }
            weights[i] = weight
            totalWeight += weight
        }

        if (totalWeight <= 0f) return emptyList()
        val out = ArrayList<WordSpan>(matches.size)
        var acc = 0f
        for (i in matches.indices) {
            val startFrac = acc / totalWeight
            acc += weights[i]
            val endFrac = (acc / totalWeight).coerceAtMost(1f)
            val r = matches[i].range
            out.add(WordSpan(r.first, r.last + 1, startFrac, endFrac))
        }
        return out
    }

    fun findWordAt(timeline: List<WordSpan>, fraction: Float): WordSpan? {
        if (timeline.isEmpty()) return null
        val f = fraction.coerceIn(0f, 0.9999f)
        var lo = 0
        var hi = timeline.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val item = timeline[mid]
            when {
                f < item.startFraction -> hi = mid - 1
                f >= item.endFraction -> lo = mid + 1
                else -> return item
            }
        }
        return timeline.last()
    }
}
