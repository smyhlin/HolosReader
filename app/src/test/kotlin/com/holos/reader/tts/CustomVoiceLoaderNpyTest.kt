package com.holos.reader.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class CustomVoiceLoaderNpyTest {

    private val values = FloatArray(CustomVoiceLoader.VOICE_DIM) { (it + 1).toFloat() }

    @Test
    fun readsVersionOneAndTwoFloat32Vectors() {
        for (version in 1..2) {
            val bytes = npy(
                version = version,
                descriptor = "<f4",
                payload = float32Payload(values, ByteOrder.LITTLE_ENDIAN),
            )

            assertArrayEquals(values, CustomVoiceLoader.parseVoiceBytes(bytes), 0f)
        }
    }

    @Test
    fun honorsNativeAndSwappedByteOrder() {
        val native = npy(
            version = 1,
            descriptor = "=f4",
            payload = float32Payload(values, ByteOrder.nativeOrder()),
        )
        val swapped = npy(
            version = 2,
            descriptor = ">f4",
            payload = float32Payload(values, ByteOrder.BIG_ENDIAN),
        )

        assertArrayEquals(values, CustomVoiceLoader.parseVoiceBytes(native), 0f)
        assertArrayEquals(values, CustomVoiceLoader.parseVoiceBytes(swapped), 0f)
    }

    @Test
    fun keepsRawBinaryInputLittleEndian() {
        val bytes = float32Payload(values, ByteOrder.LITTLE_ENDIAN)

        assertArrayEquals(values, CustomVoiceLoader.parseVoiceBytes(bytes), 0f)
    }

    @Test
    fun rejectsNonFloat32Dtypes() {
        val float64 = npy(1, "<f8", payload = ByteArray(CustomVoiceLoader.VOICE_DIM * 8))
        val int32 = npy(2, "<i4", payload = ByteArray(CustomVoiceLoader.VOICE_DIM * 4))

        for (bytes in listOf(float64, int32)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                CustomVoiceLoader.parseVoiceBytes(bytes)
            }
            assertTrue(error.message.orEmpty().contains("float32"))
        }
    }

    @Test
    fun rejectsUnexpectedShapeAndPayloadLength() {
        val payload = float32Payload(values, ByteOrder.LITTLE_ENDIAN)
        val wrongCount = npy(1, "<f4", shape = "(255,)", payload = payload)
        val wrongRank = npy(2, "<f4", shape = "(128, 2)", payload = payload)
        val malformedTuple = npy(1, "<f4", shape = "(256)", payload = payload)
        val truncated = npy(1, "<f4", payload = payload.copyOf(payload.size - 1))
        val extra = npy(2, "<f4", payload = payload + byteArrayOf(0))

        for (bytes in listOf(wrongCount, wrongRank, malformedTuple, truncated, extra)) {
            assertThrows(IllegalArgumentException::class.java) {
                CustomVoiceLoader.parseVoiceBytes(bytes)
            }
        }
    }

    @Test
    fun rejectsUnsupportedNpyVersionsAndTruncatedHeaders() {
        val unsupportedVersion = npy(
            version = 3,
            descriptor = "<f4",
            payload = float32Payload(values, ByteOrder.LITTLE_ENDIAN),
        )
        val valid = npy(
            version = 1,
            descriptor = "<f4",
            payload = float32Payload(values, ByteOrder.LITTLE_ENDIAN),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CustomVoiceLoader.parseVoiceBytes(unsupportedVersion)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustomVoiceLoader.parseVoiceBytes(valid.copyOf(11))
        }
    }

    @Test
    fun invalidImportLeavesExistingVoiceFilesUntouched() {
        val dir = Files.createTempDirectory("holos_npy_import_").toFile()
        try {
            val existing = CustomVoiceLoader.saveCustomVoice(dir, "Existing", values)
            val originalBytes = existing.readBytes()
            val invalidFloat64 = npy(1, "<f8", payload = ByteArray(CustomVoiceLoader.VOICE_DIM * 8))

            assertThrows(IllegalArgumentException::class.java) {
                CustomVoiceLoader.importCustomVoice(dir, "Incoming", invalidFloat64)
            }

            assertArrayEquals(originalBytes, existing.readBytes())
            assertEquals(listOf("custom:Existing"), CustomVoiceLoader.listCustomVoices(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun npy(
        version: Int,
        descriptor: String,
        shape: String = "(256,)",
        payload: ByteArray,
    ): ByteArray {
        val headerText = "{'descr': '$descriptor', 'fortran_order': False, 'shape': $shape, }"
        val headerContent = headerText.toByteArray(Charsets.US_ASCII)
        val prefixSize = if (version == 1) 10 else 12
        val paddingSize = (16 - ((prefixSize + headerContent.size + 1) % 16)) % 16
        val header = headerContent + ByteArray(paddingSize) { ' '.code.toByte() } + byteArrayOf('\n'.code.toByte())
        val prefix = ByteBuffer.allocate(prefixSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x93.toByte())
            put("NUMPY".toByteArray(Charsets.US_ASCII))
            put(version.toByte())
            put(0.toByte())
            if (version == 1) putShort(header.size.toShort()) else putInt(header.size)
        }.array()

        return prefix + header + payload
    }

    private fun float32Payload(values: FloatArray, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(order).apply {
            values.forEach { putFloat(it) }
        }.array()
}
