package com.holos.reader.g2p

import com.holos.reader.tts.CustomVoiceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Batch2Milestone2Test {

    // #26: Leading-zero Ukrainian decimals & 4+ fractional digits preserve exact numerical value
    @Test
    fun decimalNormalizationPreservesLeadingZeroAndHighPrecisionFractions() {
        val n0001Comma = UkrainianTextNormalizer.normalize("0,001")
        assertTrue("Expected thousandth for 0,001, got: $n0001Comma", n0001Comma.contains("нуль цілих одна тисячна"))

        val n00001Dot = UkrainianTextNormalizer.normalize("0.0001")
        assertTrue("Expected ten-thousandth for 0.0001, got: $n00001Dot", n00001Dot.contains("нуль цілих одна десятитисячна"))

        val grouped = UkrainianTextNormalizer.normalize("10,000")
        assertTrue("Expected 10,000 grouped integer -> десять тисяч, got: $grouped", grouped.contains("десять тисяч"))
    }

    // #37: Unicode minus U+2212 speaks "мінус" identically to ASCII '-'
    @Test
    fun unicodeMinusMatchesAsciiMinusInTemperaturesAndSignedValues() {
        val uTemp = UkrainianTextNormalizer.normalize("\u22125 °C")
        val aTemp = UkrainianTextNormalizer.normalize("-5 °C")
        assertEquals(aTemp, uTemp)
        assertTrue(uTemp.contains("мінус"))

        val uHp = UkrainianTextNormalizer.normalize("Зміна: [\u221225 HP]")
        val aHp = UkrainianTextNormalizer.normalize("Зміна: [-25 HP]")
        assertEquals(aHp, uHp)
        assertTrue(uHp.contains("мінус"))
    }

    @Test
    fun spokenPercentAndPermilleKeepTheirSigns() {
        val normalize = UkrainianTextNormalizer::normalize
        assertEquals("мінус п'ять відсотків", normalize("−5%", false))
        assertEquals("мінус п'ять відсотків", normalize("-5%", false))
        assertEquals("плюс два проміле", normalize("+2‰", false))
        assertEquals("плюс-мінус п'ять відсотків", normalize("±5%", false))
        assertTrue("мінус" !in normalize("5-10%", false))
    }

    // #28: Strict .npy header validation rejects float64, big-endian, int32, and wrong shapes
    private fun makeNpy(descr: String, shape: String, payload: ByteArray, major: Int = 1): ByteArray {
        val dict = "{'descr': '$descr', 'fortran_order': False, 'shape': $shape, }"
        val preamble = if (major == 1) 10 else 12
        val pad = (16 - ((preamble + dict.length + 1) % 16)) % 16
        val hdr = (dict + " ".repeat(pad) + "\n").toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(preamble + hdr.size + payload.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0x93.toByte())
        bb.put("NUMPY".toByteArray(Charsets.US_ASCII))
        bb.put(major.toByte())
        bb.put(0.toByte())
        if (major == 1) bb.putShort(hdr.size.toShort()) else bb.putInt(hdr.size)
        bb.put(hdr)
        bb.put(payload)
        return out
    }

    @Test
    fun customVoiceNpyAcceptsLittleEndianFloat32AndRejectsOtherDtypesAndShapes() {
        val goodPayload = ByteArray(256 * 4)
        val bb = ByteBuffer.wrap(goodPayload).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 1..256) bb.putFloat(i.toFloat())

        val ok1 = CustomVoiceLoader.parseVoiceBytes(makeNpy("<f4", "(256,)", goodPayload, major = 1))
        assertEquals(1.0f, ok1[0], 1e-5f)
        assertEquals(256.0f, ok1[255], 1e-5f)

        val ok2 = CustomVoiceLoader.parseVoiceBytes(makeNpy("<f4", "(1, 256)", goodPayload, major = 2))
        assertEquals(2.0f, ok2[1], 1e-5f)

        fun assertRejected(label: String, bytes: ByteArray) {
            var threw = false
            try {
                CustomVoiceLoader.parseVoiceBytes(bytes)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected rejection for $label", threw)
        }

        assertRejected("float64", makeNpy("<f8", "(256,)", ByteArray(256 * 8)))
        val bePayload = ByteArray(256 * 4)
        val beBb = ByteBuffer.wrap(bePayload).order(ByteOrder.BIG_ENDIAN)
        for (i in 1..256) beBb.putFloat(i.toFloat())
        val okBe = CustomVoiceLoader.parseVoiceBytes(makeNpy(">f4", "(256,)", bePayload))
        assertEquals(1.0f, okBe[0], 1e-5f)
        assertEquals(256.0f, okBe[255], 1e-5f)
        assertRejected("int32", makeNpy("<i4", "(256,)", goodPayload))
        assertRejected("512 float32", makeNpy("<f4", "(512,)", ByteArray(512 * 4)))
        assertRejected("trailing bytes", makeNpy("<f4", "(256,)", ByteArray(256 * 4 + 16)))
    }
}
