package edu.jhu.cobra.commons.graph.storage

import kotlin.concurrent.withLock

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
 */
public inline fun <T> IStorage.criticalSection(action: () -> T): T {
    val lock = criticalSectionLock ?: return action()
    return lock.withLock(action)
}
