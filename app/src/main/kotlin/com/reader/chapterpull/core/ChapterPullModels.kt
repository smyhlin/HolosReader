package com.reader.chapterpull.core

/**
 * Visual animation style for the bidirectional chapter pull-to-navigate indicator.
 */
enum class ChapterPullStyle {
    /**
     * Minimalist circular progress ring that fills as you pull and snaps into a
     * spring-popped filled accent circle with an expanding halo upon crossing the threshold.
     * (Inspired by ChatGPT Android "Pull up for New chat".)
     */
    PROGRESS_RING_POP,

    /**
     * Elastic vertical capsule with a directional arrow and chapter badge above/below
     * a frosted status pill. Crossing the threshold detaches and pops the badge with
     * a ripple wave, reveals the target chapter title, and morphs the pill prompt to
     * "Release to ...".
     * (Inspired by Telegram Android "Pull up to go to the next unread channel".)
     */
    ELASTIC_CAPSULE_BADGE,
}

/**
 * Overscroll pull direction mapped to chapter navigation.
 */
enum class PullDirection {
    /** User is at the top of the current chapter and pulls DOWN to go to the previous chapter. */
    TOP,

    /** User is at the bottom of the current chapter and pulls UP to go to the next chapter. */
    BOTTOM,
}

/**
 * Metadata describing the adjacent chapter target shown during the pull gesture.
 */
data class ChapterTarget(
    /** Short badge text displayed inside the circular badge (e.g., "14", "Ch.14", "I"). */
    val badgeLabel: String,
    /** Full or truncated chapter title shown when armed (e.g., "Chapter 14: The Shadow Gate"). */
    val title: String,
    /** Optional small superscript tag (e.g., "NEW", "1", or percentage) for the elastic badge style. */
    val superscriptTag: String? = null,
)

/**
 * Customizable copy for both pull directions and armed/unarmed states.
 * Supports full localization (e.g., Ukrainian, English, etc.).
 */
data class ChapterPullStrings(
    val pullDownForPrevious: String = "Pull down for previous chapter",
    val releaseForPrevious: String = "Release for previous chapter",
    val pullUpForNext: String = "Pull up for next chapter",
    val releaseForNext: String = "Release for next chapter",
    val shortPreviousLabel: String = "Previous chapter",
    val shortNextLabel: String = "Next chapter",
    val startOfBookLabel: String = "Beginning of book",
    val endOfBookLabel: String = "End of book",
) {
    fun promptFor(direction: PullDirection, isArmed: Boolean, hasTarget: Boolean): String {
        if (!hasTarget) {
            return if (direction == PullDirection.TOP) startOfBookLabel else endOfBookLabel
        }
        return when (direction) {
            PullDirection.TOP -> if (isArmed) releaseForPrevious else pullDownForPrevious
            PullDirection.BOTTOM -> if (isArmed) releaseForNext else pullUpForNext
        }
    }

    fun shortLabelFor(direction: PullDirection, hasTarget: Boolean): String {
        if (!hasTarget) {
            return if (direction == PullDirection.TOP) startOfBookLabel else endOfBookLabel
        }
        return if (direction == PullDirection.TOP) shortPreviousLabel else shortNextLabel
    }

    companion object {
        /** Built-in Ukrainian localization preset. */
        val Ukrainian = ChapterPullStrings(
            pullDownForPrevious = "Потягніть вниз до попереднього розділу",
            releaseForPrevious = "Відпустіть для переходу до попереднього розділу",
            pullUpForNext = "Потягніть вгору до наступного розділу",
            releaseForNext = "Відпустіть для переходу до наступного розділу",
            shortPreviousLabel = "Попередній розділ",
            shortNextLabel = "Наступний розділ",
            startOfBookLabel = "Початок книги",
            endOfBookLabel = "Кінець книги",
        )
    }
}

/**
 * Color palette for both indicator styles (stored as ARGB Longs so it is shared cleanly
 * between Jetpack Compose and Android View layers).
 */
data class ChapterPullPalette(
    /** Primary accent fill when armed (e.g., vibrant blue or reader theme accent). */
    val accentArgb: Long = 0xFF3B82F6L,
    /** Secondary accent for badge gradients / superscript pill in Elastic Capsule style. */
    val badgeAccentArgb: Long = 0xFF8B5CF6L,
    /** Foreground icon/text color on top of [accentArgb]. */
    val onAccentArgb: Long = 0xFFFFFFFFL,
    /** Unarmed circular ring stroke / subtle icon tint. */
    val ringTrackArgb: Long = 0x44FFFFFFL,
    /** Active ring sweep color before arming. */
    val ringProgressArgb: Long = 0xFF9CA3AFL,
    /** Elastic capsule and bottom/top pill background. */
    val surfaceContainerArgb: Long = 0xCC1E2430L,
    /** Pill border / subtle divider stroke. */
    val outlineArgb: Long = 0x26FFFFFFL,
    /** Primary text color for chapter titles and armed prompts. */
    val textPrimaryArgb: Long = 0xFFF3F4F6L,
    /** Secondary muted text color for unarmed hints. */
    val textSecondaryArgb: Long = 0xFF9CA3AFL,
) {
    companion object {
        /** Dark OLED / Night reading theme (matches both reference videos). */
        val Dark = ChapterPullPalette()

        /** Crisp daytime / paper white reading theme. */
        val Light = ChapterPullPalette(
            accentArgb = 0xFF2563EBL,
            badgeAccentArgb = 0xFF7C3AEDL,
            onAccentArgb = 0xFFFFFFFFL,
            ringTrackArgb = 0x26000000L,
            ringProgressArgb = 0xFF4B5563L,
            surfaceContainerArgb = 0xE6F3F4F6L,
            outlineArgb = 0x1F000000L,
            textPrimaryArgb = 0xFF111827L,
            textSecondaryArgb = 0xFF6B7280L,
        )

        /** Warm sepia / parchment EPUB reading theme. */
        val Sepia = ChapterPullPalette(
            accentArgb = 0xFFB45309L,
            badgeAccentArgb = 0xFF92400EL,
            onAccentArgb = 0xFFFFFBEBL,
            ringTrackArgb = 0x265C4033L,
            ringProgressArgb = 0xFF785A46L,
            surfaceContainerArgb = 0xE6EFE3D0L,
            outlineArgb = 0x295C4033L,
            textPrimaryArgb = 0xFF3E2723L,
            textSecondaryArgb = 0xFF795548L,
        )
    }
}

/**
 * Tuning parameters for pull distance, resistance curve, hysteresis, and haptic feedback.
 */
data class ChapterPullConfig(
    /** Visual drag distance (in dp) required to arm the chapter switch. */
    val triggerThresholdDp: Float = 84f,
    /** Maximum visual overscroll offset (in dp) at infinite pull. */
    val maxVisualOffsetDp: Float = 156f,
    /** Multiplier converting raw touch drag into damped visual offset before clamping. */
    val dragResistanceFactor: Float = 0.52f,
    /** Hysteresis ratio below 1.0 required to disarm once armed (prevents jittery haptics). */
    val disarmHysteresisRatio: Float = 0.86f,
    /** Whether to trigger tactile haptic ticks when arming, disarming, and releasing. */
    val enableHaptics: Boolean = true,
    /** Whether the content itself rubber-bands with the pull (true = matches both reference videos). */
    val translateContent: Boolean = true,
    /** Minimum raw drag (in dp) before the indicator starts fading in (prevents accidental flashes). */
    val activationSlopDp: Float = 8f,
)
