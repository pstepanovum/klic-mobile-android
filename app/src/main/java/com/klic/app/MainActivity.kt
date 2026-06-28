package com.klic.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.klic.app.calling.CallInvite
import com.klic.app.calling.CallSignalingService
import com.klic.app.calling.IncomingCallActivity
import com.klic.app.feature.KlicViewModel
import com.klic.app.feature.auth.AuthScreen
import com.klic.app.feature.call.CallScreen
import com.klic.app.feature.chat.ChatScreen
import com.klic.app.feature.conversations.ConversationsScreen
import com.klic.app.feature.friends.FriendsScreen
import com.klic.app.ui.theme.KlicTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Chats", Icons.AutoMirrored.Filled.Chat),
    Tab("friends", "Friends", Icons.Default.People),
)

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
            KlicTheme {
                val vm: KlicViewModel = viewModel(factory = factory(container))
                val isAuthed by vm.isAuthenticated.collectAsState()
                val context = LocalContext.current
                LaunchedEffect(isAuthed) {
                    if (isAuthed) CallSignalingService.start(context) else CallSignalingService.stop(context)
                }
                if (!isAuthed) AuthScreen(vm) else Home(vm)
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
        val showBar = route == "home" || route == "friends"

        // An accepted incoming call → join and show the call screen.
        val incoming by pendingCall.collectAsState()
        LaunchedEffect(incoming) {
            incoming?.let { invite ->
                vm.acceptIncomingCall(invite.callId, invite.fromName)
                navController.navigate("call")
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
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") {
                    ConversationsScreen(vm) { convo -> navController.navigate("chat/${convo.id}") }
                }
                composable("friends") {
                    FriendsScreen(vm) { conversationId -> navController.navigate("chat/$conversationId") }
                }
                composable("chat/{conversationId}") { entry ->
                    val id = entry.arguments?.getString("conversationId").orEmpty()
                    vm.conversations.value.firstOrNull { it.id == id }?.let { convo ->
                        ChatScreen(
                            vm = vm,
                            conversation = convo,
                            onBack = { navController.popBackStack() },
                            onCall = { navController.navigate("call") },
                        )
                    }
                }
                composable("call") {
                    val call by vm.activeCall.collectAsState()
                    val peer by vm.callPeerName.collectAsState()
                    call?.let { CallScreen(vm, it, peerName = peer) { navController.popBackStack() } }
                }
            }
        }
    }

    private fun factory(container: AppContainer) = object : Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KlicViewModel(container.repository, container.tokenStore, container.socket, container.callManager) as T
    }
}
