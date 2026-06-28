package com.klicmobile.app

import android.app.Application
import com.klicmobile.app.calling.CallManager
import com.klicmobile.app.calling.CallNotifications
import com.klicmobile.app.data.KlicRepository
import com.klicmobile.app.data.Network
import com.klicmobile.app.data.TokenStore
import com.klicmobile.app.realtime.SocketService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/** Tiny manual DI container — swap for Hilt as the app grows. */
class KlicApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CallNotifications.createChannels(this)
    }
}

class AppContainer(app: Application) {
    val tokenStore = TokenStore(app)

    // Emitted when the server rejects our refresh token (a genuine sign-out).
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired

    val repository = KlicRepository(
        Network.create(tokenStore) { _sessionExpired.tryEmit(Unit) },
        tokenStore,
    )
    val socket = SocketService()
    val callManager = CallManager(app)

    /** Conversation id of the call the user is currently placing/in. The call service reads
     *  this to suppress a duplicate incoming-call screen for that same conversation (glare):
     *  the server already collapses simultaneous calls into one. Null when not in a call. */
    val activeCallConversationId = MutableStateFlow<String?>(null)

    private val prefs = app.getSharedPreferences("klic_prefs", android.content.Context.MODE_PRIVATE)
    var isDark: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) { prefs.edit().putBoolean("dark_theme", value).apply() }
}
