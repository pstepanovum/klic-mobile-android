package com.klic.mobile.app.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.klic.mobile.app.feature.KlicViewModel

/**
 * Auth flow entry point. Login and Sign Up are now separate pages (each with their own
 * circle-container backdrop) instead of one toggle-mode form; this just hosts the
 * horizontal push between them, mirroring the iOS AuthView's navigation stack.
 */
@Composable
fun AuthScreen(vm: KlicViewModel) {
    var showSignUp by rememberSaveable { mutableStateOf(false) }

    // Don't carry a stale error from a previous visit into a fresh auth flow.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.error.value = null }

    // Hardware back from Sign Up pops back to Login, like a navigation stack.
    BackHandler(enabled = showSignUp) {
        vm.error.value = null
        showSignUp = false
    }

    AnimatedContent(
        targetState = showSignUp,
        transitionSpec = {
            if (targetState) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        label = "authPages",
    ) { signUp ->
        if (signUp) {
            SignUpScreen(vm, onHaveAccount = {
                vm.error.value = null
                showSignUp = false
            })
        } else {
            LoginScreen(vm, onCreateAccount = {
                vm.error.value = null
                showSignUp = true
            })
        }
    }
}
