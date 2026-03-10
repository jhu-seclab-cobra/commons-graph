package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

class JgraphtStorageImplTest {
    private lateinit var storage: JgraphtStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge1 = EdgeID(node1, node2, "edge1")
    private val edge2 = EdgeID(node2, node3, "edge2")
    private val edge3 = EdgeID(node1, node3, "edge3")

    @Before
    fun setup() {
        storage = JgraphtStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
    }

    @Test
    fun `test basic CRUD operations`() {
        storage.addNode(node1, mapOf("prop1" to "value1".strVal))
        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeIDs.size)

        val nodeProps = storage.getNodeProperties(node1)
        assertEquals(1, nodeProps.size)
        assertEquals("value1", (nodeProps["prop1"] as StrVal).core)

        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsEdge(edge1))
        assertEquals(1, storage.edgeIDs.size)

        val edgeProps = storage.getEdgeProperties(edge1)
        assertEquals(1, edgeProps.size)
        assertEquals("value1", (edgeProps["prop1"] as StrVal).core)

        assertFailsWith<EntityAlreadyExistException> { storage.addNode(node1) }
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(NodeID("nonexistent")) }
        assertFailsWith<EntityNotExistException> { storage.addEdge(EdgeID(node1, NodeID("nonexistent"), "edge")) }
    }

    @Test
    fun `test property updates`() {
        storage.addNode(node1, mapOf("prop1" to "value1".strVal))
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal))

        storage.setNodeProperties(node1, mapOf("prop1" to "newValue".strVal, "prop2" to 42.numVal))
        val nodeProps = storage.getNodeProperties(node1)
        assertEquals(2, nodeProps.size)
        assertEquals("newValue", (nodeProps["prop1"] as StrVal).core)
        assertEquals(42, (nodeProps["prop2"] as NumVal).core)

        storage.setEdgeProperties(edge1, mapOf("prop1" to "newValue".strVal, "prop2" to 42.numVal))
        val edgeProps = storage.getEdgeProperties(edge1)
        assertEquals(2, edgeProps.size)
        assertEquals("newValue", (edgeProps["prop1"] as StrVal).core)
        assertEquals(42, (edgeProps["prop2"] as NumVal).core)

        storage.setNodeProperties(node1, mapOf("prop1" to null))
        val updatedNodeProps = storage.getNodeProperties(node1)
        assertEquals(1, updatedNodeProps.size)
        assertNull(updatedNodeProps["prop1"])
        assertEquals(42, (updatedNodeProps["prop2"] as NumVal).core)

        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(NodeID("nonexistent"), mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test entity removal`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge3)

        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))

        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsNode(node3))

        assertFailsWith<EntityNotExistException> { storage.deleteNode(NodeID("nonexistent")) }
    }

    @Test
    fun `test collection views`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        val nodeIds = storage.nodeIDs.toList()
        assertEquals(2, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))

        val edgeIds = storage.edgeIDs.toList()
        assertEquals(1, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))

        storage.addNode(node3)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        val incomingEdges = storage.getIncomingEdges(node3)
        assertEquals(2, incomingEdges.size)
        assertTrue(incomingEdges.contains(edge2))
        assertTrue(incomingEdges.contains(edge3))

        val outgoingEdges = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoingEdges.size)
        assertTrue(outgoingEdges.contains(edge1))
        assertTrue(outgoingEdges.contains(edge3))

        val emptyIncoming = storage.getIncomingEdges(node1)
        assertTrue(emptyIncoming.isEmpty())

        assertFailsWith<EntityNotExistException> {
            storage.getIncomingEdges(NodeID("nonexistent"))
        }
    }

    @Test
    fun `test empty and null values`() {
        storage.addNode(node1)
        assertTrue(storage.containsNode(node1))
        assertEquals(0, storage.getNodeProperties(node1).size)

        storage.addNode(node2, mapOf("nullProp" to NullVal))
        val props = storage.getNodeProperties(node2)
        assertEquals(1, props.size)
        assertTrue(props["nullProp"] is NullVal)

        val emptyNodeIds = storage.nodeIDs.toList()
        assertEquals(2, emptyNodeIds.size)

        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.nodeIDs.toList().isEmpty())
    }

    @Test
    fun `test complex data structures`() {
        val complexValue =
            mapOf(
                "str" to "test".strVal,
                "num" to 42.numVal,
                "bool" to true.boolVal,
                "list" to listOf(1.numVal, 2.numVal, 3.numVal).listVal,
                "map" to mapOf("nested" to "value".strVal).mapVal,
            ).mapVal

        storage.addNode(node1, mapOf("complex" to complexValue))
        val props = storage.getNodeProperties(node1)
        assertEquals(complexValue, props["complex"])

        val specificProp = storage.getNodeProperties(node1)["complex"]
        assertEquals(complexValue, specificProp)

        storage.addNode(node2)
        storage.addEdge(edge1)
        val edge1b = EdgeID(node1, node2, "edge1b")
        storage.addEdge(edge1b)

        val outEdges = storage.getOutgoingEdges(node1)
        assertEquals(2, outEdges.size)
        assertTrue(outEdges.contains(edge1))
        assertTrue(outEdges.contains(edge1b))
    }

    @Test
    fun `test large dataset operations`() {
        val nodeCount = 100
        val edgesPerNode = 5

        for (i in 1..nodeCount) {
            storage.addNode(
                NodeID("largeNode$i"),
                mapOf("index" to i.numVal, "name" to "Node $i".strVal),
            )
        }

        for (i in 1..nodeCount) {
            val srcNode = NodeID("largeNode$i")
            for (j in 1..edgesPerNode) {
                val targetIdx = ((i + j) % nodeCount) + 1
                val dstNode = NodeID("largeNode$targetIdx")
                storage.addEdge(EdgeID(srcNode, dstNode, "largeEdge${i}_$targetIdx"))
            }
        }

        assertEquals(nodeCount, storage.nodeIDs.size)
        assertEquals(nodeCount * edgesPerNode, storage.edgeIDs.size)

        for (i in listOf(1, 25, 50, 75, 100)) {
            if (i <= nodeCount) {
                val nodeId = NodeID("largeNode$i")
                assertTrue(storage.containsNode(nodeId))

                val props = storage.getNodeProperties(nodeId)
                assertEquals(i, (props["index"] as NumVal).core)
                assertEquals("Node $i", (props["name"] as StrVal).core)

                val outEdges = storage.getOutgoingEdges(nodeId)
                assertEquals(edgesPerNode, outEdges.size)
            }
        }
    }

    @Test
    fun `test exception handling`() {
        storage.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(node1)
        }

        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(node2)
        }

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }

        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node2) }
    }
}
