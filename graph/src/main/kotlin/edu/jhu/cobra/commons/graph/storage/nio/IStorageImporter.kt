package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import java.nio.file.Path

interface IStorageImporter {
    /**
     * Checks if a given file is valid.
     *
     * @param file The path to the file to be checked.
     * @return true if the file is valid, false otherwise.
     */
    fun isValidFile(file: Path): Boolean

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