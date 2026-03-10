package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.MapDBStorageImpl
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.test.*

class MapDbGraphIOImplWhiteBoxTest {
    private lateinit var srcStorage: MapDBStorageImpl
    private lateinit var tempFile: Path

    @Before
    fun setup() {
        srcStorage = MapDBStorageImpl { memoryDB() }
        tempFile = Files.createTempDirectory("mapdb-io-test").resolve("test.mapdb")
    }

    @After
    fun cleanup() {
        srcStorage.close()
        tempFile.deleteIfExists()
        tempFile.parent?.toFile()?.deleteRecursively()
    }

    // -- isValidFile --

    @Test
    fun `test isValidFile returns false for nonexistent file`() {
        assertFalse(MapDbGraphIOImpl.isValidFile(Paths.get("/tmp/nonexistent_${System.nanoTime()}.mapdb")))
    }

    @Test
    fun `test isValidFile returns false for empty file`() {
        val emptyFile = Files.createTempFile("mapdb-empty", ".mapdb")
        assertFalse(MapDbGraphIOImpl.isValidFile(emptyFile))
        emptyFile.deleteIfExists()
    }

    @Test
    fun `test isValidFile returns true after valid export`() {
        srcStorage.addNode(NodeID("n1"))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        assertTrue(MapDbGraphIOImpl.isValidFile(tempFile))
    }

    @Test
    fun `test isValidFile returns false for corrupted file`() {
        val badFile = Files.createTempFile("mapdb-bad", ".mapdb")
        badFile.toFile().writeText("not a mapdb file")

        assertFalse(MapDbGraphIOImpl.isValidFile(badFile))
        badFile.deleteIfExists()
    }

    // -- Export/Import round-trip --

    @Test
    fun `test export then import preserves nodes with properties`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        srcStorage.addNode(n1, mapOf("name" to "Node1".strVal, "count" to 10.numVal))
        srcStorage.addNode(n2, mapOf("name" to "Node2".strVal))

        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertTrue(dstStorage.containsNode(n1))
        assertTrue(dstStorage.containsNode(n2))
        assertEquals("Node1", (dstStorage.getNodeProperties(n1)["name"] as StrVal).core)
        assertEquals(10, (dstStorage.getNodeProperties(n1)["count"] as NumVal).core)
        assertEquals("Node2", (dstStorage.getNodeProperties(n2)["name"] as StrVal).core)

        dstStorage.close()
    }

    @Test
    fun `test export then import preserves edges with properties`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        srcStorage.addNode(n1)
        srcStorage.addNode(n2)
        val e1 = EdgeID(n1, n2, "connects")
        srcStorage.addEdge(e1, mapOf("weight" to 5.numVal))

        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertTrue(dstStorage.containsEdge(e1))
        assertEquals(5, (dstStorage.getEdgeProperties(e1)["weight"] as NumVal).core)

        dstStorage.close()
    }

    // -- Export creates parent directories --

    @Test
    fun `test export creates parent directories if not exist`() {
        val nestedFile = Files.createTempDirectory("mapdb-nest").resolve("a/b/c/test.mapdb")
        srcStorage.addNode(NodeID("n"))

        val result = MapDbGraphIOImpl.export(nestedFile, srcStorage)
        assertEquals(nestedFile, result)
        assertTrue(MapDbGraphIOImpl.isValidFile(nestedFile))

        nestedFile.parent?.toFile()?.deleteRecursively()
    }

    // -- Export precondition: file must not exist --

    @Test
    fun `test export throws when file already exists`() {
        srcStorage.addNode(NodeID("n"))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        assertFailsWith<IllegalArgumentException> {
            MapDbGraphIOImpl.export(tempFile, srcStorage)
        }
    }

    // -- Import precondition: file must exist and be non-empty --

    @Test
    fun `test import throws when file does not exist`() {
        val badPath = Paths.get("/tmp/nonexistent_${System.nanoTime()}.mapdb")
        val dstStorage = MapDBStorageImpl { memoryDB() }

        assertFailsWith<IllegalArgumentException> {
            MapDbGraphIOImpl.import(badPath, dstStorage)
        }

        dstStorage.close()
    }

    // -- Import upsert: existing entities updated, new entities added --

    @Test
    fun `test import updates existing node properties via setNodeProperties`() {
        val n1 = NodeID("n1")
        srcStorage.addNode(n1, mapOf("version" to "v2".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        dstStorage.addNode(n1, mapOf("version" to "v1".strVal, "extra" to "keep".strVal))
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        val props = dstStorage.getNodeProperties(n1)
        assertEquals("v2", (props["version"] as StrVal).core)
        assertEquals("keep", (props["extra"] as StrVal).core)

        dstStorage.close()
    }

    @Test
    fun `test import updates existing edge properties via setEdgeProperties`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        val e1 = EdgeID(n1, n2, "e")
        srcStorage.addNode(n1)
        srcStorage.addNode(n2)
        srcStorage.addEdge(e1, mapOf("weight" to 10.numVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        dstStorage.addNode(n1)
        dstStorage.addNode(n2)
        dstStorage.addEdge(e1, mapOf("weight" to 5.numVal, "label" to "old".strVal))
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        val props = dstStorage.getEdgeProperties(e1)
        assertEquals(10, (props["weight"] as NumVal).core)
        assertEquals("old", (props["label"] as StrVal).core)

        dstStorage.close()
    }

    // -- Import auto-creates missing src/dst nodes for edges --

    @Test
    fun `test import creates missing src and dst nodes for edges`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        srcStorage.addNode(n1)
        srcStorage.addNode(n2)
        val e1 = EdgeID(n1, n2, "e")
        srcStorage.addEdge(e1)
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertTrue(dstStorage.containsNode(n1))
        assertTrue(dstStorage.containsNode(n2))
        assertTrue(dstStorage.containsEdge(e1))

        dstStorage.close()
    }

    // -- Export with predicate filtering --

    @Test
    fun `test export with node predicate filters nodes and edges`() {
        val n1 = NodeID("keep1")
        val n2 = NodeID("drop1")
        val n3 = NodeID("keep2")
        srcStorage.addNode(n1)
        srcStorage.addNode(n2)
        srcStorage.addNode(n3)
        val e1 = EdgeID(n1, n3, "ok")
        val e2 = EdgeID(n1, n2, "bad")
        srcStorage.addEdge(e1)
        srcStorage.addEdge(e2)

        MapDbGraphIOImpl.export(tempFile, srcStorage) { entity ->
            when (entity) {
                is NodeID -> entity.name.startsWith("keep")
                is EdgeID -> entity.srcNid.name.startsWith("keep") && entity.dstNid.name.startsWith("keep")
                else -> true
            }
        }

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertTrue(dstStorage.containsNode(n1))
        assertFalse(dstStorage.containsNode(n2))
        assertTrue(dstStorage.containsNode(n3))
        assertTrue(dstStorage.containsEdge(e1))
        assertFalse(dstStorage.containsEdge(e2))

        dstStorage.close()
    }

    // -- Import with predicate filtering --

    @Test
    fun `test import with predicate filters imported entities`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        srcStorage.addNode(n1, mapOf("type" to "a".strVal))
        srcStorage.addNode(n2, mapOf("type" to "b".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage) { entity ->
            when (entity) {
                is NodeID -> entity.name == "n1"
                else -> true
            }
        }

        assertTrue(dstStorage.containsNode(n1))
        assertFalse(dstStorage.containsNode(n2))

        dstStorage.close()
    }

    // -- Empty storage round-trip --

    @Test
    fun `test export and import empty storage`() {
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertEquals(0, dstStorage.nodeIDs.size)
        assertEquals(0, dstStorage.edgeIDs.size)

        dstStorage.close()
    }

    // -- NodeID/EdgeID serialization round-trip via _nid/_eid keys --

    @Test
    fun `test node ID preserved through nid key serialization`() {
        val specialNode = NodeID("node-with-special_chars.123")
        srcStorage.addNode(specialNode, mapOf("data" to "test".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertTrue(dstStorage.containsNode(specialNode))
        assertEquals("test", (dstStorage.getNodeProperties(specialNode)["data"] as StrVal).core)

        dstStorage.close()
    }

    @Test
    fun `test edge ID preserved through eid ListVal serialization`() {
        val n1 = NodeID("src")
        val n2 = NodeID("dst")
        srcStorage.addNode(n1)
        srcStorage.addNode(n2)
        val edge = EdgeID(n1, n2, "type-with-special_chars")
        srcStorage.addEdge(edge, mapOf("data" to "test".strVal))
        MapDbGraphIOImpl.export(tempFile, srcStorage)

        val dstStorage = MapDBStorageImpl { memoryDB() }
        MapDbGraphIOImpl.import(tempFile, dstStorage)

        assertTrue(dstStorage.containsEdge(edge))
        assertEquals("test", (dstStorage.getEdgeProperties(edge)["data"] as StrVal).core)

        dstStorage.close()
    }
}
