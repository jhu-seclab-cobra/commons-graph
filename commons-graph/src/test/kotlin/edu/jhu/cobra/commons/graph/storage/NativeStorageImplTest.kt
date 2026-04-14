package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Black-box tests for `NativeStorageImpl` verifying the `IStorage` contract.
 *
 * Tests:
 * - `addNode returns auto-incremented Int ID` -- addNode produces unique ascending IDs
 * - `addNode with properties stores initial properties` -- properties retrievable after creation
 * - `containsNode returns true for existing node` -- positive lookup
 * - `containsNode returns false for absent node` -- negative lookup
 * - `nodeIDs returns all added node IDs` -- set completeness
 * - `getNodeProperties returns stored properties` -- full property map retrieval
 * - `getNodeProperty returns single property value` -- single-key lookup
 * - `getNodeProperty returns null for absent key` -- missing key returns null
 * - `setNodeProperties adds updates and deletes properties atomically` -- null deletes, non-null upserts
 * - `deleteNode removes node from storage` -- node no longer contained
 * - `deleteNode cascades deletion of all incident edges` -- incoming and outgoing edges removed
 * - `deleteNode throws EntityNotExistException for absent node` -- error on missing
 * - `addEdge returns auto-incremented Int ID` -- unique edge IDs
 * - `addEdge with properties stores initial properties` -- edge properties retrievable
 * - `addEdge throws EntityNotExistException when src missing` -- missing source
 * - `addEdge throws EntityNotExistException when dst missing` -- missing destination
 * - `containsEdge returns true for existing edge` -- positive lookup
 * - `containsEdge returns false for absent edge` -- negative lookup
 * - `edgeIDs returns all added edge IDs` -- set completeness
 * - `getEdgeStructure returns src dst and tag` -- structural metadata
 * - `getEdgeProperties returns stored properties` -- full property map
 * - `getEdgeProperty returns single property value` -- single-key lookup
 * - `getEdgeProperty returns null for absent key` -- missing key returns null
 * - `setEdgeProperties adds updates and deletes properties atomically` -- null deletes, non-null upserts
 * - `deleteEdge removes edge from storage` -- edge no longer contained
 * - `deleteEdge throws EntityNotExistException for absent edge` -- error on missing
 * - `getIncomingEdges returns edge IDs targeting node` -- adjacency incoming
 * - `getOutgoingEdges returns edge IDs originating from node` -- adjacency outgoing
 * - `self-loop appears in both incoming and outgoing` -- self-loop adjacency
 * - `getIncomingEdges throws EntityNotExistException for absent node` -- error on missing
 * - `getOutgoingEdges throws EntityNotExistException for absent node` -- error on missing
 * - `getMeta returns stored metadata value` -- metadata read
 * - `getMeta returns null for absent metadata key` -- missing metadata
 * - `setMeta with null deletes metadata entry` -- metadata deletion
 * - `metaNames returns all metadata keys` -- metadata key enumeration
 * - `clear removes all nodes edges and metadata` -- full reset
 * - `transferTo copies all data and returns node ID mapping` -- cross-storage transfer
 * - `close prevents subsequent operations with AccessClosedStorageException` -- lifecycle guard
 */
internal class NativeStorageImplTest {
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region Node CRUD

    @Test
    fun `addNode returns auto-incremented Int ID`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        assertTrue(n1 < n2)
        assertTrue(n2 < n3)
    }

    @Test
    fun `addNode with properties stores initial properties`() {
        val props = mapOf("name" to "Alice".strVal, "age" to 30.numVal)
        val nodeId = storage.addNode(props)
        val retrieved = storage.getNodeProperties(nodeId)
        assertEquals("Alice", (retrieved["name"] as StrVal).core)
        assertEquals(30, (retrieved["age"] as NumVal).core)
    }

    @Test
    fun `containsNode returns true for existing node`() {
        val nodeId = storage.addNode()
        assertTrue(storage.containsNode(nodeId))
    }

    @Test
    fun `containsNode returns false for absent node`() {
        assertFalse(storage.containsNode(999))
    }

    @Test
    fun `nodeIDs returns all added node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        assertEquals(setOf(n1, n2, n3), storage.nodeIDs)
    }

    @Test
    fun `getNodeProperties returns stored properties`() {
        val nodeId = storage.addNode(mapOf("k" to "v".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals(1, props.size)
        assertEquals("v", (props["k"] as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns single property value`() {
        val nodeId = storage.addNode(mapOf("key" to "value".strVal))
        assertEquals("value", (storage.getNodeProperty(nodeId, "key") as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns null for absent key`() {
        val nodeId = storage.addNode()
        assertNull(storage.getNodeProperty(nodeId, "absent"))
    }

    @Test
    fun `setNodeProperties adds updates and deletes properties atomically`() {
        val nodeId = storage.addNode(mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.setNodeProperties(nodeId, mapOf("a" to "updated".strVal, "b" to null, "c" to "3".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertFalse(props.containsKey("b"))
        assertEquals("3", (props["c"] as StrVal).core)
    }

    @Test
    fun `deleteNode removes node from storage`() {
        val nodeId = storage.addNode()
        storage.deleteNode(nodeId)
        assertFalse(storage.containsNode(nodeId))
    }

    @Test
    fun `deleteNode cascades deletion of all incident edges`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val eOut = storage.addEdge(n1, n2, "out")
        val eIn = storage.addEdge(n3, n1, "in")
        storage.deleteNode(n1)
        assertFalse(storage.containsEdge(eOut))
        assertFalse(storage.containsEdge(eIn))
        assertTrue(storage.containsNode(n2))
        assertTrue(storage.containsNode(n3))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    // endregion

    // region Edge CRUD

    @Test
    fun `addEdge returns auto-incremented Int ID`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "t1")
        val e2 = storage.addEdge(n1, n2, "t2")
        assertTrue(e1 < e2)
    }

    @Test
    fun `addEdge with properties stores initial properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("weight" to 5.numVal))
        assertEquals(5, (storage.getEdgeProperties(edgeId)["weight"] as NumVal).core)
    }

    @Test
    fun `addEdge throws EntityNotExistException when src missing`() {
        val n2 = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(999, n2, "rel") }
    }

    @Test
    fun `addEdge throws EntityNotExistException when dst missing`() {
        val n1 = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(n1, 999, "rel") }
    }

    @Test
    fun `containsEdge returns true for existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(edgeId))
    }

    @Test
    fun `containsEdge returns false for absent edge`() {
        assertFalse(storage.containsEdge(999))
    }

    @Test
    fun `edgeIDs returns all added edge IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "t1")
        val e2 = storage.addEdge(n2, n3, "t2")
        val e3 = storage.addEdge(n1, n3, "t3")
        assertEquals(setOf(e1, e2, e3), storage.edgeIDs)
    }

    @Test
    fun `getEdgeStructure returns src dst and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "custom_tag")
        val structure = storage.getEdgeStructure(edgeId)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
        assertEquals("custom_tag", structure.tag)
    }

    @Test
    fun `getEdgeProperties returns stored properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        val props = storage.getEdgeProperties(edgeId)
        assertEquals(1, props.size)
        assertEquals(1, (props["w"] as NumVal).core)
    }

    @Test
    fun `getEdgeProperty returns single property value`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.5.numVal))
        assertEquals(1.5, (storage.getEdgeProperty(edgeId, "w") as NumVal).core)
    }

    @Test
    fun `getEdgeProperty returns null for absent key`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(edgeId, "absent"))
    }

    @Test
    fun `setEdgeProperties adds updates and deletes properties atomically`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.setEdgeProperties(edgeId, mapOf("a" to "updated".strVal, "b" to null, "c" to "3".strVal))
        val props = storage.getEdgeProperties(edgeId)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertFalse(props.containsKey("b"))
        assertEquals("3", (props["c"] as StrVal).core)
    }

    @Test
    fun `deleteEdge removes edge from storage`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(edgeId)
        assertFalse(storage.containsEdge(edgeId))
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `deleteEdge throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(999) }
    }

    // endregion

    // region Adjacency

    @Test
    fun `getIncomingEdges returns edge IDs targeting node`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n2, n1, "a")
        val e2 = storage.addEdge(n3, n1, "b")
        assertEquals(setOf(e1, e2), storage.getIncomingEdges(n1))
    }

    @Test
    fun `getOutgoingEdges returns edge IDs originating from node`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n3, "b")
        assertEquals(setOf(e1, e2), storage.getOutgoingEdges(n1))
    }

    @Test
    fun `self-loop appears in both incoming and outgoing`() {
        val node = storage.addNode()
        val selfEdge = storage.addEdge(node, node, "self")
        assertTrue(storage.getOutgoingEdges(node).contains(selfEdge))
        assertTrue(storage.getIncomingEdges(node).contains(selfEdge))
    }

    @Test
    fun `getIncomingEdges throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(999) }
    }

    @Test
    fun `getOutgoingEdges throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(999) }
    }

    // endregion

    // region Metadata

    @Test
    fun `getMeta returns stored metadata value`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
    }

    @Test
    fun `getMeta returns null for absent metadata key`() {
        assertNull(storage.getMeta("nonexistent"))
    }

    @Test
    fun `setMeta with null deletes metadata entry`() {
        storage.setMeta("key", "value".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `metaNames returns all metadata keys`() {
        storage.setMeta("key1", "v1".strVal)
        storage.setMeta("key2", "v2".strVal)
        assertEquals(setOf("key1", "key2"), storage.metaNames)
    }

    // endregion

    // region Lifecycle

    @Test
    fun `clear removes all nodes edges and metadata`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "rel")
        storage.setMeta("k", "v".strVal)
        storage.clear()
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("k"))
    }

    @Test
    fun `transferTo copies all data and returns node ID mapping`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        storage.setMeta("version", "1.0".strVal)

        val target = NativeStorageImpl()
        val idMap = storage.transferTo(target)

        assertEquals(2, idMap.size)
        assertTrue(target.containsNode(idMap[n1]!!))
        assertTrue(target.containsNode(idMap[n2]!!))
        assertEquals(1, target.edgeIDs.size)
        val targetEdge = target.edgeIDs.first()
        val structure = target.getEdgeStructure(targetEdge)
        assertEquals(idMap[n1], structure.src)
        assertEquals(idMap[n2], structure.dst)
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    @Test
    fun `close prevents subsequent operations with AccessClosedStorageException`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.clear() }
    }

    // endregion
}
