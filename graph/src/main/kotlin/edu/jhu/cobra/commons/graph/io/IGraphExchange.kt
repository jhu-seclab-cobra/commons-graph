package edu.jhu.cobra.commons.graph.io

import edu.jhu.cobra.commons.graph.storage.IStorage
import java.nio.file.Path

/**
 * The `IGraphExchange` interface provides methods for importing and exporting graph data between different storage implementations.
 * It also includes a method to validate the file before importing it.
 *
 * To use `IGraphExchange`, implement this interface and provide the necessary logic for each method.
 *
 * @see IStorage
 */
interface IGraphExchange {
    // storage
    // transfer

    /**
     * Checks if a given file is valid.
     *
     * @param file The path to the file to be checked.
     * @return true if the file is valid, false otherwise.
     */
    fun isValidFile(file: Path): Boolean

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

    /**
     * Imports entities from a source file into the specified storage.
     *
     * @param srcFile The path to the source file to import entities from.
     * @param into The storage to import the entities into.
     * @param predicate An optional filter predicate to selectively import entities. Defaults to importing all entities.
     * @return The storage with the imported entities.
     */
    fun import(srcFile: Path, into: IStorage, predicate: EntityFilter = { true }): IStorage

}