package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
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

    private fun addNode(): Int = storage.addNode()

    private fun addEdge(
        src: Int,
        dst: Int,
        tag: String = "rel",
    ): Int = storage.addEdge(src, dst, tag)

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
            storage.addEdge(src, 999, "type")
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
        val nodeId = storage.addNode(props)
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
        val nodeId = storage.addNode(mapOf("key" to "value".strVal))
        val value = storage.getNodeProperty(nodeId, "key")
        assertEquals("value", (value as StrVal).core)
    }

    @Test
    fun `add edge with properties stores them`() {
        val n1 = addNode()
        val n2 = addNode()
        val props = mapOf("weight" to 5.numVal)
        val edgeId = storage.addEdge(n1, n2, "rel", props)
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
        assertEquals(src, storage.getEdgeStructure(edgeId).src)
    }

    @Test
    fun `get edge destination returns correct node`() {
        val src = addNode()
        val dst = addNode()
        val edgeId = addEdge(src, dst)
        assertEquals(dst, storage.getEdgeStructure(edgeId).dst)
    }

    @Test
    fun `get edge tag returns correct tag`() {
        val src = addNode()
        val dst = addNode()
        val edgeId = storage.addEdge(src, dst, "custom_type")
        assertEquals("custom_type", storage.getEdgeStructure(edgeId).tag)
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
            storage.deleteNode(999)
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
        val idMap = storage.transferTo(target)

        val mappedN1 = idMap[n1]!!
        val mappedN2 = idMap[n2]!!
        assertTrue(target.containsNode(mappedN1))
        assertTrue(target.containsNode(mappedN2))
        assertEquals(1, target.edgeIDs.size)
        val targetEdgeId = target.edgeIDs.first()
        val transferredEdge = target.getEdgeStructure(targetEdgeId)
        assertEquals(mappedN1, transferredEdge.src)
        assertEquals(mappedN2, transferredEdge.dst)

        target.close()
    }
}
