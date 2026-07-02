package com.klic.mobile.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Network byte accounting for the Data & Storage page (§8.3).
 *
 * Every OkHttp stack in the app (API client, presigned uploader, attachment downloader,
 * Coil image loader) installs [interceptor], which attributes each request by class
 * (photos/videos/audio/docs/calls-signaling/api/other), direction, and the network type
 * at request time (ConnectivityManager), then folds the bytes into DataStore-persisted
 * counters. LiveKit's WebRTC media bypasses OkHttp, so calls count signaling only.
 */
object DataUsage {
    const val NET_WIFI = "wifi"
    const val NET_CELL = "cell"
    const val NET_OTHER = "other"

    const val CAT_PHOTOS = "photos"
    const val CAT_VIDEOS = "videos"
    const val CAT_AUDIO = "audio"
    const val CAT_DOCS = "docs"
    const val CAT_CALLS = "calls"     // signaling only
    const val CAT_API = "api"
    const val CAT_OTHER = "other"

    val categories = listOf(CAT_PHOTOS, CAT_VIDEOS, CAT_AUDIO, CAT_DOCS, CAT_CALLS, CAT_API, CAT_OTHER)

    private val Context.usageDataStore by preferencesDataStore(name = "klic_data_usage")
    private val KEY = stringPreferencesKey("counters")
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Long.serializer())

    /** "category.direction.network" → bytes. direction ∈ {up, down}. */
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val _totals = MutableStateFlow<Map<String, Long>>(emptyMap())
    val totals: StateFlow<Map<String, Long>> = _totals

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private var flushJob: Job? = null

    fun init(context: Context, appScope: CoroutineScope) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope = appScope
        scope.launch {
            val stored = runCatching {
                appContext.usageDataStore.data.first()[KEY]?.let { json.decodeFromString(mapSerializer, it) }
            }.getOrNull().orEmpty()
            stored.forEach { (k, v) -> counters.getOrPut(k) { AtomicLong() }.addAndGet(v) }
            publish()
        }
    }

    fun record(category: String, network: String, sentBytes: Long, receivedBytes: Long) {
        if (sentBytes <= 0 && receivedBytes <= 0) return
        if (sentBytes > 0) counters.getOrPut("$category.up.$network") { AtomicLong() }.addAndGet(sentBytes)
        if (receivedBytes > 0) counters.getOrPut("$category.down.$network") { AtomicLong() }.addAndGet(receivedBytes)
        publish()
        scheduleFlush()
    }

    suspend fun reset() {
        counters.clear()
        publish()
        runCatching { appContext.usageDataStore.edit { it.remove(KEY) } }
    }

    /** Sum for a category+direction, restricted to one network bucket (null = all). */
    fun sum(totals: Map<String, Long>, category: String, up: Boolean, network: String?): Long {
        val dir = if (up) "up" else "down"
        return totals.entries.sumOf { (key, v) ->
            val parts = key.split(".")
            if (parts.size == 3 && parts[0] == category && parts[1] == dir &&
                (network == null || parts[2] == network)
            ) v else 0L
        }
    }

    private fun publish() {
        _totals.value = counters.mapValues { it.value.get() }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(2000)
            val snapshot = counters.mapValues { it.value.get() }
            runCatching {
                appContext.usageDataStore.edit { it[KEY] = json.encodeToString(mapSerializer, snapshot) }
            }
        }
    }

    /** The current default network, bucketed for attribution. */
    fun currentNetwork(): String {
        if (!::appContext.isInitialized) return NET_OTHER
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return NET_OTHER
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NET_OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NET_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NET_CELL
            else -> NET_OTHER
        }
    }

    /** True when on (unmetered-ish) Wi-Fi — drives the auto-download matrix (§8.3). */
    fun isOnWifi(): Boolean = currentNetwork() == NET_WIFI

    /** Install on every OkHttpClient whose traffic should be attributed. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val network = currentNetwork()
        val sent = request.body?.contentLength()?.takeIf { it > 0 } ?: 0L
        val response: Response = chain.proceed(request)
        val received = response.body?.contentLength()?.takeIf { it > 0 } ?: 0L
        val category = classify(
            url = request.url.toString(),
            requestType = request.body?.contentType()?.toString(),
            responseType = response.header("Content-Type"),
        )
        if (::appContext.isInitialized) record(category, network, sent, received)
        response
    }

    /** Attribution: media kind from content type when recognizable, else api vs other. */
    internal fun classify(url: String, requestType: String?, responseType: String?): String {
        val media = mediaCategory(requestType) ?: mediaCategory(responseType)
        if (media != null) return media
        val isApi = url.startsWith(Network.BASE_HTTP)
        return when {
            isApi && url.contains("/calls") -> CAT_CALLS
            isApi -> CAT_API
            else -> CAT_OTHER
        }
    }

    private fun mediaCategory(contentType: String?): String? {
        val type = contentType?.lowercase() ?: return null
        return when {
            type.startsWith("image/") -> CAT_PHOTOS
            type.startsWith("video/") -> CAT_VIDEOS
            type.startsWith("audio/") -> CAT_AUDIO
            type.startsWith("application/pdf") || type.startsWith("application/msword") ||
                type.startsWith("application/vnd.") || type.startsWith("text/") ||
                type.startsWith("application/zip") || type.startsWith("application/octet-stream")
            -> CAT_DOCS
            else -> null
        }
    }
}

/** "1.2 GB" / "34.5 MB" / "812 KB" style byte formatting for the settings pages. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824f)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
