package com.klic.mobile.app

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.blur
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
import com.klic.mobile.app.calling.CallInvite
import com.klic.mobile.app.calling.CallNotifications
import com.klic.mobile.app.calling.CallReliability
import com.klic.mobile.app.calling.CallRinger
import com.klic.mobile.app.calling.CallSignalingService
import com.klic.mobile.app.calling.IncomingCallActivity
import com.klic.mobile.app.feature.KlicViewModel
import com.klic.mobile.app.feature.auth.AuthScreen
import com.klic.mobile.app.feature.auth.WelcomeScreen
import com.klic.mobile.app.feature.call.CallDialScreen
import com.klic.mobile.app.feature.call.CallScreen
import com.klic.mobile.app.feature.call.LocalPipController
import com.klic.mobile.app.feature.call.MinimizedCallOverlay
import com.klic.mobile.app.feature.call.PipController
import com.klic.mobile.app.feature.chat.ChatScreen
import com.klic.mobile.app.feature.chatinfo.GroupInfoScreen
import com.klic.mobile.app.feature.conversations.ConversationsScreen
import com.klic.mobile.app.feature.friends.FriendsScreen
import com.klic.mobile.app.feature.profile.ProfileScreen
import com.klic.mobile.app.feature.settings.EditProfileScreen
import com.klic.mobile.app.feature.settings.SettingsScreen
import com.klic.mobile.app.feature.update.ForceUpdateScreen
import com.klic.mobile.app.update.AppUpdater
import com.klic.mobile.app.ui.theme.KlicIcons
import com.klic.mobile.app.ui.theme.KlicTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.res.stringResource

private data class Tab(
    val route: String,
    val labelRes: Int,
    val iconRes: Int,
    val boldIconRes: Int,
)

private val tabs = listOf(
    Tab("home",     R.string.tab_chats,    KlicIcons.messageChat,    KlicIcons.messageChatBold),
    Tab("friends",  R.string.tab_friends,  KlicIcons.user,           KlicIcons.userBold),
    Tab("call",     R.string.tab_call,     KlicIcons.phone,          KlicIcons.phoneBold),
    Tab("settings", R.string.tab_settings, KlicIcons.settings,       KlicIcons.settings),
)

private val tabRoutes = tabs.map { it.route }.toSet()

class MainActivity : ComponentActivity() {
    private val pendingCall = MutableStateFlow<CallInvite?>(null)
    private val isInPipMode = mutableStateOf(false)
    private val pipSupported: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    // §7.4: PiP eligibility (video call with a live remote feed) + aspect ratio from the
    // remote track — mirrored out of compose state so onUserLeaveHint/params can read them.
    private var pipVideoActive = false
    private var pipAspect: Rational? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun attachBaseContext(newBase: android.content.Context) {
        // §10.5: apply the in-app language on pre-33 devices.
        super.attachBaseContext(com.klic.mobile.app.data.LocaleHelper.wrap(newBase))
    }

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
            // §7.4: keep the system's PiP params in sync — auto-enter (API 31+) only while a
            // call with live remote video is up, aspect ratio from the track when known.
            val activeCallForPip by vm.activeCall.collectAsState()
            val remoteVideoForPip by container.callManager.remoteVideoTrack.collectAsState()
            val remoteVideoDims by container.callManager.remoteVideoDimensions.collectAsState()
            LaunchedEffect(activeCallForPip, remoteVideoForPip, remoteVideoDims) {
                pipVideoActive = activeCallForPip != null && remoteVideoForPip != null
                pipAspect = remoteVideoDims?.let { (w, h) ->
                    if (w > 0 && h > 0) {
                        // Clamp into the platform's legal PiP range (~1:2.39 … 2.39:1).
                        val ratio = w.toFloat() / h
                        val clamped = ratio.coerceIn(0.42f, 2.38f)
                        if (clamped == ratio) Rational(w, h) else Rational((clamped * 1000).toInt(), 1000)
                    } else null
                }
                applyPipParams()
            }
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
                // §10.4/§11.3 app lock: overlay above ALL content while locked, with the
                // content behind FULLY blurred (privacy). The in-call UI (incoming answers
                // land in activeCall) bypasses the lock; the incoming ring itself lives in
                // IncomingCallActivity, outside this overlay.
                val appLocked by com.klic.mobile.app.data.AppLockStore.locked.collectAsState()
                val lockEnabled by com.klic.mobile.app.data.AppLockStore.enabled.collectAsState()
                val lockActive = isAuthed && lockEnabled && appLocked && activeCallForPip == null
                Box(
                    Modifier.fillMaxSize().then(
                        // Modifier.blur needs RenderEffect (API 31+); older devices get an
                        // opaque scrim from AppLockOverlay instead.
                        if (lockActive && android.os.Build.VERSION.SDK_INT >= 31) {
                            Modifier.blur(40.dp)
                        } else Modifier
                    ),
                ) {
                    when {
                        isAuthed       -> Home(vm)
                        showWelcome    -> WelcomeScreen { showWelcome = false }
                        else           -> AuthScreen(vm)
                    }
                }
                if (lockActive) {
                    com.klic.mobile.app.ui.components.AppLockOverlay()
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
        runCatching { enterPictureInPictureMode(pipParamsBuilder().build()) }
    }

    private fun pipParamsBuilder(): PictureInPictureParams.Builder {
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspect ?: Rational(9, 16))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipVideoActive)
        }
        return builder
    }

    private fun applyPipParams() {
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParamsBuilder().build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pre-S fallback: auto-compact when leaving during a call with live remote video.
        // On S+ the system auto-enters via setAutoEnterEnabled (see applyPipParams).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S && pipVideoActive) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    @Composable
    private fun Home(vm: KlicViewModel) {
        val navController = rememberNavController()
        // §10.1: errors were silently swallowed outside the auth screen — surface every
        // repository/UI failure pushed into vm.error as a visible toast.
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            vm.error.collect { message ->
                if (!message.isNullOrBlank()) {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    vm.error.value = null
                }
            }
        }
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
                vm.acceptIncomingCall(invite.callId, invite.displayLabel, isGroup = invite.isGroup)
                pendingCall.value = null
            }
        }

        Box(Modifier.fillMaxSize()) {
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
                                        contentDescription = stringResource(tab.labelRes),
                                        modifier = Modifier.size(28.dp),
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
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
                    // launchSingleTop everywhere below: double-taps must never stack a
                    // duplicate instance of the same page (§9.4).
                    ConversationsScreen(vm) { convo ->
                        navController.navigate("chat/${convo.id}") { launchSingleTop = true }
                    }
                }
                composable("friends") {
                    FriendsScreen(
                        vm,
                        onOpenProfile = { conversationId ->
                            navController.navigate("profile/$conversationId") { launchSingleTop = true }
                        },
                        onOpenChat = { convo ->
                            navController.navigate("chat/${convo.id}") { launchSingleTop = true }
                        },
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
                    // Reactive lookup: when the conversation drops out of the list
                    // (removed from the group, §9.3) the chat screen leaves itself.
                    val convos by vm.conversations.collectAsState()
                    val convo = convos.firstOrNull { it.id == id }
                    if (convo == null) {
                        LaunchedEffect(id) { navController.popBackStack() }
                    } else {
                        ChatScreen(
                            vm = vm,
                            conversation = convo,
                            onBack = { navController.popBackStack() },
                            onCall = {}, // navigation is reactive on activeCall (see Home)
                            onOpenProfile = {
                                val route = if (convo.type == "DIRECT") "profile/${convo.id}"
                                            else "group_info/${convo.id}"
                                navController.navigate(route) { launchSingleTop = true }
                            },
                        )
                    }
                }
                composable("group_info/{conversationId}") { entry ->
                    val id = entry.arguments?.getString("conversationId").orEmpty()
                    GroupInfoScreen(
                        vm = vm,
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                        onOpenChat = {
                            navController.navigate("chat/$id") {
                                popUpTo("home")
                                launchSingleTop = true
                            }
                        },
                    )
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
                        onOpenGroup = { groupId ->
                            navController.navigate("chat/$groupId") {
                                popUpTo("home")
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable("active_call") {
                    val call by vm.activeCall.collectAsState()
                    val peer by vm.callPeerName.collectAsState()
                    if (call == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        CallScreen(
                            vm, call!!, peerName = peer,
                            onMinimize = {
                                vm.minimizeCall()
                                navController.popBackStack()
                            },
                        ) { navController.popBackStack() }
                    }
                }
            }
        }
        // Floating minimized-call widget — above all navigation; tap restores the call screen.
        MinimizedCallOverlay(vm) {
            vm.restoreCall()
            navController.navigate("active_call") { launchSingleTop = true }
        }
        }
    }

    @Composable
    private fun ReliabilityDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_reliable_calls_title)) },
            text = { Text(stringResource(R.string.dialog_reliable_calls_body)) },
            confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.common_allow)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_not_now)) } },
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
