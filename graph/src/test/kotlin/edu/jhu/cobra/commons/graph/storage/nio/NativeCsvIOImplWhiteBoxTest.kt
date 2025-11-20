package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.graph.storage.StorageTestUtils
import edu.jhu.cobra.commons.value.*
import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * White-box tests for NativeCsvIOImpl focusing on internal implementation details,
 * boundary conditions, and edge cases within internal logic.
 */
class NativeCsvIOImplWhiteBoxTest {

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

    // ============================================================================
    // isValidFile BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test isValidFile returns false for file instead of directory`() {
        // Arrange
        val filePath = tempDir.resolve("file.txt")
        filePath.createFile()

        // Act
        val result = NativeCsvIOImpl.isValidFile(filePath)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns false for directory without nodes csv`() {
        // Arrange
        val emptyDir = tempDir.resolve("empty")
        emptyDir.createDirectories()

        // Act
        val result = NativeCsvIOImpl.isValidFile(emptyDir)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns false for directory without edges csv`() {
        // Arrange
        val partialDir = tempDir.resolve("partial")
        partialDir.createDirectories()
        partialDir.resolve("nodes.csv").createFile()

        // Act
        val result = NativeCsvIOImpl.isValidFile(partialDir)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `test isValidFile returns false for empty csv files`() {
        // Arrange
        val emptyDir = tempDir.resolve("empty_csv")
        emptyDir.createDirectories()
        emptyDir.resolve("nodes.csv").createFile()
        emptyDir.resolve("edges.csv").createFile()

        // Act
        val result = NativeCsvIOImpl.isValidFile(emptyDir)

        // Assert
        assertFalse(result)
    }

    // ============================================================================
    // export BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test export with empty storage`() {
        // Arrange
        val exportPath = tempDir.resolve("empty_export")

        // Act
        NativeCsvIOImpl.export(exportPath, storage)

        // Assert
        val nodesFile = exportPath.resolve("nodes.csv")
        val edgesFile = exportPath.resolve("edges.csv")
        assertTrue(nodesFile.exists())
        assertTrue(edgesFile.exists())
    }

    // ============================================================================
    // CSV ESCAPE BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test export with special characters in property values`() {
        // Arrange
        storage.addNode(node1, mapOf(
            "name" to "Node,with,commas".strVal,
            "newline" to "Line1\nLine2".strVal,
            "tab" to "Value\tSeparated".strVal
        ))
        val exportPath = tempDir.resolve("special_chars")
        NativeCsvIOImpl.export(exportPath, storage)

        // Act
        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        // Assert
        val props = importedStorage.getNodeProperties(node1)
        assertEquals("Node,with,commas", (props["name"] as StrVal).core)
        assertEquals("Line1\nLine2", (props["newline"] as StrVal).core)
        assertEquals("Value\tSeparated", (props["tab"] as StrVal).core)

        importedStorage.close()
    }

    @Test
    fun `test export with empty property values`() {
        // Arrange
        storage.addNode(node1, mapOf(
            "prop1" to "value1".strVal,
            "prop2" to "".strVal
        ))
        val exportPath = tempDir.resolve("empty_props")
        NativeCsvIOImpl.export(exportPath, storage)

        // Act
        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        // Assert
        val props = importedStorage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals("", (props["prop2"] as StrVal).core)

        importedStorage.close()
    }

    // ============================================================================
    // SIZE LIMIT BOUNDARY CONDITIONS
    // ============================================================================

    @Test
    fun `test export with large number of nodes and edges`() {
        // Arrange
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

        // Act
        val importedStorage = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, importedStorage)

        // Assert
        assertEquals(100, importedStorage.nodeIDs.size)
        assertEquals(99, importedStorage.edgeIDs.size)

        val node50 = NodeID("node_50")
        assertTrue(importedStorage.containsNode(node50))
        val props50 = importedStorage.getNodeProperties(node50)
        assertEquals(50, (props50["index"] as NumVal).core)

        importedStorage.close()
    }
}
