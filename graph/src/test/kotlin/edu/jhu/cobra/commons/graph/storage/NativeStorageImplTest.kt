package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.*
import kotlin.test.*

class NativeStorageImplTest {
    private lateinit var storage: NativeStorageImpl
    private var nodeIdCounter = 0
    private var edgeIdCounter = 0

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        nodeIdCounter = 0
        edgeIdCounter = 0
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    private fun addNode(): String = storage.addNode("node_${nodeIdCounter++}")

    private fun addEdge(
        src: String,
        dst: String,
        tag: String = "rel",
    ): String = storage.addEdge(src, dst, "edge_${edgeIdCounter++}", tag)

    // ============================================================================
    // BASIC OPERATIONS
    // ============================================================================

    @Test
    fun `empty storage has no nodes or edges`() {
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `add node returns the node ID`() {
        val nodeId = addNode()
        assertTrue(storage.containsNode(nodeId))
    }

    @Test
    fun `add duplicate node throws exception`() {
        val nodeId = addNode()
        assertFailsWith<EntityAlreadyExistException> {
            storage.addNode(nodeId)
        }
    }

    @Test
    fun `nodeIDs property returns all added nodes`() {
        val n1 = addNode()
        val n2 = addNode()
        val n3 = addNode()
        val allIds = storage.nodeIDs
        assertEquals(3, allIds.size)
        assertTrue(allIds.contains(n1))
        assertTrue(allIds.contains(n2))
        assertTrue(allIds.contains(n3))
    }

    @Test
    fun `add edge requires existing nodes`() {
        val src = addNode()
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(src, "nonexistent", "edge_0", "type")
        }
    }

    @Test
    fun `add edge returns the edge ID`() {
        val src = addNode()
        val dst = addNode()
        val edgeId = addEdge(src, dst)
        assertTrue(storage.containsEdge(edgeId))
    }

    @Test
    fun `edgeIDs property returns all added edges`() {
        val n1 = addNode()
        val n2 = addNode()
        val n3 = addNode()
        val e1 = addEdge(n1, n2)
        val e2 = addEdge(n2, n3)
        val e3 = addEdge(n1, n3)
        val allIds = storage.edgeIDs
        assertEquals(3, allIds.size)
        assertTrue(allIds.contains(e1))
        assertTrue(allIds.contains(e2))
        assertTrue(allIds.contains(e3))
    }

    // ============================================================================
    // PROPERTIES
    // ============================================================================

    @Test
    fun `add node with properties stores them`() {
        val props = mapOf("name" to "Alice".strVal, "age" to 30.numVal)
        val nodeId = storage.addNode("node_0", props)
        val retrieved = storage.getNodeProperties(nodeId)
        assertEquals("Alice", (retrieved["name"] as StrVal).core)
        assertEquals(30, (retrieved["age"] as NumVal).core)
    }

    @Test
    fun `set node properties updates existing properties`() {
        val nodeId = addNode()
        storage.setNodeProperties(nodeId, mapOf("key" to "value".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("value", (props["key"] as StrVal).core)
    }

    @Test
    fun `get node property returns individual value`() {
        val nodeId = storage.addNode("node_0", mapOf("key" to "value".strVal))
        val value = storage.getNodeProperty(nodeId, "key")
        assertEquals("value", (value as StrVal).core)
    }

    @Test
    fun `add edge with properties stores them`() {
        val n1 = addNode()
        val n2 = addNode()
        val props = mapOf("weight" to 5.numVal)
        val edgeId = storage.addEdge(n1, n2, "edge_0", "rel", props)
        val retrieved = storage.getEdgeProperties(edgeId)
        assertEquals(5, (retrieved["weight"] as NumVal).core)
    }

    @Test
    fun `set edge properties updates existing properties`() {
        val n1 = addNode()
        val n2 = addNode()
        val edgeId = addEdge(n1, n2)
        storage.setEdgeProperties(edgeId, mapOf("label" to "friend".strVal))
        val props = storage.getEdgeProperties(edgeId)
        assertEquals("friend", (props["label"] as StrVal).core)
    }

    // ============================================================================
    // EDGE ENDPOINTS
    // ============================================================================

    @Test
    fun `get edge source returns correct node`() {
        val src = addNode()
        val dst = addNode()
        val edgeId = addEdge(src, dst)
        assertEquals(src, storage.getEdgeSrc(edgeId))
    }

    @Test
    fun `get edge destination returns correct node`() {
        val src = addNode()
        val dst = addNode()
        val edgeId = addEdge(src, dst)
        assertEquals(dst, storage.getEdgeDst(edgeId))
    }

    @Test
    fun `get edge tag returns correct tag`() {
        val src = addNode()
        val dst = addNode()
        val edgeId = storage.addEdge(src, dst, "edge_0", "custom_type")
        assertEquals("custom_type", storage.getEdgeTag(edgeId))
    }

    // ============================================================================
    // ADJACENCY
    // ============================================================================

    @Test
    fun `outgoing edges returns edges from node`() {
        val n1 = addNode()
        val n2 = addNode()
        val n3 = addNode()
        val e1 = addEdge(n1, n2)
        val e2 = addEdge(n1, n3)
        val outgoing = storage.getOutgoingEdges(n1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(e1))
        assertTrue(outgoing.contains(e2))
    }

    @Test
    fun `incoming edges returns edges to node`() {
        val n1 = addNode()
        val n2 = addNode()
        val n3 = addNode()
        val e1 = addEdge(n2, n1)
        val e2 = addEdge(n3, n1)
        val incoming = storage.getIncomingEdges(n1)
        assertEquals(2, incoming.size)
        assertTrue(incoming.contains(e1))
        assertTrue(incoming.contains(e2))
    }

    @Test
    fun `self-loop appears in both incoming and outgoing`() {
        val node = addNode()
        val selfEdge = addEdge(node, node)
        assertTrue(storage.getOutgoingEdges(node).contains(selfEdge))
        assertTrue(storage.getIncomingEdges(node).contains(selfEdge))
    }

    // ============================================================================
    // DELETION
    // ============================================================================

    @Test
    fun `delete node removes it`() {
        val nodeId = addNode()
        storage.deleteNode(nodeId)
        assertFalse(storage.containsNode(nodeId))
    }

    @Test
    fun `delete node removes incident edges`() {
        val n1 = addNode()
        val n2 = addNode()
        val edgeId = addEdge(n1, n2)
        storage.deleteNode(n1)
        assertFalse(storage.containsEdge(edgeId))
    }

    @Test
    fun `delete edge removes it`() {
        val n1 = addNode()
        val n2 = addNode()
        val edgeId = addEdge(n1, n2)
        storage.deleteEdge(edgeId)
        assertFalse(storage.containsEdge(edgeId))
    }

    @Test
    fun `delete nonexistent node throws exception`() {
        assertFailsWith<EntityNotExistException> {
            storage.deleteNode("nonexistent")
        }
    }

    // ============================================================================
    // METADATA
    // ============================================================================

    @Test
    fun `set and get metadata`() {
        storage.setMeta("version", "1.0".strVal)
        val value = storage.getMeta("version")
        assertEquals("1.0", (value as StrVal).core)
    }

    @Test
    fun `metadata names property`() {
        storage.setMeta("key1", "val1".strVal)
        storage.setMeta("key2", "val2".strVal)
        val names = storage.metaNames
        assertTrue(names.contains("key1"))
        assertTrue(names.contains("key2"))
    }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    @Test
    fun `clear removes all data`() {
        addNode()
        addNode()
        storage.clear()
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
    }

    @Test
    fun `operations on closed storage throw exception`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> {
            addNode()
        }
    }

    @Test
    fun `transfer to another storage copies all data`() {
        val n1 = addNode()
        val n2 = addNode()
        val edgeId = addEdge(n1, n2)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertTrue(target.containsNode(n1))
        assertTrue(target.containsNode(n2))
        assertTrue(target.containsEdge(edgeId))
        assertEquals(n1, target.getEdgeSrc(edgeId))
        assertEquals(n2, target.getEdgeDst(edgeId))

        target.close()
    }
}
