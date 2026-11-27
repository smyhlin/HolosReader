package com.holos.reader.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin loader & local store for custom cloned 256-float HolosTTS voice embeddings.
 *
 * Directly imports `.pt` files downloaded from https://huggingface.co/spaces/patriotyk/HolosTTS
 * (PyTorch ZIP archive containing `archive/data/0` = 1,024 raw little-endian IEEE-754 float32 bytes)
 * WITHOUT requiring PyTorch on Android, as well as NumPy `.npy` and raw 1,024-byte `.bin` files.
 */
object CustomVoiceLoader {

    const val VOICE_DIM = 256
    private const val EXPECTED_BYTES = VOICE_DIM * 4 // 1024 bytes
    private const val MAX_ENTRY_BYTES = 1024 * 1024  // 1 MiB safety cap against ZIP bombs
    private const val MAX_NPY_HEADER_BYTES = 65536
    const val CUSTOM_PREFIX = "custom:"

    private val NPY_DESCRIPTOR_FIELD = Regex("""['"]descr['"]\s*:\s*['"]([^'"]+)['"]""")
    private val NPY_FORTRAN_FIELD = Regex("""['"]fortran_order['"]\s*:\s*(True|False)\b""")
    private val NPY_SHAPE_FIELD = Regex("""['"]shape['"]\s*:\s*\(([^)]*)\)""")

    /**
     * Parses raw bytes from a `.pt` (PyTorch ZIP), `.npy` (NumPy float32), or raw `.bin` (1024B)
     * file into a validated 256-float voice embedding.
     */
    fun parseVoiceBytes(bytes: ByteArray): FloatArray {
        // 1. Raw 1024-byte float32 binary
        if (bytes.size == EXPECTED_BYTES && !(bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())) {
            return readFloatsLE(bytes, 0)
        }

        // 2. PyTorch .pt ZIP archive (magic "PK\x03\x04")
        if (bytes.size > 30 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name.endsWith("/data/0") || name == "data/0") {
                        val raw = readBounded(zis, MAX_ENTRY_BYTES)
                        require(raw.size >= EXPECTED_BYTES) {
                            "Неочікуваний розмір тензора у .pt файлі (${raw.size} байт, очікується $EXPECTED_BYTES)"
                        }
                        return readFloatsLE(raw, 0)
                    }
                }
            }
            throw IllegalArgumentException("У .pt архіві не знайдено тензор data/0 (256 float32)")
        }

        // 3. NumPy .npy array (magic "\x93NUMPY")
        if (bytes.size >= 6 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
            return parseNpy(bytes)
        }

        throw IllegalArgumentException("Непідтримуваний формат голосу. Підтримуються .pt (з HF Space), .npy та .bin")
    }

    fun importCustomVoice(dir: File, name: String, bytes: ByteArray): File {
        val vector = parseVoiceBytes(bytes)
        return saveCustomVoice(dir, name, vector)
    }

    private fun parseNpy(bytes: ByteArray): FloatArray {
        require(bytes.size >= 8) { "Некоректний заголовок .npy" }
        val major = bytes[6].toInt() and 0xFF
        val minor = bytes[7].toInt() and 0xFF
        require((major == 1 || major == 2) && minor == 0) {
            "Непідтримувана версія .npy ($major.$minor); підтримуються версії 1.0 та 2.0"
        }

        val headerStart = if (major == 1) 10 else 12
        require(bytes.size >= headerStart) { "Некоректний заголовок .npy" }
        val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerLength = if (major == 1) {
            littleEndian.getShort(8).toInt() and 0xFFFF
        } else {
            littleEndian.getInt(8)
        }
        require(headerLength in 1..MAX_NPY_HEADER_BYTES && headerLength <= bytes.size - headerStart) {
            "Некоректний заголовок .npy ($headerLength байт)"
        }

        val dataStart = headerStart + headerLength
        val header = String(bytes, headerStart, headerLength, Charsets.US_ASCII)
        require(header.lastOrNull() == '\n') { "Некоректний заголовок .npy: відсутній завершальний символ нового рядка" }

        val descriptor = requiredNpyHeaderValue(NPY_DESCRIPTOR_FIELD, "descr", header)
        val byteOrder = when (descriptor) {
            "<f4" -> ByteOrder.LITTLE_ENDIAN
            ">f4" -> ByteOrder.BIG_ENDIAN
            "=f4" -> ByteOrder.nativeOrder()
            else -> throw IllegalArgumentException(
                "Непідтримуваний тип .npy ($descriptor); підтримується лише float32 (f4)",
            )
        }

        val fortranOrder = requiredNpyHeaderValue(NPY_FORTRAN_FIELD, "fortran_order", header)
        require(fortranOrder == "True" || fortranOrder == "False") {
            "Некоректне поле fortran_order у заголовку .npy"
        }

        val shape = requiredNpyHeaderValue(NPY_SHAPE_FIELD, "shape", header)
        val dimensions = shape.split(',').map(String::trim)
        require(dimensions.size == 2 && dimensions[0].toIntOrNull() == VOICE_DIM && dimensions[1].isEmpty()) {
            "Підтримується лише одновимірний вектор .npy форми ($VOICE_DIM,)"
        }
        require(bytes.size - dataStart == EXPECTED_BYTES) {
            "Некоректний розмір даних .npy: очікується рівно $EXPECTED_BYTES байт для $VOICE_DIM float32"
        }

        return readFloats(bytes, dataStart, byteOrder)
    }

    private fun requiredNpyHeaderValue(pattern: Regex, field: String, header: String): String {
        val matches = pattern.findAll(header).toList()
        require(matches.size == 1) { "Некоректний заголовок .npy: очікується одне поле '$field'" }
        return matches.single().groupValues[1]
    }

    fun canonicalVoiceId(savedFile: File): String = CUSTOM_PREFIX + savedFile.nameWithoutExtension

    fun saveCustomVoice(dir: File, name: String, vector: FloatArray): File {
        require(vector.size == VOICE_DIM && vector.all { it.isFinite() }) { "Некоректний вектор голосу" }
        dir.mkdirs()
        val baseName = name.replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "").trim().ifEmpty { "ClonedVoice" }
        var candidate = resolveSafeChild(dir, "$baseName.bin")
        var suffix = 2
        while (candidate.exists()) {
            candidate = resolveSafeChild(dir, "${baseName}_$suffix.bin")
            suffix++
        }
        val bb = ByteBuffer.allocate(EXPECTED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) bb.putFloat(v)
        val tmp = resolveSafeChild(dir, "${candidate.name}.tmp")
        try {
            tmp.writeBytes(bb.array())
            check(tmp.renameTo(candidate)) { "Не вдалося атомарно зберегти файл голосу" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return candidate
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
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val file = try {
            resolveSafeChild(dir, "$name.bin")
        } catch (_: Exception) {
            return null
        }
        if (!file.exists() || file.length() != EXPECTED_BYTES.toLong()) return null
        return readFloatsLE(file.readBytes(), 0)
    }

    private fun resolveSafeChild(dir: File, fileName: String): File {
        val canonicalDir = dir.canonicalFile
        val prefix = canonicalDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        val candidate = File(canonicalDir, fileName).canonicalFile
        require(candidate.path.startsWith(prefix)) { "Unsafe voice path: $fileName" }
        return candidate
    }

    private fun readBounded(stream: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            require(total <= maxBytes) { "Запис у .pt перевищує ліміт $maxBytes байт" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readFloatsLE(bytes: ByteArray, offset: Int): FloatArray {
        return readFloats(bytes, offset, ByteOrder.LITTLE_ENDIAN)
    }

    private fun readFloats(bytes: ByteArray, offset: Int, order: ByteOrder): FloatArray {
        val bb = ByteBuffer.wrap(bytes, offset, EXPECTED_BYTES).order(order)
        val vec = FloatArray(VOICE_DIM) { bb.float }
        require(vec.all { it.isFinite() }) { "Вектор голосу містить NaN або Inf" }
        return vec
    }
}
