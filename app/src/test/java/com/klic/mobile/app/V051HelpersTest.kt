package com.klic.mobile.app

import com.klic.mobile.app.data.DataUsage
import com.klic.mobile.app.data.bodyMentionsAll
import com.klic.mobile.app.feature.chat.messagelist.mentionAllRanges
import com.klic.mobile.app.feature.chatinfo.extractLinks
import com.klic.mobile.app.feature.chatinfo.linkToOpenableUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V051HelpersTest {

    @Test
    fun mentionsAll_matchesServerRegex() {
        assertTrue(bodyMentionsAll("@all meeting at 5"))
        assertTrue(bodyMentionsAll("hey @ALL"))
        assertTrue(bodyMentionsAll("hey\n@all!"))
        assertFalse(bodyMentionsAll("mail@all.com"))
        assertFalse(bodyMentionsAll("@allison hi"))
        assertFalse(bodyMentionsAll("nothing here"))
    }

    @Test
    fun mentionRanges_coverTheToken() {
        val body = "ping @all and @All again"
        val ranges = mentionAllRanges(body)
        assertEquals(2, ranges.size)
        assertEquals("@all", body.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("@All", body.substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun extractLinks_findsHttpAndBareWww() {
        val links = extractLinks("see https://example.com/a?b=1 and www.klic.chat plus text")
        assertEquals(listOf("https://example.com/a?b=1", "www.klic.chat"), links)
        assertEquals("https://www.klic.chat", linkToOpenableUrl("www.klic.chat"))
        assertEquals("https://example.com", linkToOpenableUrl("https://example.com"))
        assertTrue(extractLinks("no links here").isEmpty())
    }

    @Test
    fun dataUsage_classifiesByContentTypeThenHost() {
        assertEquals(DataUsage.CAT_PHOTOS, DataUsage.classify("https://storage.host/x", "image/jpeg", null))
        assertEquals(DataUsage.CAT_VIDEOS, DataUsage.classify("https://storage.host/x", null, "video/mp4"))
        assertEquals(DataUsage.CAT_AUDIO, DataUsage.classify("https://storage.host/x", null, "audio/m4a"))
        assertEquals(DataUsage.CAT_DOCS, DataUsage.classify("https://storage.host/x", null, "application/pdf"))
        val api = com.klic.mobile.app.data.Network.BASE_HTTP
        assertEquals(DataUsage.CAT_CALLS, DataUsage.classify("$api/api/v1/calls/abc/end", null, "application/json"))
        assertEquals(DataUsage.CAT_API, DataUsage.classify("$api/api/v1/conversations", null, "application/json"))
        assertEquals(DataUsage.CAT_OTHER, DataUsage.classify("https://elsewhere.example/x", null, "application/json"))
    }

    @Test
    fun dataUsage_sumFiltersByNetworkAndDirection() {
        val totals = mapOf(
            "photos.up.wifi" to 100L,
            "photos.up.cell" to 30L,
            "photos.down.wifi" to 500L,
            "api.down.cell" to 7L,
        )
        assertEquals(130L, DataUsage.sum(totals, DataUsage.CAT_PHOTOS, up = true, network = null))
        assertEquals(30L, DataUsage.sum(totals, DataUsage.CAT_PHOTOS, up = true, network = DataUsage.NET_CELL))
        assertEquals(500L, DataUsage.sum(totals, DataUsage.CAT_PHOTOS, up = false, network = DataUsage.NET_WIFI))
        assertEquals(7L, DataUsage.sum(totals, DataUsage.CAT_API, up = false, network = DataUsage.NET_CELL))
    }
}
