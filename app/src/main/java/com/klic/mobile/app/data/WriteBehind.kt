package com.klic.mobile.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single-writer, write-behind persistence for a value whose in-memory copy is
 * authoritative (each [offer] is a complete snapshot, so intermediate values may be
 * coalesced away). [offer] never blocks: it parks the latest value and a background
 * collector writes it after [debounceMs] of quiet. [flush] writes whatever is pending
 * right now and returns once it is durable — call it wherever a crash must not roll
 * the persisted state back (e.g. before the network learns about it).
 *
 * At most one [write] runs at a time, and writes are never cancelled midway.
 * A failed write keeps its value pending (unless a newer one has arrived) so the
 * next flush retries it.
 */
class WriteBehind<T : Any>(
    scope: CoroutineScope,
    private val debounceMs: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val write: suspend (T) -> Unit,
) {
    private val pending = MutableStateFlow<T?>(null)
    private val writeLock = Mutex()

    init {
        scope.launch(dispatcher) {
            pending.collectLatest { value ->
                if (value == null) return@collectLatest
                delay(debounceMs)
                runCatching { withContext(NonCancellable) { flush() } }
            }
        }
    }

    /** Park [value] as the newest state to persist. Non-blocking; safe from any thread. */
    fun offer(value: T) {
        pending.value = value
    }

    /** True while a value is waiting to be written. */
    val hasPending: Boolean get() = pending.value != null

    /** Write the pending value (if any) now and suspend until it is persisted. Rethrows write failures. */
    suspend fun flush() = withContext(dispatcher) {
        writeLock.withLock {
            val value = pending.getAndUpdate { null } ?: return@withLock
            try {
                write(value)
            } catch (t: Throwable) {
                // Keep it for the next attempt — unless a newer snapshot already superseded it.
                pending.compareAndSet(null, value)
                throw t
            }
        }
    }

    /**
     * Drop any pending value without writing it, waiting out an in-flight write. Use
     * before replacing the persisted state wholesale, so a stale snapshot can't land
     * on top of the replacement.
     */
    suspend fun discard() = withContext(dispatcher) {
        writeLock.withLock { pending.value = null }
    }
}
