package com.klicmobile.app.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.klicmobile.app.KlicApplication
import com.klicmobile.app.MainActivity
import com.klicmobile.app.ui.theme.KlicIcons
import com.klicmobile.app.ui.theme.KlicTheme
import kotlinx.coroutines.launch

class IncomingCallActivity : ComponentActivity() {

    companion object {
        const val ACTION_ACCEPT_CALL = "com.klicmobile.app.ACCEPT_CALL"
        const val ACTION_CALL_ENDED = "com.klicmobile.app.CALL_ENDED"
    }

    // The invite currently shown. Held as Compose state so a replacing invite delivered via
    // onNewIntent (this Activity is singleInstance) updates the UI instead of leaving it stale.
    private val invite = mutableStateOf<CallInvite?>(null)
    private val callId: String? get() = invite.value?.callId

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CALL_ENDED && intent.getStringExtra("callId") == callId) {
                CallRinger.stop()
                CallNotifications.cancelIncomingCall(this@IncomingCallActivity)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val parsed = CallInvite.fromIntent(intent)
        if (parsed == null) { finish(); return }
        invite.value = parsed
        // The ringer is started by the notification path (CallSignalingService / KlicMessagingService)
        // so the call rings whether or not this full-screen Activity launches.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, IntentFilter(ACTION_CALL_ENDED), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callEndedReceiver, IntentFilter(ACTION_CALL_ENDED))
        }

        setContent {
            KlicTheme {
                invite.value?.let { current ->
                    IncomingCallScreen(
                        callerName = current.fromName,
                        isVideo = current.kind == "VIDEO",
                        onAccept = { accept(current) },
                        onDecline = { decline(current) },
                    )
                }
            }
        }
    }

    // singleInstance: a different incoming call (e.g. a re-dial after the first was cancelled)
    // is delivered here rather than launching a second screen. Swap to it instead of showing stale.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CallInvite.fromIntent(intent)?.let { this.invite.value = it }
    }

    override fun onDestroy() {
        // Intentionally does NOT stop the ringer: if the user just dismisses this screen the call
        // is still incoming (notification still up), so the ringer keeps going until the call is
        // actually answered, declined, or ended — all of which stop it explicitly.
        runCatching { unregisterReceiver(callEndedReceiver) }
        super.onDestroy()
    }

    private fun accept(invite: CallInvite) {
        CallRinger.stop()
        CallNotifications.cancelIncomingCall(this)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_ACCEPT_CALL
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtras(invite.toBundle())
            }
        )
        finish()
    }

    private fun decline(invite: CallInvite) {
        CallRinger.stop()
        val container = (application as KlicApplication).container
        container.applicationScope.launch { container.repository.declineCall(invite.callId) }
        CallNotifications.cancelIncomingCall(this)
        finish()
    }
}

@Composable
private fun IncomingCallScreen(callerName: String, isVideo: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(120.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(KlicIcons.user), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(callerName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                if (isVideo) "Incoming video call" else "Incoming call",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onDecline,
                modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.error, CircleShape),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Decline", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(30.dp))
            }
            IconButton(
                onClick = onAccept,
                modifier = Modifier.size(72.dp).background(Color(0xFF2BD158), CircleShape),
            ) {
                Icon(Icons.Filled.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}
