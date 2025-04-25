package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.exchange.MapDBGraphExchangeImpl
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.test.*

class MapDBGraphExchangeImplTest {
    private lateinit var tempDir: Path
    private lateinit var testFile: Path
    private lateinit var storage: MapDBStorageImpl

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("mapdb-test")
        testFile = tempDir.resolve("test.db")
        storage = MapDBStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test isValidFile with non-existent file`() {
        assertFalse(MapDBGraphExchangeImpl.isValidFile(tempDir.resolve("nonexistent.db")))
    }

    @Test
    fun `test isValidFile with empty file`() {
        testFile.createFile()
        assertFalse(MapDBGraphExchangeImpl.isValidFile(testFile))
    }

    @Test
    fun `test export and import with empty storage`() {
        val exportedFile = MapDBGraphExchangeImpl.export(testFile, storage) { true }
        assertTrue(exportedFile.exists())
        assertTrue(MapDBGraphExchangeImpl.isValidFile(exportedFile))

        val newStorage = MapDBStorageImpl()
        MapDBGraphExchangeImpl.import(exportedFile, newStorage) { true }
        assertEquals(0, newStorage.nodeSize)
        assertEquals(0, newStorage.edgeSize)
    }

    @Test
    fun `test export and import with nodes and edges`() {
        // Add test data
        val node1 = NodeID("node1")
        val node2 = NodeID("node2")
        storage.addNode(node1, "prop1" to "value1".strVal)
        storage.addNode(node2, "prop2" to "value2".strVal)
        val edge = EdgeID(node1, node2, "edge1")
        storage.addEdge(edge, "edgeProp" to "edgeValue".strVal)

        // Export
        val exportedFile = MapDBGraphExchangeImpl.export(testFile, storage) { true }
        assertTrue(exportedFile.exists())

        // Import
        val newStorage = MapDBStorageImpl()
        MapDBGraphExchangeImpl.import(exportedFile, newStorage) { true }

        // Verify
        assertEquals(2, newStorage.nodeSize)
        assertEquals(1, newStorage.edgeSize)
        assertTrue(node1 in newStorage)
        assertTrue(node2 in newStorage)
        assertTrue(edge in newStorage)
        assertEquals("value1", newStorage.getNodeProperties(node1)["prop1"]?.core)
        assertEquals("value2", newStorage.getNodeProperties(node2)["prop2"]?.core)
        assertEquals("edgeValue", newStorage.getEdgeProperties(edge)["edgeProp"]?.core)
    }

    @Test
    fun `test export with predicate`() {
        val node1 = NodeID("node1")
        val node2 = NodeID("node2")
        storage.addNode(node1)
        storage.addNode(node2)
        val edge = EdgeID(node1, node2, "edge1")
        storage.addEdge(edge)

        val exportedFile = MapDBGraphExchangeImpl.export(testFile, storage) { it == node1 }
        val newStorage = MapDBStorageImpl()
        MapDBGraphExchangeImpl.import(exportedFile, newStorage) { true }

        assertEquals(1, newStorage.nodeSize)
        assertEquals(0, newStorage.edgeSize)
        assertTrue(node1 in newStorage)
        assertFalse(node2 in newStorage)
    }

    @Test
    fun `test import with predicate`() {
        val node1 = NodeID("node1")
        val node2 = NodeID("node2")
        storage.addNode(node1)
        storage.addNode(node2)
        val edge = EdgeID(node1, node2, "edge1")
        storage.addEdge(edge)

        val exportedFile = MapDBGraphExchangeImpl.export(testFile, storage) { true }
        val newStorage = MapDBStorageImpl()
        MapDBGraphExchangeImpl.import(exportedFile, newStorage) { it == node1 }

        assertEquals(1, newStorage.nodeSize)
        assertEquals(0, newStorage.edgeSize)
        assertTrue(node1 in newStorage)
        assertFalse(node2 in newStorage)
    }

    @Test
    fun `test export to existing file`() {
        testFile.createFile()
        assertFailsWith<IllegalArgumentException> {
            MapDBGraphExchangeImpl.export(testFile, storage) { true }
        }
    }

    @Test
    fun `test import from non-existent file`() {
        assertFailsWith<java.nio.file.NoSuchFileException> {
            MapDBGraphExchangeImpl.import(tempDir.resolve("nonexistent.db"), storage) { true }
        }
    }
}
