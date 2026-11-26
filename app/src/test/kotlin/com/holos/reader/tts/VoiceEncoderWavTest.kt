package com.holos.reader.tts

import com.holos.tts.VoiceEncoderOnnx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceEncoderWavTest {

    private fun buildPcmWav(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        samples: ShortArray,
        audioFormat: Short = 1,
    ): ByteArray {
        val dataSize = samples.size * 2
        val totalSize = 44 + dataSize
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(totalSize - 8)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(audioFormat)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * (bitsPerSample / 8))
        bb.putShort((channels * (bitsPerSample / 8)).toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataSize)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    @Test
    fun decodesMono16BitPcmWav() {
        val wav = buildPcmWav(24_000, 1, 16, shortArrayOf(0, 16384, -16384))
        val (pcm, sr) = VoiceEncoderOnnx.decodePcm16Wav(ByteArrayInputStream(wav))
        assertEquals(24_000, sr)
        assertEquals(3, pcm.size)
        assertEquals(0.0f, pcm[0], 1e-4f)
        assertEquals(0.5f, pcm[1], 1e-3f)
        assertEquals(-0.5f, pcm[2], 1e-3f)
    }

    @Test
    fun downmixesStereo16BitWavToMono() {
        // Frame 0: L=+16384 (+0.5), R=-16384 (-0.5) -> avg 0.0
        // Frame 1: L=+16384 (+0.5), R=+16384 (+0.5) -> avg +0.5
        val wav = buildPcmWav(44_100, 2, 16, shortArrayOf(16384, -16384, 16384, 16384))
        val (pcm, sr) = VoiceEncoderOnnx.decodePcm16Wav(ByteArrayInputStream(wav))
        assertEquals(44_100, sr)
        assertEquals(2, pcm.size)
        assertEquals(0.0f, pcm[0], 1e-3f)
        assertEquals(0.5f, pcm[1], 1e-3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonWavInput() {
        VoiceEncoderOnnx.decodePcm16Wav(ByteArrayInputStream("ID3_NOT_A_WAV".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPcmCompressedWav() {
        val wav = buildPcmWav(24_000, 1, 16, shortArrayOf(0, 100), audioFormat = 3)
        VoiceEncoderOnnx.decodePcm16Wav(ByteArrayInputStream(wav))
    }

    @Test
    fun saves256FloatEmbeddingAs1024ByteLittleEndianBin() {
        val tmp = File.createTempFile("holos_voice_", ".bin")
        try {
            val emb = FloatArray(VoiceEncoderOnnx.EMBEDDING_DIM) { i -> i * 0.001f }
            VoiceEncoderOnnx.saveEmbeddingBin(emb, tmp)
            assertEquals(1024L, tmp.length())
            val bb = ByteBuffer.wrap(tmp.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0.0f, bb.getFloat(0), 1e-6f)
            assertTrue(bb.getFloat(1020) > 0.25f)
        } finally {
            tmp.delete()
        }
    }
}
