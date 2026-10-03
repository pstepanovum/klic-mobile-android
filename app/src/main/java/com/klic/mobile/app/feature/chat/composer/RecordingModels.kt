package com.klic.mobile.app.feature.chat.composer

import androidx.compose.ui.unit.dp

/** Which action the composer's hold-to-record button performs. */
enum class CaptureMode { AUDIO, VIDEO }

/** §16.2: lifecycle of one hold-to-record interaction. */
enum class RecordPhase { IDLE, HELD, LOCKED }

/** §16.2: what a drag update did to the in-flight recording. */
enum class RecordDragResult { CONTINUE, LOCKED, CANCELED }

// §16.2 gesture geometry, studied from the reference client (§15.0):
// slide-left cancel distance = min(35% of screen width, 140dp); releasing past 55%
// of it (progress < 0.45) also cancels; slide-up lock threshold = 57dp.
internal val LOCK_TRAVEL = 57.dp

internal const val CANCEL_WIDTH_FRACTION = 0.35f

internal val CANCEL_MAX_TRAVEL = 140.dp

internal const val RELEASE_CANCEL_PROGRESS = 0.45f

// MARK: - Mentions (§9.5)
