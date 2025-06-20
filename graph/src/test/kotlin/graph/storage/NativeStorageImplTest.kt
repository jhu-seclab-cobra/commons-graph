package graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.NumVal
import kotlin.test.*

class NativeStorageImplTest {
    private lateinit var storage: NativeStorageImpl
    private val testNode1 = NodeID("test1")
    private val testNode2 = NodeID("test2")
    private val testEdge = EdgeID(testNode1, testNode2, "testEdge")
    private val testProperty = "testProp" to StrVal("testValue")

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
    }

    // ============================================================================
    // INITIAL STATE TESTS
    // ============================================================================

    @Test
    fun initialState_shouldBeEmpty() {
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    // ============================================================================
    // CLOSED STATE TESTS (checkNotClosed implementation)
    // ============================================================================

    @Test
    fun closedStorage_shouldPreventAllOperations() {
        storage.close()
        
        // Test all operations that call checkNotClosed()
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(testNode1, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(testEdge) }
        assertFailsWith<AccessClosedStorageException> { storage.addEdge(testEdge) }
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(testEdge) }
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(testEdge, emptyMap()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(testEdge) }
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("test") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("test", StrVal("value")) }
    }

    @Test
    fun closedStorage_shouldPreventPropertyAccess() {
        storage.close()
        
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
    }

    // ============================================================================
    // NODE OPERATION TESTS
    // ============================================================================

    @Test
    fun addNode_validNode_shouldSucceed() {
        storage.addNode(testNode1, mapOf(testProperty.first to testProperty.second))
        
        assertTrue(storage.containsNode(testNode1))
        assertEquals(1, storage.nodeIDs.size)
        assertEquals(testProperty.second, storage.getNodeProperties(testNode1)[testProperty.first])
    }

    @Test
    fun addNode_duplicateNode_shouldThrowException() {
        storage.addNode(testNode1)
        
        assertFailsWith<EntityAlreadyExistException> { 
            storage.addNode(testNode1) 
        }
    }

    @Test
    fun addNode_emptyProperties_shouldSucceed() {
        storage.addNode(testNode1)
        
        assertTrue(storage.containsNode(testNode1))
        assertTrue(storage.getNodeProperties(testNode1).isEmpty())
    }

    @Test
    fun getNodeProperties_existingNode_shouldReturnProperties() {
        storage.addNode(testNode1, mapOf(testProperty.first to testProperty.second))
        
        val properties = storage.getNodeProperties(testNode1)
        assertEquals(1, properties.size)
        assertEquals(testProperty.second, properties[testProperty.first])
    }

    @Test
    fun getNodeProperties_nonexistentNode_shouldThrowException() {
        assertFailsWith<EntityNotExistException> { 
            storage.getNodeProperties(testNode1) 
        }
    }

    @Test
    fun setNodeProperties_existingNode_shouldUpdateProperties() {
        storage.addNode(testNode1)
        val newProperty = "newProp" to StrVal("newValue")
        
        storage.setNodeProperties(testNode1, mapOf(newProperty.first to newProperty.second))
        
        assertEquals(newProperty.second, storage.getNodeProperties(testNode1)[newProperty.first])
    }

    @Test
    fun setNodeProperties_nonexistentNode_shouldThrowException() {
        val newProperty = "newProp" to StrVal("newValue")
        
        assertFailsWith<EntityNotExistException> { 
            storage.setNodeProperties(testNode1, mapOf(newProperty.first to newProperty.second))
        }
    }

    @Test
    fun setNodeProperties_nullValue_shouldDeleteProperty() {
        storage.addNode(testNode1, mapOf(testProperty.first to testProperty.second))
        
        storage.setNodeProperties(testNode1, mapOf(testProperty.first to null))
        
        assertNull(storage.getNodeProperties(testNode1)[testProperty.first])
        assertEquals(0, storage.getNodeProperties(testNode1).size)
    }

    @Test
    fun deleteNode_existingNode_shouldRemoveNode() {
        storage.addNode(testNode1)
        
        storage.deleteNode(testNode1)
        
        assertFalse(storage.containsNode(testNode1))
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun deleteNode_nonexistentNode_shouldThrowException() {
        assertFailsWith<EntityNotExistException> { 
            storage.deleteNode(testNode1) 
        }
    }

    @Test
    fun deleteNode_withConnectedEdges_shouldRemoveEdges() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        storage.deleteNode(testNode1)
        
        assertFalse(storage.containsNode(testNode1))
        assertFalse(storage.containsEdge(testEdge))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun deleteNode_withMultipleEdges_shouldRemoveAllEdges() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        val edge1 = EdgeID(testNode1, testNode2, "edge1")
        val edge2 = EdgeID(testNode2, testNode1, "edge2")
        
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        
        storage.deleteNode(testNode1)
        
        assertFalse(storage.containsNode(testNode1))
        assertFalse(storage.containsEdge(edge1))
        assertFalse(storage.containsEdge(edge2))
        assertEquals(0, storage.edgeIDs.size)
    }

    // ============================================================================
    // EDGE OPERATION TESTS
    // ============================================================================

    @Test
    fun addEdge_validEdge_shouldSucceed() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        
        storage.addEdge(testEdge, mapOf(testProperty.first to testProperty.second))
        
        assertTrue(storage.containsEdge(testEdge))
        assertEquals(1, storage.edgeIDs.size)
        assertEquals(testProperty.second, storage.getEdgeProperties(testEdge)[testProperty.first])
    }

    @Test
    fun addEdge_duplicateEdge_shouldThrowException() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        assertFailsWith<EntityAlreadyExistException> { 
            storage.addEdge(testEdge) 
        }
    }

    @Test
    fun addEdge_missingSourceNode_shouldThrowException() {
        storage.addNode(testNode2)
        
        assertFailsWith<EntityNotExistException> { 
            storage.addEdge(testEdge) 
        }
    }

    @Test
    fun addEdge_missingTargetNode_shouldThrowException() {
        storage.addNode(testNode1)
        
        assertFailsWith<EntityNotExistException> { 
            storage.addEdge(testEdge) 
        }
    }

    @Test
    fun getEdgeProperties_existingEdge_shouldReturnProperties() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge, mapOf(testProperty.first to testProperty.second))
        
        val properties = storage.getEdgeProperties(testEdge)
        assertEquals(1, properties.size)
        assertEquals(testProperty.second, properties[testProperty.first])
    }

    @Test
    fun getEdgeProperties_nonexistentEdge_shouldThrowException() {
        assertFailsWith<EntityNotExistException> { 
            storage.getEdgeProperties(testEdge) 
        }
    }

    @Test
    fun setEdgeProperties_existingEdge_shouldUpdateProperties() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        val newProperty = "newProp" to StrVal("newValue")
        
        storage.setEdgeProperties(testEdge, mapOf(newProperty.first to newProperty.second))
        
        assertEquals(newProperty.second, storage.getEdgeProperties(testEdge)[newProperty.first])
    }

    @Test
    fun setEdgeProperties_nonexistentEdge_shouldThrowException() {
        val newProperty = "newProp" to StrVal("newValue")
        
        assertFailsWith<EntityNotExistException> { 
            storage.setEdgeProperties(testEdge, mapOf(newProperty.first to newProperty.second))
        }
    }

    @Test
    fun deleteEdge_existingEdge_shouldRemoveEdge() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        storage.deleteEdge(testEdge)
        
        assertFalse(storage.containsEdge(testEdge))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun deleteEdge_nonexistentEdge_shouldThrowException() {
        assertFailsWith<EntityNotExistException> { 
            storage.deleteEdge(testEdge) 
        }
    }

    // ============================================================================
    // GRAPH STRUCTURE QUERY TESTS
    // ============================================================================

    @Test
    fun getIncomingEdges_nodeWithIncomingEdges_shouldReturnEdges() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        val incomingEdges = storage.getIncomingEdges(testNode2)
        
        assertEquals(setOf(testEdge), incomingEdges)
    }

    @Test
    fun getIncomingEdges_nodeWithoutIncomingEdges_shouldReturnEmpty() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        val incomingEdges = storage.getIncomingEdges(testNode1)
        
        assertTrue(incomingEdges.isEmpty())
    }

    @Test
    fun getIncomingEdges_nonexistentNode_shouldThrowException() {
        assertFailsWith<EntityNotExistException> { 
            storage.getIncomingEdges(testNode1) 
        }
    }

    @Test
    fun getOutgoingEdges_nodeWithOutgoingEdges_shouldReturnEdges() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        val outgoingEdges = storage.getOutgoingEdges(testNode1)
        
        assertEquals(setOf(testEdge), outgoingEdges)
    }

    @Test
    fun getOutgoingEdges_nodeWithoutOutgoingEdges_shouldReturnEmpty() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        
        val outgoingEdges = storage.getOutgoingEdges(testNode2)
        
        assertTrue(outgoingEdges.isEmpty())
    }

    @Test
    fun getOutgoingEdges_nonexistentNode_shouldThrowException() {
        assertFailsWith<EntityNotExistException> { 
            storage.getOutgoingEdges(testNode1) 
        }
    }

    // ============================================================================
    // METADATA OPERATION TESTS
    // ============================================================================

    @Test
    fun setMeta_validMetadata_shouldStoreValue() {
        storage.setMeta("version", StrVal("1.0"))
        
        assertEquals(StrVal("1.0"), storage.getMeta("version"))
    }

    @Test
    fun setMeta_nullValue_shouldDeleteMetadata() {
        storage.setMeta("version", StrVal("1.0"))
        storage.setMeta("version", null)
        
        assertNull(storage.getMeta("version"))
    }

    @Test
    fun getMeta_nonexistentKey_shouldReturnNull() {
        assertNull(storage.getMeta("nonexistent"))
    }

    // ============================================================================
    // UTILITY OPERATION TESTS
    // ============================================================================

    @Test
    fun clear_emptyStorage_shouldReturnTrue() {
        assertTrue(storage.clear())
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun clear_populatedStorage_shouldRemoveAllData() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        storage.setMeta("version", StrVal("1.0"))
        
        assertTrue(storage.clear())
        
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertNull(storage.getMeta("version"))
    }

    @Test
    fun close_storage_shouldPreventOperations() {
        storage.addNode(testNode1)
        storage.close()
        
        assertFailsWith<AccessClosedStorageException> { storage.addNode(testNode2) }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(testNode1) }
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("test") }
    }

    @Test
    fun close_storage_shouldClearData() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        storage.addEdge(testEdge)
        storage.setMeta("version", StrVal("1.0"))
        
        storage.close()
        
        // Data should be cleared after close
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    // ============================================================================
    // BOUNDARY AND EDGE CASE TESTS
    // ============================================================================

    @Test
    fun multipleProperties_shouldHandleCorrectly() {
        storage.addNode(testNode1)
        val properties = mapOf(
            "name" to StrVal("John"),
            "age" to NumVal(30),
            "active" to StrVal("true")
        )
        
        storage.setNodeProperties(testNode1, properties)
        
        val retrieved = storage.getNodeProperties(testNode1)
        assertEquals(3, retrieved.size)
        assertEquals(StrVal("John"), retrieved["name"])
        assertEquals(NumVal(30), retrieved["age"])
        assertEquals(StrVal("true"), retrieved["active"])
    }

    @Test
    fun complexGraphStructure_shouldHandleCorrectly() {
        val nodes = (1..5).map { NodeID("node$it") }
        val edges = listOf(
            EdgeID(nodes[0], nodes[1], "edge1"),
            EdgeID(nodes[1], nodes[2], "edge2"),
            EdgeID(nodes[2], nodes[3], "edge3"),
            EdgeID(nodes[3], nodes[4], "edge4"),
            EdgeID(nodes[0], nodes[4], "edge5")
        )
        
        nodes.forEach { storage.addNode(it) }
        edges.forEach { storage.addEdge(it) }
        
        assertEquals(5, storage.nodeIDs.size)
        assertEquals(5, storage.edgeIDs.size)
        assertEquals(setOf(edges[0], edges[4]), storage.getOutgoingEdges(nodes[0]))
        assertEquals(setOf(edges[3], edges[4]), storage.getIncomingEdges(nodes[4]))
    }

    @Test
    fun selfLoopEdge_shouldHandleCorrectly() {
        storage.addNode(testNode1)
        val selfLoop = EdgeID(testNode1, testNode1, "selfLoop")
        
        storage.addEdge(selfLoop)
        
        assertTrue(storage.containsEdge(selfLoop))
        assertEquals(setOf(selfLoop), storage.getIncomingEdges(testNode1))
        assertEquals(setOf(selfLoop), storage.getOutgoingEdges(testNode1))
    }

    @Test
    fun multipleEdgesBetweenSameNodes_shouldHandleCorrectly() {
        storage.addNode(testNode1)
        storage.addNode(testNode2)
        val edge1 = EdgeID(testNode1, testNode2, "edge1")
        val edge2 = EdgeID(testNode1, testNode2, "edge2")
        
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        
        assertEquals(setOf(edge1, edge2), storage.getOutgoingEdges(testNode1))
        assertEquals(setOf(edge1, edge2), storage.getIncomingEdges(testNode2))
    }
}