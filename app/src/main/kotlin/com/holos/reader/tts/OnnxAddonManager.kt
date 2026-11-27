package com.holos.reader.tts

import android.content.Context
import android.net.Uri
import com.holos.reader.ui.AppStrings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Downloads, imports, verifies, and removes optional ONNX add-on bundles:
 * - [OnnxAddonId.FLASH_SR]: Direct HF download (~487 KB)
 * - [OnnxAddonId.UK_EMOTION]: Direct HF download (~580 MB)
 * - [OnnxAddonId.CONTEXTUAL_STRESS]: Local ONNX import + auto HF tokenizer.json download
 * - [OnnxAddonId.VOICE_ENCODER]: Local ONNX import (`voice_encoder.onnx`)
 */
object OnnxAddonManager {

    private const val USER_AGENT = "HolosReader/5.2 (Android)"

    const val FLASH_SR_ONNX_URL = "https://huggingface.co/YatharthS/FlashSR/resolve/main/onnx/model.onnx"
    const val UK_EMOTION_ONNX_URL = "https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx/resolve/main/model_quantized.onnx"
    const val UK_EMOTION_TOKENIZER_URL = "https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx/resolve/main/tokenizer.json"
    const val CONTEXTUAL_STRESS_TOKENIZER_URL = "https://huggingface.co/aloudreader/uk-stress-token-classifier/resolve/main/tokenizer.json"
    const val CONTEXTUAL_STRESS_FP16_ONNX_URL =
        "https://github.com/smyhlin/HolosReader/releases/download/onnx-addons-v1/uk_stress_contextual_fp16.onnx"
    const val CONTEXTUAL_STRESS_INT8_ONNX_URL =
        "https://github.com/smyhlin/HolosReader/releases/download/onnx-addons-v1/uk_stress_contextual_int8.onnx"
    const val VOICE_ENCODER_ONNX_URL =
        "https://github.com/smyhlin/HolosReader/releases/download/onnx-addons-v1/voice_encoder.onnx"

    private val KNOWN_VERIFICATIONS: Map<String, Pair<Long, String>> = mapOf(
        CONTEXTUAL_STRESS_FP16_ONNX_URL to (220507456L to "41d57395dd5ab2efa4db8640dd2046b8663164eedf9ceb782f5f0a4e01b0db1e"),
        CONTEXTUAL_STRESS_INT8_ONNX_URL to (110908512L to "0487b6bccd7f129a8c36fabcf7b59d5a94017df9750b504cfc3b2af00d25a2c8"),
        VOICE_ENCODER_ONNX_URL to (97359300L to "31c2cd3a8b9d7849b7fd9c485f28b469148a71db4ee01362d93e720f5a881fc1"),
    )

    /** Recommends FP16 contextual stress on 6+ GB RAM devices with NNAPI/AUTO acceleration. */
    fun recommendFp16Stress(context: Context): Boolean {
        val hw = DeviceCapabilityScanner.scan(context)
        val accel = ReaderPreferences(context).onnxAccelerator.uppercase(Locale.US)
        return hw.totalRamGb >= 6.0 && (accel == "AUTO" || accel == "NNAPI")
    }

    fun getAddonDir(context: Context, addonId: OnnxAddonId): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        return File(base, OnnxAddonCatalog.byId(addonId).dirName)
    }

    fun isAddonReady(context: Context, addonId: OnnxAddonId): Boolean {
        val dir = getAddonDir(context, addonId)
        return OnnxAddonCatalog.byId(addonId).requiredFiles.all { rel ->
            val f = File(dir, rel)
            f.exists() && f.length() > 0L
        }
    }

    fun deleteAddon(context: Context, addonId: OnnxAddonId): Boolean {
        val dir = getAddonDir(context, addonId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    /**
     * Imports a user-supplied `.onnx` file (e.g., `uk_stress_contextual.onnx` or `voice_encoder.onnx`)
     * into the add-on directory. For [OnnxAddonId.CONTEXTUAL_STRESS], automatically downloads
     * `tokenizer.json` from Hugging Face if missing.
     */
    fun importOnnxFile(
        context: Context,
        addonId: OnnxAddonId,
        uri: Uri,
        onProgress: (message: String, percent: Int) -> Unit = { _, _ -> },
    ): File {
        val en = AppStrings.isEnglish(context)
        val concurrency = ReaderPreferences(context).downloadConcurrency
        val dir = getAddonDir(context, addonId).apply { mkdirs() }
        val targetName = when (addonId) {
            OnnxAddonId.FLASH_SR -> "model.onnx"
            OnnxAddonId.UK_EMOTION -> "model_quantized.onnx"
            OnnxAddonId.CONTEXTUAL_STRESS -> "uk_stress_contextual.onnx"
            OnnxAddonId.VOICE_ENCODER -> "voice_encoder.onnx"
        }
        val target = File(dir, targetName)
        val part = File(target.path + ".part")
        onProgress(if (en) "Importing $targetName…" else "Імпорт $targetName…", 20)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: throw IllegalStateException("Cannot open selected ONNX file")
        check(part.length() > 1024L) { "Selected ONNX file is too small or empty" }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Failed to save $targetName" }

        if (addonId == OnnxAddonId.CONTEXTUAL_STRESS) {
            val tokFile = File(dir, "tokenizer.json")
            if (!tokFile.exists() || tokFile.length() == 0L) {
                onProgress(if (en) "Downloading tokenizer.json…" else "Завантаження tokenizer.json…", 70)
                downloadFile(CONTEXTUAL_STRESS_TOKENIZER_URL, tokFile) { _, _ -> }
            }
        }
        onProgress(if (en) "Add-on ready!" else "Модуль готовий!", 100)
        return target
    }

    fun downloadAddon(
        context: Context,
        addonId: OnnxAddonId,
        preferFp16: Boolean = false,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val en = AppStrings.isEnglish(context)
        val dir = getAddonDir(context, addonId).apply { mkdirs() }
        val items: List<Pair<String, File>> = when (addonId) {
            OnnxAddonId.FLASH_SR -> listOf(
                FLASH_SR_ONNX_URL to File(dir, "model.onnx"),
            )
            OnnxAddonId.UK_EMOTION -> listOf(
                UK_EMOTION_TOKENIZER_URL to File(dir, "tokenizer.json"),
                UK_EMOTION_ONNX_URL to File(dir, "model_quantized.onnx"),
            )
            OnnxAddonId.CONTEXTUAL_STRESS -> listOf(
                CONTEXTUAL_STRESS_TOKENIZER_URL to File(dir, "tokenizer.json"),
                (if (preferFp16) CONTEXTUAL_STRESS_FP16_ONNX_URL else CONTEXTUAL_STRESS_INT8_ONNX_URL) to
                    File(dir, "uk_stress_contextual.onnx"),
            )
            OnnxAddonId.VOICE_ENCODER -> listOf(
                VOICE_ENCODER_ONNX_URL to File(dir, "voice_encoder.onnx"),
            )
        }

        val variant = if (addonId == OnnxAddonId.CONTEXTUAL_STRESS) (if (preferFp16) "fp16" else "int8") else addonId.id
        val totalCount = items.size
        for ((index, pair) in items.withIndex()) {
            val (url, target) = pair
            val known = KNOWN_VERIFICATIONS[url]
            val diag = ModelIntegrityChecker.diagnoseBundle(
                bundleDir = dir,
                expected = listOf(
                    ModelIntegrityChecker.ExpectedFile(
                        relPath = target.name,
                        file = target,
                        sourceUrl = url,
                        variant = variant,
                        expectedBytes = known?.first ?: -1L,
                        expectedSha256 = known?.second,
                    )
                ),
                deepHashCheck = false,
            ).files.firstOrNull()?.status
            if (diag == ModelIntegrityChecker.FileStatus.VERIFIED) {
                onProgress("${target.name} (${index + 1}/$totalCount)", ((index + 1) * 100) / totalCount)
                continue
            }
            onProgress(
                if (en) "Downloading ${target.name}…" else "Завантаження ${target.name}…",
                (index * 100) / totalCount,
            )
            downloadFile(url, target, concurrency = concurrency) { prog ->
                val frac = if (prog.totalBytes > 0L) prog.downloadedBytes.toDouble() / prog.totalBytes else 0.0
                val pct = (((index + frac) / totalCount) * 100).toInt().coerceIn(0, 99)
                val label = VerifiedHttpDownloader.formatProgressSummary(target.name, prog, en)
                onProgress(label, pct)
            }
            ModelIntegrityChecker.recordVerifiedFile(dir, target.name, target, url, variant, known?.second)
        }
        onProgress(if (en) "Add-on ready!" else "Модуль готовий!", 100)
        return dir
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        concurrency: Int = VerifiedHttpDownloader.DEFAULT_CONCURRENCY,
        onProgress: (VerifiedHttpDownloader.DownloadProgress) -> Unit = { _ -> },
    ) {
        val known = KNOWN_VERIFICATIONS[urlStr]
        VerifiedHttpDownloader.download(
            urlStr = urlStr,
            target = target,
            userAgent = USER_AGENT,
            expectedBytes = known?.first ?: -1L,
            expectedSha256 = known?.second,
            concurrency = concurrency,
            onProgress = onProgress,
        )
    }

    fun expectedFilesForAddon(
        context: Context,
        addonId: OnnxAddonId,
        preferFp16: Boolean? = null,
    ): List<ModelIntegrityChecker.ExpectedFile> {
        val dir = getAddonDir(context, addonId)
        val useFp16 = addonId == OnnxAddonId.CONTEXTUAL_STRESS &&
            (preferFp16 ?: installedContextualPrecision(dir) ?: recommendFp16Stress(context))
        val variant = if (addonId == OnnxAddonId.CONTEXTUAL_STRESS) {
            if (useFp16) "fp16" else "int8"
        } else addonId.id
        val pairs: List<Pair<String, File>> = when (addonId) {
            OnnxAddonId.FLASH_SR -> listOf(FLASH_SR_ONNX_URL to File(dir, "model.onnx"))
            OnnxAddonId.UK_EMOTION -> listOf(
                UK_EMOTION_TOKENIZER_URL to File(dir, "tokenizer.json"),
                UK_EMOTION_ONNX_URL to File(dir, "model_quantized.onnx"),
            )
            OnnxAddonId.CONTEXTUAL_STRESS -> listOf(
                CONTEXTUAL_STRESS_TOKENIZER_URL to File(dir, "tokenizer.json"),
                (if (useFp16) CONTEXTUAL_STRESS_FP16_ONNX_URL else CONTEXTUAL_STRESS_INT8_ONNX_URL) to File(dir, "uk_stress_contextual.onnx"),
            )
            OnnxAddonId.VOICE_ENCODER -> listOf(VOICE_ENCODER_ONNX_URL to File(dir, "voice_encoder.onnx"))
        }
        return pairs.map { (url, f) ->
            val known = KNOWN_VERIFICATIONS[url]
            ModelIntegrityChecker.ExpectedFile(
                relPath = f.name,
                file = f,
                sourceUrl = url,
                variant = if (addonId == OnnxAddonId.CONTEXTUAL_STRESS && f.name == "tokenizer.json") "" else variant,
                expectedBytes = known?.first ?: -1L,
                expectedSha256 = known?.second,
            )
        }
    }

    internal fun installedContextualPrecision(dir: File): Boolean? {
        val model = File(dir, "uk_stress_contextual.onnx")
        val recorded = ModelIntegrityChecker.readManifest(dir)[model.name]
        return when (recorded?.sourceUrl) {
            CONTEXTUAL_STRESS_FP16_ONNX_URL -> true
            CONTEXTUAL_STRESS_INT8_ONNX_URL -> false
            else -> when (model.length()) {
                KNOWN_VERIFICATIONS.getValue(CONTEXTUAL_STRESS_FP16_ONNX_URL).first -> true
                KNOWN_VERIFICATIONS.getValue(CONTEXTUAL_STRESS_INT8_ONNX_URL).first -> false
                else -> null
            }
        }
    }

    fun diagnoseAddon(context: Context, addonId: OnnxAddonId, preferFp16: Boolean? = null, deepHashCheck: Boolean = true): ModelIntegrityChecker.BundleReport {
        val dir = getAddonDir(context, addonId)
        return ModelIntegrityChecker.diagnoseBundle(dir, expectedFilesForAddon(context, addonId, preferFp16), deepHashCheck)
    }

    fun repairAddon(context: Context, addonId: OnnxAddonId, preferFp16: Boolean? = null): ModelIntegrityChecker.BundleReport {
        val dir = getAddonDir(context, addonId).apply { mkdirs() }
        return ModelIntegrityChecker.repairBundle(dir, expectedFilesForAddon(context, addonId, preferFp16)) { item ->
            downloadFile(item.sourceUrl, item.file, concurrency = ReaderPreferences(context).downloadConcurrency)
        }
    }
}
