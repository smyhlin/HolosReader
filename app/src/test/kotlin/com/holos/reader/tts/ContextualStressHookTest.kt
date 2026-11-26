package com.holos.reader.tts

import com.holos.reader.g2p.ContextualHomographResolver
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UserStressOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualStressHookTest {

    @Test
    fun userOverrideTakesPriorityOverContextualResolver() {
        val overrides = UserStressOverrides().apply {
            loadFromText("замок = за\u0301мок")
        }
        var resolverCalled = false
        val stressifier = UkrainianStressifier(
            dict = null,
            overrides = overrides,
            neuralAccentor = null,
            contextualResolver = ContextualHomographResolver { _, _, _, _ ->
                resolverCalled = true
                1
            },
        )
        val out = stressifier.stressify("замок")
        assertEquals("за\u0301мок", out)
        assertTrue(!resolverCalled)
    }

    @Test
    fun crashingContextualResolverFallsBackGracefully() {
        val stressifier = UkrainianStressifier(
            dict = null,
            overrides = null,
            neuralAccentor = null,
            contextualResolver = ContextualHomographResolver { _, _, _, _ ->
                throw IllegalStateException("Simulated ONNX failure")
            },
        )
        val out = stressifier.stressify("замок стоїть на горі")
        assertTrue(out.contains('\u0301'))
    }
}
