package com.holos.reader.tts

import com.holos.reader.g2p.ContextualHomographResolver
import com.holos.reader.g2p.MorphTag
import com.holos.reader.g2p.StressDictionaryLookup
import com.holos.reader.g2p.StressLookupResult
import com.holos.reader.g2p.StressVariant
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UserStressOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualStressHookTest {

    private val homographDict = StressDictionaryLookup { word ->
        if (word == "замок") {
            StressLookupResult.Ambiguous(
                listOf(
                    StressVariant(intArrayOf(2), intArrayOf(MorphTag.UPOS_NOUN, MorphTag.CASE_NOM)), // за́мок (vowel 0)
                    StressVariant(intArrayOf(4), intArrayOf(MorphTag.UPOS_NOUN, MorphTag.CASE_NOM)), // замо́к (vowel 1)
                )
            )
        } else null
    }

    @Test
    fun contextualResolverDisambiguatesHomographWhenDictHasVariants() {
        var invoked = false
        val stressifier = UkrainianStressifier(
            dict = homographDict,
            overrides = null,
            neuralAccentor = null,
            contextualResolver = ContextualHomographResolver { ctx, s, e, cands ->
                invoked = true
                assertEquals("замок", ctx.substring(s, e))
                assertTrue(0 in cands && 1 in cands)
                1 // choose замо́к
            },
        )
        val out = stressifier.stressify("На дверях висів замок.")
        assertTrue(invoked)
        assertTrue(out.contains("замо́к"))
    }

    @Test
    fun userOverrideWinsOverAuthorStressAndContextualResolver() {
        val overrides = UserStressOverrides().apply {
            loadFromText("замок = за́мок")
        }
        var resolverCalled = false
        val stressifier = UkrainianStressifier(
            dict = homographDict,
            overrides = overrides,
            neuralAccentor = null,
            contextualResolver = ContextualHomographResolver { _, _, _, _ ->
                resolverCalled = true
                1
            },
        )
        // Even with author acute mark on замо́к, explicit user override за́мок wins (H20)
        assertEquals("за́мок", stressifier.stressify("замо́к"))
        assertTrue(!resolverCalled)
    }

    @Test
    fun crashingContextualResolverIsInvokedAndFallsBackCleanly() {
        var invoked = false
        val stressifier = UkrainianStressifier(
            dict = homographDict,
            overrides = null,
            neuralAccentor = null,
            contextualResolver = ContextualHomographResolver { _, _, _, _ ->
                invoked = true
                throw IllegalStateException("Simulated ONNX failure")
            },
        )
        val out = stressifier.stressify("Старий замок стоїть на горі.")
        assertTrue(invoked)
        assertTrue(out.contains("за́мок"))
    }
}
