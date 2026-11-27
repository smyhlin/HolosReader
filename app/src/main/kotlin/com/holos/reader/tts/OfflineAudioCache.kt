package com.holos.reader.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strict, shared offline WAV + `.idx` + `.ready` cache publisher and validator (#20).
 * Both [TtsPlaybackController] and [com.holos.reader.ui.AudioSheetController] rely on
 * [validate] so incomplete, truncated, malformed, or partially published caches never show
 * "Ready" and never silently skip paragraphs.
 */
object OfflineAudioCache {

    data class BlockEntry(
        val blockIndex: Int,
        val startSample: Long,
        val speechSamples: Long,
    )

    data class ValidatedChapter(
        val wavFile: File,
        val sampleRate: Int,
        val totalSamples: Long,
        val entries: List<BlockEntry>,
    )

    fun indexFileFor(wavFile: File): File = File(wavFile.path + ".idx")
    fun readyFileFor(wavFile: File): File = File(wavFile.path + ".ready")

    /**
     * Atomically commits a rendered generation: promotes `.wav.part` and `.idx.part` and writes
     * the `.ready` manifest marker LAST via atomic rename. If any step fails, removes the `.ready`
     * marker and partial files so no half-published generation is ever exposed.
     */
    fun publishAtomically(
        tmpWav: File,
        entries: List<BlockEntry>,
        sampleRate: Int,
        dataBytes: Long,
        targetWav: File,
    ) {
        val targetIdx = indexFileFor(targetWav)
        val targetReady = readyFileFor(targetWav)
        val tmpIdx = File(targetWav.path + ".idx.part")
        val tmpReady = File(targetWav.path + ".ready.part")

        // Remove any prior ready marker first so readers never mix old/new generations
        if (targetReady.exists()) targetReady.delete()

        val sb = StringBuilder(entries.size * 24)
        for (e in entries) {
            sb.append(e.blockIndex).append('\t')
                .append(e.startSample).append('\t')
                .append(e.speechSamples).append('\n')
        }
        tmpIdx.writeText(sb.toString(), Charsets.UTF_8)

        val manifest = buildString {
            append("version=2\n")
            append("sampleRate=").append(sampleRate).append('\n')
            append("dataBytes=").append(dataBytes).append('\n')
            append("blockCount=").append(entries.size).append('\n')
        }
        tmpReady.writeText(manifest, Charsets.UTF_8)

        if (targetWav.exists()) targetWav.delete()
        if (targetIdx.exists()) targetIdx.delete()
        val wavOk = tmpWav.renameTo(targetWav)
        val idxOk = wavOk && tmpIdx.renameTo(targetIdx)
        val readyOk = idxOk && tmpReady.renameTo(targetReady)
        if (!readyOk) {
            tmpWav.delete()
            tmpIdx.delete()
            tmpReady.delete()
            targetReady.delete()
            throw IllegalStateException("Atomic offline WAV/index/manifest publish failed")
        }
    }

    /**
     * Strictly validates WAV RIFF/PCM16 header, data byte length, `.idx` syntax/ordering/bounds,
     * `.ready` manifest match, and optional expected speakable block coverage.
     * Returns `null` on ANY inconsistency (never silently drops malformed rows).
     */
    fun validate(
        wavFile: File,
        expectedSpeakableBlockIds: List<Int>? = null,
    ): ValidatedChapter? {
        val idxFile = indexFileFor(wavFile)
        val readyFile = readyFileFor(wavFile)
        if (!wavFile.isFile || !idxFile.isFile || !readyFile.isFile) return null
        val fileLen = wavFile.length()
        if (fileLen <= 44L) return null

        val manifest = parseManifest(readyFile) ?: return null
        val header = parseWavHeader(wavFile, fileLen) ?: return null
        if (header.sampleRate != manifest.sampleRate || header.dataBytes != manifest.dataBytes) return null

        val totalSamples = header.dataBytes / 2L
        if (totalSamples <= 0L) return null

        val entries = parseStrictIndex(idxFile, totalSamples) ?: return null
        if (entries.isEmpty() || entries.size != manifest.blockCount) return null

        if (expectedSpeakableBlockIds != null) {
            if (expectedSpeakableBlockIds.isEmpty()) return null
            if (entries.size != expectedSpeakableBlockIds.size) return null
            for (i in entries.indices) {
                if (entries[i].blockIndex != expectedSpeakableBlockIds[i]) return null
            }
        }

        return ValidatedChapter(
            wavFile = wavFile,
            sampleRate = header.sampleRate,
            totalSamples = totalSamples,
            entries = entries,
        )
    }

    private data class WavHeader(val sampleRate: Int, val dataBytes: Long)
    private data class ReadyManifest(val sampleRate: Int, val dataBytes: Long, val blockCount: Int)

    private fun parseManifest(file: File): ReadyManifest? {
        return try {
            val map = HashMap<String, String>()
            for (line in file.readLines(Charsets.UTF_8)) {
                val eq = line.indexOf('=')
                if (eq > 0) map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            if (map["version"] != "2") return null
            val sr = map["sampleRate"]?.toIntOrNull() ?: return null
            val db = map["dataBytes"]?.toLongOrNull() ?: return null
            val bc = map["blockCount"]?.toIntOrNull() ?: return null
            if (sr !in 8000..96000 || db <= 0L || bc <= 0) return null
            ReadyManifest(sr, db, bc)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWavHeader(wavFile: File, fileLen: Long): WavHeader? {
        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                val hdr = ByteArray(44)
                if (raf.read(hdr) != 44) return null
                if (String(hdr, 0, 4, Charsets.US_ASCII) != "RIFF") return null
                if (String(hdr, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                if (String(hdr, 12, 4, Charsets.US_ASCII) != "fmt ") return null
                if (String(hdr, 36, 4, Charsets.US_ASCII) != "data") return null
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = bb.getShort(20).toInt() and 0xFFFF
                val channels = bb.getShort(22).toInt() and 0xFFFF
                val sampleRate = bb.getInt(24)
                val bitsPerSample = bb.getShort(34).toInt() and 0xFFFF
                val declaredDataBytes = bb.getInt(40).toLong() and 0xFFFFFFFFL
                val actualDataBytes = fileLen - 44L
                if (audioFormat != 1 || channels != 1 || bitsPerSample != 16) return null
                if (sampleRate !in 8000..96000) return null
                if (declaredDataBytes <= 0L || declaredDataBytes != actualDataBytes || (actualDataBytes and 1L) != 0L) {
                    return null
                }
                WavHeader(sampleRate, actualDataBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStrictIndex(idxFile: File, totalSamples: Long): List<BlockEntry>? {
        return try {
            val lines = idxFile.readLines(Charsets.UTF_8)
            val result = ArrayList<BlockEntry>(lines.size)
            var prevBlock = -1
            var prevEndSample = 0L
            for (raw in lines) {
                if (raw.isEmpty()) continue
                val parts = raw.split('\t')
                if (parts.size != 3) return null
                val b = parts[0].toIntOrNull() ?: return null
                val st = parts[1].toLongOrNull() ?: return null
                val sp = parts[2].toLongOrNull() ?: return null
                if (b < 0 || b <= prevBlock) return null
                if (st < prevEndSample || st < 0L || st >= totalSamples) return null
                if (sp <= 0L || sp > totalSamples - st) return null
                val endSample = st + sp
                result.add(BlockEntry(b, st, sp))
                prevBlock = b
                prevEndSample = endSample
            }
            result
        } catch (_: Exception) {
            null
        }
    }
}
