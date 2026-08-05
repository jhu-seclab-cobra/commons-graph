package edu.jhu.cobra.commons.graph.storage

import org.mapdb.DBMaker

/**
 * Implementation of the [IStorage] interface using MapDB for off-heap storage of nodes and edges.
 * This class provides efficient memory management by storing data outside the Java heap,
 * reducing garbage collection overhead and improving performance for large datasets.
 * Please notice that this implementation is not thread-safe.
 * If you need to use it in a concurrent environment, consider using [MapDBConcurStorageImpl].
 *
 * All storage behavior lives in [AbcMapDBStorage]; this class disables MapDB concurrency
 * and passes actions through unguarded.
 *
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
public class MapDBStorageImpl(
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() },
) : AbcMapDBStorage(
        DBMaker
            .config()
            .concurrencyDisable()
            .closeOnJvmShutdown()
            .make(),
    ) {
    override fun <R> readGuarded(action: () -> R): R = action()

    override fun <R> writeGuarded(action: () -> R): R = action()
}
