package com.holos.reader.tts

enum class VoiceGender(val labelUk: String) {
    FEMALE("Жіночий"),
    MALE("Чоловічий"),
    UNKNOWN("Офлайн"),
}

data class VoiceInfo(
    val rawId: String,
    val ukrainianName: String,
    val numberTag: String?,
    val gender: VoiceGender,
    val timbreUk: String,
    val pitchHz: Float,
) {
    val displayName: String
        get() = if (numberTag != null) "$ukrainianName ($numberTag)" else ukrainianName
}

/**
 * Verified catalog of HolosTTS preset voices ordered by acoustically measured vocal depth (F0),
 * paired with strong, authentic Ukrainian names matching each voice's timbre.
 */
object HolosVoiceCatalog {

    private val entries: Map<String, VoiceInfo> = listOf(
        // Male voices (16) — ordered from deepest bass to brightest tenor
        VoiceInfo("Speaker_36", "Святогор", "36", VoiceGender.MALE, "Глибокий бас", 95.8f),
        VoiceInfo("Speaker_11", "Святослав", "11", VoiceGender.MALE, "Бас-баритон", 101.9f),
        VoiceInfo("Speaker_56", "Всеволод", "56", VoiceGender.MALE, "Густий баритон", 110.3f),
        VoiceInfo("Speaker_24", "Борислав", "24", VoiceGender.MALE, "Глибокий баритон", 111.1f),
        VoiceInfo("Speaker_0", "Яромир", "0", VoiceGender.MALE, "Впевнений баритон", 115.9f),
        VoiceInfo("Speaker_34", "Любомир", "34", VoiceGender.MALE, "Оксамитовий баритон", 115.9f),
        VoiceInfo("Speaker_84", "Богдан", "84", VoiceGender.MALE, "Баритон", 119.4f),
        VoiceInfo("Speaker_53", "Тарас", "53", VoiceGender.MALE, "Баритон", 120.3f),
        VoiceInfo("Speaker_1", "Ростислав", "1", VoiceGender.MALE, "Баритон", 121.2f),
        VoiceInfo("Speaker_16", "Володимир", "16", VoiceGender.MALE, "Баритон", 125.0f),
        VoiceInfo("Speaker_69", "Ярослав", "69", VoiceGender.MALE, "Виразний баритон", 126.0f),
        VoiceInfo("Speaker_42", "Остап", "42", VoiceGender.MALE, "Тенор-баритон", 141.6f),
        VoiceInfo("Speaker_18", "Назар", "18", VoiceGender.MALE, "Тенор-баритон", 141.6f),
        VoiceInfo("Speaker_21", "Олесь", "21", VoiceGender.MALE, "Світлий тенор", 145.5f),
        VoiceInfo("Speaker_29", "Данило", "29", VoiceGender.MALE, "Тенор", 150.9f),
        VoiceInfo("Speaker_35", "Левко", "35", VoiceGender.MALE, "Дзвінкий тенор", 152.4f),

        // Female voices (11) — ordered from deepest alto to brightest soprano
        VoiceInfo("Speaker_30", "Роксолана", "30", VoiceGender.FEMALE, "Глибокий альт", 188.2f),
        VoiceInfo("Speaker_43", "Ярослава", "43", VoiceGender.FEMALE, "Оксамитове мецо", 200.0f),
        VoiceInfo("Speaker_67", "Богдана", "67", VoiceGender.FEMALE, "Глибоке мецо", 207.8f),
        VoiceInfo("Speaker_12", "Соломія", "12", VoiceGender.FEMALE, "Мецо-сопрано", 210.5f),
        VoiceInfo("Speaker_17", "Мирослава", "17", VoiceGender.FEMALE, "Тепле мецо", 210.5f),
        VoiceInfo("Speaker_26", "Зоряна", "26", VoiceGender.FEMALE, "Чисте сопрано", 219.2f),
        VoiceInfo("Speaker_78", "Дарина", "78", VoiceGender.FEMALE, "М’яке сопрано", 219.2f),
        VoiceInfo("Speaker_3", "Лада", "3", VoiceGender.FEMALE, "Ніжне сопрано", 222.2f),
        VoiceInfo("Speaker_37", "Квітослава", "37", VoiceGender.FEMALE, "Світле сопрано", 225.4f),
        VoiceInfo("Speaker_59", "Орися", "59", VoiceGender.FEMALE, "Дзвінке сопрано", 225.4f),
        VoiceInfo("Гаська Шиян", "Гаська Шиян", null, VoiceGender.FEMALE, "Дзвінке сопрано", 228.6f),
    ).associateBy { it.rawId }

    fun info(rawId: String): VoiceInfo {
        entries[rawId]?.let { return it }
        if (rawId.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)) {
            return VoiceInfo(
                rawId = rawId,
                ukrainianName = rawId.removePrefix(CustomVoiceLoader.CUSTOM_PREFIX),
                numberTag = null,
                gender = VoiceGender.UNKNOWN,
                timbreUk = "Клонований",
                pitchHz = 0f,
            )
        }
        val num = rawId.removePrefix("Speaker_").takeIf { rawId.startsWith("Speaker_") && it.all(Char::isDigit) }
        return VoiceInfo(
            rawId = rawId,
            ukrainianName = rawId,
            numberTag = num,
            gender = VoiceGender.UNKNOWN,
            timbreUk = "Офлайн",
            pitchHz = 999f,
        )
    }

    fun displayName(rawId: String?): String {
        if (rawId.isNullOrBlank()) return "Голос"
        return info(rawId).displayName
    }

    /**
     * Filters by gender and sorts from deepest voice to highest voice (custom voices pinned first).
     */
    fun sortAndFilter(voices: List<String>, filter: String): List<String> {
        val filtered = when (filter) {
            "FEMALE" -> voices.filter { info(it).gender == VoiceGender.FEMALE }
            "MALE" -> voices.filter { info(it).gender == VoiceGender.MALE }
            else -> voices
        }
        return filtered.sortedWith(
            compareBy<String>(
                { if (it.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)) 0 else 1 },
                { info(it).pitchHz },
                { info(it).ukrainianName },
            )
        )
    }
}
