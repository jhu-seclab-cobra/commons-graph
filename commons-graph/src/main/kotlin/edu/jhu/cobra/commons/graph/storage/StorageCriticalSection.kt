package edu.jhu.cobra.commons.graph.storage

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Runs [action] holding this storage's [IStorage.criticalSectionLock], or
 * unguarded when the storage is not thread-safe.
 *
 * [IStorage] implementations guarantee only that each individual call is atomic.
 * A graph-layer operation that makes several sequential calls per logical step
 * (allocate an ID then persist it, check-then-create, read-modify-write, a
 * multi-edge traversal) needs this lock to keep that sequence atomic as a unit
 * when several threads share one storage instance. Every graph and poset over
 * one storage serializes through that storage's lock.
 *
 * Reentrant: a call nested inside another critical section on the same storage,
 * on the same thread, proceeds without blocking, and so does every storage call
 * the action makes.
 *
 * The lock is released on every exit, a [StackOverflowError] included. A section
 * that finds too little stack to release its lock throws the overflow before it
 * takes the lock, and its exit also releases the holds that a storage call made
 * by [action] left behind when the overflow cut that call's own release.
 */
public inline fun <T> IStorage.criticalSection(action: () -> T): T {
    val lock = criticalSectionLock ?: return action()
    val outerHolds = lock.reserveStackToRelease()
    lock.lock()
    try {
        return action()
    } finally {
        lock.releaseDownTo(outerHolds)
    }
}

// Frames of `reserve` that must fit in the stack at a section's frame before the section takes its lock. The release
// of the lock (`holdsOfCurrentThread`, `unlock`, and the release in AbstractQueuedSynchronizer) runs at that frame, and
// `StorageCriticalSectionTest` leaves the lock held at 4 frames and passes at 8; 16 is twice that.
private const val RELEASE_FRAMES = 16

/**
 * Throws [StackOverflowError] when less stack than [RELEASE_FRAMES] frames is free at the caller's frame, and returns
 * the holds this thread has on this lock, or -1 when the lock does not count them. Taking the lock after this check
 * leaves the stack the release of the lock needs when the section exits at the same frame; the check runs before the
 * lock is taken so that the overflow it raises leaves no hold behind.
 */
@PublishedApi
internal fun Lock.reserveStackToRelease(): Int {
    reserve(RELEASE_FRAMES, 1L, 2L, 3L, 4L, 5L, 6L)
    return holdsOfCurrentThread()
}

private fun reserve(frames: Int, a: Long, b: Long, c: Long, d: Long, e: Long, f: Long): Long =
    if (frames == 0) a + f else reserve(frames - 1, b, c, d, e, f, a) + a + b + c + d + e + f

/**
 * Releases this lock until this thread holds it [outerHolds] times; releases it once when the lock does not count holds
 * ([outerHolds] is -1). More than one release happens only when a storage call of the section lost its own release to
 * a [StackOverflowError].
 */
@PublishedApi
internal fun Lock.releaseDownTo(outerHolds: Int) {
    if (outerHolds < 0) {
        unlock()
        return
    }
    while (holdsOfCurrentThread() > outerHolds) unlock()
}

internal fun Lock.holdsOfCurrentThread(): Int = when (this) {
    is ReentrantReadWriteLock.WriteLock -> holdCount
    is ReentrantLock -> holdCount
    is HoldCountingLock -> holdsOfCurrentThread()
    else -> -1
}

/** A [Lock] that wraps another and reports the holds the current thread has on it. */
internal interface HoldCountingLock : Lock {
    fun holdsOfCurrentThread(): Int
}
