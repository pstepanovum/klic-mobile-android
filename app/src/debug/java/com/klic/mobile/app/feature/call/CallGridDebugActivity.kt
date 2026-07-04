package com.klic.mobile.app.feature.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.klic.mobile.app.R
import com.klic.mobile.app.ui.components.CircleControl
import com.klic.mobile.app.ui.theme.KlicTheme

/**
 * Debug-only harness for the group-call grid (§17.1): renders the REAL grid + tile
 * composables with a fake roster so the never-scrolls layout, "You" tile placement, and
 * active-speaker glow can be inspected without live peers.
 *
 *   adb shell am start -n com.klic.mobile.app/.feature.call.CallGridDebugActivity \
 *       --ei tiles 6 --ei speaking 2
 *
 * `tiles` = TOTAL tile count including the local tile (which is always last);
 * `speaking` = tile index to flag as the active speaker (-1 = none).
 * Tapping anywhere cycles the speaking highlight to the next tile.
 */
class CallGridDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tiles = intent.getIntExtra("tiles", 4).coerceIn(2, 16)
        val initialSpeaking = intent.getIntExtra("speaking", -1)
        setContent {
            KlicTheme {
                var speaking by remember { mutableIntStateOf(initialSpeaking) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(
                        Modifier.fillMaxSize().padding(vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Header stand-in at the real call screen's vertical slot.
                        Text(
                            "Group call ($tiles tiles)",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.call_status_connected),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                        NonScrollingCallGrid(
                            tileCount = tiles,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                        ) { index ->
                            val isLocal = index == tiles - 1
                            CallGridTile(
                                room = null,
                                videoTrack = null,
                                displayName = if (isLocal) stringResource(R.string.common_you) else "Member ${index + 1}",
                                avatarUrl = null,
                                micMuted = index % 2 == 1,
                                isSpeaking = index == speaking,
                                avatarName = if (isLocal) "Hero Account" else "Member ${index + 1}",
                            )
                        }
                        // Controls-bar stand-in occupying the same space as the real one.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircleControl(
                                painter = rememberVectorPainter(Icons.Filled.Mic),
                                contentDescription = "Cycle speaker",
                            ) { speaking = (speaking + 1).mod(tiles) }
                            CircleControl(
                                painter = rememberVectorPainter(Icons.Filled.CallEnd),
                                contentDescription = "Close",
                                fill = MaterialTheme.colorScheme.error,
                                tint = MaterialTheme.colorScheme.onError,
                                diameter = 72,
                            ) { finish() }
                            CircleControl(
                                painter = rememberVectorPainter(Icons.Filled.Videocam),
                                contentDescription = "Cycle speaker",
                            ) { speaking = (speaking + 1).mod(tiles) }
                        }
                    }
                }
            }
        }
    }
}
