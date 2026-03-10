package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import kotlin.test.*

class Neo4jStorageImplTest {
    private lateinit var storage: Neo4jStorageImpl
    private lateinit var tempDir: String

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("neo4j-test").toString()
        storage = Neo4jStorageImpl(Files.createTempDirectory("neo4j-test"))
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        Files
            .walk(Files.createTempDirectory("neo4j-test"))
            .sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
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
        val nodeId = NodeID("test-node")
        storage.addNode(nodeId, mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsNode(nodeId))
        assertEquals(1, storage.nodeIDs.size)
        assertEquals("value1", storage.getNodeProperties(nodeId)["prop1"]?.core)
    }

    @Test
    fun `test add duplicate node`() {
        val nodeId = NodeID("test-node")
        storage.addNode(nodeId)

        assertFailsWith<EntityAlreadyExistException> { storage.addNode(nodeId) }
    }

    @Test
    fun `test add and query edge`() {
        val srcId = NodeID("src")
        val dstId = NodeID("dst")
        storage.addNode(srcId)
        storage.addNode(dstId)

        val edgeId = EdgeID(srcId, dstId, "test-edge")
        storage.addEdge(edgeId, mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsEdge(edgeId))
        assertEquals(1, storage.edgeIDs.size)
        assertEquals("value1", storage.getEdgeProperties(edgeId)["prop1"]?.core)
    }

    @Test
    fun `test add edge with non-existent nodes`() {
        val srcId = NodeID("src")
        val dstId = NodeID("dst")
        val edgeId = EdgeID(srcId, dstId, "test-edge")

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edgeId)
        }
    }

    @Test
    fun `test node properties operations`() {
        val nodeId = NodeID("test-node")
        storage.addNode(
            nodeId,
            mapOf("prop1" to "value1".strVal, "prop2" to "value2".strVal),
        )

        val props = storage.getNodeProperties(nodeId)
        assertEquals(2, props.size)
        assertEquals("value1", props["prop1"]?.core)
        assertEquals("value2", props["prop2"]?.core)

        storage.setNodeProperties(nodeId, mapOf("prop1" to "updated".strVal))
        assertEquals("updated", storage.getNodeProperties(nodeId)["prop1"]?.core)
    }

    @Test
    fun `test edge properties operations`() {
        val srcId = NodeID("src")
        val dstId = NodeID("dst")
        storage.addNode(srcId)
        storage.addNode(dstId)

        val edgeId = EdgeID(srcId, dstId, "test-edge")
        storage.addEdge(
            edgeId,
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
        val nodeId = NodeID("test-node")
        storage.addNode(nodeId)
        assertTrue(storage.containsNode(nodeId))

        storage.deleteNode(nodeId)
        assertFalse(storage.containsNode(nodeId))
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `test delete edge`() {
        val srcId = NodeID("src")
        val dstId = NodeID("dst")
        storage.addNode(srcId)
        storage.addNode(dstId)

        val edgeId = EdgeID(srcId, dstId, "test-edge")
        storage.addEdge(edgeId)
        assertTrue(storage.containsEdge(edgeId))

        storage.deleteEdge(edgeId)
        assertFalse(storage.containsEdge(edgeId))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test incoming and outgoing edges`() {
        val srcId = NodeID("src")
        val dstId = NodeID("dst")
        storage.addNode(srcId)
        storage.addNode(dstId)

        val edgeId = EdgeID(srcId, dstId, "test-edge")
        storage.addEdge(edgeId)

        assertEquals(1, storage.getOutgoingEdges(srcId).size)
        assertEquals(1, storage.getIncomingEdges(dstId).size)
        assertTrue(edgeId in storage.getOutgoingEdges(srcId))
        assertTrue(edgeId in storage.getIncomingEdges(dstId))
    }

    @Test
    fun `test clear storage`() {
        val nodeId = NodeID("test-node")
        storage.addNode(nodeId)

        assertTrue(storage.clear())
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test operations on closed storage`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.addNode(NodeID("test"))
        }
        assertFailsWith<AccessClosedStorageException> {
            storage.nodeIDs
        }
    }

    @Test
    fun `test invalid property names`() {
        val nodeId = NodeID("test-node")
        storage.addNode(nodeId)

        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(nodeId, mapOf("__meta_id__" to "value".strVal))
        }
    }

    @Test
    fun `test concurrent operations`() {
        val threads =
            List(10) { threadId ->
                Thread {
                    val nodeId = NodeID("node-$threadId")
                    storage.addNode(nodeId)
                    storage.setNodeProperties(nodeId, mapOf("prop" to "value-$threadId".strVal))
                }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(10, storage.nodeIDs.size)
    }
}
