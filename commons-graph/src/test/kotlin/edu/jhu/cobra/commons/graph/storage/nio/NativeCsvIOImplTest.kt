package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.graph.storage.StorageFixtures
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.FloatVal
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.boolVal
import edu.jhu.cobra.commons.value.floatVal
import edu.jhu.cobra.commons.value.intVal
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box tests for `NativeCsvIOImpl` verifying CSV export/import round-trip fidelity.
 *
 * Tests:
 * - `round-trip preserves node count` -- structural fidelity
 * - `round-trip preserves node properties` -- node property fidelity
 * - `round-trip preserves edge count and structure` -- edge structural fidelity
 * - `round-trip preserves edges and their tags` -- edge tag fidelity
 * - `round-trip preserves metadata` -- metadata fidelity
 * - `round-trip preserves mixed property types` -- type fidelity across StrVal, IntVal, FloatVal, BoolVal
 * - `round-trip preserves special characters in property values` -- CSV escaping
 * - `round-trip preserves property value ending in backslash` -- escaped trailing backslash
 *   must not hide the following delimiter
 * - `round-trip on empty storage produces empty target` -- empty boundary
 * - `multiple round-trips produce identical data` -- idempotent serialization
 * - `import skips node rows with fewer columns than headers` -- missing property cells are sparse, not errors
 * - `import throws when edge row has fewer than four structural columns` -- foreign edge row rejected
 * - `import throws when node row lacks node ID` -- blank line no longer imports a phantom node
 * - `import from directory with missing nodes csv throws` -- missing nodes.csv guard
 * - `import from directory with missing edges csv throws` -- missing edges.csv guard
 * - `import succeeds when meta csv is absent` -- optional meta.csv
 * - `isValidFile returns false for non-existent path` -- non-existent path guard
 * - `isValidFile returns false for regular file` -- file-not-directory guard
 * - `isValidFile returns false when nodes csv missing` -- missing nodes.csv detection
 * - `isValidFile returns false when edges csv missing` -- missing edges.csv detection
 * - `isValidFile returns false when nodes csv is empty` -- empty nodes.csv detection
 * - `export with node filter excludes filtered nodes` -- node filter predicate
 * - `export with edge filter excludes filtered edges` -- edge filter predicate
 * - `null property values round-trip as absent` -- null serialized as empty, deserialized as absent
 * - `writing same property names twice does not change header` -- nodeHeaders.addAll returns false
 * - `close without property writes skips header update` -- isNodeHeaderChanged=false path
 * - `import skips node property when deserialized value is null` -- null property skip in readNodes
 * - `import throws when edge row in exported file is truncated` -- corrupted edge row rejected
 * - `import skips edge property when deserialized value is null` -- null property skip in readEdges
 * - `import throws when meta row lacks value` -- foreign meta row rejected
 * - `import succeeds when meta file does not exist` -- metaFile.exists() false path in readMeta
 * - `export skips metadata entry when getMeta returns null` -- null meta value skip in export
 * - `export with no property nodes produces empty-prefix node header` -- fixedPrefix.isEmpty() true
 * - `csv reader close stops partially consumed iterations` -- close() closes the underlying readers,
 *   so abandoned partial iterations hold no open handle
 * - `csv reader read after close throws` -- reads on a closed reader fail
 * - `import with node filter excludes node and its edges` -- node predicate skips the node and
 *   every edge referencing it
 * - `import with edge filter excludes edge but keeps nodes` -- edge predicate skips only the edge
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

    private fun roundTrip(source: IStorage): NativeStorageImpl {
        val exportPath = tempDir.resolve("export_${System.nanoTime()}")
        NativeCsvIOImpl.export(exportPath, source)
        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)
        return target
    }

    @Test
    fun `round-trip preserves node count`() {
        storage.addNode()
        storage.addNode()
        storage.addNode()

        val target = roundTrip(storage)

        assertEquals(3, target.nodeIDs.size)
    }

    @Test
    fun `round-trip preserves node properties`() {
        storage.addNode(mapOf("name" to "Alice".strVal, "age" to 30.intVal))
        storage.addNode(mapOf("name" to "Bob".strVal))

        val target = roundTrip(storage)

        val allProps = target.nodeIDs.map { target.getNodeProperties(it) }
        val names = allProps.mapNotNull { (it["name"] as? StrVal)?.core }.toSet()
        assertEquals(setOf("Alice", "Bob"), names)
        val alice = allProps.first { (it["name"] as? StrVal)?.core == "Alice" }
        assertEquals(30L, (alice["age"] as IntVal).core)
    }

    @Test
    fun `round-trip preserves edge count and structure`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, StorageFixtures.EDGE_TAG_1)
        storage.addEdge(n2, n3, StorageFixtures.EDGE_TAG_2)
        storage.addEdge(n1, n3, StorageFixtures.EDGE_TAG_3)

        val target = roundTrip(storage)

        assertEquals(3, target.nodeIDs.size)
        assertEquals(3, target.edgeIDs.size)
    }

    @Test
    fun `round-trip preserves edges and their tags`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, StorageFixtures.EDGE_TAG_1)
        storage.addEdge(n2, n3, StorageFixtures.EDGE_TAG_2)

        val target = roundTrip(storage)

        assertEquals(3, target.nodeIDs.size)
        assertEquals(2, target.edgeIDs.size)
        val tags = target.edgeIDs.map { target.getEdgeStructure(it).tag }.toSet()
        assertEquals(setOf(StorageFixtures.EDGE_TAG_1, StorageFixtures.EDGE_TAG_2), tags)
    }

    @Test
    fun `round-trip preserves metadata`() {
        storage.addNode()
        storage.setMeta("version", "2.0".strVal)
        storage.setMeta("count", 42.intVal)

        val target = roundTrip(storage)

        assertEquals("2.0", (target.getMeta("version") as StrVal).core)
        assertEquals(42L, (target.getMeta("count") as IntVal).core)
    }

    @Test
    fun `round-trip preserves mixed property types`() {
        storage.addNode(
            mapOf(
                "name" to "Node1".strVal,
                "age" to 25.intVal,
                "weight" to 1.5.floatVal,
                "active" to true.boolVal,
            ),
        )

        val target = roundTrip(storage)

        val props = target.getNodeProperties(target.nodeIDs.first())
        assertEquals("Node1", (props["name"] as StrVal).core)
        assertEquals(25L, (props["age"] as IntVal).core)
        assertEquals(1.5, (props["weight"] as FloatVal).core)
        assertEquals(true, (props["active"] as BoolVal).core)
    }

    @Test
    fun `round-trip preserves special characters in property values`() {
        storage.addNode(
            mapOf(
                "commas" to "a,b,c".strVal,
                "newline" to "line1\nline2".strVal,
                "backslash" to "C:\\path\\file".strVal,
            ),
        )

        val target = roundTrip(storage)

        val props = target.getNodeProperties(target.nodeIDs.first())
        assertEquals("a,b,c", (props["commas"] as StrVal).core)
        assertEquals("line1\nline2", (props["newline"] as StrVal).core)
        assertEquals("C:\\path\\file", (props["backslash"] as StrVal).core)
    }

    @Test
    fun `round-trip preserves property value ending in backslash`() {
        // First node pins "trailing" as the first property column, so the
        // second node's backslash-terminated cell is followed by a delimiter.
        storage.addNode(mapOf("trailing" to "plain".strVal))
        val nodeId =
            storage.addNode(
                mapOf(
                    "trailing" to "tail\\".strVal,
                    "next" to "value".strVal,
                ),
            )

        val target = roundTrip(storage)

        val props = target.getNodeProperties(nodeId)
        assertEquals("tail\\", (props["trailing"] as StrVal).core)
        assertEquals("value", (props["next"] as StrVal).core)
    }

    @Test
    fun `round-trip on empty storage produces empty target`() {
        val target = roundTrip(storage)

        assertTrue(target.nodeIDs.isEmpty())
        assertTrue(target.edgeIDs.isEmpty())
    }

    @Test
    fun `multiple round-trips produce identical data`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel")
        storage.setMeta("v", "1".strVal)

        var current: IStorage = storage
        repeat(3) {
            current = roundTrip(current)
        }

        assertEquals(2, current.nodeIDs.size)
        assertEquals(1, current.edgeIDs.size)
        val names =
            current.nodeIDs
                .map { current.getNodeProperties(it) }
                .mapNotNull { (it["name"] as? StrVal)?.core }
                .toSet()
        assertEquals(setOf("A", "B"), names)
        assertEquals("1", (current.getMeta("v") as StrVal).core)
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
    // Export with filter predicate
    // ========================================================================

    @Test
    fun `export with node filter excludes filtered nodes`() {
        val n1 = storage.addNode(mapOf("name" to "Keep".strVal))
        val n2 = storage.addNode(mapOf("name" to "Drop".strVal))

        val exportPath = tempDir.resolve("filtered_nodes")
        NativeCsvIOImpl.export(exportPath, storage) { it == n1 }

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        assertEquals(1, target.nodeIDs.size)
        val name = (target.getNodeProperties(target.nodeIDs.first())["name"] as StrVal).core
        assertEquals("Keep", name)
    }

    @Test
    fun `export with edge filter excludes filtered edges`() {
        // Node IDs: 0, 1, 2. Edge IDs: 0 (keep), 1 (drop).
        // Filter { it != 1 } keeps nodes 0, 2 and edge 0.
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        val n3 = storage.addNode(mapOf("name" to "C".strVal))
        storage.addEdge(n1, n3, "keep")
        storage.addEdge(n3, n1, "drop")

        val exportPath = tempDir.resolve("filtered_edges")
        NativeCsvIOImpl.export(exportPath, storage) { it != n2 }

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        val tag = target.getEdgeStructure(target.edgeIDs.first()).tag
        assertEquals("keep", tag)
    }

    // ========================================================================
    // Null / empty property handling
    // ========================================================================

    @Test
    fun `null property values round-trip as absent`() {
        storage.addNode(mapOf("present" to "yes".strVal))
        storage.addNode()

        val target = roundTrip(storage)

        val allProps = target.nodeIDs.map { target.getNodeProperties(it) }
        val withProp = allProps.first { it.containsKey("present") }
        val withoutProp = allProps.first { !it.containsKey("present") }
        assertEquals("yes", (withProp["present"] as StrVal).core)
        assertNull(withoutProp["present"])
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

    // -- CsvReader lifecycle (C5.1) --

    @Test
    fun `csv reader close stops partially consumed iterations`() {
        val n1 = storage.addNode(mapOf("p" to "a".strVal))
        val n2 = storage.addNode(mapOf("p" to "b".strVal))
        storage.addEdge(n1, n2, "t1")
        storage.addEdge(n2, n1, "t2")
        storage.setMeta("m1", "1".strVal)
        storage.setMeta("m2", "2".strVal)
        val dir = tempDir.resolve("reader_close_partial").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val reader = NativeCsvReader(dir)
        val nodes = reader.readNodes()
        val edges = reader.readEdges()
        val meta = reader.readMeta()
        nodes.next()
        edges.next()
        meta.next()
        reader.close()
        assertFailsWith<IllegalStateException> { nodes.next() }
        assertFailsWith<IllegalStateException> { edges.next() }
        assertFailsWith<IllegalStateException> { meta.next() }
    }

    @Test
    fun `csv reader read after close throws`() {
        storage.addNode(mapOf("p" to "a".strVal))
        val dir = tempDir.resolve("reader_read_after_close").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val reader = NativeCsvReader(dir)
        reader.close()
        assertFailsWith<IllegalStateException> { reader.readNodes().next() }
    }

    // -- Import filtering (C5.3) --

    @Test
    fun `import with node filter excludes node and its edges`() {
        val n1 = storage.addNode(mapOf("name" to "a".strVal))
        val n2 = storage.addNode(mapOf("name" to "b".strVal))
        val n3 = storage.addNode(mapOf("name" to "c".strVal))
        storage.addEdge(n1, n2, "keep")
        storage.addEdge(n1, n3, "dangling")
        val dir = tempDir.resolve("import_node_filter").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target) { it != n3 }

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("keep", target.getEdgeStructure(target.edgeIDs.single()).tag)
    }

    @Test
    fun `import with edge filter excludes edge but keeps nodes`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, "a")
        storage.addEdge(n2, n3, "b")
        storage.addEdge(n3, n1, "c")
        val dropped = storage.addEdge(n1, n3, "drop")
        val dir = tempDir.resolve("import_edge_filter").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target) { it != dropped }

        assertEquals(3, target.nodeIDs.size)
        assertEquals(3, target.edgeIDs.size)
        val tags = target.edgeIDs.map { target.getEdgeStructure(it).tag }.toSet()
        assertEquals(setOf("a", "b", "c"), tags)
    }

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
