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
private enum class AuthPage { LOGIN, SIGN_UP, FORGOT }

@Composable
fun AuthScreen(vm: KlicViewModel) {
    var page by rememberSaveable { mutableStateOf(AuthPage.LOGIN) }

    // Don't carry a stale error from a previous visit into a fresh auth flow.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.error.value = null }

    // Hardware back from Sign Up / Forgot pops back to Login, like a navigation stack.
    BackHandler(enabled = page != AuthPage.LOGIN) {
        vm.error.value = null
        page = AuthPage.LOGIN
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != AuthPage.LOGIN) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        label = "authPages",
    ) { current ->
        when (current) {
            AuthPage.SIGN_UP -> SignUpScreen(vm, onHaveAccount = {
                vm.error.value = null
                page = AuthPage.LOGIN
            })
            AuthPage.FORGOT -> ForgotPasswordScreen(vm, onBack = {
                vm.error.value = null
                page = AuthPage.LOGIN
            })
            AuthPage.LOGIN -> LoginScreen(
                vm,
                onCreateAccount = {
                    vm.error.value = null
                    page = AuthPage.SIGN_UP
                },
                onForgotPassword = {
                    vm.error.value = null
                    page = AuthPage.FORGOT
                },
            )
        }
    }
}
