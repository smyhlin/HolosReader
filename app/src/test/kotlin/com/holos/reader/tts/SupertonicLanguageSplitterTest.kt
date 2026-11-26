package com.holos.reader.tts

import org.junit.Assert.assertEquals
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
}
