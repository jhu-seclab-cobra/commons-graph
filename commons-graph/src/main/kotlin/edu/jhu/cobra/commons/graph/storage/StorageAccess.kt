package edu.jhu.cobra.commons.graph.storage

/** Supplies a read capability whose borrowed scope expires when [read] returns or throws. */
public interface GraphReader {
    /** Calls [action] under one storage guard; mutation on this thread is rejected until exit. */
    public fun <R> read(action: (StorageReadScope) -> R): R
}

/** Supplies compound writes and physical-layer lifecycle, independently of the read capability. */
public interface GraphWriter {
    /** Calls [action] under one guard; excludes other threads without promising callback rollback. */
    public fun <R> write(action: (StorageWriteScope) -> R): R

    /** Merges visible content into the Frozen layer. */
    public fun freeze()

    /** Drops the Active layer's changes. */
    public fun clearActiveLayer()

    /** Removes data and ends the current stored entity lifetime. */
    public fun clear()
}

/**
 * Owns read/write capabilities and fixed compatibility snapshots over one existing layered implementation.
 *
 * The caller transfers exclusive ownership of [storage] and closes this owner after releasing its graphs.
 * @throws IllegalArgumentException If the supplied storage has no compound guard.
 */
public class StorageAccess(private val storage: LayeredStorage) : AutoCloseable {
    private val guard = StorageAccessGuard(requireNotNull(storage.criticalSectionLock) { "Storage requires a guard" })

    /** Existing combined access for callers migrating to separate capabilities. */
    public val compatibility: LayeredStorage = StorageCompatibility(storage, guard, ::close)

    /** Read-only access whose borrowed results remain confined to a callback. */
    public val reader: GraphReader = object : GraphReader {
        override fun <R> read(action: (StorageReadScope) -> R): R = guard.readScoped {
            val lifetime = StorageScope(guard)
            try {
                action(StorageReadScope(storage, lifetime))
            } finally {
                lifetime.expire()
            }
        }
    }

    /** Write-only access with compound exclusion and physical-layer lifecycle. */
    public val writer: GraphWriter = object : GraphWriter {
        override fun <R> write(action: (StorageWriteScope) -> R): R = guard.write {
            val lifetime = StorageScope(guard)
            try {
                action(StorageWriteScope(compatibility, lifetime))
            } finally {
                lifetime.expire()
            }
        }

        override fun freeze(): Unit = compatibility.freeze()
        override fun clearActiveLayer(): Unit = compatibility.clearActiveLayer()
        override fun clear(): Unit = compatibility.clear()
    }

    /** Clears and closes the owned storage once; every capability is invalid after this call. */
    override fun close(): Unit = guard.close(storage::close)
}
