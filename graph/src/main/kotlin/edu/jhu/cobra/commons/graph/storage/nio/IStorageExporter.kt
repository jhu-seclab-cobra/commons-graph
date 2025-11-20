package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import java.nio.file.Path

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
    fun export(dstFile: Path, from: IStorage, predicate: EntityFilter = { true }): Path
}