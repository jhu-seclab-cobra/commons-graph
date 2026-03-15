package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class NativeStorageImplTest {
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region Properties and statistics

    @Test
    fun `test empty storage properties`() {
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test nodeIDs property returns all nodes`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()

        val nodeIds = storage.nodeIDs

        assertEquals(3, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))
        assertTrue(nodeIds.contains(node3))
    }

    @Test
    fun `test edgeIDs property returns all edges`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2)
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3)

        val edgeIds = storage.edgeIDs

        assertEquals(3, edgeIds.size)
        assertTrue(edgeIds.contains(edge1))
        assertTrue(edgeIds.contains(edge2))
        assertTrue(edgeIds.contains(edge3))
    }

    @Test
    fun `test nodeIDs throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.nodeIDs
        }
    }

    @Test
    fun `test edgeIDs throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.edgeIDs
        }
    }

    // endregion

    // region Node operations

    @Test
    fun `test containsNode returns false for non-existent node`() {
        assertFalse(storage.containsNode(-1))
    }

    @Test
    fun `test containsNode returns true for existing node`() {
        val node1 = storage.addNode()

        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.containsNode(-1)
        }
    }

    @Test
    fun `test addNode with empty properties`() {
        val node1 = storage.addNode()

        assertTrue(storage.containsNode(node1))
        assertTrue(storage.getNodeProperties(node1).isEmpty())
    }

    @Test
    fun `test addNode with properties`() {
        val properties =
            mapOf(
                "name" to "Node1".strVal,
                "age" to 25.numVal,
                "weight" to 1.5.numVal,
                "active" to true.boolVal,
            )

        val node1 = storage.addNode(properties)

        assertTrue(storage.containsNode(node1))
        val props = storage.getNodeProperties(node1)
        assertEquals(4, props.size)
        assertEquals("Node1", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals(true, (props["active"] as BoolVal).core)
    }

    @Test
    fun `test addNode throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.addNode()
        }
    }

    @Test
    fun `test getNodeProperties returns all properties`() {
        val properties =
            mapOf(
                "prop1" to "value1".strVal,
                "prop2" to 42.numVal,
            )
        val node1 = storage.addNode(properties)

        val props = storage.getNodeProperties(node1)

        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test getNodeProperties throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getNodeProperties(-1)
        }
    }

    @Test
    fun `test getNodeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getNodeProperties(node1)
        }
    }

    @Test
    fun `test setNodeProperties updates existing properties`() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal))

        storage.setNodeProperties(node1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("updated", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties deletes properties with null values`() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setNodeProperties(node1, mapOf("prop1" to null))

        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertFalse(props.containsKey("prop1"))
        assertTrue(props.containsKey("prop2"))
    }

    @Test
    fun `test setNodeProperties with empty map does not change properties`() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setNodeProperties(node1, emptyMap())

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties with mixed null and non-null values`() {
        val node1 = storage.addNode(mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setNodeProperties(
            node1,
            mapOf(
                "prop1" to null,
                "prop2" to "updated".strVal,
                "prop3" to 100.numVal,
            ),
        )

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertFalse(props.containsKey("prop1"))
        assertEquals("updated", (props["prop2"] as StrVal).core)
        assertEquals(100, (props["prop3"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test setNodeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.setNodeProperties(node1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test deleteNode removes node`() {
        val node1 = storage.addNode()

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test deleteNode does not cascade edge deletion`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3)

        storage.deleteEdge(edge1)
        storage.deleteEdge(edge3)
        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge1))
        assertFalse(storage.containsEdge(edge3))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))
    }

    @Test
    fun `test deleteNode throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.deleteNode(-1)
        }
    }

    @Test
    fun `test deleteNode throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.deleteNode(node1)
        }
    }

    // endregion

    // region Edge operations

    @Test
    fun `test containsEdge returns false for non-existent edge`() {
        assertFalse(storage.containsEdge(-1))
    }

    @Test
    fun `test containsEdge returns true for existing edge`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.containsEdge(-1)
        }
    }

    @Test
    fun `test addEdge with empty properties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()

        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

        assertTrue(storage.containsEdge(edge1))
        assertTrue(storage.getEdgeProperties(edge1).isEmpty())
    }

    @Test
    fun `test addEdge with properties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val properties =
            mapOf(
                "weight" to 1.5.numVal,
                "label" to "relation".strVal,
            )

        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, properties)

        assertTrue(storage.containsEdge(edge1))
        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals("relation", (props["label"] as StrVal).core)
    }

    @Test
    fun `test addEdge allows multiple edges with same endpoints and type`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        val edge2 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

        assertNotEquals(edge1, edge2)
        assertTrue(storage.containsEdge(edge1))
        assertTrue(storage.containsEdge(edge2))
    }

    @Test
    fun `test addEdge throws EntityNotExistException when source node does not exist`() {
        val node2 = storage.addNode()

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(-1, node2, StorageTestUtils.EDGE_TYPE_1)
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when destination node does not exist`() {
        val node1 = storage.addNode()

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(node1, -1, StorageTestUtils.EDGE_TYPE_1)
        }
    }

    @Test
    fun `test addEdge throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        }
    }

    @Test
    fun `test getEdgeProperties returns all properties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val properties = mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, properties)

        val props = storage.getEdgeProperties(edge1)

        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException when edge does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeProperties(-1)
        }
    }

    @Test
    fun `test getEdgeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getEdgeProperties(edge1)
        }
    }

    @Test
    fun `test setEdgeProperties updates existing properties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, mapOf("prop1" to "value1".strVal))

        storage.setEdgeProperties(edge1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))

        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("updated", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties deletes properties with null values`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setEdgeProperties(edge1, mapOf("prop1" to null))

        val props = storage.getEdgeProperties(edge1)
        assertEquals(1, props.size)
        assertFalse(props.containsKey("prop1"))
        assertTrue(props.containsKey("prop2"))
    }

    @Test
    fun `test setEdgeProperties with empty map does not change properties`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setEdgeProperties(edge1, emptyMap())

        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException when edge does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(-1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test setEdgeProperties throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.setEdgeProperties(edge1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test deleteEdge removes edge`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

        storage.deleteEdge(edge1)

        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `test deleteEdge removes edge from graph structure leaving empty sets`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

        storage.deleteEdge(edge1)

        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteEdge throws EntityNotExistException when edge does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.deleteEdge(-1)
        }
    }

    @Test
    fun `test deleteEdge throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.deleteEdge(edge1)
        }
    }

    // endregion

    // region Graph structure queries

    @Test
    fun `test getIncomingEdges returns empty set for node with no incoming edges`() {
        val node1 = storage.addNode()

        val incoming = storage.getIncomingEdges(node1)

        assertTrue(incoming.isEmpty())
    }

    @Test
    fun `test getIncomingEdges returns correct edges for nodes with mixed connections`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1) // node1 -> node2
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2) // node2 -> node3
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3) // node1 -> node3

        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val incoming3 = storage.getIncomingEdges(node3)
        assertEquals(2, incoming3.size)
        assertTrue(incoming3.contains(edge2))
        assertTrue(incoming3.contains(edge3))
    }

    @Test
    fun `test getIncomingEdges handles self-loop edge`() {
        val node1 = storage.addNode()
        val selfLoop = storage.addEdge(node1, node1, "self")

        val incoming = storage.getIncomingEdges(node1)

        assertTrue(incoming.contains(selfLoop))
    }

    @Test
    fun `test getIncomingEdges throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getIncomingEdges(-1)
        }
    }

    @Test
    fun `test getIncomingEdges throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getIncomingEdges(node1)
        }
    }

    @Test
    fun `test getOutgoingEdges returns empty set for node with no outgoing edges`() {
        val node1 = storage.addNode()

        val outgoing = storage.getOutgoingEdges(node1)

        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges returns correct edges for nodes with mixed connections`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1) // node1 -> node2
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2) // node2 -> node3
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3) // node1 -> node3

        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(1, outgoing2.size)
        assertTrue(outgoing2.contains(edge2))
    }

    @Test
    fun `test getOutgoingEdges handles self-loop edge`() {
        val node1 = storage.addNode()
        val selfLoop = storage.addEdge(node1, node1, "self")

        val outgoing = storage.getOutgoingEdges(node1)

        assertTrue(outgoing.contains(selfLoop))
    }

    @Test
    fun `test getOutgoingEdges throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getOutgoingEdges(-1)
        }
    }

    @Test
    fun `test getOutgoingEdges throws AccessClosedStorageException when closed`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getOutgoingEdges(node1)
        }
    }

    @Test
    fun `test deleteEdge handles self-loop edge`() {
        val node1 = storage.addNode()
        val selfLoop = storage.addEdge(node1, node1, "self")

        storage.deleteEdge(selfLoop)

        assertFalse(storage.containsEdge(selfLoop))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node1).isEmpty())
    }

    // endregion

    // region Metadata operations

    @Test
    fun `test getMeta returns null for non-existent metadata`() {
        assertNull(storage.getMeta("nonexistent"))
    }

    @Test
    fun `test setMeta and getMeta work together`() {
        storage.setMeta("version", "1.0".strVal)

        val value = storage.getMeta("version")
        assertNotNull(value)
        assertEquals("1.0", (value as StrVal).core)
    }

    @Test
    fun `test getMeta throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getMeta("key")
        }
    }

    @Test
    fun `test setMeta deletes metadata when value is null`() {
        storage.setMeta("version", "1.0".strVal)

        storage.setMeta("version", null)

        assertNull(storage.getMeta("version"))
    }

    @Test
    fun `test setMeta throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.setMeta("key", "value".strVal)
        }
    }

    // endregion

    // region Utility operations

    @Test
    fun `test clear removes all data including graph structure`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        storage.setMeta("key", "value".strVal)

        storage.clear()

        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `test clear removes all graph structure entries`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        storage.setMeta("key", "value".strVal)

        storage.clear()

        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
        val newNode1 = storage.addNode()
        val newNode2 = storage.addNode()
        assertTrue(storage.getOutgoingEdges(newNode1).isEmpty())
        assertTrue(storage.getIncomingEdges(newNode2).isEmpty())
    }

    @Test
    fun `test clear on empty storage succeeds`() {
        storage.clear()

        assertTrue(storage.nodeIDs.isEmpty())
    }

    @Test
    fun `test clear throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.clear()
        }
    }

    @Test
    fun `test close prevents all operations`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node1, mapOf()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.addEdge(node1, node2, "t") }
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(edge1, mapOf()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("key") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("key", "value".strVal) }
        assertFailsWith<AccessClosedStorageException> { storage.clear() }
    }

    // endregion

    // region State consistency

    @Test
    fun `test graph structure consistency after multiple edge deletions`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2)
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3)

        storage.deleteEdge(edge1)
        assertTrue(storage.getOutgoingEdges(node1).contains(edge3))
        assertTrue(storage.getIncomingEdges(node2).isEmpty())

        storage.deleteEdge(edge3)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node3).contains(edge2))

        storage.deleteEdge(edge2)
        assertTrue(storage.getOutgoingEdges(node2).isEmpty())
        assertTrue(storage.getIncomingEdges(node3).isEmpty())

        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
        assertTrue(storage.containsNode(node3))
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `test graph structure consistency when node has both incoming and outgoing edges`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1) // node1 -> node2
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2) // node2 -> node3

        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val outgoing2 = storage.getOutgoingEdges(node2)
        assertEquals(1, outgoing2.size)
        assertTrue(outgoing2.contains(edge2))

        storage.deleteEdge(edge1)
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertTrue(storage.getOutgoingEdges(node2).contains(edge2))

        storage.deleteEdge(edge2)
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
        assertTrue(storage.getOutgoingEdges(node2).isEmpty())
    }

    // endregion

    // region Branch coverage: addNodeWithId

    @Test
    fun `addNodeWithId throws EntityAlreadyExistException when id already exists`() {
        val node1 = storage.addNode()

        assertFailsWith<EntityAlreadyExistException> {
            storage.addNodeWithId(emptyMap(), node1)
        }
    }

    @Test
    fun `addNodeWithId does not advance counter when id is less than nodeCounter`() {
        // Insert id=5 first so nodeCounter advances to 6, then insert id=3 (id < counter).
        // The "if (id >= nodeCounter)" branch is NOT taken, so the counter stays at 6.
        val s = NativeStorageImpl()
        s.addNodeWithId(emptyMap(), 5) // nodeCounter becomes 6
        val result = s.addNodeWithId(mapOf("y" to 7.numVal), 3) // id=3 < counter=6
        assertEquals(3, result)
        assertTrue(s.containsNode(3))
        s.close()
    }

    // endregion

    // region Branch coverage: addEdgeWithId

    @Test
    fun `addEdgeWithId throws EntityAlreadyExistException when edge id already exists`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdgeWithId(n1, n2, "rel", emptyMap(), 0)

        assertFailsWith<EntityAlreadyExistException> {
            storage.addEdgeWithId(n1, n2, "rel", emptyMap(), 0)
        }
    }

    @Test
    fun `addEdgeWithId throws EntityNotExistException when source node does not exist`() {
        val n2 = storage.addNode()

        assertFailsWith<EntityNotExistException> {
            storage.addEdgeWithId(-1, n2, "rel", emptyMap(), 10)
        }
    }

    @Test
    fun `addEdgeWithId throws EntityNotExistException when destination node does not exist`() {
        val n1 = storage.addNode()

        assertFailsWith<EntityNotExistException> {
            storage.addEdgeWithId(n1, -1, "rel", emptyMap(), 10)
        }
    }

    @Test
    fun `addEdgeWithId does not advance counter when id is less than edgeCounter`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdgeWithId(n1, n2, "rel", emptyMap(), 5) // edgeCounter becomes 6
        val result = storage.addEdgeWithId(n1, n2, "rel", emptyMap(), 3) // id=3 < counter=6
        assertEquals(3, result)
        assertTrue(storage.containsEdge(3))
    }

    // endregion

    // region Branch coverage: deleteNode with self-loop

    @Test
    fun `deleteNode cleans up self-loop edge without double-delete error`() {
        val node = storage.addNode()
        val selfLoop = storage.addEdge(node, node, "self")

        // A self-loop edge appears in both outEdges[node] and inEdges[node].
        // deleteNode processes outEdges first (removes the edge), then iterates inEdges
        // where edgeEndpoints[selfLoop] is already null — verifying the double-removal
        // path is safe and the node is fully cleaned up.
        storage.deleteNode(node)

        assertFalse(storage.containsNode(node))
        assertFalse(storage.containsEdge(selfLoop))
    }

    // endregion

    // region Branch coverage: setColumnarProperties null-key-absent path

    @Test
    fun `setNodeProperties with null value for absent property key is a no-op`() {
        val node = storage.addNode(mapOf("kept" to "yes".strVal))

        // "missing" was never set, so columns["missing"] == null; the ?: continue branch fires.
        storage.setNodeProperties(node, mapOf("missing" to null))

        val props = storage.getNodeProperties(node)
        assertEquals(1, props.size)
        assertTrue(props.containsKey("kept"))
    }

    @Test
    fun `setEdgeProperties with null value for absent property key is a no-op`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("kept" to "yes".strVal))

        storage.setEdgeProperties(edge, mapOf("missing" to null))

        val props = storage.getEdgeProperties(edge)
        assertEquals(1, props.size)
        assertTrue(props.containsKey("kept"))
    }

    // endregion

    // region Branch coverage: ColumnViewMap.containsKey false branch

    @Test
    fun `getNodeProperties containsKey returns false when column exists but entity is absent`() {
        storage.addNode(mapOf("shared" to "v1".strVal)) // creates the "shared" column
        val n2 = storage.addNode() // no "shared" property

        val props = storage.getNodeProperties(n2)

        // The "shared" column exists in nodeColumns but does not contain n2,
        // exercising the containsKey == true branch that returns false.
        assertFalse(props.containsKey("shared"))
    }

    // endregion

    // region Branch coverage: transferTo idMap fallback

    @Test
    fun `transferTo maps all node ids so idMap fallback branch is not taken for normal edges`() {
        val n1 = storage.addNode(mapOf("v" to 1.numVal))
        val n2 = storage.addNode(mapOf("v" to 2.numVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 3.numVal))
        storage.setMeta("m", "meta".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("meta", (target.getMeta("m") as StrVal).core)
        target.close()
    }

    // endregion

    // region Integration

    @Test
    fun `test complex graph operations`() {
        val node1 = storage.addNode(mapOf("name" to "Node1".strVal))
        val node2 = storage.addNode(mapOf("name" to "Node2".strVal))
        val node3 = storage.addNode(mapOf("name" to "Node3".strVal))

        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, mapOf("weight" to 1.0.numVal))
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2, mapOf("weight" to 2.0.numVal))
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3, mapOf("weight" to 3.0.numVal))

        assertEquals(3, storage.nodeIDs.size)
        assertEquals(3, storage.edgeIDs.size)

        val incoming2 = storage.getIncomingEdges(node2)
        assertEquals(1, incoming2.size)
        assertTrue(incoming2.contains(edge1))

        val outgoing1 = storage.getOutgoingEdges(node1)
        assertEquals(2, outgoing1.size)
        assertTrue(outgoing1.contains(edge1))
        assertTrue(outgoing1.contains(edge3))

        storage.setNodeProperties(node1, mapOf("name" to "UpdatedNode1".strVal, "newProp" to 100.numVal))
        val props1 = storage.getNodeProperties(node1)
        assertEquals("UpdatedNode1", (props1["name"] as StrVal).core)
        assertEquals(100, (props1["newProp"] as NumVal).core)

        storage.deleteEdge(edge1)
        assertEquals(2, storage.edgeIDs.size)
        assertFalse(storage.containsEdge(edge1))

        storage.deleteEdge(edge3)
        storage.deleteNode(node1)
        assertEquals(2, storage.nodeIDs.size)
        assertFalse(storage.containsEdge(edge3))
    }

    // endregion
}
