package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeConcurStorageImplTest {

    private lateinit var storage: NativeConcurStorageImpl

    private val node1 = StorageTestUtils.node1
    private val node2 = StorageTestUtils.node2
    private val node3 = StorageTestUtils.node3
    private val edge1 = StorageTestUtils.edge1
    private val edge2 = StorageTestUtils.edge2
    private val edge3 = StorageTestUtils.edge3

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
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)

        val nodeIds = storage.nodeIDs

        assertEquals(3, nodeIds.size)
        assertTrue(nodeIds.contains(node1))
        assertTrue(nodeIds.contains(node2))
        assertTrue(nodeIds.contains(node3))
    }

    @Test
    fun `test edgeIDs property returns all edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

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
        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode returns true for existing node`() {
        storage.addNode(node1)

        assertTrue(storage.containsNode(node1))
    }

    @Test
    fun `test containsNode throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.containsNode(node1)
        }
    }

    @Test
    fun `test addNode with empty properties`() {
        storage.addNode(node1)

        assertTrue(storage.containsNode(node1))
        assertTrue(storage.getNodeProperties(node1).isEmpty())
    }

    @Test
    fun `test addNode with properties`() {
        val properties = mapOf(
            "name" to "Node1".strVal,
            "age" to 25.numVal,
            "weight" to 1.5.numVal,
            "active" to true.boolVal
        )

        storage.addNode(node1, properties)

        assertTrue(storage.containsNode(node1))
        val props = storage.getNodeProperties(node1)
        assertEquals(4, props.size)
        assertEquals("Node1", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals(true, (props["active"] as BoolVal).core)
    }

    @Test
    fun `test addNode throws EntityAlreadyExistException when node exists`() {
        storage.addNode(node1)

        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(node1)
        }
    }

    @Test
    fun `test addNode throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.addNode(node1)
        }
    }

    @Test
    fun `test getNodeProperties returns all properties`() {
        val properties = mapOf(
            "prop1" to "value1".strVal,
            "prop2" to 42.numVal
        )
        storage.addNode(node1, properties)

        val props = storage.getNodeProperties(node1)

        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test getNodeProperties throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getNodeProperties(node1)
        }
    }

    @Test
    fun `test getNodeProperties throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getNodeProperties(node1)
        }
    }

    @Test
    fun `test setNodeProperties updates existing properties`() {
        storage.addNode(node1, mapOf("prop1" to "value1".strVal))

        storage.setNodeProperties(node1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("updated", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties deletes properties with null values`() {
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setNodeProperties(node1, mapOf("prop1" to null))

        val props = storage.getNodeProperties(node1)
        assertEquals(1, props.size)
        assertFalse(props.containsKey("prop1"))
        assertTrue(props.containsKey("prop2"))
    }

    @Test
    fun `test setNodeProperties with empty map does not change properties`() {
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setNodeProperties(node1, emptyMap())

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties with mixed null and non-null values`() {
        storage.addNode(node1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setNodeProperties(node1, mapOf(
            "prop1" to null,
            "prop2" to "updated".strVal,
            "prop3" to 100.numVal
        ))

        val props = storage.getNodeProperties(node1)
        assertEquals(2, props.size)
        assertFalse(props.containsKey("prop1"))
        assertEquals("updated", (props["prop2"] as StrVal).core)
        assertEquals(100, (props["prop3"] as NumVal).core)
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(node1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test setNodeProperties throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.setNodeProperties(node1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test deleteNode removes node`() {
        storage.addNode(node1)

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
    }

    @Test
    fun `test deleteNode removes connected edges`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge3)

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
            storage.deleteNode(node1)
        }
    }

    @Test
    fun `test deleteNode throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.deleteNode(node1)
        }
    }

    // endregion

    // region Edge operations

    @Test
    fun `test containsEdge returns false for non-existent edge`() {
        assertFalse(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge returns true for existing edge`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        assertTrue(storage.containsEdge(edge1))
    }

    @Test
    fun `test containsEdge throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.containsEdge(edge1)
        }
    }

    @Test
    fun `test addEdge with empty properties`() {
        storage.addNode(node1)
        storage.addNode(node2)

        storage.addEdge(edge1)

        assertTrue(storage.containsEdge(edge1))
        assertTrue(storage.getEdgeProperties(edge1).isEmpty())
    }

    @Test
    fun `test addEdge with properties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        val properties = mapOf(
            "weight" to 1.5.numVal,
            "label" to "relation".strVal
        )

        storage.addEdge(edge1, properties)

        assertTrue(storage.containsEdge(edge1))
        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals(1.5, (props["weight"] as NumVal).core)
        assertEquals("relation", (props["label"] as StrVal).core)
    }

    @Test
    fun `test addEdge throws EntityAlreadyExistException when edge exists`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        assertFailsWith<EntityAlreadyExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when source node does not exist`() {
        storage.addNode(node2)

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when destination node does not exist`() {
        storage.addNode(node1)

        assertFailsWith<EntityNotExistException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test addEdge throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.addEdge(edge1)
        }
    }

    @Test
    fun `test getEdgeProperties returns all properties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        val properties = mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal)
        storage.addEdge(edge1, properties)

        val props = storage.getEdgeProperties(edge1)

        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException when edge does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeProperties(edge1)
        }
    }

    @Test
    fun `test getEdgeProperties throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getEdgeProperties(edge1)
        }
    }

    @Test
    fun `test setEdgeProperties updates existing properties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal))

        storage.setEdgeProperties(edge1, mapOf("prop1" to "updated".strVal, "prop2" to 42.numVal))

        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("updated", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties deletes properties with null values`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setEdgeProperties(edge1, mapOf("prop1" to null))

        val props = storage.getEdgeProperties(edge1)
        assertEquals(1, props.size)
        assertFalse(props.containsKey("prop1"))
        assertTrue(props.containsKey("prop2"))
    }

    @Test
    fun `test setEdgeProperties with empty map does not change properties`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1, mapOf("prop1" to "value1".strVal, "prop2" to 42.numVal))

        storage.setEdgeProperties(edge1, emptyMap())

        val props = storage.getEdgeProperties(edge1)
        assertEquals(2, props.size)
        assertEquals("value1", (props["prop1"] as StrVal).core)
        assertEquals(42, (props["prop2"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException when edge does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(edge1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test setEdgeProperties throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.setEdgeProperties(edge1, mapOf("prop" to "value".strVal))
        }
    }

    @Test
    fun `test deleteEdge removes edge`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

        storage.deleteEdge(edge1)

        assertFalse(storage.containsEdge(edge1))
        assertTrue(storage.containsNode(node1))
        assertTrue(storage.containsNode(node2))
    }

    @Test
    fun `test deleteEdge removes edge from graph structure leaving empty sets`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)

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
            storage.deleteEdge(edge1)
        }
    }

    @Test
    fun `test deleteEdge throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.deleteEdge(edge1)
        }
    }

    // endregion

    // region Graph structure queries

    @Test
    fun `test getIncomingEdges returns empty set for node with no incoming edges`() {
        storage.addNode(node1)

        val incoming = storage.getIncomingEdges(node1)

        assertTrue(incoming.isEmpty())
    }

    @Test
    fun `test getIncomingEdges returns correct edges for nodes with mixed connections`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3
        storage.addEdge(edge3) // node1 -> node3

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
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        val incoming = storage.getIncomingEdges(node1)

        assertTrue(incoming.contains(selfLoop))
    }

    @Test
    fun `test getIncomingEdges throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getIncomingEdges(node1)
        }
    }

    @Test
    fun `test getIncomingEdges throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getIncomingEdges(node1)
        }
    }

    @Test
    fun `test getOutgoingEdges returns empty set for node with no outgoing edges`() {
        storage.addNode(node1)

        val outgoing = storage.getOutgoingEdges(node1)

        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges returns correct edges for nodes with mixed connections`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3
        storage.addEdge(edge3) // node1 -> node3

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
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

        val outgoing = storage.getOutgoingEdges(node1)

        assertTrue(outgoing.contains(selfLoop))
    }

    @Test
    fun `test getOutgoingEdges throws EntityNotExistException when node does not exist`() {
        assertFailsWith<EntityNotExistException> {
            storage.getOutgoingEdges(node1)
        }
    }

    @Test
    fun `test getOutgoingEdges throws AccessClosedStorageException when closed`() {
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.getOutgoingEdges(node1)
        }
    }

    @Test
    fun `test deleteEdge handles self-loop edge`() {
        storage.addNode(node1)
        val selfLoop = EdgeID(node1, node1, "self")
        storage.addEdge(selfLoop)

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
    fun `test clear removes all data`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.setMeta("key", "value".strVal)

        val result = storage.clear()

        assertTrue(result)
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `test clear removes all graph structure entries`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge1)
        storage.setMeta("key", "value".strVal)

        storage.clear()

        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("key"))
        storage.addNode(node1)
        storage.addNode(node2)
        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test clear on empty storage returns true`() {
        val result = storage.clear()

        assertTrue(result)
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
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node2) }
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node1, mapOf()) }
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(edge1) }
        assertFailsWith<AccessClosedStorageException> { storage.addEdge(edge1) }
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
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)

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
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1) // node1 -> node2
        storage.addEdge(edge2) // node2 -> node3

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
        storage.addNode(node1, mapOf("name" to "Node1".strVal))
        storage.addNode(node2, mapOf("name" to "Node2".strVal))
        storage.addNode(node3, mapOf("name" to "Node3".strVal))

        storage.addEdge(edge1, mapOf("weight" to 1.0.numVal))
        storage.addEdge(edge2, mapOf("weight" to 2.0.numVal))
        storage.addEdge(edge3, mapOf("weight" to 3.0.numVal))

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
                        val nodeId = NodeID("node_${t}_$i")
                        storage.addNode(nodeId, mapOf("thread" to t.toString().strVal, "index" to i.numVal))
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
        storage.addNode(node1, mapOf("counter" to 0.numVal))
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
        for (i in 0 until 100) {
            storage.addNode(NodeID("test_node_$i"), mapOf("index" to i.numVal))
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
                            storage.deleteNode(NodeID("test_node_$i"))
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
                        val nodeId = NodeID("test_node_$i")
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
        for (i in 0 until 50) {
            storage.addNode(NodeID("node_$i"))
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
                        val srcNode = NodeID("node_$srcIdx")
                        val dstNode = NodeID("node_$dstIdx")
                        val edgeId = EdgeID(srcNode, dstNode, "edge_${t}_$i")
                        storage.addEdge(edgeId, mapOf("thread" to t.toString().strVal))
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
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge1)
        storage.addEdge(edge2)
        storage.addEdge(edge3)
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
        storage.addNode(node1, mapOf("counter" to 0.numVal))
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
        for (i in 0 until 50) {
            storage.addNode(NodeID("node_$i"))
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
                        val nodeId = NodeID("node_$i")
                        storage.containsNode(nodeId)
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
