package edu.jhu.cobra.commons.graph.storage

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using total in-memory storage backed by the JGraphT library.
 * Please notice that there are performance overheads for the concurrency features.
 * For normal use cases, use [JgraphtStorageImpl] instead (about 20% quicker for basic operations).
 *
 * All storage behavior lives in [AbcJgraphtStorage]; this class wraps actions in a [ReentrantReadWriteLock].
 */
public class JgraphtConcurStorageImpl : AbcJgraphtStorage() {
    private val storageLock = ReentrantReadWriteLock()

    override fun <R> readGuarded(action: () -> R): R = storageLock.read(action)

    override fun <R> writeGuarded(action: () -> R): R = storageLock.write(action)
}
