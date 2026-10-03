package com.klic.mobile.app.ui.components

/**
 * Stable, guaranteed-unique LazyColumn keys from natural ids that may repeat (the same
 * link twice in one message, a tone listed twice). The first occurrence keeps its id;
 * later ones get a "#n" suffix — duplicate keys crash a LazyColumn.
 */
fun uniqueLazyKeys(ids: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return ids.map { id ->
        val n = (seen[id] ?: 0) + 1
        seen[id] = n
        if (n == 1) id else "$id#$n"
    }
}
