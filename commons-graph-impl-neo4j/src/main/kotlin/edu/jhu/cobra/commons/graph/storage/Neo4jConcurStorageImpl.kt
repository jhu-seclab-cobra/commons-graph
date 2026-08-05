package edu.jhu.cobra.commons.graph.storage

import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe [IStorage] using Neo4j 5.x embedded mode with zero in-memory ID mappings.
 *
 * All storage behavior lives in [AbcNeo4jStorage]; this class wraps actions in a
 * [ReentrantReadWriteLock] for thread safety on the mutable ID counters.
 *
 * For normal use cases without concurrent access, use [Neo4jStorageImpl] instead.
 *
 * @param graphPath The file path where the Neo4j database will be stored.
 */
public class Neo4jConcurStorageImpl(
    graphPath: Path,
) : AbcNeo4jStorage(graphPath) {
    private val storageLock = ReentrantReadWriteLock()

    override fun <R> readGuarded(action: () -> R): R = storageLock.read(action)

    override fun <R> writeGuarded(action: () -> R): R = storageLock.write(action)
}
