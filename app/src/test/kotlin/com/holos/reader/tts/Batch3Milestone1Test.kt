package com.holos.reader.tts

import com.holos.reader.epub.BlockType
import com.holos.reader.epub.EpubStreamingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for Batch 3 Milestone 1 (#44, #48, #54, #55).
 */
class Batch3Milestone1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeValidWav(file: File, sampleRate: Int, sampleCount: Int): Long {
        val dataBytes = sampleCount.toLong() * 2L
        val hdr = ByteArray(44)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36L + dataBytes).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(1) // mono
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes.toInt())
        file.outputStream().use { out ->
            out.write(hdr)
            out.write(ByteArray(dataBytes.toInt()))
        }
        return dataBytes
    }

    // --- #54: Anchors on images and section boundaries ---

    @Test
    fun `anchor on inline img binds to image block not preceding text (#54)`() {
        val xhtml = """<html><body><p>Before<img id="i" src="pic.png"/>After</p></body></html>"""
        val parsed = EpubStreamingParser.parseHtmlToRichBlocksWithAnchors(xhtml, "OEBPS/ch1.xhtml")
        assertEquals(3, parsed.blocks.size)
        assertEquals("Before", parsed.blocks[0].text)
        assertEquals(BlockType.IMAGE, parsed.blocks[1].type)
        assertEquals("After", parsed.blocks[2].text)
        assertEquals(1, parsed.anchorToBlock["i"])
    }

    @Test
    fun `anchor on section boundary binds to section content not preceding text (#54)`() {
        val xhtml = """<html><body>Intro<section id="s"><p>Target</p></section></body></html>"""
        val parsed = EpubStreamingParser.parseHtmlToRichBlocksWithAnchors(xhtml, "OEBPS/ch1.xhtml")
        assertEquals(2, parsed.blocks.size)
        assertEquals("Intro", parsed.blocks[0].text)
        assertEquals("Target", parsed.blocks[1].text)
        assertEquals(1, parsed.anchorToBlock["s"])
    }

    @Test
    fun `anchor on hr following buffered text binds to hr block and empty anchors survive (#54)`() {
        val xhtml = """<html><body><p>Lead<hr id="sep"/>Trail</p><a id="a1"></a><a id="a2" name="a3"></a><p>Final</p></body></html>"""
        val parsed = EpubStreamingParser.parseHtmlToRichBlocksWithAnchors(xhtml, "OEBPS/ch1.xhtml")
        assertEquals(BlockType.HR, parsed.blocks[1].type)
        assertEquals(1, parsed.anchorToBlock["sep"])
        val finalIdx = parsed.blocks.indexOfFirst { it.text == "Final" }
        assertTrue(finalIdx >= 2)
        assertEquals(finalIdx, parsed.anchorToBlock["a1"])
        assertEquals(finalIdx, parsed.anchorToBlock["a2"])
        assertEquals(finalIdx, parsed.anchorToBlock["a3"])
    }

    // --- #55: Table cell scope & inline image ordering ---

    @Test
    fun `multi-paragraph table cell stays in one cell and preserves empty cells (#55)`() {
        val xhtml = """
            <html><body>
              <table>
                <tr><th>H1</th><th>H2</th></tr>
                <tr><td><p>A</p><p>B</p></td><td>C</td></tr>
                <tr><td></td><td>OnlyRight</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val parsed = EpubStreamingParser.parseHtmlToRichBlocksWithAnchors(xhtml, "OEBPS/t.xhtml")
        assertEquals(3, parsed.blocks.size)
        assertEquals("H1  |  H2", parsed.blocks[0].text)
        assertEquals("A\nB  |  C", parsed.blocks[1].text)
        assertEquals("  |  OnlyRight", parsed.blocks[2].text)
    }

    @Test
    fun `inline image inside table preserves document order and nested tables do not corrupt outer row (#55)`() {
        val xhtml = """
            <html><body>
              <table>
                <tr><td>Before</td><td><img src="fig1.png"/></td><td>After</td></tr>
                <tr><td>Outer1<table><tr><td>Inner1</td><td>Inner2</td></tr></table>Outer2</td><td>Outer3</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val parsed = EpubStreamingParser.parseHtmlToRichBlocksWithAnchors(xhtml, "OEBPS/t2.xhtml")
        val idxBefore = parsed.blocks.indexOfFirst { it.text.contains("Before") }
        val idxImg = parsed.blocks.indexOfFirst { it.type == BlockType.IMAGE }
        val idxAfter = parsed.blocks.indexOfFirst { it.text.contains("After") }
        assertTrue("Before ($idxBefore) < Image ($idxImg) < After ($idxAfter)", idxBefore in 0 until idxImg && idxImg < idxAfter)

        val idxOuter1 = parsed.blocks.indexOfFirst { it.text.contains("Outer1") }
        val idxInner = parsed.blocks.indexOfFirst { it.text == "Inner1  |  Inner2" }
        val idxOuter3 = parsed.blocks.indexOfFirst { it.text.contains("Outer3") }
        assertTrue("Outer1 ($idxOuter1) < Inner ($idxInner) < Outer3 ($idxOuter3)", idxOuter1 in 0 until idxInner && idxInner < idxOuter3)
    }

    // --- #48: Strict offline cache ID equality and overflow guard ---

    @Test
    fun `offline cache rejects foreign block IDs, empty expected list, and Long MAX_VALUE overflow (#48)`() {
        val wav = File(tmp.root, "ch0.wav")
        val dataBytes = writeValidWav(wav, 24000, 10)
        val ready = OfflineAudioCache.readyFileFor(wav)
        val idx = OfflineAudioCache.indexFileFor(wav)

        // 1. Extraneous foreign block ID 99
        ready.writeText("version=2\nsampleRate=24000\ndataBytes=$dataBytes\nblockCount=2\n")
        idx.writeText("0\t0\t1\n99\t1\t1\n")
        assertNull("Must reject extraneous foreign block ID 99", OfflineAudioCache.validate(wav, listOf(0)))

        // 2. Empty expected list must be rejected
        ready.writeText("version=2\nsampleRate=24000\ndataBytes=$dataBytes\nblockCount=1\n")
        idx.writeText("0\t0\t5\n")
        assertNull("Must reject empty expected speakable IDs", OfflineAudioCache.validate(wav, emptyList()))
        assertNotNull("Exact match must succeed", OfflineAudioCache.validate(wav, listOf(0)))

        // 3. Long.MAX_VALUE overflow in st + sp
        ready.writeText("version=2\nsampleRate=24000\ndataBytes=$dataBytes\nblockCount=2\n")
        idx.writeText("0\t0\t1\n1\t${Long.MAX_VALUE}\t2\n")
        assertNull("Must reject overflowing startSample + speechSamples", OfflineAudioCache.validate(wav, listOf(0, 1)))

        // 4. Negative block ID
        ready.writeText("version=2\nsampleRate=24000\ndataBytes=$dataBytes\nblockCount=1\n")
        idx.writeText("-1\t0\t5\n")
        assertNull("Must reject negative block ID", OfflineAudioCache.validate(wav, listOf(-1)))
    }

    // --- #44: Resolved synthesis recipe & snapshot ---

    @Test
    fun `resolved recipe distinguishes fallback model, addon availability, and role custom voice fingerprints (#44)`() {
        val fallbackSnap = AudioCacheRecipe.resolve(
            parserVersion = 3,
            requestedModelId = "holos_fp32",
            effectiveModelId = "holos_int8",
            selectedVoice = "Speaker_0",
            adaptiveVoicesEnabled = true,
            systemVoice = "custom:sysA",
            dialogueVoice = "custom:dlgA",
            voiceFingerprintProvider = { v -> if (v == "custom:sysA") "1024:100" else if (v == "custom:dlgA") "1024:200" else "" },
            speechSpeed = 1.0f,
            supertonicSteps = 5,
            supertonicEnglishPronunciation = false,
            flashSrActive = false,
            flashSrRevision = "",
            emotionActive = false,
            emotionRevision = "",
            contextualStressActive = false,
            contextualStressRevision = "",
            stressOverridesMtime = 0L,
            stressOverridesSize = 0L,
        )
        val installedSnap = AudioCacheRecipe.resolve(
            parserVersion = 3,
            requestedModelId = "holos_fp32",
            effectiveModelId = "holos_fp32",
            selectedVoice = "Speaker_0",
            adaptiveVoicesEnabled = true,
            systemVoice = "custom:sysA",
            dialogueVoice = "custom:dlgA",
            voiceFingerprintProvider = { v -> if (v == "custom:sysA") "1024:100" else if (v == "custom:dlgA") "1024:200" else "" },
            speechSpeed = 1.0f,
            supertonicSteps = 5,
            supertonicEnglishPronunciation = false,
            flashSrActive = false,
            flashSrRevision = "",
            emotionActive = false,
            emotionRevision = "",
            contextualStressActive = false,
            contextualStressRevision = "",
            stressOverridesMtime = 0L,
            stressOverridesSize = 0L,
        )
        val flashSrNowReadySnap = installedSnap.recipe.copy(flashSrEnabled = true, flashSrRevision = "model.onnx:500000:999")
        val updatedRoleVoiceSnap = AudioCacheRecipe.resolve(
            parserVersion = 3,
            requestedModelId = "holos_fp32",
            effectiveModelId = "holos_fp32",
            selectedVoice = "Speaker_0",
            adaptiveVoicesEnabled = true,
            systemVoice = "custom:sysA",
            dialogueVoice = "custom:dlgA",
            voiceFingerprintProvider = { v -> if (v == "custom:sysA") "1024:101" else if (v == "custom:dlgA") "1024:200" else "" },
            speechSpeed = 1.0f,
            supertonicSteps = 5,
            supertonicEnglishPronunciation = false,
            flashSrActive = false,
            flashSrRevision = "",
            emotionActive = false,
            emotionRevision = "",
            contextualStressActive = false,
            contextualStressRevision = "",
            stressOverridesMtime = 0L,
            stressOverridesSize = 0L,
        )

        assertEquals(4, AudioCacheRecipe.CURRENT_RECIPE_VERSION)
        assertNotEquals(installedSnap.recipe.cacheKeyHex(), installedSnap.recipe.copy(recipeVersion = 3).cacheKeyHex())
        assertTrue(fallbackSnap.isFallbackModel)
        assertNotEquals(fallbackSnap.recipe.cacheKeyHex(), installedSnap.recipe.cacheKeyHex())
        assertNotEquals(installedSnap.recipe.cacheKeyHex(), flashSrNowReadySnap.cacheKeyHex())
        assertNotEquals(installedSnap.recipe.cacheKeyHex(), updatedRoleVoiceSnap.recipe.cacheKeyHex())
    }
}
