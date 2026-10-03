package com.klic.mobile.app.data

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Write-behind semantics backing the E2EE protocol-store persistence. */
class WriteBehindTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    @Test
    fun flushWritesLatestImmediatelyAndOnlyOnce() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val writer = WriteBehind<Int>(scope, debounceMs = 10_000, dispatcher = Dispatchers.IO) { writes += it }

        (1..50).forEach(writer::offer) // a burst of libsignal mutations
        assertTrue(writer.hasPending)
        writer.flush()

        assertEquals(listOf(50), writes.toList())
        assertFalse(writer.hasPending)
        writer.flush() // nothing pending — no extra write
        assertEquals(listOf(50), writes.toList())
    }

    @Test
    fun debouncedWriteCoalescesBurst() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val writer = WriteBehind<Int>(scope, debounceMs = 50, dispatcher = Dispatchers.IO) { writes += it }

        (1..20).forEach(writer::offer)
        repeat(100) { if (writes.isEmpty()) delay(20) }

        assertEquals(listOf(20), writes.toList())
        assertFalse(writer.hasPending)
    }

    @Test
    fun failedWriteStaysPendingForRetry() = runBlocking {
        var failNext = true
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val writer = WriteBehind<String>(scope, debounceMs = 10_000, dispatcher = Dispatchers.IO) {
            if (failNext) { failNext = false; error("disk full") }
            writes += it
        }

        writer.offer("state")
        try {
            writer.flush()
            fail("flush should rethrow the write failure")
        } catch (_: IllegalStateException) {
        }
        assertTrue(writer.hasPending)
        writer.flush()
        assertEquals(listOf("state"), writes.toList())
    }

    @Test
    fun discardDropsPendingValue() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val writer = WriteBehind<Int>(scope, debounceMs = 10_000, dispatcher = Dispatchers.IO) { writes += it }

        writer.offer(1)
        writer.discard()
        writer.flush()
        assertTrue(writes.isEmpty())
    }
}
