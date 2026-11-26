package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicLanguageSplitterTest {

    @Test
    fun disabledKeepsEntireSentenceUkrainian() {
        val spans = SupertonicLanguageSplitter.split("Він активував Shadow Step миттєво.", false)
        assertEquals(1, spans.size)
        assertEquals("uk", spans[0].lang)
        assertEquals("Він активував Shadow Step миттєво.", spans[0].text)
    }

    @Test
    fun enabledSplitsEmbeddedEnglishPhrases() {
        val spans = SupertonicLanguageSplitter.split("Він активував Shadow Step миттєво.", true)
        assertEquals(3, spans.size)
        assertEquals(SupertonicLanguageSplitter.LanguageSpan("Він активував", "uk"), spans[0])
        assertEquals(SupertonicLanguageSplitter.LanguageSpan("Shadow Step", "en"), spans[1])
        assertEquals(SupertonicLanguageSplitter.LanguageSpan("миттєво.", "uk"), spans[2])
    }

    @Test
    fun romanNumeralsStayUkrainian() {
        val spans = SupertonicLanguageSplitter.split("Навичка II «Бенкет» і Том IV готові.", true)
        assertEquals(1, spans.size)
        assertEquals("uk", spans[0].lang)
    }

    @Test
    fun handlesMixedTechTermsAndPunctuation() {
        val spans = SupertonicLanguageSplitter.split("Система показала Level Up! Далі — новий рівень.", true)
        assertEquals(3, spans.size)
        assertEquals("uk", spans[0].lang)
        assertEquals("Level Up!", spans[1].text)
        assertEquals("en", spans[1].lang)
        assertEquals("uk", spans[2].lang)
    }

    @Test
    fun blankAndNumbersOnlyDefaultCleanly() {
        assertTrue(SupertonicLanguageSplitter.split("   ", true).isEmpty())
        val nums = SupertonicLanguageSplitter.split("2026 — 100%", true)
        assertEquals(1, nums.size)
        assertEquals("uk", nums[0].lang)
    }

    @Test
    fun handlesHyphenatedEnglishAndContractions() {
        val spans = SupertonicLanguageSplitter.split("Це high-speed режим, don't stop!", true)
        assertEquals(4, spans.size)
        assertEquals("uk", spans[0].lang)
        assertEquals("high-speed", spans[1].text)
        assertEquals("en", spans[1].lang)
        assertEquals("uk", spans[2].lang)
        assertEquals("don't stop!", spans[3].text)
        assertEquals("en", spans[3].lang)
    }
    @Test
    fun prepareSupertonicSpansPreservesEnglishBeforeUkrainianNormalization() {
        val spans = HolosSafeSynthesizer.prepareSupertonicSpans("Використав Shadow Step і відновив 50 HP", true)
        assertTrue(spans.any { it.lang == "en" && it.text.contains("Shadow Step") })
        assertTrue(spans.any { it.lang == "en" && it.text.contains("HP") })
        assertTrue(spans.any { it.lang == "uk" })
    }
}
