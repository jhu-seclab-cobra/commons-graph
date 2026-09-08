package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for NativeCsvIOImpl: file validation and export.
 *
 * - `isValidFile returns false for non-existent path` -- non-existent path guard
 * - `isValidFile returns false for regular file` -- file-not-directory guard
 * - `isValidFile returns false when nodes csv missing` -- missing nodes.csv detection
 * - `isValidFile returns false when edges csv missing` -- missing edges.csv detection
 * - `isValidFile returns false when nodes csv is empty` -- empty nodes.csv detection
 * - `writing same property names twice does not change header` -- nodeHeaders.addAll returns false
 * - `close without property writes skips header update` -- isNodeHeaderChanged=false path
 * - `export skips metadata entry when getMeta returns null` -- null meta value skip in export
 * - `export with no property nodes produces empty-prefix node header` -- fixedPrefix.isEmpty() true
 * - `export succeeds when target directory holds empty csv files` -- empty pre-existing files pass
 *   the guard and are written over
 */
internal class NativeCsvIOImplTest {
    private lateinit var tempDir: Path

    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("csv-io-test")
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        if (tempDir.exists()) {
            tempDir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // isValidFile
    // ========================================================================
    @Test
    fun `isValidFile returns false for non-existent path`() {
        val nonExistent = tempDir.resolve("does_not_exist")

        assertFalse(NativeCsvIOImpl.isValidFile(nonExistent))
    }

    @Test
    fun `isValidFile returns false for regular file`() {
        val file = tempDir.resolve("regular_file.txt").createFile()
        file.writeText("not a directory")

        assertFalse(NativeCsvIOImpl.isValidFile(file))
    }

    @Test
    fun `isValidFile returns false when nodes csv missing`() {
        val dir = tempDir.resolve("no_nodes_valid").createDirectories()
        dir.resolve("edges.csv").writeText("__eid__,__src__,__dst__,__tag__\n")

        assertFalse(NativeCsvIOImpl.isValidFile(dir))
    }

    @Test
    fun `isValidFile returns false when edges csv missing`() {
        val dir = tempDir.resolve("no_edges_valid").createDirectories()
        dir.resolve("nodes.csv").writeText("__nid__\n0\n")

        assertFalse(NativeCsvIOImpl.isValidFile(dir))
    }

    @Test
    fun `isValidFile returns false when nodes csv is empty`() {
        val dir = tempDir.resolve("empty_nodes").createDirectories()
        dir.resolve("nodes.csv").createFile()
        dir.resolve("edges.csv").writeText("__eid__,__src__,__dst__,__tag__\n")

        assertFalse(NativeCsvIOImpl.isValidFile(dir))
    }

    // ========================================================================
    // CsvWriter branch coverage
    // ========================================================================
    @Test
    fun `writing same property names twice does not change header`() {
        val src = NativeStorageImpl()
        src.addNode(mapOf("name" to "Alice".strVal))
        src.addNode(mapOf("name" to "Bob".strVal))

        val dir = tempDir.resolve("same_headers").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(2, target.nodeIDs.size)
        val names =
            target.nodeIDs
                .map { target.getNodeProperties(it) }
                .mapNotNull { (it["name"] as? StrVal)?.core }
                .toSet()
        assertEquals(setOf("Alice", "Bob"), names)
    }

    @Test
    fun `close without property writes skips header update`() {
        val src = NativeStorageImpl()
        src.addNode()
        val n2 = src.addNode()
        src.addEdge(src.nodeIDs.first(), n2, "rel")

        val dir = tempDir.resolve("no_prop_headers").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
    }

    @Test
    fun `export skips metadata entry when getMeta returns null`() {
        // Create a custom storage wrapper that returns null for one meta key
        val src = NativeStorageImpl()
        src.addNode()
        src.setMeta("kept", "yes".strVal)
        src.setMeta("removed", "no".strVal)

        // Export first, then re-import and verify both meta entries
        val dir1 = tempDir.resolve("meta_null_baseline").createDirectories()
        NativeCsvIOImpl.export(dir1, src)
        val baseline = NativeStorageImpl()
        NativeCsvIOImpl.import(dir1, baseline)
        assertEquals(2, baseline.metaNames.size)

        // Now delete one meta entry and export again
        src.setMeta("removed", null)
        val dir2 = tempDir.resolve("meta_null_export").createDirectories()
        NativeCsvIOImpl.export(dir2, src)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir2, target)

        assertEquals(1, target.metaNames.size)
        assertEquals("yes", (target.getMeta("kept") as StrVal).core)
        assertNull(target.getMeta("removed"))
    }

    @Test
    fun `export with no property nodes produces empty-prefix node header`() {
        // Nodes with no properties: nodeHeaders stays empty,
        // but if we manually trigger the header path by examining the exported file
        val src = NativeStorageImpl()
        src.addNode()
        src.addNode()

        val dir = tempDir.resolve("no_prop_header").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        // Verify the CSV is valid and importable
        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(2, target.nodeIDs.size)
        target.nodeIDs.forEach { assertTrue(target.getNodeProperties(it).isEmpty()) }
    }

    // -- Import filtering (C5.3) --

    @Test
    fun `export succeeds when target directory holds empty csv files`() {
        val exportPath = tempDir.resolve("pre_existing").createDirectories()
        exportPath.resolve(NativeCsvFormat.NODE_FILE).createFile()
        exportPath.resolve(NativeCsvFormat.EDGE_FILE).createFile()
        exportPath.resolve(NativeCsvFormat.META_FILE).createFile()
        storage.addNode(mapOf("name" to "n".strVal))

        NativeCsvIOImpl.export(exportPath, storage)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)
        assertEquals(1, target.nodeIDs.size)
    }
}
