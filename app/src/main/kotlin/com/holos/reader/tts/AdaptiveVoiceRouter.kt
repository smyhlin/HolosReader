package com.holos.reader.tts

enum class VoiceRole(val labelUk: String, val shortLabelUk: String, val previewTextUk: String) {
    NARRATOR(
        labelUk = "Оповідач",
        shortLabelUk = "Оповідач",
        previewTextUk = "Привіт! Я читатиму для тебе цю книгу українською.",
    ),
    SYSTEM(
        labelUk = "[СИСТЕМА]",
        shortLabelUk = "[Система]",
        previewTextUk = "Система активована. Навичка першого рівня успішно розблокована.",
    ),
    DIALOGUE(
        labelUk = "Діалоги (—)",
        shortLabelUk = "— Діалоги",
        previewTextUk = "Тримайся поруч, попереду на нас чекає справжнє випробування!",
    ),
}

data class VoiceSegment(
    val role: VoiceRole,
    val text: String,
)

/**
 * Context-aware adaptive narration router for Ukrainian light novels & LitRPG:
 * - `[СИСТЕМА]`, `[...]`, `【...】`, `《...》`, `『...』` and LitRPG status/skill lines → [VoiceRole.SYSTEM]
 * - Direct speech starting with long dash (`—` / `–`), with smart splitting of Ukrainian author
 *   attribution clauses (`— Репліка! — сказав він. — Продовження.`) → [VoiceRole.DIALOGUE] / [VoiceRole.NARRATOR]
 * - Standard prose → [VoiceRole.NARRATOR]
 */
object AdaptiveVoiceRouter {

    private val RE_INLINE_SYSTEM = Regex(
        """(\[[^\]]{2,}\]|【[^】]{2,}】|《[^》]{2,}》|『[^』]{2,}』)"""
    )

    private val RE_SYSTEM_KEYWORD_LINE = Regex(
        """^(?:Ранг(?:\s+родоводу)?|Статура|Сила|Спритність|Дух|Інтелект|Мудрість|Витривалість|Здоров['’]я|Мана|Опір|Захист|Атака|Навичка|Вміння|Здібність|Титул|Клас|Рівень|Досвід|Перезаряджання|Ефект|Опис|Стан|Статус|Квест|Завдання|Нагорода|Повідомлення|Система|Увага|HP|MP|XP|SP)\b(?:\s*(?:[:★☆+«]|\d|[IVXІ]{1,4}\b))""",
        RegexOption.IGNORE_CASE
    )

    private val RE_LEADING_DASH = Regex("""^[«"„]?\s*[—–]\s*""")

    // Split dialogue vs author attribution only after sentence/clause punctuation before ` — `
    private val RE_DIALOGUE_SPLIT = Regex("""(?<=[,.!?…»"])\s+[—–]\s+""")

    fun segmentParagraph(raw: String): List<VoiceSegment> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        // 1. Whole-block system message or LitRPG status sheet line
        if (isWholeBlockSystem(text)) {
            return listOf(VoiceSegment(VoiceRole.SYSTEM, text))
        }

        // 2. Check for inline bracketed system tags (`[...]`, `【...】`, etc.)
        val sysMatches = RE_INLINE_SYSTEM.findAll(text).filter { hasLetters(it.value) }.toList()
        if (sysMatches.isNotEmpty()) {
            val out = ArrayList<VoiceSegment>()
            var cursor = 0
            for (m in sysMatches) {
                if (m.range.first > cursor) {
                    val before = text.substring(cursor, m.range.first).trim()
                    if (before.isNotEmpty()) out.addAll(segmentProseOrDialogue(before))
                }
                val sysText = m.value.trim()
                if (sysText.isNotEmpty()) out.add(VoiceSegment(VoiceRole.SYSTEM, sysText))
                cursor = m.range.last + 1
            }
            if (cursor < text.length) {
                val tail = text.substring(cursor).trim()
                if (tail.isNotEmpty()) out.addAll(segmentProseOrDialogue(tail))
            }
            return mergeAdjacent(out)
        }

        // 3. Standard dialogue vs narrator segmentation
        return mergeAdjacent(segmentProseOrDialogue(text))
    }

    fun resolveVoice(
        role: VoiceRole,
        prefs: ReaderPreferences,
        availableVoices: List<String>,
    ): String? {
        fun valid(v: String?): String? = v?.takeIf {
            it.startsWith(CustomVoiceLoader.CUSTOM_PREFIX) || it in availableVoices
        }

        val narrator = valid(prefs.selectedVoice) ?: availableVoices.firstOrNull()
        if (!prefs.adaptiveVoicesEnabled || role == VoiceRole.NARRATOR) return narrator

        return when (role) {
            VoiceRole.NARRATOR -> narrator
            VoiceRole.SYSTEM -> valid(prefs.systemVoice) ?: pickContrastingDefault(
                narrator = narrator,
                availableVoices = availableVoices,
                preferredIfNarratorMale = listOf("Speaker_30", "Speaker_12", "Speaker_26"),
                preferredIfNarratorFemale = listOf("Speaker_36", "Speaker_11", "Speaker_0"),
            )
            VoiceRole.DIALOGUE -> valid(prefs.dialogueVoice) ?: pickContrastingDefault(
                narrator = narrator,
                availableVoices = availableVoices,
                preferredIfNarratorMale = listOf("Speaker_43", "Speaker_21", "Speaker_67"),
                preferredIfNarratorFemale = listOf("Speaker_0", "Speaker_84", "Speaker_53"),
            )
        }
    }

    private fun pickContrastingDefault(
        narrator: String?,
        availableVoices: List<String>,
        preferredIfNarratorMale: List<String>,
        preferredIfNarratorFemale: List<String>,
    ): String? {
        val narratorGender = narrator?.let { HolosVoiceCatalog.info(it).gender }
        val candidates = if (narratorGender == VoiceGender.FEMALE) {
            preferredIfNarratorFemale
        } else {
            preferredIfNarratorMale
        }
        return candidates.firstOrNull { it in availableVoices && it != narrator }
            ?: availableVoices.firstOrNull { it != narrator }
            ?: narrator
    }

    private val RE_SYSTEM_PREFIX_TAG = Regex(
        """^\s*(?:\[(?:СИСТЕМА|УВАГА|ПОВІДОМЛЕННЯ|СТАТУС|НАВИЧКА|КВЕСТ|ОТРИМАНО|РІВЕНЬ|ЕВОЛЮЦІЯ|СИСТЕМНЕ[^\]]*)\]|【(?:СИСТЕМА|УВАГА|ПОВІДОМЛЕННЯ|СТАТУС)[^】]*】)""",
        RegexOption.IGNORE_CASE
    )

    private fun isWholeBlockSystem(text: String): Boolean {
        val wrappedSystem = (text.startsWith("[") && text.endsWith("]")) ||
            (text.startsWith("【") && text.endsWith("】")) ||
            (text.startsWith("《") && text.endsWith("》")) ||
            (text.startsWith("『") && text.endsWith("』"))
        if (wrappedSystem && hasLetters(text)) return true
        if (RE_SYSTEM_PREFIX_TAG.containsMatchIn(text)) return true
        if (text.contains('★') || text.contains('☆')) return true
        if (RE_SYSTEM_KEYWORD_LINE.containsMatchIn(text)) return true
        return false
    }

    private fun segmentProseOrDialogue(text: String): List<VoiceSegment> {
        if (!RE_LEADING_DASH.containsMatchIn(text)) {
            return listOf(VoiceSegment(VoiceRole.NARRATOR, text))
        }

        val cleaned = text.replaceFirst(RE_LEADING_DASH, "").trim()
        if (cleaned.isEmpty()) return emptyList()

        val parts = RE_DIALOGUE_SPLIT.split(cleaned).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            return listOf(VoiceSegment(VoiceRole.DIALOGUE, cleaned))
        }

        // Ukrainian dialogue alternates: Dialogue -> Author attribution -> Dialogue -> ...
        val segments = ArrayList<VoiceSegment>(parts.size)
        var inDialogue = true
        for ((idx, part) in parts.withIndex()) {
            if (idx == 0) {
                segments.add(VoiceSegment(VoiceRole.DIALOGUE, part))
                continue
            }
            val firstLetter = part.firstOrNull { it.isLetter() }
            if (inDialogue && firstLetter != null && firstLetter.isLowerCase()) {
                // Lowercase after ` — ` indicates author's remark ("сказав він.")
                inDialogue = false
                segments.add(VoiceSegment(VoiceRole.NARRATOR, part))
            } else if (!inDialogue) {
                // Subsequent ` — ` after author remark returns to direct speech
                inDialogue = true
                segments.add(VoiceSegment(VoiceRole.DIALOGUE, part))
            } else {
                segments.add(VoiceSegment(VoiceRole.DIALOGUE, part))
            }
        }
        return segments
    }

    private fun mergeAdjacent(items: List<VoiceSegment>): List<VoiceSegment> {
        val out = ArrayList<VoiceSegment>(items.size)
        for (item in items) {
            if (!hasSpeakableContent(item.text)) continue
            val last = out.lastOrNull()
            if (last != null && last.role == item.role) {
                out[out.lastIndex] = VoiceSegment(last.role, "${last.text} ${item.text}")
            } else {
                out.add(item)
            }
        }
        return out
    }

    private fun hasLetters(s: String): Boolean = s.any { it.isLetter() }
    private fun hasSpeakableContent(s: String): Boolean = s.any { it.isLetterOrDigit() }
}
