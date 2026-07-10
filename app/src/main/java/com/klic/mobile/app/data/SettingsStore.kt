package com.klic.mobile.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * App settings + per-conversation notification/media prefs (§8.3–§8.5), persisted in
 * a Preferences DataStore and mirrored into an in-memory [snapshot] so composables and
 * the push/ringer paths (which can't suspend at decision time) read them cheaply.
 *
 * Server-synced values (global notification toggles, per-chat mutes) are CACHED here so
 * the local notification path can honor them even when offline; the server remains the
 * source of truth and screens refresh the cache from GET on open.
 */
object SettingsStore {
    private val Context.settingsDataStore by preferencesDataStore(name = "klic_settings")

    /** Auto-download matrix rows (§8.3). */
    const val KIND_PHOTOS = "photos"
    const val KIND_AUDIO = "audio"
    const val KIND_VIDEO = "video"
    const val KIND_DOCS = "docs"

    /** Save-to-Photos modes (§8.4). */
    const val SAVE_DEFAULT = "default"
    const val SAVE_ALWAYS = "always"
    const val SAVE_NEVER = "never"

    /** "Open links in" modes (§10.4). */
    const val LINKS_IN_APP = "in_app"
    const val LINKS_CHROME = "chrome"
    const val LINKS_SYSTEM = "system"

    data class Snapshot(
        val uploadHd: Boolean = false,
        /** "kind_wifi"/"kind_cell" → allowed. Missing key = the default for that cell. */
        val autoDownload: Map<String, Boolean> = emptyMap(),
        // Global notification toggles (cache of /me/notification-prefs).
        val notifMessages: Boolean = true,
        val notifGroups: Boolean = true,
        val notifCalls: Boolean = true,
        val notifFriendRequests: Boolean = true,
        // Per-conversation prefs, keyed by conversation id.
        val messagesMutedUntil: Map<String, Long> = emptyMap(),   // epoch millis
        val callsMutedUntil: Map<String, Long> = emptyMap(),
        val muteMentions: Set<String> = emptySet(),
        val messageTones: Map<String, String> = emptyMap(),       // ringtone URIs
        val callTones: Map<String, String> = emptyMap(),
        val saveToPhotos: Map<String, String> = emptyMap(),       // SAVE_* modes
        /** Conversation ids known to be GROUP — lets the push path pick the right toggle. */
        val groupConversationIds: Set<String> = emptySet(),
        /** Attachment ids already auto-saved to the gallery (dedup). */
        val savedGalleryIds: Set<String> = emptySet(),
        // ── v0.5.3 (§10.4) ──
        /** "Open links in": LINKS_IN_APP / LINKS_CHROME / LINKS_SYSTEM. */
        val linkOpenMode: String = LINKS_IN_APP,
        /** "Don't open links in-app": forces external even when linkOpenMode is in-app. */
        val neverOpenLinksInApp: Boolean = false,
        /** Sync Contacts toggle state (hashes uploaded while on). */
        val contactsSyncEnabled: Boolean = false,
        /** "Suggest Frequent Contacts" — gates the Frequent row on pickers. */
        val suggestFrequentContacts: Boolean = true,
        /** Saved composer drafts per conversation (§10.4 "Delete All Drafts"). */
        val drafts: Map<String, String> = emptyMap(),
        /** Locally counted sent messages per conversation — drives Frequent contacts. */
        val sentCounts: Map<String, Long> = emptyMap(),
        /** Message ids hidden on this device via the "Hide" action (UGC filter). */
        val hiddenMessageIds: Set<String> = emptySet(),
    ) {
        fun autoDownloadAllowed(kind: String, onWifi: Boolean): Boolean {
            val key = "${kind}_${if (onWifi) "wifi" else "cell"}"
            // Defaults: photos everywhere; audio/video/docs on Wi-Fi only.
            val default = onWifi || kind == KIND_PHOTOS
            return autoDownload[key] ?: default
        }

        fun messagesMuted(conversationId: String): Boolean =
            (messagesMutedUntil[conversationId] ?: 0L) > System.currentTimeMillis()

        fun callsMuted(conversationId: String): Boolean =
            (callsMutedUntil[conversationId] ?: 0L) > System.currentTimeMillis()
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val loaded = CompletableDeferred<Unit>()
    private lateinit var appContext: Context

    /** Idempotent; call once from Application.onCreate. */
    fun init(context: Context, scope: CoroutineScope) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope.launch {
            appContext.settingsDataStore.data.collect { prefs ->
                _snapshot.value = parse(prefs)
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
    }

    /** Blocks (briefly) until the first DataStore read lands — for the FCM/ringer path. */
    fun snapshotBlocking(): Snapshot {
        runBlocking { loaded.await() }
        return _snapshot.value
    }

    // ── Writers ──────────────────────────────────────────────────────────────

    suspend fun setUploadHd(value: Boolean) = edit { it[UPLOAD_HD] = value }

    suspend fun setAutoDownload(kind: String, wifi: Boolean, allowed: Boolean) =
        edit { it[booleanPreferencesKey("autodl_${kind}_${if (wifi) "wifi" else "cell"}")] = allowed }

    suspend fun setNotificationToggles(prefs: NotificationPrefs) = edit {
        it[NOTIF_MESSAGES] = prefs.messages
        it[NOTIF_GROUPS] = prefs.groups
        it[NOTIF_CALLS] = prefs.calls
        it[NOTIF_FRIEND_REQUESTS] = prefs.friendRequests
    }

    /** Cache the server's per-conversation prefs for local push gating. */
    suspend fun cacheConversationPrefs(conversationId: String, prefs: ConversationPrefs) = edit {
        val msgKey = longPreferencesKey("muted_msgs_$conversationId")
        val callKey = longPreferencesKey("muted_calls_$conversationId")
        parseInstant(prefs.messagesMutedUntil)?.let { ms -> it[msgKey] = ms } ?: it.remove(msgKey)
        parseInstant(prefs.callsMutedUntil)?.let { ms -> it[callKey] = ms } ?: it.remove(callKey)
        it[booleanPreferencesKey("mute_mentions_$conversationId")] = prefs.muteMentions
    }

    suspend fun setMessageTone(conversationId: String, uri: String?) =
        setOrRemove(stringPreferencesKey("tone_msg_$conversationId"), uri)

    suspend fun setCallTone(conversationId: String, uri: String?) =
        setOrRemove(stringPreferencesKey("tone_call_$conversationId"), uri)

    suspend fun setSaveToPhotos(conversationId: String, mode: String) =
        setOrRemove(stringPreferencesKey("save_photos_$conversationId"), mode.takeIf { it != SAVE_DEFAULT })

    suspend fun setGroupConversationIds(ids: Set<String>) = edit { it[GROUP_IDS] = ids }

    suspend fun markSavedToGallery(attachmentId: String) = edit {
        val current = it[SAVED_GALLERY] ?: emptySet()
        // Cap the dedup set so it can't grow without bound.
        it[SAVED_GALLERY] = (current + attachmentId).let { set ->
            if (set.size > 800) set.drop(set.size - 800).toSet() else set
        }
    }

    // ── v0.5.3 (§10.4): links, contacts, frequent, drafts ────────────────────

    suspend fun setLinkOpenMode(mode: String) = edit { it[LINK_MODE] = mode }
    suspend fun setNeverOpenLinksInApp(value: Boolean) = edit { it[LINKS_NO_IN_APP] = value }
    suspend fun setContactsSyncEnabled(value: Boolean) = edit { it[CONTACTS_SYNC] = value }
    suspend fun setSuggestFrequentContacts(value: Boolean) = edit { it[SUGGEST_FREQUENT] = value }

    /** Persist (or clear) the composer draft for one conversation. */
    suspend fun setDraft(conversationId: String, text: String?) =
        setOrRemove(stringPreferencesKey("draft_$conversationId"), text?.takeIf { it.isNotBlank() })

    /** §10.4 "Delete All Drafts": clears every saved composer draft on this device. */
    suspend fun deleteAllDrafts() = edit { prefs ->
        prefs.asMap().keys.filter { it.name.startsWith("draft_") }.forEach { prefs.remove(it) }
    }

    /** Bump the local sent-message counter for a conversation (Frequent contacts). */
    suspend fun bumpSentCount(conversationId: String) = edit {
        val key = longPreferencesKey("sent_count_$conversationId")
        it[key] = (it[key] ?: 0L) + 1L
    }

    /** "Hide" message action: keep the message out of this device's chat rendering. */
    suspend fun hideMessage(messageId: String) = edit {
        val current = it[HIDDEN_MESSAGES] ?: emptySet()
        // Cap the set so it can't grow without bound.
        it[HIDDEN_MESSAGES] = (current + messageId).let { set ->
            if (set.size > 800) set.drop(set.size - 800).toSet() else set
        }
    }

    /** "Reset hidden messages": every locally hidden message becomes visible again. */
    suspend fun resetHiddenMessages() = edit { it.remove(HIDDEN_MESSAGES) }

    /** "Reset notification settings" (§8.5): toggles back to on + all local tones dropped. */
    suspend fun resetNotificationSettings() = edit { prefs ->
        prefs.remove(NOTIF_MESSAGES)
        prefs.remove(NOTIF_GROUPS)
        prefs.remove(NOTIF_CALLS)
        prefs.remove(NOTIF_FRIEND_REQUESTS)
        prefs.asMap().keys
            .filter { it.name.startsWith("tone_msg_") || it.name.startsWith("tone_call_") }
            .forEach { prefs.remove(it) }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private val UPLOAD_HD = booleanPreferencesKey("upload_hd")
    private val NOTIF_MESSAGES = booleanPreferencesKey("notif_messages")
    private val NOTIF_GROUPS = booleanPreferencesKey("notif_groups")
    private val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
    private val NOTIF_FRIEND_REQUESTS = booleanPreferencesKey("notif_friend_requests")
    private val GROUP_IDS = stringSetPreferencesKey("group_conversation_ids")
    private val SAVED_GALLERY = stringSetPreferencesKey("saved_gallery_ids")
    private val LINK_MODE = stringPreferencesKey("link_open_mode")
    private val LINKS_NO_IN_APP = booleanPreferencesKey("links_never_in_app")
    private val CONTACTS_SYNC = booleanPreferencesKey("contacts_sync_enabled")
    private val SUGGEST_FREQUENT = booleanPreferencesKey("suggest_frequent_contacts")
    private val HIDDEN_MESSAGES = stringSetPreferencesKey("hidden_message_ids")

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        appContext.settingsDataStore.edit(block)
    }

    private suspend fun setOrRemove(key: Preferences.Key<String>, value: String?) =
        edit { if (value != null) it[key] = value else it.remove(key) }

    private fun parseInstant(iso: String?): Long? =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun parse(prefs: Preferences): Snapshot {
        val autoDownload = mutableMapOf<String, Boolean>()
        val msgMuted = mutableMapOf<String, Long>()
        val callMuted = mutableMapOf<String, Long>()
        val mentions = mutableSetOf<String>()
        val msgTones = mutableMapOf<String, String>()
        val callTones = mutableMapOf<String, String>()
        val savePhotos = mutableMapOf<String, String>()
        val drafts = mutableMapOf<String, String>()
        val sentCounts = mutableMapOf<String, Long>()
        prefs.asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name.startsWith("draft_") -> (value as? String)?.let { drafts[name.removePrefix("draft_")] = it }
                name.startsWith("sent_count_") -> (value as? Long)?.let { sentCounts[name.removePrefix("sent_count_")] = it }
                name.startsWith("autodl_") -> (value as? Boolean)?.let { autoDownload[name.removePrefix("autodl_")] = it }
                name.startsWith("muted_msgs_") -> (value as? Long)?.let { msgMuted[name.removePrefix("muted_msgs_")] = it }
                name.startsWith("muted_calls_") -> (value as? Long)?.let { callMuted[name.removePrefix("muted_calls_")] = it }
                name.startsWith("mute_mentions_") -> if (value == true) mentions += name.removePrefix("mute_mentions_")
                name.startsWith("tone_msg_") -> (value as? String)?.let { msgTones[name.removePrefix("tone_msg_")] = it }
                name.startsWith("tone_call_") -> (value as? String)?.let { callTones[name.removePrefix("tone_call_")] = it }
                name.startsWith("save_photos_") -> (value as? String)?.let { savePhotos[name.removePrefix("save_photos_")] = it }
            }
        }
        return Snapshot(
            uploadHd = prefs[UPLOAD_HD] ?: false,
            autoDownload = autoDownload,
            notifMessages = prefs[NOTIF_MESSAGES] ?: true,
            notifGroups = prefs[NOTIF_GROUPS] ?: true,
            notifCalls = prefs[NOTIF_CALLS] ?: true,
            notifFriendRequests = prefs[NOTIF_FRIEND_REQUESTS] ?: true,
            messagesMutedUntil = msgMuted,
            callsMutedUntil = callMuted,
            muteMentions = mentions,
            messageTones = msgTones,
            callTones = callTones,
            saveToPhotos = savePhotos,
            groupConversationIds = prefs[GROUP_IDS] ?: emptySet(),
            savedGalleryIds = prefs[SAVED_GALLERY] ?: emptySet(),
            linkOpenMode = prefs[LINK_MODE] ?: LINKS_IN_APP,
            neverOpenLinksInApp = prefs[LINKS_NO_IN_APP] ?: false,
            contactsSyncEnabled = prefs[CONTACTS_SYNC] ?: false,
            suggestFrequentContacts = prefs[SUGGEST_FREQUENT] ?: true,
            drafts = drafts,
            sentCounts = sentCounts,
            hiddenMessageIds = prefs[HIDDEN_MESSAGES] ?: emptySet(),
        )
    }
}

/** Matches the server's push gating: an "@all" mention in a plaintext body (§8.2). */
fun bodyMentionsAll(body: String): Boolean =
    Regex("""(^|\s)@all\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
