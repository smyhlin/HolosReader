package com.holos.reader.tts

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reference-counted engine generation pool (#15).
 * Ensures active synthesis borrowers never have their native ONNX sessions closed out from
 * under them when settings change or [retireCurrent] is called: stale generations are marked
 * retired under lock and closed via [closer] only when their last active [Lease] is released.
 */
class EngineLeasePool<T : AutoCloseable>(
    private val closer: (T) -> Unit = { it.close() },
) {
    private class Entry<T : AutoCloseable>(
        val key: String,
        val engine: T,
    ) {
        var activeLeases: Int = 0
        var retired: Boolean = false
        var closed: Boolean = false
    }

    class Lease<T : AutoCloseable> internal constructor(
        val engine: T,
        private val onRelease: () -> Unit,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                onRelease()
            }
        }
    }

    private val lock = Any()
    private var current: Entry<T>? = null

    fun acquire(key: String, factory: () -> T?): Lease<T>? {
        var staleToClose: T? = null
        val leasedEntry: Entry<T>? = try {
            synchronized(lock) {
                val cur = current
                if (cur != null && !cur.retired && cur.key == key) {
                    cur.activeLeases++
                    return@synchronized cur
                }
                if (cur != null) {
                    cur.retired = true
                    if (cur.activeLeases == 0 && !cur.closed) {
                        cur.closed = true
                        staleToClose = cur.engine
                    }
                    current = null
                }
                val created = factory() ?: return@synchronized null
                val fresh = Entry(key, created)
                fresh.activeLeases = 1
                current = fresh
                fresh
            }
        } finally {
            staleToClose?.let { closer(it) }
        }
        return leasedEntry?.let { entry -> Lease(entry.engine) { releaseEntry(entry) } }
    }

    fun retireCurrent() {
        var toClose: T? = null
        synchronized(lock) {
            val cur = current
            current = null
            if (cur != null) {
                cur.retired = true
                if (cur.activeLeases == 0 && !cur.closed) {
                    cur.closed = true
                    toClose = cur.engine
                }
            }
        }
        toClose?.let { closer(it) }
    }

    inline fun <R> withLease(key: String, noinline factory: () -> T?, block: (T) -> R): R? {
        val lease = acquire(key, factory) ?: return null
        return lease.use { block(it.engine) }
    }

    private fun releaseEntry(entry: Entry<T>) {
        var toClose: T? = null
        synchronized(lock) {
            if (entry.activeLeases > 0) entry.activeLeases--
            if (entry.retired && entry.activeLeases == 0 && !entry.closed) {
                entry.closed = true
                toClose = entry.engine
            }
        }
        toClose?.let { closer(it) }
    }
}
