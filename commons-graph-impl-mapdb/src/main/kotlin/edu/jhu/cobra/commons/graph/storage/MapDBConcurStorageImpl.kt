package edu.jhu.cobra.commons.graph.storage

import org.mapdb.DBMaker
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe implementation of the [IStorage] interface using MapDB for off-heap storage.
 * This implementation uses a [ReentrantReadWriteLock] to ensure thread safety.
 * There are performance overheads for the concurrency features.
 * For normal use cases, use [MapDBStorageImpl] instead.
 *
 * All storage behavior lives in [AbcMapDBStorage]; this class wraps actions in the lock.
 *
 * @param config Configuration function for initializing the MapDB database.
 *              Defaults to a temporary file-based off-heap configuration.
 */
public class MapDBConcurStorageImpl(
    config: DBMaker.() -> DBMaker.Maker = { tempFileDB().fileMmapEnableIfSupported() },
) : AbcMapDBStorage(DBMaker.config().closeOnJvmShutdown().make()) {
    private val dbLock = ReentrantReadWriteLock()

    override fun <R> readGuarded(action: () -> R): R = dbLock.read(action)

    override fun <R> writeGuarded(action: () -> R): R = dbLock.write(action)
}
