package com.holos.reader.tts

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin loader & local store for custom cloned 256-float HolosTTS voice embeddings.
 *
 * Directly imports `.pt` files downloaded from https://huggingface.co/spaces/patriotyk/HolosTTS
 * (PyTorch ZIP archive containing `archive/data/0` = 1,024 raw little-endian IEEE-754 float32 bytes)
 * WITHOUT requiring PyTorch on Android, as well as NumPy `.npy` and raw 1,024-byte `.bin` files!
 */
object CustomVoiceLoader {

    const val VOICE_DIM = 256
    private const val EXPECTED_BYTES = VOICE_DIM * 4 // 1024 bytes
    const val CUSTOM_PREFIX = "custom:"

    /**
     * Parses raw bytes from a `.pt` (PyTorch ZIP), `.npy` (NumPy float32), or raw `.bin` (1024B)
     * file into a validated 256-float voice embedding.
     */
    fun parseVoiceBytes(bytes: ByteArray): FloatArray {
        // 1. Raw 1024-byte float32 binary
        if (bytes.size == EXPECTED_BYTES && !(bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())) {
            return readFloatsLE(bytes, 0)
        }

        // 2. PyTorch .pt ZIP archive (magic "PK\x03\x04") — saved by torch.save() on HF Space!
        if (bytes.size > 30 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name.endsWith("/data/0") || name == "data/0") {
                        val raw = zis.readBytes()
                        require(raw.size >= EXPECTED_BYTES) {
                            "Неочікуваний розмір тензора у .pt файлі (${raw.size} байт, очікується $EXPECTED_BYTES)"
                        }
                        return readFloatsLE(raw, 0)
                    }
                }
            }
            throw IllegalArgumentException("У .pt архіві не знайдено тензор data/0 (256 float32)")
        }

        // 3. NumPy .npy float32 array (magic "\x93NUMPY")
        if (bytes.size > 12 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val major = bytes[6].toInt()
            val headerStart = if (major == 1) 10 else 12
            val headerLen = if (major == 1) bb.getShort(8).toInt() and 0xFFFF else bb.getInt(8)
            val dataStart = headerStart + headerLen
            require(bytes.size - dataStart >= EXPECTED_BYTES) {
                "Файл .npy занадто малий для вектора з $VOICE_DIM float32"
            }
            return readFloatsLE(bytes, dataStart)
        }

        throw IllegalArgumentException("Непідтримуваний формат голосу. Підтримуються .pt (з HF Space), .npy та .bin")
    }

    fun saveCustomVoice(dir: File, name: String, vector: FloatArray): File {
        require(vector.size == VOICE_DIM && vector.all { it.isFinite() }) { "Некоректний вектор голосу" }
        dir.mkdirs()
        val safeName = name.replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "").trim().ifEmpty { "ClonedVoice" }
        val outFile = File(dir, "$safeName.bin")
        val bb = ByteBuffer.allocate(EXPECTED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) bb.putFloat(v)
        outFile.writeBytes(bb.array())
        return outFile
    }

    fun listCustomVoices(dir: File): List<String> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("bin", ignoreCase = true) && f.length() == EXPECTED_BYTES.toLong() }
            ?.sortedBy { it.nameWithoutExtension }
            ?.map { CUSTOM_PREFIX + it.nameWithoutExtension }
            ?: emptyList()
    }

    fun loadCustomVoiceVector(dir: File, prefixedName: String): FloatArray? {
        if (!prefixedName.startsWith(CUSTOM_PREFIX)) return null
        val name = prefixedName.removePrefix(CUSTOM_PREFIX)
        val file = File(dir, "$name.bin")
        if (!file.exists() || file.length() != EXPECTED_BYTES.toLong()) return null
        return readFloatsLE(file.readBytes(), 0)
    }

    private fun readFloatsLE(bytes: ByteArray, offset: Int): FloatArray {
        val bb = ByteBuffer.wrap(bytes, offset, EXPECTED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val vec = FloatArray(VOICE_DIM) { bb.float }
        require(vec.all { it.isFinite() }) { "Вектор голосу містить NaN або Inf" }
        return vec
    }
}
