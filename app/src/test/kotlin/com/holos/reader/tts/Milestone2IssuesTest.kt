package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class Milestone2IssuesTest {

    // Issue #15: Reference-counted engine lease defers close until last borrower releases
    @Test
    fun engineLeaseDefersCloseDuringResetAndClosesExactlyOnce() {
        val closeA = AtomicInteger(0)
        val closeB = AtomicInteger(0)
        val pool = EngineLeasePool<AutoCloseable>()
        val engA = AutoCloseable { closeA.incrementAndGet() }
        val engB = AutoCloseable { closeB.incrementAndGet() }

        val leaseA1 = pool.acquire("modelA") { engA }!!
        val leaseA2 = pool.acquire("modelA") { error("should reuse") }!!
        assertEquals(0, closeA.get())

        // Config change / reset while A has 2 active borrowers
        val leaseB = pool.acquire("modelB") { engB }!!
        assertEquals("A must NOT close while borrowed", 0, closeA.get())

        leaseA1.close()
        assertEquals("A must wait for second borrower", 0, closeA.get())
        leaseA2.close()
        leaseA2.close() // idempotent
        assertEquals("A must close exactly once after last borrower releases", 1, closeA.get())

        pool.retireCurrent()
        assertEquals("B must NOT close while borrowed", 0, closeB.get())
        leaseB.close()
        assertEquals("B must close exactly once on release", 1, closeB.get())
    }

    // Issue #5: Canonical SHA-256 cache recipe is locale-invariant and sensitive to all model/add-on settings
    @Test
    fun audioCacheRecipeIsLocaleInvariantAndCapturesAllSettings() {
        val base = AudioCacheRecipe(
            modelId = "holos_int8",
            selectedVoice = "F1",
            adaptiveVoicesEnabled = false,
            speechSpeed = 1.15f,
            flashSrEnabled = false,
        )
        val orig = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val keyUs = base.cacheKeyHex()
            Locale.setDefault(Locale("uk", "UA"))
            val keyUk = base.cacheKeyHex()
            Locale.setDefault(Locale.GERMANY)
            val keyDe = base.cacheKeyHex()
            assertEquals(keyUs, keyUk)
            assertEquals(keyUs, keyDe)
        } finally {
            Locale.setDefault(orig)
        }
        assertNotEquals(base.cacheKeyHex(), base.copy(flashSrEnabled = true).cacheKeyHex())
        assertNotEquals(base.cacheKeyHex(), base.copy(modelId = "supertonic3").cacheKeyHex())
        assertNotEquals(base.cacheKeyHex(), base.copy(emotionPacingEnabled = true).cacheKeyHex())
        assertNotEquals(base.cacheKeyHex(), base.copy(contextualStressEnabled = true).cacheKeyHex())
        assertNotEquals(base.cacheKeyHex(), base.copy(supertonicSteps = 10).cacheKeyHex())
        assertNotEquals(base.cacheKeyHex(), base.copy(supertonicEnglishPronunciation = true).cacheKeyHex())
        assertNotEquals(base.cacheKeyHex(), base.copy(customVoiceFingerprint = "1024:999").cacheKeyHex())
    }

    // Issue #17: Custom voice import derives canonical ID from saved file and avoids silent collision overwrite
    @Test
    fun customVoiceSaveSanitizesHandlesCollisionsAndLoadsBack() {
        val dir = createTempDir(prefix = "holos_voices_")
        try {
            val vec1 = FloatArray(CustomVoiceLoader.VOICE_DIM) { 0.1f }
            val vec2 = FloatArray(CustomVoiceLoader.VOICE_DIM) { 0.2f }
            val vec3 = FloatArray(CustomVoiceLoader.VOICE_DIM) { 0.3f }

            val f1 = CustomVoiceLoader.saveCustomVoice(dir, "Voice (test)", vec1)
            val id1 = CustomVoiceLoader.canonicalVoiceId(f1)
            assertEquals("custom:Voice test", id1)
            assertTrue(id1 in CustomVoiceLoader.listCustomVoices(dir))
            assertEquals(0.1f, CustomVoiceLoader.loadCustomVoiceVector(dir, id1)!![0], 1e-5f)

            // Distinct original name sanitizing to same base gets unique _2 suffix instead of overwriting
            val f2 = CustomVoiceLoader.saveCustomVoice(dir, "Voice [test]!", vec2)
            val id2 = CustomVoiceLoader.canonicalVoiceId(f2)
            assertEquals("custom:Voice test_2", id2)
            assertEquals(0.1f, CustomVoiceLoader.loadCustomVoiceVector(dir, id1)!![0], 1e-5f)
            assertEquals(0.2f, CustomVoiceLoader.loadCustomVoiceVector(dir, id2)!![0], 1e-5f)

            // Punctuation-only name falls back to ClonedVoice
            val f3 = CustomVoiceLoader.saveCustomVoice(dir, "!!!???", vec3)
            assertEquals("custom:ClonedVoice", CustomVoiceLoader.canonicalVoiceId(f3))
        } finally {
            dir.deleteRecursively()
        }
    }

    // Issue #20: Strict offline WAV + index + manifest validator rejects corrupt/truncated/incomplete caches
    @Test
    fun offlineAudioCacheValidatesGoodAndRejectsCorruptFixtures() {
        val dir = createTempDir(prefix = "holos_cache_")
        try {
            val wav = File(dir, "ch0.wav")
            val tmp = File(dir, "ch0.wav.part")
            val totalSamples = 48000L
            val dataBytes = totalSamples * 2L
            tmp.writeBytes(makeWav(sampleRate = 48000, dataBytes = dataBytes.toInt()))
            val entries = listOf(
                OfflineAudioCache.BlockEntry(0, 0L, 20000L),
                OfflineAudioCache.BlockEntry(1, 24000L, 20000L),
            )
            OfflineAudioCache.publishAtomically(tmp, entries, 48000, dataBytes, wav)

            // Valid cache passes
            val ok = OfflineAudioCache.validate(wav, listOf(0, 1))
            assertNotNull(ok)
            assertEquals(48000, ok!!.sampleRate)
            assertEquals(2, ok.entries.size)

            // Missing expected speakable block rejected
            assertNull(OfflineAudioCache.validate(wav, listOf(0, 1, 2)))

            // Malformed index row rejects entire cache
            val idxFile = OfflineAudioCache.indexFileFor(wav)
            val origIdx = idxFile.readText()
            idxFile.writeText(origIdx + "corrupt_row\n")
            assertNull(OfflineAudioCache.validate(wav, listOf(0, 1)))
            idxFile.writeText(origIdx)

            // Missing ready marker rejects cache (simulating interrupted publish)
            val readyFile = OfflineAudioCache.readyFileFor(wav)
            val origReady = readyFile.readText()
            assertTrue(readyFile.delete())
            assertNull(OfflineAudioCache.validate(wav, listOf(0, 1)))
            readyFile.writeText(origReady)

            // Truncated WAV data rejected
            wav.writeBytes(wav.readBytes().copyOf(100))
            assertNull(OfflineAudioCache.validate(wav, listOf(0, 1)))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun makeWav(sampleRate: Int, dataBytes: Int): ByteArray {
        val out = ByteArray(44 + dataBytes)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        return out
    }
}
