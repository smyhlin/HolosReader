package com.holos.reader.tts

/**
 * Optional ONNX add-ons that enhance HolosReader's audio quality, Ukrainian stress
 * disambiguation, emotional pacing, and on-device voice cloning.
 */
enum class OnnxAddonId(val id: String) {
    FLASH_SR("flash_sr"),
    UK_EMOTION("uk_emotion"),
    CONTEXTUAL_STRESS("contextual_stress"),
    VOICE_ENCODER("voice_encoder"),
}

data class OnnxAddonSpec(
    val id: OnnxAddonId,
    val titleEn: String,
    val titleUk: String,
    val summaryEn: String,
    val summaryUk: String,
    val sizeLabelEn: String,
    val sizeLabelUk: String,
    val dirName: String,
    val directDownloadAvailable: Boolean,
    val requiredFiles: List<String>,
) {
    fun title(isEnglish: Boolean): String = if (isEnglish) titleEn else titleUk
    fun summary(isEnglish: Boolean): String = if (isEnglish) summaryEn else summaryUk
    fun sizeLabel(isEnglish: Boolean): String = if (isEnglish) sizeLabelEn else sizeLabelUk
}

object OnnxAddonCatalog {

    val FLASH_SR = OnnxAddonSpec(
        id = OnnxAddonId.FLASH_SR,
        titleEn = "FlashSR 48 kHz Upsampler",
        titleUk = "FlashSR 48 кГц апскейлер",
        summaryEn = "Neural super-resolution (YatharthS/FlashSR) lifting HolosTTS 24 kHz audio to crisp 48 kHz",
        summaryUk = "Нейронний апскейлінг (YatharthS/FlashSR): піднімає звук HolosTTS з 24 до 48 кГц",
        sizeLabelEn = "~0.5 MB",
        sizeLabelUk = "~0,5 МБ",
        dirName = "addon-flashsr",
        directDownloadAvailable = true,
        requiredFiles = listOf("model.onnx"),
    )

    val UK_EMOTION = OnnxAddonSpec(
        id = OnnxAddonId.UK_EMOTION,
        titleEn = "Ukrainian Emotion Pacing",
        titleUk = "Емоційна динаміка (ONNX)",
        summaryEn = "7-emotion Ukrainian classifier (speedbrain-emotion-uk-onnx) adjusting narration speed & pauses",
        summaryUk = "Класифікатор 7 емоцій українською: динамічно змінює темп і паузи у діалогах та боях",
        sizeLabelEn = "~580 MB",
        sizeLabelUk = "~580 МБ",
        dirName = "addon-uk-emotion",
        directDownloadAvailable = true,
        requiredFiles = listOf("model_quantized.onnx", "tokenizer.json"),
    )

    val CONTEXTUAL_STRESS = OnnxAddonSpec(
        id = OnnxAddonId.CONTEXTUAL_STRESS,
        titleEn = "Contextual Stress (Homographs)",
        titleUk = "Контекстні наголоси (омографи)",
        summaryEn = "Sentence-level homograph resolver (за́мок/замо́к, му́ка/мука́) via exported aloudreader ONNX",
        summaryUk = "Контекстне розрізнення омографів (за́мок/замо́к, му́ка/мука́) через експортований ONNX",
        sizeLabelEn = "~110 MB (import)",
        sizeLabelUk = "~110 МБ (імпорт)",
        dirName = "addon-contextual-stress",
        directDownloadAvailable = false,
        requiredFiles = listOf("uk_stress_contextual.onnx", "tokenizer.json"),
    )

    val VOICE_ENCODER = OnnxAddonSpec(
        id = OnnxAddonId.VOICE_ENCODER,
        titleEn = "On-Device Voice Cloner",
        titleUk = "Клонування голосу на телефоні",
        summaryEn = "Extract 256-dim HolosTTS speaker vectors directly from 5–15s WAV clips via voice_encoder.onnx",
        summaryUk = "Створення власного голосу HolosTTS прямо з 5–15 с WAV-запису через voice_encoder.onnx",
        sizeLabelEn = "~20 MB (import)",
        sizeLabelUk = "~20 МБ (імпорт)",
        dirName = "addon-voice-encoder",
        directDownloadAvailable = false,
        requiredFiles = listOf("voice_encoder.onnx"),
    )

    val all: List<OnnxAddonSpec> = listOf(FLASH_SR, UK_EMOTION, CONTEXTUAL_STRESS, VOICE_ENCODER)

    fun byId(id: OnnxAddonId): OnnxAddonSpec = when (id) {
        OnnxAddonId.FLASH_SR -> FLASH_SR
        OnnxAddonId.UK_EMOTION -> UK_EMOTION
        OnnxAddonId.CONTEXTUAL_STRESS -> CONTEXTUAL_STRESS
        OnnxAddonId.VOICE_ENCODER -> VOICE_ENCODER
    }
}
