package com.holos.reader.tts

import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.holos.reader.g2p.NeuralAccentor
import com.holos.reader.g2p.UkrainianStressDict
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UserStressOverrides
import com.holos.reader.ui.AppStrings
import com.holos.tts.HolosOnnx
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * Shared thread-safe singleton holding:
 * - [HolosOnnx] neural TTS engine (24 kHz streaming) with hardware EP selection (NNAPI / XNNPACK / CPU)
 * - [UkrainianStressDict] v2 (2.88M-word mmap binary dictionary with 36,414 tagged homographs)
 * - [NeuralAccentor] (bundled 1.17 MB accentor-lite.onnx for OOV words & neutral homographs)
 * - [UserStressOverrides] (`stress_user.txt` custom book glossary)
 */
object HolosEngineHolder {

    private const val TAG = "HolosEngineHolder"
    private const val STRESS_ASSET_GZ = "uk_stress.bin.gz"
    private const val STRESS_ASSET_RAW = "uk_stress.bin"
    private const val STRESS_UNPACKED = "uk_stress_v2.bin"
    private const val ACCENTOR_ASSET = "accentor-lite.onnx"
    private const val USER_STRESS_FILE = "stress_user.txt"

    const val EP_AUTO = "AUTO"
    const val EP_NNAPI = "NNAPI"
    const val EP_XNNPACK = "XNNPACK"
    const val EP_CPU = "CPU"

    private val engineLock = Any()
    private val stressLock = Any()
    private val overridesLock = Any()

    @Volatile
    private var engine: HolosOnnx? = null
    @Volatile
    private var loadedThreads: Int = -1
    @Volatile
    private var loadedAccelPref: String? = null
    @Volatile
    var activeAccelerator: String = "CPU"
        private set

    @Volatile
    private var stressDict: UkrainianStressDict? = null
    @Volatile
    private var neuralAccentor: NeuralAccentor? = null
    @Volatile
    private var userOverrides: UserStressOverrides? = null
    @Volatile
    private var stressifier: UkrainianStressifier? = null
    @Volatile
    private var stressLoadError: String? = null

    fun getCustomVoicesDir(context: Context): File =
        File(context.applicationContext.filesDir, "custom_voices").apply { mkdirs() }

    fun getUserStressFile(context: Context): File =
        File(context.applicationContext.filesDir, USER_STRESS_FILE)

    fun getUserOverrides(context: Context): UserStressOverrides {
        userOverrides?.let { return it }
        synchronized(overridesLock) {
            userOverrides?.let { return it }
            return UserStressOverrides().apply {
                loadFromFile(getUserStressFile(context))
            }.also { userOverrides = it }
        }
    }

    fun saveUserOverrides(context: Context, rawText: String) {
        synchronized(overridesLock) {
            val file = getUserStressFile(context)
            file.writeText(rawText, Charsets.UTF_8)
            val overrides = getUserOverrides(context)
            overrides.loadFromText(rawText)
        }
    }

    /** Non-blocking voice list peek safe to call on the UI thread. */
    fun peekVoicesOrEmpty(): List<String> = engine?.voices ?: emptyList()

    /**
     * Closes the active [HolosOnnx] engine so updated CPU threads or hardware accelerator
     * settings take effect immediately on the next synthesis without restarting the app.
     */
    fun resetEngine() {
        synchronized(engineLock) {
            val old = engine
            engine = null
            loadedThreads = -1
            loadedAccelPref = null
            if (old != null) {
                try {
                    old.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing previous HolosOnnx engine", e)
                }
            }
        }
    }

    fun getStressStatusSummary(context: Context): String {
        getStressifier(context)
        val en = AppStrings.isEnglish(context)
        val dictState = if (stressDict != null) {
            val sizeMb = File(context.applicationContext.filesDir, STRESS_UNPACKED).length() / (1024.0 * 1024.0)
            if (en) String.format("built-in (2.88M forms · %.1f MB)", sizeMb)
            else String.format("вбудований (2,88 млн форм · %.1f МБ)", sizeMb)
        } else {
            val err = stressLoadError ?: if (en) "error" else "помилка"
            if (en) "not loaded ($err)" else "не завантажено ($err)"
        }
        val accState = if (neuralAccentor != null) {
            if (en) "active" else "активний"
        } else {
            if (en) "off" else "вимк."
        }
        val userCount = getUserOverrides(context).size()
        return if (en) {
            "Dict: $dictState · Neural Accentor: $accState · Custom rules: $userCount"
        } else {
            "Словник: $dictState · Нейроакцентор: $accState · Власні правила: $userCount"
        }
    }

    fun getStressifier(context: Context): UkrainianStressifier {
        stressifier?.let { return it }
        synchronized(stressLock) {
            stressifier?.let { return it }
            val appCtx = context.applicationContext

            // 1. Unpack & mmap v2 stress dictionary (handles both .bin.gz and AAPT-stripped .bin)
            val outFile = File(appCtx.filesDir, STRESS_UNPACKED)
            val dict = try {
                if (!outFile.exists() || outFile.length() == 0L) {
                    unpackStressDict(appCtx, outFile)
                }
                try {
                    UkrainianStressDict(outFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Corrupted stress dict cache, re-unpacking", e)
                    outFile.delete()
                    unpackStressDict(appCtx, outFile)
                    UkrainianStressDict(outFile)
                }
            } catch (e: Throwable) {
                stressLoadError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "Stress dictionary unavailable, falling back to neural accentor", e)
                null
            }
            stressDict = dict

            // 2. Copy & load bundled 1.17 MB accentor-lite.onnx neural accentor
            val accentorFile = File(appCtx.filesDir, ACCENTOR_ASSET)
            val accentor = try {
                if (!accentorFile.exists() || accentorFile.length() == 0L) {
                    copyAsset(appCtx, ACCENTOR_ASSET, accentorFile)
                }
                NeuralAccentor(accentorFile).also { neuralAccentor = it }
            } catch (e: Exception) {
                Log.w(TAG, "Neural accentor unavailable, falling back to rule heuristics", e)
                null
            }

            val overrides = getUserOverrides(appCtx)
            return UkrainianStressifier(dict, overrides, accentor).also { stressifier = it }
        }
    }

    fun getEngine(context: Context): HolosOnnx? {
        val appCtx = context.applicationContext
        val prefs = ReaderPreferences(appCtx)
        val wantThreads = prefs.cpuThreads
        val wantAccel = prefs.onnxAccelerator

        engine?.let { existing ->
            if (loadedThreads == wantThreads && loadedAccelPref == wantAccel) {
                return existing
            }
        }

        synchronized(engineLock) {
            engine?.let { existing ->
                if (loadedThreads == wantThreads && loadedAccelPref == wantAccel) {
                    return existing
                }
                try {
                    existing.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing stale HolosOnnx engine", e)
                }
                engine = null
            }

            if (!HolosModelManager.isModelReady(appCtx)) return null
            val bundleDir = HolosModelManager.getBundleDir(appCtx)

            val candidates: List<String> = when (wantAccel) {
                EP_NNAPI -> listOf(EP_NNAPI, EP_XNNPACK, EP_CPU)
                EP_XNNPACK -> listOf(EP_XNNPACK, EP_CPU)
                EP_CPU -> listOf(EP_CPU)
                else -> listOf(EP_NNAPI, EP_XNNPACK, EP_CPU) // AUTO
            }

            for (ep in candidates) {
                val created = tryCreateEngine(bundleDir, wantThreads, ep)
                if (created != null) {
                    engine = created
                    loadedThreads = wantThreads
                    loadedAccelPref = wantAccel
                    activeAccelerator = when {
                        ep == EP_NNAPI -> "NNAPI (GPU/NPU)"
                        ep == EP_XNNPACK && wantAccel == EP_NNAPI -> "XNNPACK (NNAPI fallback)"
                        ep == EP_XNNPACK -> "XNNPACK (SIMD)"
                        wantAccel == EP_NNAPI -> "CPU (NNAPI fallback)"
                        wantAccel == EP_XNNPACK -> "CPU (XNNPACK fallback)"
                        else -> "CPU ($wantThreads th)"
                    }
                    Log.i(TAG, "Loaded HolosOnnx with EP=$activeAccelerator (pref=$wantAccel, threads=$wantThreads)")
                    return created
                }
            }
            return null
        }
    }

    private fun tryCreateEngine(bundleDir: File, threads: Int, ep: String): HolosOnnx? {
        val options = OrtSession.SessionOptions()
        return try {
            options.setIntraOpNumThreads(threads)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            when (ep) {
                EP_NNAPI -> {
                    options.addNnapi()
                }
                EP_XNNPACK -> {
                    options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
            }
            HolosOnnx(bundleDir, streaming = true, options = options)
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX EP=$ep failed to initialize; trying fallback", t)
            null
        } finally {
            try {
                options.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openStressAsset(context: Context): InputStream {
        return try {
            context.assets.open(STRESS_ASSET_GZ)
        } catch (_: FileNotFoundException) {
            // Android AAPT automatically strips `.gz` and decompresses gzipped assets at APK build time.
            context.assets.open(STRESS_ASSET_RAW)
        }
    }

    private fun unpackStressDict(context: Context, target: File) {
        val part = File(target.path + ".part")
        try {
            openStressAsset(context).use { raw ->
                val pb = PushbackInputStream(raw, 2)
                val header = ByteArray(2)
                val read = pb.read(header)
                if (read > 0) pb.unread(header, 0, read)
                val isGzip = read == 2 &&
                    (header[0].toInt() and 0xFF) == 0x1F &&
                    (header[1].toInt() and 0xFF) == 0x8B
                val source: InputStream = if (isGzip) GZIPInputStream(pb, 1 shl 16) else pb
                source.use { input ->
                    part.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
            }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Failed to unpack $STRESS_UNPACKED" }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private fun copyAsset(context: Context, assetName: String, target: File) {
        val part = File(target.path + ".part")
        try {
            context.assets.open(assetName).use { input ->
                part.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Failed to copy $assetName" }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }
}
