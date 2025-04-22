package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.*
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import kotlin.test.*

class MapDBStorageImplTest {
    private lateinit var storage: MapDBStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge1 = EdgeID(node1, node2, "edge1")
    private val edge2 = EdgeID(node2, node3, "edge2")
    private val edge3 = EdgeID(node1, node3, "edge3")

    @BeforeTest
    fun setup() {
        storage = MapDBStorageImpl(DftByteArraySerializerImpl) { memoryDB() }
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    @Test
    fun `test storage initialization`() {
        assertNotNull(storage)
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
    }

    @Test
    fun `test add and get node`() {
        storage.addNode(node1, "prop1" to "value1".strVal)
        assertTrue(storage.containsNode(node1))
        assertEquals(1, storage.nodeSize)

        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
    }

    @Test
    fun `test add duplicate node`() {
        storage.addNode(node1)
        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(node1)
        }
    }

    @Test
    fun `test add and get edge`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, "prop1" to "value1".strVal)

        assertTrue(storage.containsEdge(edge1))
        assertEquals(1, storage.edgeSize)

        val props = storage.getEdgeProperties(edge1)
        assertEquals(1, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
    }

    @Test
    fun `test add edge with non-existent nodes`() {
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test get node properties`() {
        storage.addNode(node1, "prop1" to "value1".strVal, "prop2" to 42.numVal)
        val props = storage.getNodeProperties(node1)

        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test get non-existent node properties`() {
        assertFailsWith<EntityNotExistException> {
            storage.getNodeProperties(node1)
        }
    }

    @Test
    fun `test set node properties`() {
        storage.addNode(node1, "prop1" to "value1".strVal)
        storage.setNodeProperties(node1, "prop1" to "newValue".strVal, "prop2" to 42.numVal)

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("newValue", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test delete node`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test delete non-existent node`() {
        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(node1)
        }
    }

    @Test
    fun `test delete edge`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test get incoming and outgoing edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        val incoming = storage.getIncomingEdges(node2)
        val outgoing = storage.getOutgoingEdges(node1)

        assertEquals(1, incoming.size)
        assertEquals(1, outgoing.size)
        assertTrue(incoming.contains(edge1))
        assertTrue(outgoing.contains(edge1))
    }

    @Test
    fun `test get edges between nodes`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        val edges = storage.getEdgesBetween(node1, node2)
        assertEquals(1, edges.size)
        assertTrue(edges.contains(edge1))
    }

    @Test
    fun `test clear storage`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        assertTrue(storage.clear())
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
    }

    @Test
    fun `test access after close`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> {
            storage.nodeSize
        }
    }

    @Test
    fun `test node IDs sequence`() {
        storage.addNode(node1)
        storage.addNode(node2)

        val ids = storage.nodeIDsSequence.toList()
        assertEquals(2, ids.size)
        assertTrue(ids.contains(node1))
        assertTrue(ids.contains(node2))
    }

    @Test
    fun `test edge IDs sequence`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        val ids = storage.edgeIDsSequence.toList()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(edge1))
    }

    @Test
    fun `test delete nodes with condition`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteNodes { it == node1 }
        assertFalse(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test delete edges with condition`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteEdges { it == edge1 }
        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `test add node with empty properties`() {
        storage.addNode(node1)
        assertTrue(storage.containsNode(node1))
        assertEquals(0, storage.getNodeProperties(node1).size)
    }

    @Test
    fun `test add node with null property value`() {
        storage.addNode(node1, "prop1" to NullVal)
        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertTrue(props["prop1"] is NullVal)
    }

    @Test
    fun `test add node with complex property values`() {
        val complexValue = mapOf(
            "str" to "test".strVal,
            "num" to 42.numVal,
            "bool" to true.boolVal,
            "list" to listOf(1.numVal, 2.numVal, 3.numVal).listVal,
            "map" to mapOf("nested" to "value".strVal).mapVal
        ).mapVal

        storage.addNode(node1, "complex" to complexValue)
        val props = storage.getNodeProperties(node1)
        assertEquals(complexValue, props["complex"])
    }

    @Test
    fun `test add multiple edges between same nodes`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.addEdge(EdgeID(node1, node2, "edge4"))

        val edges = storage.getEdgesBetween(node1, node2)
        assertEquals(2, edges.size)
    }

    @Test
    fun `test get non-existent edge properties`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeProperties(edge1)
        }
    }

    @Test
    fun `test set edge properties with null values`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, "prop1" to "value1".strVal)

        storage.setEdgeProperties(edge1, "prop1" to null)
        val props = storage.getEdgeProperties(edge1)
        assertFalse("prop1" in props)
    }

    @Test
    fun `test delete node with multiple edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

        storage.deleteNode(node1)
        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsEdge(edge2))
    }

    @Test
    fun `test delete edge with complex properties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        val complexValue = mapOf(
            "str" to "test".strVal,
            "num" to 42.numVal
        ).mapVal
        storage.addEdge(edge1, "complex" to complexValue)

        storage.deleteEdge(edge1)
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test get edges between non-existent nodes`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgesBetween(node1, node2)
        }
    }

    @Test
    fun `test get incoming edges for node with no edges`() {
        storage.addNode(node1)
        val edges = storage.getIncomingEdges(node1)
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test get outgoing edges for node with no edges`() {
        storage.addNode(node1)
        val edges = storage.getOutgoingEdges(node1)
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test clear empty storage`() {
        assertTrue(storage.clear())
        assertEquals(0, storage.nodeSize)
        assertEquals(0, storage.edgeSize)
    }

    @Test
    fun `test node IDs sequence for empty storage`() {
        val ids = storage.nodeIDsSequence.toList()
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `test edge IDs sequence for empty storage`() {
        val ids = storage.edgeIDsSequence.toList()
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `test delete nodes with no matching condition`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteNodes { false }
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test delete edges with no matching condition`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteEdges { false }
        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test storage persistence`() {
        storage.addNode(node1, "prop1" to "value1".strVal)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.commit()

        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsEdge(edge1))
        assertEquals("value1", (storage.getNodeProperties(node1)["prop1"] as StrVal).core)

        val newStorage = MapDBStorageImpl(DftByteArraySerializerImpl) { memoryDB() }
        assertFalse(newStorage.containsNode(node1))
        assertFalse(newStorage.containsNode(node2))
        assertFalse(newStorage.containsEdge(edge1))
    }

    @Test
    fun `test storage asynchronous behavior`() {
        // Test without commit
        storage.addNode(node1, "prop1" to "value1".strVal)
        val newStorage1 = MapDBStorageImpl(DftByteArraySerializerImpl) { memoryDB() }
        assertFalse(newStorage1.containsNode(node1))

        // Test with commit
        storage.commit()
        val newStorage2 = MapDBStorageImpl(DftByteArraySerializerImpl) { memoryDB() }
        assertFalse(newStorage2.containsNode(node1)) // Still false because it's a new instance

        // Test concurrent updates with commit
        val thread1 = Thread {
            storage.setNodeProperties(node1, "prop1" to "newValue1".strVal)
            storage.commit()
        }
        val thread2 = Thread {
            storage.setNodeProperties(node1, "prop2" to 42.numVal)
            storage.commit()
        }

        thread1.start()
        thread2.start()
        thread1.join()
        thread2.join()

        // Verify final state
        val props = storage.getNodeProperties(node1)
        assertTrue("prop1" in props)
        assertTrue("prop2" in props)
        assertEquals("newValue1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test storage with transaction support`() {
        // Create storage with transaction support
        val txStorage = MapDBStorageImpl(DftByteArraySerializerImpl) {
            memoryDB().transactionEnable()
        }

        try {
            // Test basic operations
            txStorage.addNode(node1, "prop1" to "value1".strVal)
            txStorage.commit()
            assertTrue(txStorage.containsNode(node1))

            // Test concurrent operations
            val thread1 = Thread {
                txStorage.setNodeProperties(node1, "prop1" to "newValue1".strVal)
                txStorage.commit()
            }
            val thread2 = Thread {
                txStorage.setNodeProperties(node1, "prop2" to 42.numVal)
                txStorage.commit()
            }

            thread1.start()
            thread2.start()
            thread1.join()
            thread2.join()

            // Verify final state
            val props = txStorage.getNodeProperties(node1)
            assertTrue("prop1" in props)
            assertTrue("prop2" in props)
            assertEquals("newValue1", (props["prop1"] as StrVal).core)
            assertEquals(42, (props["prop2"] as NumVal).core)
        } finally {
            txStorage.close()
        }
    }

    @Test
    fun `test storage with file-based persistence`() {
        val fileStorage = MapDBStorageImpl(DftByteArraySerializerImpl) {
            tempFileDB().transactionEnable()
        }

        try {
            // Test basic operations
            fileStorage.addNode(node1, "prop1" to "value1".strVal)
            fileStorage.commit()
            assertTrue(fileStorage.containsNode(node1))

            // Test data persistence
            fileStorage.addNode(node2, "prop2" to "value2".strVal)
            fileStorage.commit()

            // Verify data is accessible
            assertTrue(fileStorage.containsNode(node1))
            assertTrue(fileStorage.containsNode(node2))
            assertEquals("value1", (fileStorage.getNodeProperties(node1)["prop1"] as StrVal).core)
            assertEquals("value2", (fileStorage.getNodeProperties(node2)["prop2"] as StrVal).core)
        } finally {
            fileStorage.close()
        }
    }
}
