package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import kotlin.test.*

class Neo4jStorageImplTest {
    private lateinit var storage: Neo4jStorageImpl
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("neo4j-test")
        storage = Neo4jStorageImpl(tempDir)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test empty storage properties`() {
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.nodeIDs.toList().isEmpty())
        assertTrue(storage.edgeIDs.toList().isEmpty())
    }

    @Test
    fun `test add and query node`() {
        val nodeId = storage.addNode("n1", mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsNode(nodeId))
        assertEquals(1, storage.nodeIDs.size)
        assertEquals("value1", storage.getNodeProperties(nodeId)["prop1"]?.core)
    }

    @Test
    fun `test add and query edge`() {
        val srcId = storage.addNode("src")
        val dstId = storage.addNode("dst")

        val edgeId = storage.addEdge(srcId, dstId, "e1", "test-edge", mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsEdge(edgeId))
        assertEquals(1, storage.edgeIDs.size)
        assertEquals("value1", storage.getEdgeProperties(edgeId)["prop1"]?.core)
    }

    @Test
    fun `test add edge with non-existent nodes`() {
        assertFailsWith<EntityNotExistException> {
            storage.addEdge("missing-src", "missing-dst", "e1", "test-edge")
        }
    }

    @Test
    fun `test node properties operations`() {
        val nodeId = storage.addNode("n1", mapOf("prop1" to "value1".strVal, "prop2" to "value2".strVal))

        val props = storage.getNodeProperties(nodeId)
        assertEquals(2, props.size)
        assertEquals("value1", props["prop1"]?.core)
        assertEquals("value2", props["prop2"]?.core)

        storage.setNodeProperties(nodeId, mapOf("prop1" to "updated".strVal))
        assertEquals("updated", storage.getNodeProperties(nodeId)["prop1"]?.core)
    }

    @Test
    fun `test edge properties operations`() {
        val srcId = storage.addNode("src")
        val dstId = storage.addNode("dst")

        val edgeId = storage.addEdge(
            srcId, dstId, "e1", "test-edge",
            mapOf("prop1" to "value1".strVal, "prop2" to "value2".strVal),
        )

        val props = storage.getEdgeProperties(edgeId)
        assertEquals(2, props.size)
        assertEquals("value1", props["prop1"]?.core)
        assertEquals("value2", props["prop2"]?.core)

        storage.setEdgeProperties(edgeId, mapOf("prop1" to "updated".strVal))
        assertEquals("updated", storage.getEdgeProperties(edgeId)["prop1"]?.core)
    }

    @Test
    fun `test delete node`() {
        val nodeId = storage.addNode("n1")
        assertTrue(storage.containsNode(nodeId))

        storage.deleteNode(nodeId)
        assertFalse(storage.containsNode(nodeId))
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `test delete edge`() {
        val srcId = storage.addNode("src")
        val dstId = storage.addNode("dst")

        val edgeId = storage.addEdge(srcId, dstId, "e1", "test-edge")
        assertTrue(storage.containsEdge(edgeId))

        storage.deleteEdge(edgeId)
        assertFalse(storage.containsEdge(edgeId))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test incoming and outgoing edges`() {
        val srcId = storage.addNode("src")
        val dstId = storage.addNode("dst")

        val edgeId = storage.addEdge(srcId, dstId, "e1", "test-edge")

        assertEquals(1, storage.getOutgoingEdges(srcId).size)
        assertEquals(1, storage.getIncomingEdges(dstId).size)
        assertTrue(edgeId in storage.getOutgoingEdges(srcId))
        assertTrue(edgeId in storage.getIncomingEdges(dstId))
    }

    @Test
    fun `test clear storage`() {
        storage.addNode("n1")

        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test operations on closed storage`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.addNode("n1")
        }
        assertFailsWith<AccessClosedStorageException> {
            storage.nodeIDs
        }
    }

    @Test
    fun `test invalid property names`() {
        val nodeId = storage.addNode("n1")

        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(nodeId, mapOf("__meta_id__" to "value".strVal))
        }
    }
}
