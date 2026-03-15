package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

class JgraphtStorageImplTest {
    private lateinit var storage: JgraphtStorageImpl

    @Before
    fun setup() {
        storage = JgraphtStorageImpl()
    }

    @After
    fun cleanup() {
        storage.close()
    }

    private fun addThreeNodes(): Triple<String, String, String> {
        val n1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        return Triple(n1, n2, n3)
    }

    @Test
    fun `test basic CRUD operations`() {
        val n1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        assertTrue(storage.containsNode(n1))
        assertEquals(1, storage.nodeIDs.size)

        val nodeProps = storage.getNodeProperties(n1)
        assertEquals(1, nodeProps.size)
        assertEquals("value1", (nodeProps["prop1"] as StrVal).core)

        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "edge1", mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsEdge(e1))
        assertEquals(1, storage.edgeIDs.size)

        val edgeProps = storage.getEdgeProperties(e1)
        assertEquals(1, edgeProps.size)
        assertEquals("value1", (edgeProps["prop1"] as StrVal).core)

        assertFailsWith<EntityNotExistException> { storage.getNodeProperties("nonexistent") }
        assertFailsWith<EntityNotExistException> { storage.addEdge(n1, "nonexistent", "edge") }
    }

    @Test
    fun `test property updates`() {
        val n1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "edge1", mapOf("prop1" to "value1".strVal))

        storage.setNodeProperties(n1, mapOf("prop1" to "newValue".strVal, "prop2" to 42.numVal))
        val nodeProps = storage.getNodeProperties(n1)
        assertEquals(2, nodeProps.size)
        assertEquals("newValue", (nodeProps["prop1"] as StrVal).core)
        assertEquals(42, (nodeProps["prop2"] as NumVal).core)

        storage.setEdgeProperties(e1, mapOf("prop1" to "newValue".strVal, "prop2" to 42.numVal))
        val edgeProps = storage.getEdgeProperties(e1)
        assertEquals(2, edgeProps.size)
        assertEquals("newValue", (edgeProps["prop1"] as StrVal).core)
        assertEquals(42, (edgeProps["prop2"] as NumVal).core)

        storage.setNodeProperties(n1, mapOf("prop1" to null))
        val updatedNodeProps = storage.getNodeProperties(n1)
        assertEquals(1, updatedNodeProps.size)
        assertNull(updatedNodeProps["prop1"])
        assertEquals(42, (updatedNodeProps["prop2"] as NumVal).core)

        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties("nonexistent", mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test entity removal`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "edge1")
        val e3 = storage.addEdge(n1, n3, "edge3")

        storage.deleteEdge(e1)
        assertFalse(storage.containsEdge(e1))
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))

        storage.deleteNode(n1)
        assertFalse(storage.containsNode(n1))
        assertFalse(storage.containsEdge(e3))
        assertTrue(storage.containsNode(n3))

        assertFailsWith<EntityNotExistException> { storage.deleteNode("nonexistent") }
    }

    @Test
    fun `test collection views`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "edge1")

        val nodeIds = storage.nodeIDs.toList()
        assertEquals(2, nodeIds.size)
        assertTrue(nodeIds.contains(n1))
        assertTrue(nodeIds.contains(n2))

        val edgeIds = storage.edgeIDs.toList()
        assertEquals(1, edgeIds.size)
        assertTrue(edgeIds.contains(e1))

        val n3 = storage.addNode()
        val e2 = storage.addEdge(n2, n3, "edge2")
        val e3 = storage.addEdge(n1, n3, "edge3")

        val incomingEdges = storage.getIncomingEdges(n3)
        assertEquals(2, incomingEdges.size)
        assertTrue(incomingEdges.contains(e2))
        assertTrue(incomingEdges.contains(e3))

        val outgoingEdges = storage.getOutgoingEdges(n1)
        assertEquals(2, outgoingEdges.size)
        assertTrue(outgoingEdges.contains(e1))
        assertTrue(outgoingEdges.contains(e3))

        val emptyIncoming = storage.getIncomingEdges(n1)
        assertTrue(emptyIncoming.isEmpty())

        assertFailsWith<EntityNotExistException> {
            storage.getIncomingEdges("nonexistent")
        }
    }

    @Test
    fun `test empty and null values`() {
        val n1 = storage.addNode()
        assertTrue(storage.containsNode(n1))
        assertEquals(0, storage.getNodeProperties(n1).size)

        val n2 = storage.addNode(mapOf("nullProp" to NullVal))
        val props = storage.getNodeProperties(n2)
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

        val n1 = storage.addNode(mapOf("complex" to complexValue))
        val props = storage.getNodeProperties(n1)
        assertEquals(complexValue, props["complex"])

        val specificProp = storage.getNodeProperties(n1)["complex"]
        assertEquals(complexValue, specificProp)

        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "edge1")
        val e1b = storage.addEdge(n1, n2, "edge1b")

        val outEdges = storage.getOutgoingEdges(n1)
        assertEquals(2, outEdges.size)
        assertTrue(outEdges.contains(e1))
        assertTrue(outEdges.contains(e1b))
    }

    @Test
    fun `test large dataset operations`() {
        val nodeCount = 100
        val edgesPerNode = 5

        val nodeIds = mutableListOf<String>()
        for (i in 1..nodeCount) {
            nodeIds.add(storage.addNode(mapOf("index" to i.numVal, "name" to "Node $i".strVal)))
        }

        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val targetIdx = ((i + j) % nodeCount)
                storage.addEdge(nodeIds[i], nodeIds[targetIdx], "largeEdge${i + 1}_${targetIdx + 1}")
            }
        }

        assertEquals(nodeCount, storage.nodeIDs.size)
        assertEquals(nodeCount * edgesPerNode, storage.edgeIDs.size)

        for (i in listOf(0, 24, 49, 74, 99)) {
            if (i < nodeCount) {
                val nodeId = nodeIds[i]
                assertTrue(storage.containsNode(nodeId))

                val props = storage.getNodeProperties(nodeId)
                assertEquals(i + 1, (props["index"] as NumVal).core)
                assertEquals("Node ${i + 1}", (props["name"] as StrVal).core)

                val outEdges = storage.getOutgoingEdges(nodeId)
                assertEquals(edgesPerNode, outEdges.size)
            }
        }
    }

    @Test
    fun `test exception handling`() {
        val n1 = storage.addNode()
        val n2Id = "nonexistent"

        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(n2Id)
        }

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(n1, "nonexistent", "edge1")
        }

        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }
}
