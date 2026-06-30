package com.klicmobile.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.klicmobile.app.calling.CallInvite
import com.klicmobile.app.calling.CallNotifications
import com.klicmobile.app.calling.CallReliability
import com.klicmobile.app.calling.CallRinger
import com.klicmobile.app.calling.CallSignalingService
import com.klicmobile.app.calling.IncomingCallActivity
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.feature.auth.AuthScreen
import com.klicmobile.app.feature.auth.WelcomeScreen
import com.klicmobile.app.feature.call.CallDialScreen
import com.klicmobile.app.feature.call.CallScreen
import com.klicmobile.app.feature.call.LocalPipController
import com.klicmobile.app.feature.call.PipController
import com.klicmobile.app.feature.chat.ChatScreen
import com.klicmobile.app.feature.conversations.ConversationsScreen
import com.klicmobile.app.feature.friends.FriendsScreen
import com.klicmobile.app.feature.profile.ProfileScreen
import com.klicmobile.app.feature.settings.EditProfileScreen
import com.klicmobile.app.feature.settings.SettingsScreen
import com.klicmobile.app.feature.update.ForceUpdateScreen
import com.klicmobile.app.update.AppUpdater
import com.klicmobile.app.ui.theme.KlicIcons
import com.klicmobile.app.ui.theme.KlicTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class Tab(
    val route: String,
    val label: String,
    val iconRes: Int,
    val boldIconRes: Int,
)

private val tabs = listOf(
    Tab("home",     "Chats",    KlicIcons.messageChat,    KlicIcons.messageChatBold),
    Tab("friends",  "Friends",  KlicIcons.user,           KlicIcons.userBold),
    Tab("call",     "Call",     KlicIcons.phone,          KlicIcons.phoneBold),
    Tab("settings", "Settings", KlicIcons.settings,       KlicIcons.settings),
)

private val tabRoutes = tabs.map { it.route }.toSet()

class MainActivity : ComponentActivity() {
    private val pendingCall = MutableStateFlow<CallInvite?>(null)
    private val isInPipMode = mutableStateOf(false)
    private val pipSupported: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.CAMERA)
                // BLUETOOTH_CONNECT lets call audio route to Bluetooth headsets/car (Android 12+).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }.toTypedArray()
        )
        handleIntent(intent)
        val container = (application as KlicApplication).container

        setContent {
            val vm: KlicViewModel = viewModel(factory = factory(container))
            val isAuthed by vm.isAuthenticated.collectAsState()
            val themeMode by vm.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                "light"  -> false
                "dark"   -> true
                else     -> systemDark
            }
            val context = LocalContext.current
            LaunchedEffect(isAuthed) {
                if (isAuthed) CallSignalingService.start(context) else CallSignalingService.stop(context)
            }
            var showWelcome by remember { mutableStateOf(true) }
            var showReliabilityDialog by remember { mutableStateOf(false) }
            // Once after sign-in, nudge the user to allow reliable background calls.
            LaunchedEffect(isAuthed) {
                if (isAuthed && !container.reliabilityPrompted &&
                    CallReliability.needsAttention(this@MainActivity)
                ) {
                    showReliabilityDialog = true
                }
            }
            CompositionLocalProvider(
                LocalPipController provides PipController(
                    supported = pipSupported,
                    isInPipMode = isInPipMode.value,
                    enter = ::enterPipMode,
                ),
            ) {
            KlicTheme(isDark = isDark) {
                // Gate the whole app behind a mandatory update when a newer release exists.
                // Offline or already on the latest version → proceeds normally.
                var requiredUpdate by remember { mutableStateOf<AppUpdater.Release?>(null) }
                LaunchedEffect(Unit) {
                    AppUpdater.fetchLatest()?.let { latest ->
                        if (AppUpdater.isNewerThanInstalled(latest.versionName)) requiredUpdate = latest
                    }
                }
                val update = requiredUpdate
                if (update != null) {
                    ForceUpdateScreen(update)
                    return@KlicTheme
                }
                when {
                    isAuthed       -> Home(vm)
                    showWelcome    -> WelcomeScreen { showWelcome = false }
                    else           -> AuthScreen(vm)
                }
                if (showReliabilityDialog) {
                    ReliabilityDialog(
                        onAllow = {
                            if (CallReliability.isBatteryOptimized(this@MainActivity)) {
                                CallReliability.requestDisableBatteryOptimization(this@MainActivity)
                            }
                            if (!CallReliability.canUseFullScreenIntent(this@MainActivity)) {
                                CallReliability.requestFullScreenIntent(this@MainActivity)
                            }
                            container.reliabilityPrompted = true
                            showReliabilityDialog = false
                        },
                        onDismiss = {
                            container.reliabilityPrompted = true
                            showReliabilityDialog = false
                        },
                    )
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == IncomingCallActivity.ACTION_ACCEPT_CALL) {
            val invite = CallInvite.fromIntent(intent)
            pendingCall.value = invite
            // Answering from the notification's Answer button lands here without going through
            // IncomingCallActivity, so tear down the incoming surfaces ourselves: stop the ring,
            // remove the incoming notification, and dismiss the full-screen Activity if it's up.
            CallRinger.stop()
            CallNotifications.cancelIncomingCall(this)
            invite?.callId?.let { id ->
                sendBroadcast(
                    Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(packageName)
                        putExtra("callId", id)
                    }
                )
            }
        }
    }

    // Picture-in-Picture: "compact" the call so the user can keep using Klic during it.
    private fun enterPipMode() {
        if (!pipSupported) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-compact an active video call when the user navigates away (WhatsApp-style).
        val cm = (application as KlicApplication).container.callManager
        if (pipSupported && cm.isConnected.value && cm.cameraEnabled.value) enterPipMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    @Composable
    private fun Home(vm: KlicViewModel) {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showBar = route in tabRoutes

        // Navigate to the call UI when a call actually becomes active — not on the button tap.
        // startCall/acceptIncomingCall are async, so navigating eagerly landed on an empty
        // active_call that popped straight back (the "double-tap to open the call" bug).
        val activeCall by vm.activeCall.collectAsState()
        LaunchedEffect(activeCall?.callId) {
            if (activeCall?.callId != null) {
                navController.navigate("active_call") { launchSingleTop = true }
            }
        }

        val incoming by pendingCall.collectAsState()
        LaunchedEffect(incoming) {
            incoming?.let { invite ->
                vm.acceptIncomingCall(invite.callId, invite.fromName)
                pendingCall.value = null
            }
        }

        Scaffold(
            bottomBar = {
                if (showBar) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = route == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (selected) tab.boldIconRes else tab.iconRes),
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(28.dp),
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController,
                startDestination = "home",
                modifier = Modifier.padding(padding),
            ) {
                composable("home") {
                    ConversationsScreen(vm) { convo -> navController.navigate("chat/${convo.id}") }
                }
                composable("friends") {
                    FriendsScreen(
                        vm,
                        onOpenProfile = { conversationId -> navController.navigate("profile/$conversationId") },
                        onOpenChat = { convo -> navController.navigate("chat/${convo.id}") },
                    )
                }
                composable("call") {
                    CallDialScreen(vm)
                }
                composable("settings") {
                    SettingsScreen(vm, onEditProfile = { navController.navigate("edit_profile") })
                }
                composable("edit_profile") {
                    EditProfileScreen(vm) { navController.popBackStack() }
                }
                composable("chat/{conversationId}") { entry ->
                    val id = entry.arguments?.getString("conversationId").orEmpty()
                    vm.conversations.value.firstOrNull { it.id == id }?.let { convo ->
                        ChatScreen(
                            vm = vm,
                            conversation = convo,
                            onBack = { navController.popBackStack() },
                            onCall = {}, // navigation is reactive on activeCall (see Home)
                            onOpenProfile = {
                                if (convo.type == "DIRECT") navController.navigate("profile/${convo.id}")
                            },
                        )
                    }
                }
                composable("profile/{conversationId}") { entry ->
                    val id = entry.arguments?.getString("conversationId").orEmpty()
                    ProfileScreen(
                        vm = vm,
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                        onCall = {}, // navigation is reactive on activeCall (see Home)
                        onMessage = {
                            navController.navigate("chat/$id") { popUpTo("home") }
                        },
                    )
                }
                composable("active_call") {
                    val call by vm.activeCall.collectAsState()
                    val peer by vm.callPeerName.collectAsState()
                    if (call == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        CallScreen(vm, call!!, peerName = peer) { navController.popBackStack() }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReliabilityDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Reliable calls") },
            text = {
                Text(
                    "To ring on time and keep calls from dropping in the background, allow Klic " +
                        "to run without battery restrictions. You can change this later in Settings.",
                )
            },
            confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        )
    }

    private fun factory(container: AppContainer) = object : Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KlicViewModel(
                container.repository,
                container.tokenStore,
                container.socket,
                container.callManager,
                container,
            ) as T
    }
}
