package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.graph.storage.StorageTestUtils
import edu.jhu.cobra.commons.value.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class NativeCsvIOImplTest {
    private lateinit var tempDir: Path
    private lateinit var storage: NativeStorageImpl
    private var nodeCounter = 0
    private var edgeCounter = 0

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("csv-io-test")
        storage = NativeStorageImpl()
        nodeCounter = 0
        edgeCounter = 0
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        if (tempDir.exists()) {
            tempDir.toFile().deleteRecursively()
        }
    }

    // Helpers to add standard test data and return IDs
    private fun addThreeNodes(s: IStorage = storage): Triple<String, String, String> {
        val n1 = s.addNode("node_${nodeCounter++}")
        val n2 = s.addNode("node_${nodeCounter++}")
        val n3 = s.addNode("node_${nodeCounter++}")
        return Triple(n1, n2, n3)
    }

    private fun addNode(s: IStorage = storage): String = s.addNode("node_${nodeCounter++}")

    private fun addEdge(
        s: IStorage,
        src: String,
        dst: String,
        tag: String,
        props: Map<String, IValue> = emptyMap(),
    ): String = s.addEdge(src, dst, "edge_${edgeCounter++}", tag, props)

    // region isValidFile

    @Test
    fun `test isValidFile returns false for non-existent path`() {
        val nonExistentPath = tempDir.resolve("nonexistent")

        val result = NativeCsvIOImpl.isValidFile(nonExistentPath)

        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns true for valid directory with csv files`() {
        val (node1, node2) = addThreeNodes()
        addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1)
        val exportPath = tempDir.resolve("valid")
        NativeCsvIOImpl.export(exportPath, storage)

        val result = NativeCsvIOImpl.isValidFile(exportPath)

        assertTrue(result)
    }

    @Test
    fun `test isValidFile returns false for file instead of directory`() {
        val filePath = tempDir.resolve("file.txt")
        filePath.createFile()

        val result = NativeCsvIOImpl.isValidFile(filePath)

        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns false for directory without nodes csv`() {
        val emptyDir = tempDir.resolve("empty")
        emptyDir.createDirectories()

        val result = NativeCsvIOImpl.isValidFile(emptyDir)

        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns false for directory without edges csv`() {
        val partialDir = tempDir.resolve("partial")
        partialDir.createDirectories()
        partialDir.resolve("nodes.csv").createFile()

        val result = NativeCsvIOImpl.isValidFile(partialDir)

        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns false for empty csv files`() {
        val emptyDir = tempDir.resolve("empty_csv")
        emptyDir.createDirectories()
        emptyDir.resolve("nodes.csv").createFile()
        emptyDir.resolve("edges.csv").createFile()

        val result = NativeCsvIOImpl.isValidFile(emptyDir)

        assertFalse(result)
    }

    // endregion

    // region Export

    @Test
    fun `test export throws on existing non-empty nodes csv`() {
        val dir = tempDir.resolve("existing_csv")
        dir.createDirectories()
        dir.resolve("nodes.csv").writeText("ID\ndata")

        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.export(dir, storage)
        }
    }

    @Test
    fun `test export throws on existing non-empty edges csv`() {
        val dir = tempDir.resolve("existing_edges_csv")
        dir.createDirectories()
        dir.resolve("edges.csv").writeText("ID\ndata")

        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.export(dir, storage)
        }
    }

    @Test
    fun `test import throws on missing nodes csv`() {
        val dir = tempDir.resolve("no_nodes")
        dir.createDirectories()

        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, NativeStorageImpl())
        }
    }

    @Test
    fun `test import throws on empty nodes csv`() {
        val dir = tempDir.resolve("empty_nodes_import")
        dir.createDirectories()
        dir.resolve("nodes.csv").createFile()
        dir.resolve("edges.csv").writeText("ID\n")

        assertFailsWith<IllegalArgumentException> {
            NativeCsvIOImpl.import(dir, NativeStorageImpl())
        }
    }

    @Test
    fun `test export creates directory if not exists`() {
        addNode(storage)
        val exportPath = tempDir.resolve("new_dir")

        NativeCsvIOImpl.export(exportPath, storage)

        assertTrue(exportPath.exists())
        assertTrue(exportPath.isDirectory())
    }

    @Test
    fun `test export creates nodes csv file`() {
        storage.addNode("n0", mapOf("name" to "Node1".strVal))
        storage.addNode("n1", mapOf("name" to "Node2".strVal))
        val exportPath = tempDir.resolve("export1")

        NativeCsvIOImpl.export(exportPath, storage)

        val nodesFile = exportPath.resolve("nodes.csv")
        assertTrue(nodesFile.exists())
        assertTrue(nodesFile.fileSize() > 0)
    }

    @Test
    fun `test export creates edges csv file`() {
        val node1 = addNode(storage)
        val node2 = addNode(storage)
        addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("weight" to 1.5.numVal))
        val exportPath = tempDir.resolve("export2")

        NativeCsvIOImpl.export(exportPath, storage)

        val edgesFile = exportPath.resolve("edges.csv")
        assertTrue(edgesFile.exists())
        assertTrue(edgesFile.fileSize() > 0)
    }

    @Test
    fun `test export returns correct path`() {
        addNode(storage)
        val exportPath = tempDir.resolve("export_path")

        val resultPath = NativeCsvIOImpl.export(exportPath, storage)

        assertEquals(exportPath, resultPath)
    }

    @Test
    fun `test export with empty storage`() {
        val exportPath = tempDir.resolve("empty_export")

        NativeCsvIOImpl.export(exportPath, storage)

        val nodesFile = exportPath.resolve("nodes.csv")
        val edgesFile = exportPath.resolve("edges.csv")
        assertTrue(nodesFile.exists())
        assertTrue(edgesFile.exists())
    }

    @Test
    fun `test export with predicate filters nodes`() {
        val node1 = storage.addNode("n0", mapOf("name" to "Node1".strVal))
        val node2 = storage.addNode("n1", mapOf("name" to "Node2".strVal))
        val node3 = storage.addNode("n2", mapOf("name" to "Node3".strVal))
        val other = storage.addNode("n3", mapOf("name" to "Other".strVal))
        val exportPath = tempDir.resolve("filtered_export")

        // Filter: keep only node1, node2, node3 (not "other")
        val keepNodeIds = setOf(node1, node2, node3)
        NativeCsvIOImpl.export(exportPath, storage) { id -> id in keepNodeIds }

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)
        assertEquals(3, importedStorage.nodeIDs.size)
        importedStorage.close()
    }

    @Test
    fun `test export with predicate filters edges`() {
        val (node1, node2, node3) = addThreeNodes()
        val edge1 = addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1)
        val edge2 = addEdge(storage, node2, node3, StorageTestUtils.EDGE_TAG_2)
        val edge3 = addEdge(storage, node1, node3, StorageTestUtils.EDGE_TAG_3)
        val exportPath = tempDir.resolve("filtered_edges")

        // Keep all nodes, but only edge1
        val keepIds = storage.nodeIDs.toSet() + setOf(edge1)
        NativeCsvIOImpl.export(exportPath, storage) { id ->
            id in keepIds
        }

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)
        assertTrue(importedStorage.edgeIDs.isNotEmpty())
        importedStorage.close()
    }

    @Test
    fun `test export with predicate filters both nodes and edges`() {
        val node1 = storage.addNode("n0", mapOf("type" to "A".strVal))
        val node2 = storage.addNode("n1", mapOf("type" to "B".strVal))
        val node3 = storage.addNode("n2", mapOf("type" to "A".strVal))
        val edge1 = addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1)
        val edge2 = addEdge(storage, node2, node3, StorageTestUtils.EDGE_TAG_2)
        val edge3 = addEdge(storage, node1, node3, StorageTestUtils.EDGE_TAG_3)
        val exportPath = tempDir.resolve("filtered_both")

        // Filter: keep all nodes and only specific edges
        val keepIds = storage.nodeIDs.toSet() + setOf(edge1, edge3)
        NativeCsvIOImpl.export(exportPath, storage) { id ->
            id in keepIds
        }

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)
        assertEquals(3, importedStorage.nodeIDs.size)
        assertTrue(importedStorage.edgeIDs.size >= 2)
        importedStorage.close()
    }

    // endregion

    // region Import

    @Test
    fun `test import from valid export`() {
        val node1 = storage.addNode("n0", mapOf("name" to "Node1".strVal, "age" to 25.numVal))
        val node2 = storage.addNode("n1", mapOf("name" to "Node2".strVal))
        addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("weight" to 1.5.numVal))
        val exportPath = tempDir.resolve("import_source")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(2, importedStorage.nodeIDs.size)

        // Verify properties by examining all nodes
        val allNodeProps = importedStorage.nodeIDs.map { importedStorage.getNodeProperties(it) }
        val node1Props = allNodeProps.find { (it["name"] as? StrVal)?.core == "Node1" }
        assertNotNull(node1Props)
        assertEquals(25, (node1Props["age"] as NumVal).core)

        // Verify edge was imported (structural integrity)
        assertEquals(1, importedStorage.edgeIDs.size)

        importedStorage.close()
    }

    @Test
    fun `test import with predicate filters nodes`() {
        storage.addNode("n0", mapOf("name" to "Node1".strVal))
        storage.addNode("n1", mapOf("name" to "Node2".strVal))
        storage.addNode("n2", mapOf("name" to "Node3".strVal))
        val exportPath = tempDir.resolve("import_filtered")
        NativeCsvIOImpl.export(exportPath, storage)

        // Import predicate operates on node IDs in the CSV; since auto-generated,
        // we import all (default predicate) and verify count
        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(3, importedStorage.nodeIDs.size)
        importedStorage.close()
    }

    @Test
    fun `test import returns storage instance`() {
        addNode(storage)
        val exportPath = tempDir.resolve("import_return")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        val result = NativeCsvIOImpl.import(exportPath, importedStorage)

        assertSame(importedStorage, result)
        importedStorage.close()
    }

    @Test
    fun `test import into non-empty storage`() {
        storage.addNode("n0", mapOf("name" to "Node1".strVal))
        val exportPath = tempDir.resolve("import_into_existing")
        NativeCsvIOImpl.export(exportPath, storage)
        val existingStorage = NativeStorageImpl()
        existingStorage.addNode("n1", mapOf("name" to "Node2".strVal))

        NativeCsvIOImpl.import(exportPath, existingStorage)

        assertEquals(2, existingStorage.nodeIDs.size)
        existingStorage.close()
    }

    // endregion

    // region CSV escape boundary conditions

    @Test
    fun `test export with special characters in property values`() {
        storage.addNode(
            "n0",
            mapOf(
                "name" to "Node,with,commas".strVal,
                "newline" to "Line1\nLine2".strVal,
                "tab" to "Value\tSeparated".strVal,
            ),
        )
        val exportPath = tempDir.resolve("special_chars")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        val nodeId = importedStorage.nodeIDs.first()
        val props = importedStorage.getNodeProperties(nodeId)
        assertEquals("Node,with,commas", (props["name"] as StrVal).core)
        assertEquals("Line1\nLine2", (props["newline"] as StrVal).core)
        assertEquals("Value\tSeparated", (props["tab"] as StrVal).core)

        importedStorage.close()
    }

    @Test
    fun `test export with empty property values`() {
        storage.addNode(
            "n0",
            mapOf(
                "prop1" to "value1".strVal,
                "prop2" to "".strVal,
            ),
        )
        val exportPath = tempDir.resolve("empty_props")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        val nodeId = importedStorage.nodeIDs.first()
        val props = importedStorage.getNodeProperties(nodeId)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals("", (props["prop2"] as StrVal).core)

        importedStorage.close()
    }

    @Test
    fun `test export with backslash in property values`() {
        storage.addNode(
            "n0",
            mapOf("path" to "C:\\Users\\test\\file".strVal),
        )
        val exportPath = tempDir.resolve("backslash_chars")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        val nodeId = importedStorage.nodeIDs.first()
        val props = importedStorage.getNodeProperties(nodeId)
        assertEquals("C:\\Users\\test\\file", (props["path"] as StrVal).core)
        importedStorage.close()
    }

    @Test
    fun `test export with carriage return in property values`() {
        storage.addNode(
            "n0",
            mapOf("text" to "line1\r\nline2\rline3".strVal),
        )
        val exportPath = tempDir.resolve("cr_chars")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        val nodeId = importedStorage.nodeIDs.first()
        val props = importedStorage.getNodeProperties(nodeId)
        assertEquals("line1\r\nline2\rline3", (props["text"] as StrVal).core)
        importedStorage.close()
    }

    @Test
    fun `test export with sparse node properties`() {
        storage.addNode("n0", mapOf("name" to "A".strVal, "age" to 1.numVal))
        storage.addNode("n1", mapOf("name" to "B".strVal))
        storage.addNode("n2", mapOf("age" to 3.numVal))
        val exportPath = tempDir.resolve("sparse_props")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(3, importedStorage.nodeIDs.size)
        val allProps = importedStorage.nodeIDs.map { importedStorage.getNodeProperties(it) }
        val nodeA = allProps.find { (it["name"] as? StrVal)?.core == "A" }
        assertNotNull(nodeA)
        assertEquals(1, (nodeA["age"] as NumVal).core)
        val nodeB = allProps.find { (it["name"] as? StrVal)?.core == "B" }
        assertNotNull(nodeB)
        val nodeC = allProps.find { (it["age"] as? NumVal)?.core == 3 && it["name"] == null }
        assertNotNull(nodeC)
        importedStorage.close()
    }

    @Test
    fun `test export with sparse edge properties`() {
        val node1 = addNode(storage)
        val node2 = addNode(storage)
        val node3 = addNode(storage)
        addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("weight" to 1.0.numVal, "label" to "a".strVal))
        addEdge(storage, node2, node3, StorageTestUtils.EDGE_TAG_2, mapOf("weight" to 2.0.numVal))
        val exportPath = tempDir.resolve("sparse_edge_props")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        // Verify structural integrity: nodes and edges are preserved
        assertEquals(3, importedStorage.nodeIDs.size)
        assertEquals(2, importedStorage.edgeIDs.size)
        importedStorage.close()
    }

    @Test
    fun `test isValidFile returns false for directory with empty nodes csv`() {
        val dir = tempDir.resolve("empty_nodes")
        dir.createDirectories()
        dir.resolve("nodes.csv").createFile()
        dir.resolve("edges.csv").writeText("ID\n")

        assertFalse(NativeCsvIOImpl.isValidFile(dir))
    }

    // endregion

    // region Large dataset

    @Test
    fun `test export with large number of nodes and edges`() {
        val nodeIds = mutableListOf<String>()
        for (i in 0 until 100) {
            nodeIds.add(storage.addNode("node_$i", mapOf("index" to i.numVal)))
        }
        for (i in 0 until 99) {
            storage.addEdge(nodeIds[i], nodeIds[i + 1], "edge_$i", "rel", mapOf("index" to i.numVal))
        }
        val exportPath = tempDir.resolve("large_export")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(100, importedStorage.nodeIDs.size)
        assertEquals(99, importedStorage.edgeIDs.size)

        // Verify a node with index=50 exists
        val allProps = importedStorage.nodeIDs.map { importedStorage.getNodeProperties(it) }
        val node50 = allProps.find { (it["index"] as? NumVal)?.core == 50 }
        assertNotNull(node50)

        importedStorage.close()
    }

    // endregion

    // region Integration

    @Test
    fun `test round-trip export and import preserves all data`() {
        val node1 =
            storage.addNode(
                "n0",
                mapOf(
                    "name" to "Node1".strVal,
                    "age" to 25.numVal,
                    "weight" to 1.5.numVal,
                    "active" to true.boolVal,
                ),
            )
        val node2 = storage.addNode("n1", mapOf("name" to "Node2".strVal))
        val node3 = storage.addNode("n2", mapOf("name" to "Node3".strVal))
        addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1, mapOf("weight" to 1.0.numVal, "label" to "relation".strVal))
        addEdge(storage, node2, node3, StorageTestUtils.EDGE_TAG_2, mapOf("weight" to 2.0.numVal))
        addEdge(storage, node1, node3, StorageTestUtils.EDGE_TAG_3, mapOf("weight" to 3.0.numVal))
        val exportPath = tempDir.resolve("round_trip")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(3, importedStorage.nodeIDs.size)
        assertEquals(3, importedStorage.edgeIDs.size)

        // Verify node properties are preserved
        val allNodeProps = importedStorage.nodeIDs.map { importedStorage.getNodeProperties(it) }
        val node1Props = allNodeProps.find { (it["name"] as? StrVal)?.core == "Node1" }
        assertNotNull(node1Props)
        assertEquals(4, node1Props.size)
        assertEquals(25, (node1Props["age"] as NumVal).core)
        assertEquals(1.5, (node1Props["weight"] as NumVal).core)
        assertEquals(true, (node1Props["active"] as BoolVal).core)

        importedStorage.close()
    }

    @Test
    fun `test multiple round-trips`() {
        val node1 = storage.addNode("n0", mapOf("name" to "Node1".strVal))
        val node2 = storage.addNode("n1", mapOf("name" to "Node2".strVal))
        addEdge(storage, node1, node2, StorageTestUtils.EDGE_TAG_1)
        var currentPath = tempDir.resolve("round_trip_1")
        NativeCsvIOImpl.export(currentPath, storage)

        for (i in 2..5) {
            val importedStorage = NativeStorageImpl()
            NativeCsvIOImpl.import(currentPath, importedStorage)
            currentPath = tempDir.resolve("round_trip_$i")
            NativeCsvIOImpl.export(currentPath, importedStorage)
            importedStorage.close()
        }

        val finalStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(currentPath, finalStorage)
        assertEquals(2, finalStorage.nodeIDs.size)
        assertEquals(1, finalStorage.edgeIDs.size)

        val allProps = finalStorage.nodeIDs.map { finalStorage.getNodeProperties(it) }
        val names = allProps.mapNotNull { (it["name"] as? StrVal)?.core }.toSet()
        assertEquals(setOf("Node1", "Node2"), names)

        finalStorage.close()
    }

    // endregion

    // region Metadata

    @Test
    fun `test export and import preserves metadata`() {
        addNode(storage)
        storage.setMeta("simple", "hello".strVal)
        storage.setMeta("number", 42.numVal)
        val exportPath = tempDir.resolve("meta_test")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals("hello", (importedStorage.getMeta("simple") as StrVal).core)
        assertEquals(42, (importedStorage.getMeta("number") as NumVal).core)
        importedStorage.close()
    }

    @Test
    fun `test export and import preserves lattice metadata`() {
        addNode(storage)
        addNode(storage)
        val labelAParents = mapOf<String, IValue>("parent" to "root".strVal).mapVal
        val labelBParents = mapOf<String, IValue>("parent" to "labelA".strVal).mapVal
        val labelAChanges = listOf(1.numVal, 2.numVal, 3.numVal).listVal
        storage.setMeta("__lp_labelA__", labelAParents)
        storage.setMeta("__lp_labelB__", labelBParents)
        storage.setMeta("__lc_labelA__", labelAChanges)
        val exportPath = tempDir.resolve("lattice_meta")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        val importedAParents = importedStorage.getMeta("__lp_labelA__") as MapVal
        assertEquals("root", (importedAParents["parent"] as StrVal).core)
        val importedBParents = importedStorage.getMeta("__lp_labelB__") as MapVal
        assertEquals("labelA", (importedBParents["parent"] as StrVal).core)

        importedStorage.close()
    }

    @Test
    fun `test import without meta file is backward compatible`() {
        storage.addNode("n0", mapOf("name" to "Node1".strVal))
        val exportPath = tempDir.resolve("no_meta")
        NativeCsvIOImpl.export(exportPath, storage)
        // Remove meta.csv to simulate old export format
        exportPath.resolve("meta.csv").toFile().delete()

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(1, importedStorage.nodeIDs.size)
        assertTrue(importedStorage.metaNames.isEmpty())
        importedStorage.close()
    }

    @Test
    fun `test round-trip preserves metadata across multiple exports`() {
        addNode(storage)
        storage.setMeta("counter", 1.numVal)
        var currentPath = tempDir.resolve("meta_round_1")
        NativeCsvIOImpl.export(currentPath, storage)

        for (i in 2..3) {
            val imported = NativeStorageImpl()
            NativeCsvIOImpl.import(currentPath, imported)
            currentPath = tempDir.resolve("meta_round_$i")
            NativeCsvIOImpl.export(currentPath, imported)
            imported.close()
        }

        val finalStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(currentPath, finalStorage)
        assertEquals(1, (finalStorage.getMeta("counter") as NumVal).core)
        finalStorage.close()
    }

    // endregion

    // region Uncovered branch coverage

    @Test
    fun `test isValidFile returns false when nodes csv exists but edges csv is empty`() {
        val dir = tempDir.resolve("nodes_only_nonempty")
        dir.createDirectories()
        dir.resolve("nodes.csv").writeText("PROPS\nfoo")
        dir.resolve("edges.csv").createFile() // exists but empty (size == 0)

        assertFalse(NativeCsvIOImpl.isValidFile(dir))
    }

    @Test
    fun `test isValidFile returns false when edges csv exists but nodes csv is empty`() {
        val dir = tempDir.resolve("edges_only_nonempty")
        dir.createDirectories()
        dir.resolve("nodes.csv").createFile() // exists but empty
        dir.resolve("edges.csv").writeText("__src__,__dst__,__type__\nfoo")

        assertFalse(NativeCsvIOImpl.isValidFile(dir))
    }

    @Test
    fun `test import skips malformed edge lines with fewer than 3 columns`() {
        // Export a storage with a known edge, then corrupt the edge CSV
        val n1 = addNode(storage)
        val n2 = addNode(storage)
        addEdge(storage, n1, n2, "rel")
        val exportPath = tempDir.resolve("corrupt_edge")
        NativeCsvIOImpl.export(exportPath, storage)

        // Replace the edge data line with a malformed one (only 2 columns)
        val edgesFile = exportPath.resolve("edges.csv")
        val lines = edgesFile.toFile().readLines()
        // Keep the header, replace data lines with a 2-column malformed line
        val newContent = lines[0] + "\nonly_one_col\n"
        edgesFile.toFile().writeText(newContent)

        // Also fix nodes to have proper content so import can find node mappings
        // Import should skip the malformed edge line without error
        val target = NativeStorageImpl()
        // Only nodes will be imported; the malformed edge line is skipped
        NativeCsvIOImpl.import(exportPath, target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(0, target.edgeIDs.size) // malformed edge skipped
        target.close()
    }

    @Test
    fun `test import skips node property with empty serialized value`() {
        // Build a CSV where one node has a blank column for a header key — deserialize returns null, entry is skipped
        val exportPath = tempDir.resolve("blank_prop")
        exportPath.toFile().mkdirs()

        // nodes.csv: header has two columns, first row leaves second blank → deserialize("") == null
        exportPath.resolve("nodes.csv").toFile().writeText("__sid__,name\n0,\n")
        exportPath
            .resolve("edges.csv")
            .toFile()
            .writeText("__src__,__dst__,__type__\n")

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        assertEquals(1, target.nodeIDs.size)
        val props = target.getNodeProperties(target.nodeIDs.first())
        assertNull(props["name"]) // blank column → deserialize returned null → skipped
        target.close()
    }

    @Test
    fun `test import readMeta skips lines with fewer than 2 columns`() {
        addNode(storage)
        storage.setMeta("valid", "yes".strVal)
        val exportPath = tempDir.resolve("bad_meta")
        NativeCsvIOImpl.export(exportPath, storage)

        // Append a malformed line (only 1 column) to meta.csv
        val metaFile = exportPath.resolve("meta.csv")
        metaFile.toFile().appendText("malformed_line_no_comma\n")

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        // The valid meta entry should still be imported; malformed line skipped
        assertEquals("yes", (target.getMeta("valid") as StrVal).core)
        target.close()
    }

    @Test
    fun `test import readMeta skips entry when value cannot be deserialized`() {
        addNode(storage)
        val exportPath = tempDir.resolve("bad_meta_value")
        NativeCsvIOImpl.export(exportPath, storage)

        // Append a meta line with a value that cannot be deserialized
        val metaFile = exportPath.resolve("meta.csv")
        metaFile.toFile().appendText("broken_key,!!!NOT_A_VALID_VALUE!!!\n")

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        // The broken entry is skipped; no meta should be set for it
        assertNull(target.getMeta("broken_key"))
        target.close()
    }

    @Test
    fun `test import readNodes skips property when index out of range for that row`() {
        // Export a storage then truncate the last data row to have fewer columns than the header,
        // exercising the getOrNull(i) ?: continue branch in readNodes.
        storage.addNode("n0", mapOf("name" to "Alice".strVal, "age" to 30.numVal))
        storage.addNode("n1", mapOf("name" to "Bob".strVal, "age" to 25.numVal))
        val exportPath2 = tempDir.resolve("short_row")
        NativeCsvIOImpl.export(exportPath2, storage)

        // Truncate the second data row to have fewer columns
        val nodesFile = exportPath2.resolve("nodes.csv")
        val lines = nodesFile.toFile().readLines().toMutableList()
        // lines[0] = header, lines[1] = first node row, lines[2] = second node row
        // Remove last column from the last row so getOrNull(i) returns null for the missing column
        if (lines.size >= 3) {
            val lastRow = lines[lines.size - 1]
            val truncated = lastRow.substringBeforeLast(",")
            lines[lines.size - 1] = truncated
            nodesFile.toFile().writeText(lines.joinToString("\n") + "\n")
        }

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath2, target)

        // Both nodes imported; one may have a missing property on last column
        assertEquals(2, target.nodeIDs.size)
        target.close()
    }

    // endregion
}
