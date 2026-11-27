package com.holos.reader.g2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPercentNarrationTest {
    @Test
    fun speaksTheReportedSystemMessageWithSingularPercent() {
        val line = "[Поточна інтеграція: 1%. Здобуто родовід «Небесна Кров».]"
        assertTrue(UkrainianTextNormalizer.normalize(line).contains("Поточна інтеграція: один відсоток."))
    }

    @Test
    fun agreementUsesTheMagnitudeOfSignedIntegers() {
        assertEquals("мінус один відсоток", UkrainianTextNormalizer.normalize("−1%"))
        assertEquals("плюс два відсотки", UkrainianTextNormalizer.normalize("+2%"))
        assertEquals("одинадцять відсотків", UkrainianTextNormalizer.normalize("11%"))
    }
}
