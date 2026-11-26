package com.holos.reader.g2p

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Compact morphological tag bytes preserved from patriotyk/ukrainian-word-stress («Словники України»).
 */
object MorphTag {
    const val NUM_SING: Int = 0x11
    const val NUM_PLUR: Int = 0x12
    const val CASE_NOM: Int = 0x20
    const val CASE_GEN: Int = 0x21
    const val CASE_DAT: Int = 0x22
    const val CASE_ACC: Int = 0x23
    const val CASE_INS: Int = 0x24
    const val CASE_LOC: Int = 0x25
    const val CASE_VOC: Int = 0x26
    const val UPOS_NOUN: Int = 0x61
    const val UPOS_ADJ: Int = 0x62
    const val UPOS_VERB: Int = 0x67
    const val UPOS_PROPN: Int = 0x68
    const val UPOS_ADV: Int = 0x69
    const val UPOS_NOUN2: Int = 0x6A
    const val UPOS_NUM: Int = 0x6B
}

data class StressVariant(
    val positions: IntArray,
    val tags: IntArray,
) {
    fun hasTag(tag: Int): Boolean = tags.any { it == tag }
    fun isNoun(): Boolean = hasTag(MorphTag.UPOS_NOUN) || hasTag(MorphTag.UPOS_NOUN2) || hasTag(MorphTag.UPOS_PROPN)
    fun isVerb(): Boolean = hasTag(MorphTag.UPOS_VERB)
    fun isAdj(): Boolean = hasTag(MorphTag.UPOS_ADJ)
}

sealed class StressLookupResult {
    data class Unambiguous(val positions: IntArray) : StressLookupResult()
    data class Ambiguous(val variants: List<StressVariant>) : StressLookupResult()
}

/**
 * Zero-heap-overhead memory-mapped binary dictionary v2 of 2,886,186 Ukrainian wordforms
 * (including 36,414 homographs with morphological Case/Number/UPOS tags) compiled from
 * patriotyk/ukrainian-word-stress («Словники України»).
 */
fun interface StressDictionaryLookup {
    fun lookup(wordLower: String): StressLookupResult?
}

class UkrainianStressDict(file: File) : StressDictionaryLookup, Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    private val buf: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        .apply { order(ByteOrder.LITTLE_ENDIAN) }

    val blockCount: Int
    private val indexOffset: Int
    private val keyTableOffset: Int

    init {
        val magic = "${buf.get(0).toInt().toChar()}${buf.get(1).toInt().toChar()}" +
            "${buf.get(2).toInt().toChar()}${buf.get(3).toInt().toChar()}"
        require(magic == "UKST") { "Invalid stress dictionary magic: $magic" }
        val version = buf.getInt(4)
        require(version == 2) { "Expected stress dictionary v2, got $version" }
        blockCount = buf.getInt(8)
        indexOffset = buf.getInt(12)
        keyTableOffset = indexOffset + blockCount * 4
    }

    override fun lookup(wordLower: String): StressLookupResult? {
        if (wordLower.isEmpty() || blockCount == 0) return null
        val target = wordLower.toByteArray(Charsets.UTF_8)

        var lo = 0
        var hi = blockCount - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val relOff = buf.getInt(indexOffset + mid * 4)
            val entryPos = keyTableOffset + relOff
            val keyLen = buf.get(entryPos + 8).toInt() and 0xFF
            val cmp = compareBytes(entryPos + 9, keyLen, target)
            if (cmp <= 0) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return null

        val relOff = buf.getInt(indexOffset + best * 4)
        val entryPos = keyTableOffset + relOff
        val dataOff = buf.getInt(entryPos)
        val dataLen = buf.getInt(entryPos + 4)

        return scanBlock(dataOff, dataOff + dataLen, target)
    }

    private fun scanBlock(start: Int, end: Int, target: ByteArray): StressLookupResult? {
        var pos = start
        val currentKey = ByteArray(256)

        while (pos < end) {
            val lcp = buf.get(pos++).toInt() and 0xFF
            val sufLen = buf.get(pos++).toInt() and 0xFF
            for (i in 0 until sufLen) {
                currentKey[lcp + i] = buf.get(pos++)
            }
            val currentLen = lcp + sufLen
            val valLen = buf.get(pos++).toInt() and 0xFF
            val valStart = pos
            pos += valLen

            val cmp = compareByteArrays(currentKey, currentLen, target)
            if (cmp == 0) {
                return decodeValue(valStart)
            }
            if (cmp > 0) return null
        }
        return null
    }

    private fun decodeValue(start: Int): StressLookupResult {
        var p = start
        val header = buf.get(p++).toInt() and 0xFF
        if ((header and 0x80) == 0) {
            val nPos = header
            val positions = IntArray(nPos) { buf.get(p++).toInt() and 0xFF }
            return StressLookupResult.Unambiguous(positions)
        }
        val nVariants = header and 0x7F
        val variants = ArrayList<StressVariant>(nVariants)
        repeat(nVariants) {
            val nPos = buf.get(p++).toInt() and 0xFF
            val positions = IntArray(nPos) { buf.get(p++).toInt() and 0xFF }
            val nTags = buf.get(p++).toInt() and 0xFF
            val tags = IntArray(nTags) { buf.get(p++).toInt() and 0xFF }
            variants.add(StressVariant(positions, tags))
        }
        return StressLookupResult.Ambiguous(variants)
    }

    private fun compareBytes(bufOffset: Int, bufLen: Int, target: ByteArray): Int {
        val minLen = minOf(bufLen, target.size)
        for (i in 0 until minLen) {
            val a = buf.get(bufOffset + i).toInt() and 0xFF
            val b = target[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return bufLen - target.size
    }

    private fun compareByteArrays(a: ByteArray, aLen: Int, b: ByteArray): Int {
        val minLen = minOf(aLen, b.size)
        for (i in 0 until minLen) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return aLen - b.size
    }

    override fun close() {
        channel.close()
        raf.close()
    }
}
