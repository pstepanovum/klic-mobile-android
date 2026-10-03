package com.klic.mobile.app.feature.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.klic.mobile.app.R

/** Elapsed call time since connect: mm:ss, growing to h:mm:ss once past the first hour. */
internal fun formatCallDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** §10.5: user-visible mapping of the internal call-status sentinels. */
@Composable
internal fun localizedCallStatus(status: String): String = when (status) {
    "Calling..." -> stringResource(R.string.call_status_calling)
    "Connecting..." -> stringResource(R.string.call_status_connecting)
    "Connected" -> stringResource(R.string.call_status_connected)
    "Reconnecting…" -> stringResource(R.string.call_status_reconnecting)
    "On Hold" -> stringResource(R.string.call_status_on_hold)
    "Busy" -> stringResource(R.string.call_status_busy)
    "No answer" -> stringResource(R.string.call_status_no_answer)
    "Call failed" -> stringResource(R.string.call_status_failed)
    "Ended" -> stringResource(R.string.call_status_ended)
    else -> status
}
