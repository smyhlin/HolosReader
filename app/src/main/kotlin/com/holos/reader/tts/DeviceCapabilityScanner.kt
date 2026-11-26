package com.holos.reader.tts

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.Locale

enum class HardwareTier {
    HIGH,
    MEDIUM,
    ENTRY,
}

data class DeviceCapabilityReport(
    val tier: HardwareTier,
    val recommendedModel: TtsModelId,
    val cpuCores: Int,
    val maxCpuMhz: Int,
    val totalRamGb: Double,
    val is64Bit: Boolean,
    val hasVulkanGpu: Boolean,
    val glEsVersion: String,
    val socLabel: String,
) {
    fun summary(isEnglish: Boolean): String {
        val ramStr = String.format(Locale.US, "%.1f", totalRamGb)
        val freqStr = if (maxCpuMhz > 0) {
            String.format(Locale.US, " @ %.2f GHz", maxCpuMhz / 1000.0)
        } else ""
        val gpuStr = if (hasVulkanGpu) "Vulkan GPU" else "GLES $glEsVersion"
        val tierLabel = when (tier) {
            HardwareTier.HIGH -> if (isEnglish) "High-end" else "Потужний"
            HardwareTier.MEDIUM -> if (isEnglish) "Mid-range" else "Середній"
            HardwareTier.ENTRY -> if (isEnglish) "Entry-level" else "Базовий"
        }
        return if (isEnglish) {
            "Detected: $tierLabel ($cpuCores cores$freqStr · $ramStr GB RAM · $gpuStr)"
        } else {
            "Пристрій: $tierLabel ($cpuCores ядер$freqStr · $ramStr ГБ ОЗП · $gpuStr)"
        }
    }
}

/**
 * Scans device CPU, RAM, 64-bit ABI, and GPU (Vulkan / OpenGL ES) capabilities
 * to recommend the best-fitting offline ONNX TTS model on first launch.
 */
object DeviceCapabilityScanner {

    fun scan(context: Context): DeviceCapabilityReport {
        val appCtx = context.applicationContext
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxMhz = readMaxCpuFrequencyMhz(cores)
        val ramGb = readTotalRamGb(appCtx)
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val hasVulkan = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val glEsVersion = readGlEsVersion(appCtx)
        val socLabel = readSocLabel()

        val (tier, recommended) = classifyHardware(
            cpuCores = cores,
            maxCpuMhz = maxMhz,
            totalRamGb = ramGb,
            is64Bit = is64Bit,
            hasVulkanGpu = hasVulkan,
        )

        return DeviceCapabilityReport(
            tier = tier,
            recommendedModel = recommended,
            cpuCores = cores,
            maxCpuMhz = maxMhz,
            totalRamGb = ramGb,
            is64Bit = is64Bit,
            hasVulkanGpu = hasVulkan,
            glEsVersion = glEsVersion,
            socLabel = socLabel,
        )
    }

    /**
     * Pure deterministic hardware classifier (unit-testable on JVM).
     */
    internal fun classifyHardware(
        cpuCores: Int,
        maxCpuMhz: Int,
        totalRamGb: Double,
        is64Bit: Boolean,
        hasVulkanGpu: Boolean,
    ): Pair<HardwareTier, TtsModelId> {
        if (!is64Bit || totalRamGb < 3.5 || cpuCores < 6) {
            return HardwareTier.ENTRY to TtsModelId.HOLOS_INT8
        }
        val strongCpu = maxCpuMhz >= 2400 || (maxCpuMhz == 0 && hasVulkanGpu && totalRamGb >= 7.5)
        if (totalRamGb >= 5.5 && cpuCores >= 8 && strongCpu) {
            return HardwareTier.HIGH to TtsModelId.HOLOS_FP32
        }
        val decentCpu = maxCpuMhz >= 1800 || maxCpuMhz == 0
        if (totalRamGb >= 3.5 && cpuCores >= 6 && decentCpu) {
            return HardwareTier.MEDIUM to TtsModelId.SUPERTONIC_3
        }
        return HardwareTier.ENTRY to TtsModelId.HOLOS_INT8
    }

    private fun readMaxCpuFrequencyMhz(cores: Int): Int {
        var maxKhz = 0L
        val limit = cores.coerceIn(1, 32)
        for (i in 0 until limit) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (f.exists() && f.canRead()) {
                try {
                    val khz = f.readText().trim().toLongOrNull() ?: 0L
                    if (khz > maxKhz) maxKhz = khz
                } catch (_: Throwable) {
                }
            }
        }
        return (maxKhz / 1000L).toInt()
    }

    private fun readTotalRamGb(context: Context): Double {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                mi.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            } else {
                4.0
            }
        } catch (_: Throwable) {
            4.0
        }
    }

    private fun readGlEsVersion(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.deviceConfigurationInfo?.glEsVersion ?: "3.0"
        } catch (_: Throwable) {
            "3.0"
        }
    }

    private fun readSocLabel(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != Build.UNKNOWN) {
                Build.SOC_MODEL
            } else {
                Build.HARDWARE.ifBlank { Build.BOARD }
            }
        } catch (_: Throwable) {
            Build.HARDWARE
        }
    }
}
