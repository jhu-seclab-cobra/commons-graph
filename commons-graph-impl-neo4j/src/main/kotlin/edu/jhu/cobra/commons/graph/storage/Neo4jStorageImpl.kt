package edu.jhu.cobra.commons.graph.storage

import java.nio.file.Path

/**
 * Non-concurrent [IStorage] using Neo4j 5.x embedded mode with zero in-memory ID mappings.
 *
 * This trades per-operation latency for unlimited capacity — the only limit
 * is disk space, not JVM heap.
 *
 * All storage behavior lives in [AbcNeo4jStorage]; this class passes actions through unguarded.
 * For concurrent access, use [Neo4jConcurStorageImpl] instead.
 *
 * @param graphPath The file path where the Neo4j database will be stored.
 */
public class Neo4jStorageImpl(
    graphPath: Path,
) : AbcNeo4jStorage(graphPath) {
    override fun <R> readGuarded(action: () -> R): R = action()

    override fun <R> writeGuarded(action: () -> R): R = action()
}
