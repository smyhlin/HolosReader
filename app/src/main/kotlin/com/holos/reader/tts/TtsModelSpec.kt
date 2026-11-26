package com.holos.reader.tts

/**
 * Supported offline ONNX neural TTS model bundles in HolosReader.
 */
enum class TtsModelId(val id: String) {
    HOLOS_INT8("holos_int8"),
    HOLOS_FP32("holos_fp32"),
    SUPERTONIC_3("supertonic_3");

    companion object {
        fun fromId(raw: String?): TtsModelId =
            entries.firstOrNull { it.id == raw } ?: HOLOS_INT8
    }
}

data class TtsModelSpec(
    val id: TtsModelId,
    val displayNameEn: String,
    val displayNameUk: String,
    val qualityStars: String,
    val qualityScore: Float,
    val approxSizeMb: Int,
    val sampleRateHz: Int,
    val dirName: String,
    val summaryEn: String,
    val summaryUk: String,
    val hardwareHintEn: String,
    val hardwareHintUk: String,
    val supportsCustomVoices: Boolean,
    val supportsEnglishPronunciation: Boolean,
) {
    val qualityBadge: String
        get() = "$qualityStars ${String.format(java.util.Locale.US, "%.1f/5", qualityScore)}"

    fun displayName(isEnglish: Boolean): String = if (isEnglish) displayNameEn else displayNameUk
    fun summary(isEnglish: Boolean): String = if (isEnglish) summaryEn else summaryUk
    fun hardwareHint(isEnglish: Boolean): String = if (isEnglish) hardwareHintEn else hardwareHintUk
}

object TtsModelCatalog {

    val HOLOS_INT8 = TtsModelSpec(
        id = TtsModelId.HOLOS_INT8,
        displayNameEn = "HolosTTS INT8 (Fast)",
        displayNameUk = "HolosTTS INT8 (Швидка)",
        qualityStars = "★★★★☆",
        qualityScore = 4.2f,
        approxSizeMb = 320,
        sampleRateHz = 24_000,
        dirName = "onnx-int8",
        summaryEn = "Lightweight quantized Ukrainian neural TTS · 27 Ukrainian voices · 24 kHz · custom voice cloning",
        summaryUk = "Легка квантована українська нейромережа · 27 голосів · 24 кГц · клонування голосу",
        hardwareHintEn = "Best for battery life & entry/older CPUs",
        hardwareHintUk = "Найкраще для автономності та слабших процесорів",
        supportsCustomVoices = true,
        supportsEnglishPronunciation = false,
    )

    val HOLOS_FP32 = TtsModelSpec(
        id = TtsModelId.HOLOS_FP32,
        displayNameEn = "HolosTTS FP32 (Studio)",
        displayNameUk = "HolosTTS FP32 (Студійна)",
        qualityStars = "★★★★★",
        qualityScore = 4.8f,
        approxSizeMb = 760,
        sampleRateHz = 24_000,
        dirName = "onnx-fp32",
        summaryEn = "Full-precision Ukrainian neural TTS · cleanest intonation & diction · 27 voices · custom voice cloning",
        summaryUk = "Повноточна українська нейромережа · найчистіша інтонація та дикція · 27 голосів",
        hardwareHintEn = "Best for flagship CPUs / 6+ GB RAM",
        hardwareHintUk = "Найкраще для потужних процесорів та 6+ ГБ ОЗП",
        supportsCustomVoices = true,
        supportsEnglishPronunciation = false,
    )

    val SUPERTONIC_3 = TtsModelSpec(
        id = TtsModelId.SUPERTONIC_3,
        displayNameEn = "Supertonic 3 (44.1 kHz)",
        displayNameUk = "Supertonic 3 (44,1 кГц)",
        qualityStars = "★★★★★",
        qualityScore = 4.7f,
        approxSizeMb = 398,
        sampleRateHz = 44_100,
        dirName = "supertonic-3",
        summaryEn = "Ultra-crisp 44.1 kHz multilingual flow-matching TTS · 10 expressive voices · experimental English words mode",
        summaryUk = "Кришталева 44,1 кГц мультимовна flow-matching модель · 10 виразних голосів · експериментальна англійська вимова",
        hardwareHintEn = "Great on mid-range & flagship 64-bit phones",
        hardwareHintUk = "Чудово працює на сучасних 64-бітних смартфонах",
        supportsCustomVoices = false,
        supportsEnglishPronunciation = true,
    )

    val all: List<TtsModelSpec> = listOf(HOLOS_FP32, SUPERTONIC_3, HOLOS_INT8)

    fun byId(id: TtsModelId): TtsModelSpec = when (id) {
        TtsModelId.HOLOS_INT8 -> HOLOS_INT8
        TtsModelId.HOLOS_FP32 -> HOLOS_FP32
        TtsModelId.SUPERTONIC_3 -> SUPERTONIC_3
    }
}
