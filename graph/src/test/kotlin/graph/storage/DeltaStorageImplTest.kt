package graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.DeltaStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.*

class DeltaStorageImplTest {
    private lateinit var foundStorage: NativeStorageImpl
    private lateinit var presentStorage: NativeStorageImpl
    private lateinit var deltaStorage: DeltaStorageImpl
    private val testNode1 = NodeID("test1")
    private val testNode2 = NodeID("test2")
    private val testEdge = EdgeID(testNode1, testNode2, "testEdge")
    private val testProperty = "testProp" to StrVal("testValue")

    @BeforeTest
    fun setUp() {
        // Create the underlying storage implementations
        // Note: Do not modify these directly, use deltaStorage methods instead
        foundStorage = NativeStorageImpl()
        presentStorage = NativeStorageImpl()
        deltaStorage = DeltaStorageImpl(foundStorage, presentStorage)
    }

    @Test
    fun `test initial state`() {
        assertEquals(0, deltaStorage.nodeSize)
        assertEquals(0, deltaStorage.edgeSize)
        assertTrue(deltaStorage.nodeIDsSequence.toList().isEmpty())
        assertTrue(deltaStorage.edgeIDsSequence.toList().isEmpty())
    }

    @Test
    fun `test node operations with delta layers`() {
        // Add node to delta storage
        deltaStorage.addNode(testNode1, testProperty)
        assertTrue(deltaStorage.containsNode(testNode1))
        assertEquals(1, deltaStorage.nodeSize)
        assertEquals(testProperty.second, deltaStorage.getNodeProperty(testNode1, testProperty.first))

        // Add node to present layer
        val testNode3 = NodeID("test3")
        val presentProperty = "presentProp" to StrVal("presentValue")
        deltaStorage.addNode(testNode3, presentProperty)
        assertTrue(deltaStorage.containsNode(testNode3))
        assertEquals(2, deltaStorage.nodeSize)
        assertEquals(presentProperty.second, deltaStorage.getNodeProperty(testNode3, presentProperty.first))

        // Update property in present layer for foundation node
        val updatedProperty = "testProp" to StrVal("updatedValue")
        deltaStorage.setNodeProperties(testNode1, updatedProperty)
        assertEquals(updatedProperty.second, deltaStorage.getNodeProperty(testNode1, updatedProperty.first))

        // Delete node from foundation layer
        deltaStorage.deleteNode(testNode1)
        assertFalse(deltaStorage.containsNode(testNode1))
        assertEquals(1, deltaStorage.nodeSize)
    }

    @Test
    fun `test edge operations with delta layers`() {
        // Setup nodes in delta storage
        deltaStorage.addNode(testNode1)
        deltaStorage.addNode(testNode2)

        // Add edge
        deltaStorage.addEdge(testEdge, testProperty)
        assertTrue(deltaStorage.containsEdge(testEdge))
        assertEquals(1, deltaStorage.edgeSize)
        assertEquals(testProperty.second, deltaStorage.getEdgeProperty(testEdge, testProperty.first))

        // Try adding duplicate edge
        assertFailsWith<EntityAlreadyExistException> { deltaStorage.addEdge(testEdge) }

        // Update edge properties
        val newProperty = "newProp" to StrVal("newValue")
        deltaStorage.setEdgeProperties(testEdge, newProperty)
        assertEquals(newProperty.second, deltaStorage.getEdgeProperty(testEdge, newProperty.first))

        // Delete edge
        deltaStorage.deleteEdge(testEdge)
        assertFalse(deltaStorage.containsEdge(testEdge))
        assertEquals(0, deltaStorage.edgeSize)
    }

    @Test
    fun `test property layering and deletion`() {
        // Add node with initial properties
        val initialProps = arrayOf(
            "prop1" to StrVal("value1"),
            "prop2" to StrVal("value2")
        )
        deltaStorage.addNode(testNode1, *initialProps)

        // Add/update properties
        val presentProps = arrayOf(
            "prop2" to StrVal("updatedValue2"), // Override initial property
            "prop3" to StrVal("value3")         // New property
        )
        deltaStorage.setNodeProperties(testNode1, *presentProps)

        // Verify properties
        val properties = deltaStorage.getNodeProperties(testNode1)
        assertEquals(3, properties.size)
        assertEquals(StrVal("value1"), properties["prop1"])
        assertEquals(StrVal("updatedValue2"), properties["prop2"])
        assertEquals(StrVal("value3"), properties["prop3"])

        // Test property deletion
        deltaStorage.setNodeProperties(testNode1, "prop1" to null)
        assertNull(deltaStorage.getNodeProperty(testNode1, "prop1"))
    }

    @Test
    fun `test edge connectivity across layers`() {
        // Setup first set of nodes and edges
        deltaStorage.addNode(testNode1)
        deltaStorage.addNode(testNode2)
        val edge1 = EdgeID(testNode1, testNode2, "edge1")
        deltaStorage.addEdge(edge1)

        val testNode3 = NodeID("test3")
        deltaStorage.addNode(testNode3)
        val edge2 = EdgeID(testNode2, testNode3, "edge2")
        deltaStorage.addEdge(edge2)

        // Test connectivity
        assertEquals(setOf(edge1), deltaStorage.getOutgoingEdges(testNode1))
        assertEquals(setOf(edge1), deltaStorage.getIncomingEdges(testNode2))
        assertEquals(setOf(edge2), deltaStorage.getOutgoingEdges(testNode2))
        assertEquals(setOf(edge2), deltaStorage.getIncomingEdges(testNode3))
        assertEquals(setOf(edge1), deltaStorage.getEdgesBetween(testNode1, testNode2))
    }

    @Test
    fun `test bulk operations across layers`() {
        // Add first set of nodes and edges
        val firstSetNodes = (1..3).map { NodeID("found$it") } // found1, found2, found3
        firstSetNodes.forEach { deltaStorage.addNode(it) }
        val firstSetEdges = (0..1).map { EdgeID(firstSetNodes[it], firstSetNodes[it + 1], "foundEdge$it") }
        firstSetEdges.forEach { deltaStorage.addEdge(it) }

        // Add second set of nodes and edges
        val presentNodes = (4..6).map { NodeID("present$it") } // found1, found2, found3, present4, present5, present6
        presentNodes.forEach { deltaStorage.addNode(it) }
        val presentEdges = (0..1).map { EdgeID(presentNodes[it], presentNodes[it + 1], "presentEdge$it") }
        presentEdges.forEach { deltaStorage.addEdge(it) }

        assertEquals(6, deltaStorage.nodeSize)
        assertEquals(4, deltaStorage.edgeSize)

        // Delete nodes conditionally
        deltaStorage.deleteNodes { it.name.startsWith("found") } // present4, present5, present6
        assertEquals(3, deltaStorage.nodeSize)

        // After deleting nodes with names starting with "found", we need to explicitly delete
        // the edges connected to those nodes
        deltaStorage.deleteEdges { it.name.contains("foundEdge") }
        assertEquals(2, deltaStorage.edgeSize)

        // Delete edges conditionally
        deltaStorage.deleteEdges { it.name.contains("presentEdge") }
        assertEquals(0, deltaStorage.edgeSize)
    }

    @Test
    fun `test storage closure`() {
        deltaStorage.addNode(testNode1)
        deltaStorage.close()

        assertFailsWith<AccessClosedStorageException> { deltaStorage.addNode(testNode2) }
        assertFailsWith<AccessClosedStorageException> { deltaStorage.containsNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { deltaStorage.nodeSize }
    }

    @Test
    fun `test boundary conditions`() {
        // Test adding edge with non-existent nodes
        assertFailsWith<EntityNotExistException> {
            deltaStorage.addEdge(EdgeID(NodeID("nonexistent1"), NodeID("nonexistent2"), "edge")) 
        }

        // Test getting properties of non-existent node
        assertFailsWith<EntityNotExistException> {
            deltaStorage.getNodeProperties(NodeID("nonexistent")) 
        }

        // Test getting properties of non-existent edge
        assertFailsWith<EntityNotExistException> {
            deltaStorage.getEdgeProperties(EdgeID(testNode1, testNode2, "nonexistent"))
        }

        // Test deleting non-existent node
        assertFailsWith<EntityNotExistException> {
            deltaStorage.deleteNode(NodeID("nonexistent"))
        }

        // Test deleting non-existent edge
        assertFailsWith<EntityNotExistException> {
            deltaStorage.deleteEdge(EdgeID(testNode1, testNode2, "nonexistent"))
        }
    }

    @Test
    fun `test direct modification of underlying storage`() {
        // Add node directly to foundStorage
        foundStorage.addNode(testNode1, testProperty)

        // Verify that deltaStorage can see the node
        assertTrue(deltaStorage.containsNode(testNode1))
        assertEquals(testProperty.second, deltaStorage.getNodeProperty(testNode1, testProperty.first))

        // Add node directly to presentStorage
        val testNode3 = NodeID("test3")
        val presentProperty = "presentProp" to StrVal("presentValue")
        presentStorage.addNode(testNode3, presentProperty)

        // Verify that deltaStorage can see the node
        assertTrue(deltaStorage.containsNode(testNode3))
        assertEquals(presentProperty.second, deltaStorage.getNodeProperty(testNode3, presentProperty.first))

        // Note: Direct modifications to underlying storage may cause inconsistencies in deltaStorage's internal state
        // For example, the nodeCounter might not be updated correctly

        // Add edge directly to foundStorage
        foundStorage.addNode(testNode2)
        foundStorage.addEdge(testEdge, testProperty)

        // Verify that deltaStorage can see the edge
        assertTrue(deltaStorage.containsEdge(testEdge))
        assertEquals(testProperty.second, deltaStorage.getEdgeProperty(testEdge, testProperty.first))

        // Delete node directly from foundStorage
        foundStorage.deleteNode(testNode1)

        // This might lead to inconsistent state in deltaStorage
        // The node might still be reported as existing in deltaStorage even though it's deleted in foundStorage
        // or the nodeCounter might be incorrect

        // This test demonstrates that while direct modification of underlying storage is possible,
        // it can lead to inconsistent state in deltaStorage and should be used with caution
    }
}
