package com.klic.mobile.app.calling

import android.content.Intent
import android.os.Bundle

/** Incoming-call payload, passed via FCM data and Intent extras. */
data class CallInvite(
    val callId: String,
    val conversationId: String,
    val roomName: String,
    val livekitUrl: String,
    val kind: String,
    val fromName: String,
    val conversationType: String = "DIRECT",
    val conversationTitle: String = "",
    val participantCount: Int = 0,
) {
    val isGroup: Boolean get() = conversationType == "GROUP"

    /** Ring-surface label: "<Caller> · <Group title>" for groups, else just the caller. */
    val displayLabel: String
        get() = if (isGroup && conversationTitle.isNotBlank()) "$fromName · $conversationTitle" else fromName

    fun toBundle(): Bundle = Bundle().apply {
        putString("callId", callId)
        putString("conversationId", conversationId)
        putString("roomName", roomName)
        putString("livekitUrl", livekitUrl)
        putString("kind", kind)
        putString("fromName", fromName)
        putString("conversationType", conversationType)
        putString("conversationTitle", conversationTitle)
        putInt("participantCount", participantCount)
    }

    companion object {
        fun fromMap(d: Map<String, String>) = CallInvite(
            callId = d["callId"].orEmpty(),
            conversationId = d["conversationId"].orEmpty(),
            roomName = d["roomName"].orEmpty(),
            livekitUrl = d["livekitUrl"].orEmpty(),
            kind = d["kind"] ?: "AUDIO",
            fromName = d["fromName"] ?: "Incoming call",
            conversationType = d["conversationType"] ?: "DIRECT",
            conversationTitle = d["conversationTitle"].orEmpty(),
            participantCount = d["participantCount"]?.toIntOrNull() ?: 0,
        )

        fun fromIntent(intent: Intent): CallInvite? {
            val callId = intent.getStringExtra("callId") ?: return null
            return CallInvite(
                callId = callId,
                conversationId = intent.getStringExtra("conversationId").orEmpty(),
                roomName = intent.getStringExtra("roomName").orEmpty(),
                livekitUrl = intent.getStringExtra("livekitUrl").orEmpty(),
                kind = intent.getStringExtra("kind") ?: "AUDIO",
                fromName = intent.getStringExtra("fromName") ?: "Incoming call",
                conversationType = intent.getStringExtra("conversationType") ?: "DIRECT",
                conversationTitle = intent.getStringExtra("conversationTitle").orEmpty(),
                participantCount = intent.getIntExtra("participantCount", 0),
            )
        }
    }
}
