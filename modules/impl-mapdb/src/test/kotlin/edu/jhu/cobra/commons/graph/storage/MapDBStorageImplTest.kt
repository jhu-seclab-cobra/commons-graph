package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class MapDBStorageImplTest {
    private lateinit var storage: MapDBStorageImpl

    @BeforeTest
    fun setup() {
        storage = MapDBStorageImpl { memoryDB() }
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    @Test
    fun testPutAndGet() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeIDs.size)

        val nodeProps = storage.getNodeProperties(node1)
        assertEquals(1, nodeProps.size)
        assertEquals("value1", (nodeProps["prop1"] as StrVal).core)

        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1", mapOf("prop1" to "value1".strVal))

        assertTrue(storage.containsEdge(edge1))
        assertEquals(1, storage.edgeIDs.size)

        val edgeProps = storage.getEdgeProperties(edge1)
        assertEquals(1, edgeProps.size)
        assertEquals("value1", (edgeProps["prop1"] as StrVal).core)

        assertFailsWith<EntityNotExistException> { storage.getNodeProperties("nonexistent") }
        assertFailsWith<EntityNotExistException> { storage.addEdge(node1, "nonexistent", "edge") }
    }

    @Test
    fun testUpdateProperties() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1", mapOf("prop1" to "value1".strVal))

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
            storage.setNodeProperties("nonexistent", mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun testRemoveEntity() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1")
        val edge3 = storage.addEdge(node1, node3, "edge3")

        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))

        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsNode(node3))

        assertFailsWith<EntityNotExistException> { storage.deleteNode("nonexistent") }
    }

    @Test
    fun testCollectionViews() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1")

        val nodeIds = storage.nodeIDs.toList()
        assertEquals(2, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))

        val edgeIds = storage.edgeIDs.toList()
        assertEquals(1, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))

        val node3 = storage.addNode()
        val edge2 = storage.addEdge(node2, node3, "edge2")
        val edge3 = storage.addEdge(node1, node3, "edge3")

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
            storage.getIncomingEdges("nonexistent")
        }
    }

    @Test
    fun testEmptyAndNullValues() {
        val node1 = storage.addNode()
        assertTrue(storage.containsNode(node1))
        assertEquals(0, storage.getNodeProperties(node1).size)

        val node2 = storage.addNode(mapOf("nullProp" to NullVal))
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
    fun testNullValueHandling() {
        val node1 =
            storage.addNode(
                mapOf("nullValue" to NullVal, "normalValue" to "normal".strVal),
            )

        val props = storage.getNodeProperties(node1)
        assertEquals(NullVal, props["nullValue"])
        assertEquals("normal".strVal, props["normalValue"])

        storage.setNodeProperties(node1, mapOf("normalValue" to null))
        val updatedProps = storage.getNodeProperties(node1)
        assertEquals(1, updatedProps.size)
        assertTrue("normalValue" !in updatedProps)
        assertTrue("nullValue" in updatedProps)
    }

    @Test
    fun testEdgeCases() {
        assertFalse(storage.containsNode("nonexistent"))
        assertFalse(storage.containsEdge("nonexistent"))

        assertFailsWith<EntityNotExistException> { storage.getNodeProperties("nonexistent") }
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties("nonexistent") }

        assertFailsWith<EntityNotExistException> { storage.addEdge("nonexistent-src", "nonexistent-dst", "edge1") }

        assertTrue(storage.clear())

        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    @Test
    fun testComplexDataStructures() {
        val complexValue =
            mapOf(
                "str" to "test".strVal,
                "num" to 42.numVal,
                "bool" to true.boolVal,
                "list" to listOf(1.numVal, 2.numVal, 3.numVal).listVal,
                "map" to mapOf("nested" to "value".strVal).mapVal,
            ).mapVal

        val node1 = storage.addNode(mapOf("complex" to complexValue))
        val props = storage.getNodeProperties(node1)
        assertEquals(complexValue, props["complex"])

        val specificProp = storage.getNodeProperties(node1)["complex"]
        assertEquals(complexValue, specificProp)

        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, "edge1")
        val edge1b = storage.addEdge(node1, node2, "edge1b")

        val outEdges = storage.getOutgoingEdges(node1)
        assertEquals(2, outEdges.size)
        assertTrue(outEdges.contains(edge1))
        assertTrue(outEdges.contains(edge1b))
    }

    @Test
    fun testLargeDataSet() {
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
    fun testInternalStructureConsistency() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal))
        val node2 = storage.addNode(mapOf("prop2" to "value2".strVal))
        val node3 = storage.addNode(mapOf("prop3" to "value3".strVal))

        val edge1 = storage.addEdge(node1, node2, "edge1", mapOf("edge_prop1" to "edge_value1".strVal))
        val edge2 = storage.addEdge(node2, node3, "edge2", mapOf("edge_prop2" to "edge_value2".strVal))
        val edge3 = storage.addEdge(node1, node3, "edge3", mapOf("edge_prop3" to "edge_value3".strVal))

        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(2, incoming3.size)
        assertTrue(incoming3.contains(edge2))
        assertTrue(incoming3.contains(edge3))

        storage.deleteEdge(edge1)

        val outgoing1After = storage.getOutgoingEdges(node1)
        assertEquals(1, outgoing1After.size)
        assertTrue(outgoing1After.contains(edge3))
        assertFalse(outgoing1After.contains(edge1))

        storage.deleteNode(node1)
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsEdge(edge2))

        val incoming3After = storage.getIncomingEdges(node3)
        assertEquals(1, incoming3After.size)
        assertTrue(incoming3After.contains(edge2))
    }
}
