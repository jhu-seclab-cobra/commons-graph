package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
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

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2
    private val edge3 = StorageTestUtils.edge3

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("csv-io-test")
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        if (tempDir.exists()) {
            tempDir.toFile().deleteRecursively()
        }
    }

    // region isValidFile

    @Test
    fun `test isValidFile returns false for non-existent path`() {
        val nonExistentPath = tempDir.resolve("nonexistent")

        val result = NativeCsvIOImpl.isValidFile(nonExistentPath)

        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns true for valid directory with csv files`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
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
    fun `test export creates directory if not exists`() {
        storage.addNode(node1)
        val exportPath = tempDir.resolve("new_dir")

        NativeCsvIOImpl.export(exportPath, storage)

        assertTrue(exportPath.exists())
        assertTrue(exportPath.isDirectory())
    }

    @Test
    fun `test export creates nodes csv file`() {
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        val exportPath = tempDir.resolve("export1")

        NativeCsvIOImpl.export(exportPath, storage)

        val nodesFile = exportPath.resolve("nodes.csv")
        assertTrue(nodesFile.exists())
        assertTrue(nodesFile.fileSize() > 0)
    }

    @Test
    fun `test export creates edges csv file`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("weight" to 1.5.numVal))
        val exportPath = tempDir.resolve("export2")

        NativeCsvIOImpl.export(exportPath, storage)

        val edgesFile = exportPath.resolve("edges.csv")
        assertTrue(edgesFile.exists())
        assertTrue(edgesFile.fileSize() > 0)
    }

    @Test
    fun `test export returns correct path`() {
        storage.addNode(node1)
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
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addNode(node3, mapOf("name" to "Node3".strVal))
        storage.addNode(NodeID("other"), mapOf("name" to "Other".strVal))
        val exportPath = tempDir.resolve("filtered_export")

        NativeCsvIOImpl.export(exportPath, storage) { id ->
            id is NodeID && id.asString.startsWith("node")
        }

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)
        assertEquals(3, importedStorage.nodeIDs.size)
        assertTrue(importedStorage.containsNode(node1))
        assertTrue(importedStorage.containsNode(node2))
        assertTrue(importedStorage.containsNode(node3))
        assertFalse(importedStorage.containsNode(NodeID("other")))
        importedStorage.close()
    }

    @Test
    fun `test export with predicate filters edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)
        val exportPath = tempDir.resolve("filtered_edges")

        NativeCsvIOImpl.export(exportPath, storage) { id ->
            id is NodeID || "edge1" in id.asString
        }

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)
        assertEquals(1, importedStorage.edgeIDs.size)
        assertTrue(importedStorage.containsEdge(edge1))
        assertFalse(importedStorage.containsEdge(edge2))
        assertFalse(importedStorage.containsEdge(edge3))
        importedStorage.close()
    }

    @Test
    fun `test export with predicate filters both nodes and edges`() {
        storage.addNode(node1, mapOf("type" to "A".strVal))
        storage.addNode(node2, mapOf("type" to "B".strVal))
        storage.addNode(node3, mapOf("type" to "A".strVal))
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)
        val exportPath = tempDir.resolve("filtered_both")

        NativeCsvIOImpl.export(exportPath, storage) { id ->
            when (id) {
                is NodeID -> id.asString in listOf("node1", "node2", "node3")
                is EdgeID -> listOf("edge1", "edge3").any { it in id.asString }
            }
        }

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)
        assertEquals(3, importedStorage.nodeIDs.size)
        assertEquals(2, importedStorage.edgeIDs.size)
        assertTrue(importedStorage.containsNode(node1))
        assertTrue(importedStorage.containsNode(node3))
        assertTrue(importedStorage.containsNode(node2))
        assertTrue(importedStorage.containsEdge(edge1))
        assertTrue(importedStorage.containsEdge(edge3))
        assertFalse(importedStorage.containsEdge(edge2))
        importedStorage.close()
    }

    // endregion

    // region Import

    @Test
    fun `test import from valid export`() {
        storage.addNode(node1, mapOf("name" to "Node1".strVal, "age" to 25.numVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addEdge(edge1, mapOf("weight" to 1.5.numVal))
        val exportPath = tempDir.resolve("import_source")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(2, importedStorage.nodeIDs.size)
        assertTrue(importedStorage.containsNode(node1))
        assertTrue(importedStorage.containsNode(node2))

        val props1 = importedStorage.getNodeProperties(node1)
        assertEquals(2, props1.size)
        assertEquals("Node1", (props1["name"] as StrVal).core)
        assertEquals(25, (props1["age"] as NumVal).core)

        assertEquals(1, importedStorage.edgeIDs.size)
        assertTrue(importedStorage.containsEdge(edge1))
        val edgeProps = importedStorage.getEdgeProperties(edge1)
        assertEquals(1, edgeProps.size)
        assertEquals(1.5, (edgeProps["weight"] as NumVal).core)

        importedStorage.close()
    }

    @Test
    fun `test import with predicate filters nodes`() {
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addNode(node3, mapOf("name" to "Node3".strVal))
        val exportPath = tempDir.resolve("import_filtered")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage) { id ->
            id is NodeID && id.asString in listOf("node1", "node3")
        }

        assertEquals(2, importedStorage.nodeIDs.size)
        assertTrue(importedStorage.containsNode(node1))
        assertTrue(importedStorage.containsNode(node3))
        assertFalse(importedStorage.containsNode(node2))
        importedStorage.close()
    }

    @Test
    fun `test import with predicate filters edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)
        val exportPath = tempDir.resolve("import_filtered_edges")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage) { id ->
            id is NodeID || "edge1" in id.asString
        }

        assertEquals(1, importedStorage.edgeIDs.size)
        assertTrue(importedStorage.containsEdge(edge1))
        assertFalse(importedStorage.containsEdge(edge2))
        assertFalse(importedStorage.containsEdge(edge3))
        importedStorage.close()
    }

    @Test
    fun `test import returns storage instance`() {
        storage.addNode(node1)
        val exportPath = tempDir.resolve("import_return")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        val result = NativeCsvIOImpl.import(exportPath, importedStorage)

        assertSame(importedStorage, result)
        importedStorage.close()
    }

    @Test
    fun `test import into non-empty storage`() {
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        val exportPath = tempDir.resolve("import_into_existing")
        NativeCsvIOImpl.export(exportPath, storage)
        val existingStorage = NativeStorageImpl()
        existingStorage.addNode(node2, mapOf("name" to "Node2".strVal))

        NativeCsvIOImpl.import(exportPath, existingStorage)

        assertEquals(2, existingStorage.nodeIDs.size)
        assertTrue(existingStorage.containsNode(node1))
        assertTrue(existingStorage.containsNode(node2))
        existingStorage.close()
    }

    // endregion

    // region CSV escape boundary conditions

    @Test
    fun `test export with special characters in property values`() {
        storage.addNode(
            node1,
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

        val props = importedStorage.getNodeProperties(node1)
        assertEquals("Node,with,commas", (props["name"] as StrVal).core)
        assertEquals("Line1\nLine2", (props["newline"] as StrVal).core)
        assertEquals("Value\tSeparated", (props["tab"] as StrVal).core)

        importedStorage.close()
    }

    @Test
    fun `test export with empty property values`() {
        storage.addNode(
            node1,
            mapOf(
                "prop1" to "value1".strVal,
                "prop2" to "".strVal,
            ),
        )
        val exportPath = tempDir.resolve("empty_props")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        val props = importedStorage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals("", (props["prop2"] as StrVal).core)

        importedStorage.close()
    }

    // endregion

    // region Large dataset

    @Test
    fun `test export with large number of nodes and edges`() {
        for (i in 0 until 100) {
            storage.addNode(NodeID("node_$i"), mapOf("index" to i.numVal))
        }
        for (i in 0 until 99) {
            val srcNode = NodeID("node_$i")
            val dstNode = NodeID("node_${i + 1}")
            storage.addEdge(EdgeID(srcNode, dstNode, "edge_$i"), mapOf("index" to i.numVal))
        }
        val exportPath = tempDir.resolve("large_export")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(100, importedStorage.nodeIDs.size)
        assertEquals(99, importedStorage.edgeIDs.size)

        val node50 = NodeID("node_50")
        assertTrue(importedStorage.containsNode(node50))
        val props50 = importedStorage.getNodeProperties(node50)
        assertEquals(50, (props50["index"] as NumVal).core)

        importedStorage.close()
    }

    // endregion

    // region Integration

    @Test
    fun `test round-trip export and import preserves all data`() {
        storage.addNode(
            node1,
            mapOf(
                "name" to "Node1".strVal,
                "age" to 25.numVal,
                "weight" to 1.5.numVal,
                "active" to true.boolVal,
            ),
        )
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addNode(node3, mapOf("name" to "Node3".strVal))
        storage.addEdge(edge1, mapOf("weight" to 1.0.numVal, "label" to "relation".strVal))
        storage.addEdge(edge2, mapOf("weight" to 2.0.numVal))
        storage.addEdge(edge3, mapOf("weight" to 3.0.numVal))
        val exportPath = tempDir.resolve("round_trip")
        NativeCsvIOImpl.export(exportPath, storage)

        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        assertEquals(3, importedStorage.nodeIDs.size)
        assertEquals(3, importedStorage.edgeIDs.size)

        val props1 = importedStorage.getNodeProperties(node1)
        assertEquals(4, props1.size)
        assertEquals("Node1", (props1["name"] as StrVal).core)
        assertEquals(25, (props1["age"] as NumVal).core)
        assertEquals(1.5, (props1["weight"] as NumVal).core)
        assertEquals(true, (props1["active"] as BoolVal).core)

        val edgeProps1 = importedStorage.getEdgeProperties(edge1)
        assertEquals(2, edgeProps1.size)
        assertEquals(1.0, (edgeProps1["weight"] as NumVal).core)
        assertEquals("relation", (edgeProps1["label"] as StrVal).core)

        val incoming2 = importedStorage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val outgoing1 = importedStorage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        importedStorage.close()
    }

    @Test
    fun `test multiple round-trips`() {
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addEdge(edge1)
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
        assertTrue(finalStorage.containsNode(node1))
        assertTrue(finalStorage.containsNode(node2))
        assertTrue(finalStorage.containsEdge(edge1))

        val props1 = finalStorage.getNodeProperties(node1)
        assertEquals("Node1", (props1["name"] as StrVal).core)

        finalStorage.close()
    }

    // endregion
}
