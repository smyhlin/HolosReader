package com.holos.reader.tts

import android.content.Context
import android.util.Log
import com.holos.reader.ui.AppStrings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Downloads, verifies, and manages all 3 optional offline ONNX TTS model bundles:
 * - [TtsModelId.HOLOS_INT8]: HolosTTS ONNX INT8 streaming (~320 MB, 24 kHz)
 * - [TtsModelId.HOLOS_FP32]: HolosTTS ONNX FP32 studio precision (~760 MB, 24 kHz)
 * - [TtsModelId.SUPERTONIC_3]: Supertonic 3 multilingual flow-matching ONNX (~398 MB, 44.1 kHz)
 */
object HolosModelManager {

    private const val TAG = "HolosModelManager"
    const val HOLOS_INT8_BASE_URL = "https://huggingface.co/patriotyk/HolosTTS/resolve/main/onnx-int8/"
    const val HOLOS_FP32_BASE_URL = "https://huggingface.co/patriotyk/HolosTTS/resolve/main/onnx/"
    const val SUPERTONIC_3_BASE_URL = "https://huggingface.co/Supertone/supertonic-3/resolve/main/"
    private const val HOLOS_MANIFEST_NAME = "holos_onnx.json"
    private const val USER_AGENT = "HolosReader/5.2 (Android)"

    val SUPERTONIC_VOICES: List<String> = listOf(
        "F1", "F2", "F3", "F4", "F5",
        "M1", "M2", "M3", "M4", "M5",
    )

    private val SUPERTONIC_REQUIRED_REL_PATHS: List<String> = listOf(
        "onnx/tts.json",
        "onnx/unicode_indexer.json",
        "onnx/duration_predictor.onnx",
        "onnx/text_encoder.onnx",
        "onnx/vector_estimator.onnx",
        "onnx/vocoder.onnx",
    ) + SUPERTONIC_VOICES.map { "voice_styles/$it.json" }

    fun getBundleDir(
        context: Context,
        modelId: TtsModelId = ReaderPreferences(context).selectedTtsModelId,
    ): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        return File(base, TtsModelCatalog.byId(modelId).dirName)
    }

    fun isModelReady(
        context: Context,
        modelId: TtsModelId = ReaderPreferences(context).selectedTtsModelId,
    ): Boolean {
        val dir = getBundleDir(context, modelId)
        return when (modelId) {
            TtsModelId.HOLOS_INT8, TtsModelId.HOLOS_FP32 -> {
                val manifestFile = File(dir, HOLOS_MANIFEST_NAME)
                if (!manifestFile.exists()) return false
                try {
                    requiredHolosFiles(dir, manifestFile).all { f -> f.exists() && f.length() > 0L }
                } catch (e: Exception) {
                    Log.w(TAG, "Model readiness check failed for $modelId", e)
                    false
                }
            }
            TtsModelId.SUPERTONIC_3 -> {
                SUPERTONIC_REQUIRED_REL_PATHS.all { rel ->
                    val f = File(dir, rel)
                    f.exists() && f.length() > 0L
                }
            }
        }
    }

    fun hasAnyModelReady(context: Context): Boolean =
        TtsModelId.entries.any { isModelReady(context, it) }

    fun downloadedModels(context: Context): List<TtsModelId> =
        TtsModelId.entries.filter { isModelReady(context, it) }

    fun deleteModel(context: Context, modelId: TtsModelId): Boolean {
        val dir = getBundleDir(context, modelId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun downloadBundle(
        context: Context,
        modelId: TtsModelId = ReaderPreferences(context).selectedTtsModelId,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val en = AppStrings.isEnglish(context)
        val spec = TtsModelCatalog.byId(modelId)
        val dir = getBundleDir(context, modelId)
        dir.mkdirs()

        return when (modelId) {
            TtsModelId.HOLOS_INT8 -> downloadHolosBundle(dir, HOLOS_INT8_BASE_URL, spec.displayName(en), en, onProgress)
            TtsModelId.HOLOS_FP32 -> downloadHolosBundle(dir, HOLOS_FP32_BASE_URL, spec.displayName(en), en, onProgress)
            TtsModelId.SUPERTONIC_3 -> downloadSupertonicBundle(dir, spec.displayName(en), en, onProgress)
        }
    }

    private fun downloadHolosBundle(
        dir: File,
        baseUrl: String,
        modelName: String,
        en: Boolean,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val manifestFile = File(dir, HOLOS_MANIFEST_NAME)
        if (!manifestFile.exists() || manifestFile.length() == 0L) {
            onProgress(if (en) "Downloading $modelName manifest…" else "Завантаження маніфесту $modelName…", 0)
            downloadFile(baseUrl + HOLOS_MANIFEST_NAME, manifestFile) { _, _ -> }
        }
        val files = requiredHolosFiles(dir, manifestFile)
        downloadFileList(files.map { f -> (baseUrl + f.name) to f }, modelName, en, onProgress)
        return dir
    }

    private fun downloadSupertonicBundle(
        dir: File,
        modelName: String,
        en: Boolean,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val canonicalDir = dir.canonicalFile
        val prefix = canonicalDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        val items = SUPERTONIC_REQUIRED_REL_PATHS.map { rel ->
            val target = File(canonicalDir, rel).canonicalFile
            require(target.path.startsWith(prefix)) { "Unsafe path in Supertonic bundle: $rel" }
            (SUPERTONIC_3_BASE_URL + rel) to target
        }
        downloadFileList(items, modelName, en, onProgress)
        return dir
    }

    private fun downloadFileList(
        items: List<Pair<String, File>>,
        modelName: String,
        en: Boolean,
        onProgress: (message: String, percent: Int) -> Unit,
    ) {
        val totalCount = items.size
        for ((index, pair) in items.withIndex()) {
            val (url, target) = pair
            val name = target.name
            val bundleDir = target.parentFile ?: target
            val relKey = target.name
            val existingDiag = ModelIntegrityChecker.diagnoseBundle(
                bundleDir = bundleDir,
                expected = listOf(ModelIntegrityChecker.ExpectedFile(relKey, target, url)),
                deepHashCheck = false,
            ).files.firstOrNull()?.status
            if (existingDiag == ModelIntegrityChecker.FileStatus.VERIFIED) {
                onProgress(
                    if (en) "$name ready (${index + 1}/$totalCount)" else "$name вже готовий (${index + 1}/$totalCount)",
                    ((index + 1) * 100) / totalCount,
                )
                continue
            }
            target.parentFile?.mkdirs()
            onProgress(
                if (en) "Downloading $name (${index + 1}/$totalCount)…" else "Завантаження $name (${index + 1}/$totalCount)…",
                (index * 100) / totalCount,
            )
            downloadFile(url, target) { downloaded, total ->
                val frac = if (total > 0) downloaded.toDouble() / total else 0.0
                val pct = (((index + frac) / totalCount) * 100).toInt().coerceIn(0, 99)
                val mb = downloaded / (1024.0 * 1024.0)
                val unit = if (en) "MB" else "МБ"
                val label = if (total > 0) {
                    String.format(Locale.US, "%s (%.1f / %.1f %s)", name, mb, total / (1024.0 * 1024.0), unit)
                } else {
                    String.format(Locale.US, "%s (%.1f %s)", name, mb, unit)
                }
                onProgress(label, pct)
            }
            ModelIntegrityChecker.recordVerifiedFile(bundleDir, relKey, target, url)
        }
        onProgress(if (en) "$modelName is ready!" else "Модель $modelName готова!", 100)
    }

    private fun requiredHolosFiles(dir: File, manifestFile: File): List<File> {
        val canonicalDir = dir.canonicalFile
        val prefix = canonicalDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        val json = JSONObject(manifestFile.readText())
        val streaming = json.getJSONObject("streaming")
        val names = listOf(
            json.getString("encoder"),
            json.getString("prosody"),
            json.getString("voices"),
            streaming.getString("source"),
            streaming.getString("decoder"),
        )
        return names.map { rel ->
            val f = File(canonicalDir, rel).canonicalFile
            require(f.path.startsWith(prefix)) { "Unsafe path in manifest: $rel" }
            f
        }
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        onBytes: (downloaded: Long, total: Long) -> Unit,
    ) {
        VerifiedHttpDownloader.download(
            urlStr = urlStr,
            target = target,
            userAgent = USER_AGENT,
            onBytes = onBytes,
        )
    }

    fun expectedFilesForModel(context: Context, modelId: TtsModelId): List<ModelIntegrityChecker.ExpectedFile> {
        val dir = getBundleDir(context, modelId)
        return when (modelId) {
            TtsModelId.HOLOS_INT8, TtsModelId.HOLOS_FP32 -> {
                val baseUrl = if (modelId == TtsModelId.HOLOS_INT8) HOLOS_INT8_BASE_URL else HOLOS_FP32_BASE_URL
                val manifestFile = File(dir, HOLOS_MANIFEST_NAME)
                val out = ArrayList<ModelIntegrityChecker.ExpectedFile>()
                out.add(ModelIntegrityChecker.ExpectedFile(
                    HOLOS_MANIFEST_NAME, manifestFile, baseUrl + HOLOS_MANIFEST_NAME, modelId.id,
                    validateContent = { candidate ->
                        runCatching { requiredHolosFiles(dir, candidate) }.isSuccess
                    },
                ))
                if (manifestFile.isFile && manifestFile.length() > 0L) {
                    try {
                        for (f in requiredHolosFiles(dir, manifestFile)) {
                            out.add(ModelIntegrityChecker.ExpectedFile(f.name, f, baseUrl + f.name, modelId.id))
                        }
                    } catch (_: Exception) {}
                }
                out
            }
            TtsModelId.SUPERTONIC_3 -> SUPERTONIC_REQUIRED_REL_PATHS.map { rel ->
                ModelIntegrityChecker.ExpectedFile(rel, File(dir, rel), SUPERTONIC_3_BASE_URL + rel, modelId.id)
            }
        }
    }

    fun diagnoseModel(context: Context, modelId: TtsModelId, deepHashCheck: Boolean = true): ModelIntegrityChecker.BundleReport {
        val dir = getBundleDir(context, modelId)
        return ModelIntegrityChecker.diagnoseBundle(dir, expectedFilesForModel(context, modelId), deepHashCheck)
    }

    fun repairModel(
        context: Context,
        modelId: TtsModelId,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): ModelIntegrityChecker.BundleReport {
        val dir = getBundleDir(context, modelId).apply { mkdirs() }
        val initial = expectedFilesForModel(context, modelId)
        val manifest = initial.firstOrNull { it.relPath == HOLOS_MANIFEST_NAME }
        if (manifest != null) {
            ModelIntegrityChecker.repairBundle(dir, listOf(manifest)) { item ->
                onProgress("Repairing ${item.file.name}…", 0)
                val staging = java.nio.file.Files.createTempDirectory(dir.toPath(), "repair-").toFile()
                try {
                    val stagedManifest = File(staging, item.file.name)
                    downloadFile(item.sourceUrl, stagedManifest) { _, _ -> }
                    check(item.validateContent?.invoke(stagedManifest) == true) { "Downloaded Holos manifest is invalid" }
                    java.nio.file.Files.move(
                        stagedManifest.toPath(), item.file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

        val expected = expectedFilesForModel(context, modelId)
        val total = expected.size.coerceAtLeast(1)
        var done = 0
        return ModelIntegrityChecker.repairBundle(dir, expected) { item ->
            check(item.relPath != HOLOS_MANIFEST_NAME) { "Holos manifest could not be repaired" }
            done++
            onProgress("Repairing ${item.file.name} ($done/$total)…", ((done - 1) * 100) / total)
            downloadFile(item.sourceUrl, item.file) { _, _ -> }
        }
    }
}
