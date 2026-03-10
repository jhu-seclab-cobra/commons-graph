package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.storage.IStorage
import java.nio.file.Path

/**
 * Filter function for selecting entities in a graph based on their ID.
 */
typealias EntityFilter = (IEntity.ID) -> Boolean

interface IStorageExporter {
    /**
     * Exports data from an [IStorage] object to a destination file specified by [dstFile].
     *
     * @param dstFile The path to the destination file.
     * @param from The [IStorage] object from which to export data.
     * @param predicate An optional [EntityFilter] predicate used to filter the entities to be exported.
     *                  By default, all entities will be exported.
     * @return The path to the exported file.
     */
    fun export(
        dstFile: Path,
        from: IStorage,
        predicate: EntityFilter = { true },
    ): Path
}
