package edu.jhu.cobra.commons.graph.storage

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import kotlin.concurrent.withLock

// The adapter shares the storage lock; no independent lock can interleave a compound storage operation.
internal class StorageAccessGuard(private val lock: Lock) {
    private var closed = false
    private val readDepth = ThreadLocal<Int>()

    val mutationLock: Lock = object : Lock by lock {
        override fun lock() { acquire { lock.lock(); true } }
        override fun lockInterruptibly() { acquire { lock.lockInterruptibly(); true } }
        override fun tryLock(): Boolean = acquire { lock.tryLock() }
        override fun tryLock(time: Long, unit: TimeUnit): Boolean = acquire { lock.tryLock(time, unit) }
    }

    private fun acquire(action: () -> Boolean): Boolean {
        checkWritable()
        if (!action()) return false
        if (closed) {
            lock.unlock()
            error("Storage access is closed")
        }
        return true
    }

    fun checkOpen() { check(!closed) { "Storage access is closed" } }

    private fun checkWritable() {
        val depth = readDepth.get()
        if (depth == null) readDepth.remove()
        check((depth ?: 0) == 0) { "Mutation inside a read scope" }
    }

    fun <R> read(action: () -> R): R = lock.withLock {
        checkOpen()
        action()
    }

    fun <R> readScoped(action: () -> R): R = read {
        val depth = readDepth.get() ?: 0
        readDepth.set(depth + 1)
        try {
            action()
        } finally {
            if (depth == 0) readDepth.remove() else readDepth.set(depth)
        }
    }

    fun <R> write(action: () -> R): R {
        checkWritable()
        return read(action)
    }

    fun close(action: () -> Unit) {
        checkWritable()
        lock.withLock {
            if (closed) return
            try {
                action()
            } finally {
                closed = true
                readDepth.remove()
            }
        }
    }
}

// One callback owns each borrowed scope; immutable/captured return values have no scope dependency.
internal class StorageScope(private val guard: StorageAccessGuard) {
    private val thread = Thread.currentThread()
    private var active = true

    fun checkActive() {
        check(Thread.currentThread() === thread) { "Storage scope used on another thread" }
        guard.checkOpen()
        check(active) { "Storage scope has expired" }
    }

    fun expire() { active = false }
}
