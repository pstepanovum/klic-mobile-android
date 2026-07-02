package com.klic.mobile.app.feature.chatinfo

/**
 * Client-side URL scan for the "Links" tab (§8.4) — the server has no link index, so
 * links come from a regex sweep over fetched message history.
 */
private val urlRegex = Regex(
    """(?:https?://|www\.)[\w\-@:%.+~#=]{1,256}\.[a-zA-Z0-9()]{1,12}\b[\w\-()@:%+.~#?&/=]*""",
)

/** All URLs in a message body, in order of appearance. */
fun extractLinks(body: String): List<String> = urlRegex.findAll(body).map { it.value }.toList()

/** Normalizes a scanned link into something ACTION_VIEW can open. */
fun linkToOpenableUrl(link: String): String =
    if (link.startsWith("http://") || link.startsWith("https://")) link else "https://$link"
