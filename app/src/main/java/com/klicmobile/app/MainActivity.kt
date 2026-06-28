package com.klicmobile.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.klicmobile.app.calling.CallSignalingService
import com.klicmobile.app.calling.IncomingCallActivity
import com.klicmobile.app.feature.KlicViewModel
import com.klicmobile.app.feature.auth.AuthScreen
import com.klicmobile.app.feature.auth.WelcomeScreen
import com.klicmobile.app.feature.call.CallDialScreen
import com.klicmobile.app.feature.call.CallScreen
import com.klicmobile.app.feature.chat.ChatScreen
import com.klicmobile.app.feature.conversations.ConversationsScreen
import com.klicmobile.app.feature.friends.FriendsScreen
import com.klicmobile.app.feature.settings.SettingsScreen
import com.klicmobile.app.ui.theme.KlicIcons
import com.klicmobile.app.ui.theme.KlicTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class Tab(
    val route: String,
    val label: String,
    val iconRes: Int,
)

private val tabs = listOf(
    Tab("home",     "Chats",    KlicIcons.message),
    Tab("friends",  "Friends",  KlicIcons.user),
    Tab("call",     "Call",     KlicIcons.phone),
    Tab("settings", "Settings", KlicIcons.settings),
)

private val tabRoutes = tabs.map { it.route }.toSet()

class MainActivity : ComponentActivity() {
    private val pendingCall = MutableStateFlow<CallInvite?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
            )
        )
        handleIntent(intent)
        val container = (application as KlicApplication).container

        setContent {
            val vm: KlicViewModel = viewModel(factory = factory(container))
            val isAuthed by vm.isAuthenticated.collectAsState()
            val isDark by vm.isDark.collectAsState()
            val context = LocalContext.current
            LaunchedEffect(isAuthed) {
                if (isAuthed) CallSignalingService.start(context) else CallSignalingService.stop(context)
            }
            var showWelcome by remember { mutableStateOf(true) }
            KlicTheme(isDark = isDark) {
                when {
                    isAuthed       -> Home(vm)
                    showWelcome    -> WelcomeScreen { showWelcome = false }
                    else           -> AuthScreen(vm)
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
            pendingCall.value = CallInvite.fromIntent(intent)
        }
    }

    @Composable
    private fun Home(vm: KlicViewModel) {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showBar = route in tabRoutes

        val incoming by pendingCall.collectAsState()
        LaunchedEffect(incoming) {
            incoming?.let { invite ->
                vm.acceptIncomingCall(invite.callId, invite.fromName)
                navController.navigate("active_call")
                pendingCall.value = null
            }
        }

        Scaffold(
            bottomBar = {
                if (showBar) {
                    NavigationBar {
                        val current = backStack?.destination
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current?.hierarchy?.any { it.route == tab.route } == true,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(tab.iconRes),
                                        contentDescription = tab.label,
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
                    FriendsScreen(vm) { conversationId -> navController.navigate("chat/$conversationId") }
                }
                composable("call") {
                    CallDialScreen(vm) { navController.navigate("active_call") }
                }
                composable("settings") {
                    SettingsScreen(vm)
                }
                composable("chat/{conversationId}") { entry ->
                    val id = entry.arguments?.getString("conversationId").orEmpty()
                    vm.conversations.value.firstOrNull { it.id == id }?.let { convo ->
                        ChatScreen(
                            vm = vm,
                            conversation = convo,
                            onBack = { navController.popBackStack() },
                            onCall = { navController.navigate("active_call") },
                        )
                    }
                }
                composable("active_call") {
                    val call by vm.activeCall.collectAsState()
                    val peer by vm.callPeerName.collectAsState()
                    call?.let {
                        CallScreen(vm, it, peerName = peer) { navController.popBackStack() }
                    }
                }
            }
        }
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
