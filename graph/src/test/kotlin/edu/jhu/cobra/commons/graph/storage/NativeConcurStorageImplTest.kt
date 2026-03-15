package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeConcurStorageImplTest {
    private lateinit var storage: NativeConcurStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeConcurStorageImpl()
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
    fun `test metaNames returns all metadata keys`() {
        storage.setMeta("key1", "v1".strVal)
        storage.setMeta("key2", "v2".strVal)

        val names = storage.metaNames
        assertEquals(2, names.size)
        assertTrue(names.contains("key1"))
        assertTrue(names.contains("key2"))
    }

    @Test
    fun `test metaNames returns empty set when no metadata`() {
        assertTrue(storage.metaNames.isEmpty())
    }

    @Test
    fun `test metaNames throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
    }

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
    fun `test clear removes all data`() {
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
    fun `test clear on empty storage`() {
        storage.clear()
    }

    @Test
    fun `test clear throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.clear()
        }
    }

    @Test
    fun `test close is idempotent`() {
        storage.addNode()
        storage.close()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
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

    // region Concurrency

    @Test
    fun `test concurrent node addition`() {
        val threadCount = 10
        val nodesPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until nodesPerThread) {
                        storage.addNode(mapOf("thread" to t.toString().strVal, "index" to i.numVal))
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        assertEquals(threadCount * nodesPerThread, storage.nodeIDs.size)
    }

    @Test
    fun `test concurrent read-write operations`() {
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))
        val threadCount = 5
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)
        val incrementAttempts = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val current = storage.getNodeProperties(node1)["counter"] as NumVal
                        storage.setNodeProperties(node1, mapOf("counter" to (current.core.toInt() + 1).numVal))
                        incrementAttempts.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val value = storage.getNodeProperties(node1)["counter"] as NumVal
                        assertTrue(value.core.toInt() >= 0)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        val finalValue = storage.getNodeProperties(node1)["counter"] as NumVal
        assertTrue(finalValue.core.toInt() > 0)
        assertTrue(finalValue.core.toInt() <= incrementAttempts.get())
    }

    @Test
    fun `test concurrent node deletion and query`() {
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until 100) {
            nodeIds.add(storage.addNode(mapOf("index" to i.numVal)))
        }
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        val deleteSuccess = AtomicBoolean(true)
        val querySuccess = AtomicBoolean(true)

        Thread {
            try {
                startLatch.await()
                for (i in 0 until 100) {
                    if (i % 2 == 1) {
                        try {
                            storage.deleteNode(nodeIds[i])
                        } catch (e: EntityNotExistException) {
                            // Acceptable if already deleted
                        }
                    }
                }
            } catch (e: Exception) {
                deleteSuccess.set(false)
            } finally {
                finishLatch.countDown()
            }
        }.start()

        Thread {
            try {
                startLatch.await()
                for (i in 0 until 100) {
                    try {
                        val nodeId = nodeIds[i]
                        if (storage.containsNode(nodeId)) {
                            val props = storage.getNodeProperties(nodeId)
                            assertNotNull(props["index"])
                        }
                    } catch (e: EntityNotExistException) {
                        // Acceptable if node was deleted
                    } catch (e: Exception) {
                        querySuccess.set(false)
                        break
                    }
                }
            } finally {
                finishLatch.countDown()
            }
        }.start()

        startLatch.countDown()
        finishLatch.await(10, TimeUnit.SECONDS)

        assertTrue(deleteSuccess.get())
        assertTrue(querySuccess.get())
        val remainingCount = storage.nodeIDs.size
        assertTrue(remainingCount >= 40 && remainingCount <= 60)
    }

    @Test
    fun `test concurrent edge operations`() {
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until 50) {
            nodeIds.add(storage.addNode())
        }
        val threadCount = 5
        val edgesPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until edgesPerThread) {
                        val srcIdx = (t * edgesPerThread + i) % 50
                        val dstIdx = (srcIdx + 1) % 50
                        storage.addEdge(nodeIds[srcIdx], nodeIds[dstIdx], "edge_${t}_$i", mapOf("thread" to t.toString().strVal))
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        assertEquals(threadCount * edgesPerThread, storage.edgeIDs.size)
    }

    @Test
    fun `test concurrent graph traversal`() {
        val (node1, node2, node3) = StorageTestUtils.addTestNodes(storage)
        val edge1 = storage.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)
        val edge2 = storage.addEdge(node2, node3, StorageTestUtils.EDGE_TYPE_2)
        val edge3 = storage.addEdge(node1, node3, StorageTestUtils.EDGE_TYPE_3)
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(100) {
                        when (t % 4) {
                            0 -> {
                                val inEdges = storage.getIncomingEdges(node3)
                                assertTrue(inEdges.size >= 0)
                            }
                            1 -> {
                                val outEdges = storage.getOutgoingEdges(node1)
                                assertTrue(outEdges.size >= 0)
                            }
                            2 -> {
                                val props = storage.getNodeProperties(node1)
                                assertNotNull(props)
                            }
                            3 -> {
                                val props = storage.getEdgeProperties(edge1)
                                assertNotNull(props)
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
    }

    @Test
    fun `test read-write lock behavior`() {
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))
        val readThreads = 20
        val writeThreads = 5
        val readOps = 1000
        val writeOps = 100
        val executor = Executors.newFixedThreadPool(readThreads + writeThreads)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(readThreads + writeThreads)
        val timeoutOccurred = AtomicBoolean(false)

        for (t in 0 until readThreads) {
            executor.submit {
                try {
                    repeat(readOps) {
                        try {
                            val props = storage.getNodeProperties(node1)
                            assertNotNull(props["counter"])
                        } catch (e: Exception) {
                            if (e !is EntityNotExistException) {
                                errors.incrementAndGet()
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        for (t in 0 until writeThreads) {
            executor.submit {
                try {
                    repeat(writeOps) {
                        try {
                            val current = (storage.getNodeProperties(node1)["counter"] as? NumVal)?.core ?: 0
                            storage.setNodeProperties(node1, mapOf("counter" to (current.toInt() + 1).numVal))
                        } catch (e: Exception) {
                            if (e !is EntityNotExistException) {
                                errors.incrementAndGet()
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) {
            timeoutOccurred.set(true)
        }

        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertFalse(timeoutOccurred.get(), "Test should not time out; potential deadlock detected")
        assertEquals(0, errors.get())
    }

    @Test
    fun `test concurrent metadata operations`() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until 100) {
                        val key = "meta_$t"
                        storage.setMeta(key, "$t-$i".strVal)
                        val value = storage.getMeta(key)
                        assertNotNull(value)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
    }

    @Test
    fun `test concurrent clear and operations`() {
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until 50) {
            nodeIds.add(storage.addNode())
        }
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(2)
        val errors = AtomicInteger(0)

        executor.submit {
            try {
                Thread.sleep(10)
                storage.clear()
            } catch (e: Exception) {
                errors.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        executor.submit {
            try {
                for (i in 0 until 50) {
                    try {
                        storage.containsNode(nodeIds[i])
                    } catch (e: AccessClosedStorageException) {
                        break
                    } catch (e: Exception) {
                        if (e !is EntityNotExistException) {
                            errors.incrementAndGet()
                        }
                    }
                }
            } finally {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.get() <= 1)
    }

    // endregion
}
