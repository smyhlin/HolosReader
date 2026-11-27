package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveVoiceRouterTest {

    @Test
    fun standaloneQuotedSpeech_isRoutedToDialogue() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("«Непогано, що ти зміг добігти аж сюди, Принце Чхон».")
        assertEquals(1, segs.size)
        assertEquals(VoiceRole.DIALOGUE, segs[0].role)
        assertEquals("Непогано, що ти зміг добігти аж сюди, Принце Чхон.", segs[0].text)
    }

    @Test
    fun quotedSpeechWithNestedQuotes_staysSingleDialogue() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("«О? Тобі не подобається мій тон, „принце“? Хочеш вилетіти ще до початку змагань?»")
        assertEquals(1, segs.size)
        assertEquals(VoiceRole.DIALOGUE, segs[0].role)
    }

    @Test
    fun quotedSpeechFollowedByAuthorAttribution_splitsDialogueAndNarrator() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("«Принце! Ви опритомніли! З Вами все гаразд?» — зрадів Чан і усміхнувся.")
        assertEquals(2, segs.size)
        assertEquals(VoiceRole.DIALOGUE, segs[0].role)
        assertEquals("Принце! Ви опритомніли! З Вами все гаразд?", segs[0].text)
        assertEquals(VoiceRole.NARRATOR, segs[1].role)
        assertEquals("зрадів Чан і усміхнувся.", segs[1].text)
    }

    @Test
    fun authorIntroWithColonAndQuotedSpeech_splitsNarratorAndDialogue() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("Тієї ж миті інший нападник закричав: «Обережно!»")
        assertEquals(2, segs.size)
        assertEquals(VoiceRole.NARRATOR, segs[0].role)
        assertEquals("Тієї ж миті інший нападник закричав:", segs[0].text)
        assertEquals(VoiceRole.DIALOGUE, segs[1].role)
        assertEquals("Обережно!", segs[1].text)
    }

    @Test
    fun mergedSentenceAndQuotedThought_splitsNarratorAndDialogue() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("Він втратив надто багато крові.«Невже це справді кінець?»")
        assertEquals(2, segs.size)
        assertEquals(VoiceRole.NARRATOR, segs[0].role)
        assertEquals(VoiceRole.DIALOGUE, segs[1].role)
        assertEquals("Невже це справді кінець?", segs[1].text)
    }

    @Test
    fun inlineQuotedTitlesAndTerms_remainNarrator() {
        val cases = listOf(
            "Він не зміг вимовити слово «вкрали» на адресу свого принца.",
            "Чоловік із вишитим ієрогліфом «Небо» на чорному шовковому вбранні піднявся на поміст.",
            "Справді, це був трактат «Шляхи крові» із приватної полиці.",
        )
        for (line in cases) {
            val segs = AdaptiveVoiceRouter.segmentParagraph(line)
            assertEquals("Expected single NARRATOR segment for: $line", 1, segs.size)
            assertEquals("Expected NARRATOR for: $line", VoiceRole.NARRATOR, segs[0].role)
        }
    }

    @Test
    fun systemBracketLines_remainSystem() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("[Так, Наномашина.]")
        assertEquals(1, segs.size)
        assertEquals(VoiceRole.SYSTEM, segs[0].role)
    }

    @Test
    fun classicDashDialogue_stillSplitsCorrectly() {
        val segs = AdaptiveVoiceRouter.segmentParagraph("— Тримайся поруч! — крикнув він. — Попереду засідка!")
        assertEquals(3, segs.size)
        assertEquals(VoiceRole.DIALOGUE, segs[0].role)
        assertEquals(VoiceRole.NARRATOR, segs[1].role)
        assertEquals(VoiceRole.DIALOGUE, segs[2].role)
    }

    @Test
    fun defaultAdaptiveVoices_useDaryna78ForSystemAndLada3ForDialogue() {
        val voices = HolosVoiceCatalog.defaultVoicesFor(TtsModelId.HOLOS_INT8)

        val narrator = AdaptiveVoiceRouter.resolveVoice(
            role = VoiceRole.NARRATOR,
            selectedVoice = "Speaker_11",
            adaptiveVoicesEnabled = true,
            systemVoice = null,
            dialogueVoice = null,
            availableVoices = voices,
        )
        val system = AdaptiveVoiceRouter.resolveVoice(
            role = VoiceRole.SYSTEM,
            selectedVoice = "Speaker_11",
            adaptiveVoicesEnabled = true,
            systemVoice = null,
            dialogueVoice = null,
            availableVoices = voices,
        )
        val dialogue = AdaptiveVoiceRouter.resolveVoice(
            role = VoiceRole.DIALOGUE,
            selectedVoice = "Speaker_11",
            adaptiveVoicesEnabled = true,
            systemVoice = null,
            dialogueVoice = null,
            availableVoices = voices,
        )

        assertEquals("Speaker_11", narrator)
        assertEquals("Speaker_78", system)
        assertEquals("Дарина (78)", HolosVoiceCatalog.displayName(system!!))
        assertEquals("Speaker_3", dialogue)
        assertEquals("Лада (3)", HolosVoiceCatalog.displayName(dialogue!!))
    }

    @Test
    fun localizedEnumLabels_haveCleanEnglishAndUkrainianVariants() {
        val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")
        for (role in VoiceRole.entries) {
            org.junit.Assert.assertFalse("VoiceRole.${role.name} EN label has Cyrillic", cyrillic.containsMatchIn(role.label(true)))
            org.junit.Assert.assertFalse("VoiceRole.${role.name} EN shortLabel has Cyrillic", cyrillic.containsMatchIn(role.shortLabel(true)))
        }
        for (gender in VoiceGender.entries) {
            org.junit.Assert.assertFalse("VoiceGender.${gender.name} EN label has Cyrillic", cyrillic.containsMatchIn(gender.label(true)))
        }
        for (id in HolosVoiceCatalog.defaultVoicesFor(TtsModelId.HOLOS_INT8)) {
            val info = HolosVoiceCatalog.info(id)
            org.junit.Assert.assertFalse("VoiceInfo($id) EN timbre has Cyrillic: ${info.timbre(true)}", cyrillic.containsMatchIn(info.timbre(true)))
        }
        for (status in com.holos.reader.epub.ReadingStatus.entries) {
            org.junit.Assert.assertFalse("ReadingStatus.${status.name} EN label has Cyrillic", cyrillic.containsMatchIn(status.label(true)))
        }
        for (theme in ReaderTheme.entries) {
            org.junit.Assert.assertFalse("ReaderTheme.${theme.name} EN label has Cyrillic", cyrillic.containsMatchIn(theme.label(true)))
        }
    }
}
