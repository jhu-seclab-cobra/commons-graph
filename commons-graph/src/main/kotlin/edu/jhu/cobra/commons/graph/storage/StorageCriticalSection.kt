package edu.jhu.cobra.commons.graph.storage

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Keyed by storage-instance identity: every graph sharing one IStorage (ADG, AST,
// CCG, PDG, their PosetDftImpl) serializes through the same lock, so nested
// criticalSection calls across those classes never contend on separate locks.
// Internal + @PublishedApi: visible to the public inline criticalSection below
// for inlining, never exposed as part of the module's public API.
@PublishedApi
internal val storageLocks: ConcurrentHashMap<IStorage, ReentrantLock> = ConcurrentHashMap()

/**
 * Runs [action] under a lock scoped to this storage instance.
 *
 * [IStorage] implementations guarantee only that each individual call is atomic.
 * A graph-layer operation that makes several sequential calls per logical step
 * (allocate an ID then persist it, check-then-create, read-modify-write, a
 * multi-edge traversal) needs this lock to keep that sequence atomic as a unit
 * when several threads share one storage instance.
 *
 * Reentrant: a call nested inside another critical section on the same storage,
 * on the same thread, proceeds without blocking.
 */
public inline fun <T> IStorage.criticalSection(action: () -> T): T = storageLocks.computeIfAbsent(this) { ReentrantLock() }.withLock(action)
