package com.klicmobile.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.klicmobile.app.calling.CallManager
import com.klicmobile.app.calling.CallNotifications
import com.klicmobile.app.data.KlicRepository
import com.klicmobile.app.data.Network
import com.klicmobile.app.data.TokenStore
import com.klicmobile.app.realtime.SocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/** Tiny manual DI container — swap for Hilt as the app grows. */
class KlicApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CallNotifications.createChannels(this)
        trackForeground()
    }

    /** Track whether any activity is in the foreground, so push handlers can suppress
     *  notifications while the user is actively in the app. */
    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++; container.appForeground = started > 0
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0); container.appForeground = started > 0
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // App-wide Coil loader that can decode the SVG sticker pack served from the API.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(SvgDecoder.Factory()) }.build()
}

class AppContainer(app: Application) {
    val appContext = app.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tokenStore = TokenStore(app)

    /** True while any activity is started (app visible) — set by KlicApplication. */
    @Volatile var appForeground: Boolean = false

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

    /** Hang-up requests from outside the ViewModel (e.g. the ongoing-call notification action).
     *  The ViewModel collects this and runs its normal end-call teardown. */
    private val _callHangup = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val callHangup: SharedFlow<Unit> = _callHangup
    fun requestHangup() { _callHangup.tryEmit(Unit) }

    private val prefs = app.getSharedPreferences("klic_prefs", android.content.Context.MODE_PRIVATE)
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) { prefs.edit().putString("theme_mode", value).apply() }

    /** Whether we've already shown the one-time "reliable calls" prompt after sign-in. */
    var reliabilityPrompted: Boolean
        get() = prefs.getBoolean("reliability_prompted", false)
        set(value) { prefs.edit().putBoolean("reliability_prompted", value).apply() }
}
