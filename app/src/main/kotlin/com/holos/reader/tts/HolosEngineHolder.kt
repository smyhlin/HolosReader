package com.holos.reader.tts

import ai.onnxruntime.OrtSession
import android.content.Context
import com.holos.reader.g2p.NeuralAccentor
import com.holos.reader.g2p.UkrainianStressDict
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UserStressOverrides
import com.holos.tts.HolosOnnx
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Shared thread-safe singleton holding:
 * - [HolosOnnx] neural TTS engine (24 kHz streaming)
 * - [UkrainianStressDict] v2 (2.88M-word mmap binary dictionary with 36,414 tagged homographs)
 * - [NeuralAccentor] (bundled 1.17 MB accentor-lite.onnx for OOV words & neutral homographs)
 * - [UserStressOverrides] (`stress_user.txt` custom book glossary)
 */
object HolosEngineHolder {

    private const val STRESS_ASSET_GZ = "uk_stress.bin.gz"
    private const val STRESS_UNPACKED = "uk_stress_v2.bin"
    private const val ACCENTOR_ASSET = "accentor-lite.onnx"
    private const val USER_STRESS_FILE = "stress_user.txt"

    private val lock = Any()
    @Volatile
    private var engine: HolosOnnx? = null
    @Volatile
    private var stressDict: UkrainianStressDict? = null
    @Volatile
    private var neuralAccentor: NeuralAccentor? = null
    @Volatile
    private var userOverrides: UserStressOverrides? = null
    @Volatile
    private var stressifier: UkrainianStressifier? = null

    fun getCustomVoicesDir(context: Context): File =
        File(context.applicationContext.filesDir, "custom_voices").apply { mkdirs() }

    fun getUserStressFile(context: Context): File =
        File(context.applicationContext.filesDir, USER_STRESS_FILE)

    fun getUserOverrides(context: Context): UserStressOverrides {
        userOverrides?.let { return it }
        synchronized(lock) {
            userOverrides?.let { return it }
            return UserStressOverrides().apply {
                loadFromFile(getUserStressFile(context))
            }.also { userOverrides = it }
        }
    }

    fun saveUserOverrides(context: Context, rawText: String) {
        synchronized(lock) {
            val file = getUserStressFile(context)
            file.writeText(rawText, Charsets.UTF_8)
            val overrides = getUserOverrides(context)
            overrides.loadFromText(rawText)
        }
    }

    fun getStressifier(context: Context): UkrainianStressifier {
        stressifier?.let { return it }
        synchronized(lock) {
            stressifier?.let { return it }
            val appCtx = context.applicationContext

            // 1. Unpack & mmap v2 stress dictionary (2.88M wordforms + 36,414 tagged homographs)
            val outFile = File(appCtx.filesDir, STRESS_UNPACKED)
            if (!outFile.exists() || outFile.length() == 0L) {
                unpackStressDict(appCtx, outFile)
            }
            val dict = try {
                UkrainianStressDict(outFile)
            } catch (_: Exception) {
                outFile.delete()
                unpackStressDict(appCtx, outFile)
                UkrainianStressDict(outFile)
            }
            stressDict = dict

            // 2. Copy & load bundled 1.17 MB accentor-lite.onnx neural accentor
            val accentorFile = File(appCtx.filesDir, ACCENTOR_ASSET)
            val accentor = try {
                if (!accentorFile.exists() || accentorFile.length() == 0L) {
                    copyAsset(appCtx, ACCENTOR_ASSET, accentorFile)
                }
                NeuralAccentor(accentorFile).also { neuralAccentor = it }
            } catch (_: Exception) {
                null
            }

            val overrides = getUserOverrides(appCtx)
            return UkrainianStressifier(dict, overrides, accentor).also { stressifier = it }
        }
    }

    fun getEngine(context: Context): HolosOnnx? {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val appCtx = context.applicationContext
            if (!HolosModelManager.isModelReady(appCtx)) return null
            val prefs = ReaderPreferences(appCtx)
            val bundleDir = HolosModelManager.getBundleDir(appCtx)
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(prefs.cpuThreads)
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            return try {
                HolosOnnx(bundleDir, streaming = true, options = options).also { engine = it }
            } finally {
                options.close()
            }
        }
    }

    private fun unpackStressDict(context: Context, target: File) {
        val part = File(target.path + ".part")
        try {
            context.assets.open(STRESS_ASSET_GZ).use { raw ->
                GZIPInputStream(raw, 1 shl 16).use { gz ->
                    part.outputStream().use { out -> gz.copyTo(out, 1 shl 16) }
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
