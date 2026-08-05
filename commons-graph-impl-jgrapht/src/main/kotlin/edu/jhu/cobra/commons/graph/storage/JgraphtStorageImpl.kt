package edu.jhu.cobra.commons.graph.storage

/**
 * Non-concurrent implementation of [IStorage] using JGraphT library for in-memory graph storage.
 * For concurrent access, use [JgraphtConcurStorageImpl] instead.
 *
 * All storage behavior lives in [AbcJgraphtStorage]; this class passes actions through unguarded.
 */
public class JgraphtStorageImpl : AbcJgraphtStorage() {
    override fun <R> readGuarded(action: () -> R): R = action()

    override fun <R> writeGuarded(action: () -> R): R = action()
}
