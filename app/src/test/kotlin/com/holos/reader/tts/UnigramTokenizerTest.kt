package com.holos.reader.tts

import com.holos.tts.UnigramTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnigramTokenizerTest {

    private val sampleTokenizerJson = """
        {
          "added_tokens": [
            {"id": 0, "content": "<s>"},
            {"id": 1, "content": "<pad>"},
            {"id": 2, "content": "</s>"},
            {"id": 3, "content": "<unk>"}
          ],
          "model": {
            "type": "Unigram",
            "vocab": [
              ["<s>", 0.0],
              ["<pad>", 0.0],
              ["</s>", 0.0],
              ["<unk>", 0.0],
              ["\u2581замок", -1.5],
              ["\u2581стоїть", -1.8],
              ["\u2581на", -1.0],
              ["\u2581горі", -2.0]
            ]
          }
        }
    """.trimIndent()

    @Test
    fun encodesUkrainianSentenceWithExactSpans() {
        val tok = UnigramTokenizer.fromJsonString(sampleTokenizerJson)
        val enc = tok.encode("замок стоїть на горі")
        assertEquals(6, enc.inputIds.size)
        assertEquals(0L, enc.inputIds.first())
        assertEquals(2L, enc.inputIds.last())
        assertEquals(4L, enc.inputIds[1])
        assertEquals(5L, enc.inputIds[2])
        assertEquals(6L, enc.inputIds[3])
        assertEquals(7L, enc.inputIds[4])
        // First word span covers "замок" [0..5)
        assertTrue(enc.spans[1].charStart == 0 && enc.spans[1].charEnd == 5)
    }
}
