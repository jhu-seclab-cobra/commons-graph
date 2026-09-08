package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Black-box tests for NativeCsvIOImpl: import guards and sparse-row handling.
 *
 * - `import skips node rows with fewer columns than headers` -- missing property cells are sparse, not errors
 * - `import throws when edge row has fewer than four structural columns` -- foreign edge row rejected
 * - `import throws when node row lacks node ID` -- blank line no longer imports a phantom node
 * - `import from directory with missing nodes csv throws` -- missing nodes.csv guard
 * - `import from directory with missing edges csv throws` -- missing edges.csv guard
 * - `import succeeds when meta csv is absent` -- optional meta.csv
 * - `import skips node property when deserialized value is null` -- null property skip in readNodes
 * - `import throws when edge row in exported file is truncated` -- corrupted edge row rejected
 * - `import skips edge property when deserialized value is null` -- null property skip in readEdges
 * - `import throws when meta row lacks value` -- foreign meta row rejected
 * - `import succeeds when meta file does not exist` -- metaFile.exists() false path in readMeta
 */
internal class NativeCsvIOImplImportTest {
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
    // Malformed CSV import
    // ========================================================================
    @Test
    fun `import skips node rows with fewer columns than headers`() {
        val src = NativeStorageImpl()
        src.addNode(mapOf("name" to "Alice".strVal))
        src.addNode(mapOf("name" to "Bob".strVal))
        src.addNode(mapOf("name" to "Carol".strVal))

        val dir = tempDir.resolve("malformed_nodes").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        val nodesFile = dir.resolve("nodes.csv").toFile()
        val lines = nodesFile.readLines().toMutableList()
        if (lines.size >= 3) {
            lines[2] = lines[2].substringBefore(",")
        }
        nodesFile.writeText(lines.joinToString("\n"))

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(3, target.nodeIDs.size)
    }

    @Test
    fun `import throws when edge row has fewer than four structural columns`() {
        val dir = tempDir.resolve("malformed_edges").createDirectories()
        dir.resolve("nodes.csv").writeText("__nid__\n0\n1\n")
        dir.resolve("edges.csv").writeText("__eid__,__src__,__dst__,__tag__\n0,0,1,rel\nBAD,0\n1,1,0,back\n")
        dir.resolve("meta.csv").writeText("name,value\n")

        val target = NativeStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, target)
        }
    }

    @Test
    fun `import throws when node row lacks node ID`() {
        val dir = tempDir.resolve("empty_lines").createDirectories()
        dir.resolve("nodes.csv").writeText("__nid__\n0\n\n1\n")
        dir.resolve("edges.csv").writeText("__eid__,__src__,__dst__,__tag__\n")
        dir.resolve("meta.csv").writeText("name,value\n")

        val target = NativeStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, target)
        }
    }

    // ========================================================================
    // Missing / empty files
    // ========================================================================
    @Test
    fun `import from directory with missing nodes csv throws`() {
        val dir = tempDir.resolve("no_nodes").createDirectories()
        dir.resolve("edges.csv").writeText("__eid__,__src__,__dst__,__tag__\n")

        val target = NativeStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, target)
        }
    }

    @Test
    fun `import from directory with missing edges csv throws`() {
        val dir = tempDir.resolve("no_edges").createDirectories()
        dir.resolve("nodes.csv").writeText("__nid__\n0\n")

        val target = NativeStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, target)
        }
    }

    @Test
    fun `import succeeds when meta csv is absent`() {
        val dir = tempDir.resolve("no_meta").createDirectories()
        dir.resolve("nodes.csv").writeText("__nid__\n0\n")
        dir.resolve("edges.csv").writeText("__eid__,__src__,__dst__,__tag__\n")

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(1, target.nodeIDs.size)
        assertTrue(target.metaNames.isEmpty())
    }

    // ========================================================================
    // CsvReader branch coverage — malformed data via file manipulation
    // ========================================================================
    @Test
    fun `import skips node property when deserialized value is null`() {
        val src = NativeStorageImpl()
        src.addNode(mapOf("name" to "Alice".strVal, "age" to 30.intVal))
        src.addNode(mapOf("name" to "Bob".strVal, "age" to 25.intVal))

        val dir = tempDir.resolve("null_node_prop").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        // Corrupt one property cell to empty string (deserializes to null)
        val nodesFile = dir.resolve("nodes.csv").toFile()
        val lines = nodesFile.readLines().toMutableList()
        // Line 0 = header, lines 1+ = data rows. Replace the last column with empty.
        val parts = lines[1].split(",").toMutableList()
        parts[parts.lastIndex] = ""
        lines[1] = parts.joinToString(",")
        nodesFile.writeText(lines.joinToString("\n"))

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(2, target.nodeIDs.size)
        // One node should have one fewer property
        val propCounts = target.nodeIDs.map { target.getNodeProperties(it).size }.sorted()
        assertEquals(1, propCounts[0])
        assertEquals(2, propCounts[1])
    }

    @Test
    fun `import throws when edge row in exported file is truncated`() {
        val src = NativeStorageImpl()
        val n1 = src.addNode()
        val n2 = src.addNode()
        src.addEdge(n1, n2, "rel1")
        src.addEdge(n2, n1, "rel2")

        val dir = tempDir.resolve("short_edge_row").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        // Replace one edge data row with a row that has fewer than 4 columns
        val edgesFile = dir.resolve("edges.csv").toFile()
        val lines = edgesFile.readLines().toMutableList()
        // Line 0 = header, line 1 = first edge, line 2 = second edge
        lines[1] = "BAD,0"
        edgesFile.writeText(lines.joinToString("\n"))

        val target = NativeStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, target)
        }
    }

    @Test
    fun `import skips edge property when deserialized value is null`() {
        val src = NativeStorageImpl()
        val n1 = src.addNode()
        val n2 = src.addNode()
        src.addEdge(n1, n2, "rel", mapOf("weight" to 5.intVal))

        val dir = tempDir.resolve("null_edge_prop").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        // Corrupt the edge property cell to empty string
        val edgesFile = dir.resolve("edges.csv").toFile()
        val lines = edgesFile.readLines().toMutableList()
        // Line 0 = header, line 1 = edge data. Last column is the property.
        val parts = lines[1].split(",").toMutableList()
        parts[parts.lastIndex] = ""
        lines[1] = parts.joinToString(",")
        edgesFile.writeText(lines.joinToString("\n"))

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        val edgeProps = target.getEdgeProperties(target.edgeIDs.first())
        assertFalse(edgeProps.containsKey("weight"))
    }

    @Test
    fun `import throws when meta row lacks value`() {
        val src = NativeStorageImpl()
        src.addNode()
        src.setMeta("version", "1.0".strVal)
        src.setMeta("flag", true.boolVal)

        val dir = tempDir.resolve("short_meta_row").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        // Replace one meta data row with a row that has only one column
        val metaFile = dir.resolve("meta.csv").toFile()
        val lines = metaFile.readLines().toMutableList()
        // Line 0 = header, lines 1+ = data. Replace one row.
        lines[1] = "orphan_key_no_value"
        metaFile.writeText(lines.joinToString("\n"))

        val target = NativeStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, target)
        }
    }

    @Test
    fun `import succeeds when meta file does not exist`() {
        val src = NativeStorageImpl()
        src.addNode(mapOf("name" to "Alice".strVal))

        val dir = tempDir.resolve("deleted_meta").createDirectories()
        NativeCsvIOImpl.export(dir, src)

        // Delete the meta.csv file
        dir.resolve("meta.csv").toFile().delete()

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target)

        assertEquals(1, target.nodeIDs.size)
        assertTrue(target.metaNames.isEmpty())
    }
}
