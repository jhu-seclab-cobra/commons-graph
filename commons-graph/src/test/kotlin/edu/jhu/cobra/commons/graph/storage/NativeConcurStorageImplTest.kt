package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for NativeConcurStorageImpl: node CRUD, metadata, and lifecycle.
 *
 * - `addNode returns unique Int ID` -- node creation
 * - `addNode with properties stores initial properties` -- property creation
 * - `containsNode returns true for existing and false for absent` -- lookup
 * - `nodeIDs returns all added node IDs` -- enumeration
 * - `getNodeProperties returns stored properties` -- property retrieval
 * - `getNodeProperty returns single value or null for absent key` -- single-key lookup
 * - `setNodeProperties adds updates and deletes atomically` -- atomic property mutation
 * - `deleteNode removes node and cascades incident edge deletion` -- cascade delete
 * - `deleteNode throws EntityNotExistException for absent node` -- error path
 * - `getMeta returns stored value or null for absent key` -- metadata
 * - `setMeta with null deletes metadata entry` -- metadata deletion
 * - `metaNames returns all metadata keys` -- metadata enumeration
 * - `clear removes all nodes edges and metadata` -- full reset
 * - `transferTo copies all data and returns node ID mapping` -- transfer
 * - `transferTo same instance throws IllegalArgumentException` -- self-transfer guard
 */
internal class NativeConcurStorageImplTest {
    private lateinit var storage: NativeConcurStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeConcurStorageImpl()
    }

    // region Node CRUD

    @Test
    fun `addNode returns unique Int ID`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        assertTrue(n1 != n2)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `addNode with properties stores initial properties`() {
        val nodeId = storage.addNode(mapOf("name" to "A".strVal, "age" to 25.intVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("A", (props["name"] as StrVal).core)
        assertEquals(25L, (props["age"] as IntVal).core)
    }

    @Test
    fun `containsNode returns true for existing and false for absent`() {
        val nodeId = storage.addNode()
        assertTrue(storage.containsNode(nodeId))
        assertFalse(storage.containsNode(999))
    }

    @Test
    fun `nodeIDs returns all added node IDs`() {
        val (n1, n2, n3) = StorageFixtures.addTestNodes(storage)
        assertEquals(setOf(n1, n2, n3), storage.nodeIDs)
    }

    @Test
    fun `getNodeProperties returns stored properties`() {
        val nodeId = storage.addNode(mapOf("k" to "v".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("v", (props["k"] as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns single value or null for absent key`() {
        val nodeId = storage.addNode(mapOf("name" to "test".strVal))
        assertEquals("test", (storage.getNodeProperty(nodeId, "name") as StrVal).core)
        assertNull(storage.getNodeProperty(nodeId, "absent"))
    }

    @Test
    fun `setNodeProperties adds updates and deletes atomically`() {
        val nodeId = storage.addNode(mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.setNodeProperties(nodeId, mapOf("a" to "updated".strVal, "b" to null, "c" to "3".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertFalse(props.containsKey("b"))
        assertEquals("3", (props["c"] as StrVal).core)
    }

    @Test
    fun `deleteNode removes node and cascades incident edge deletion`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        storage.deleteNode(n1)
        assertFalse(storage.containsNode(n1))
        assertFalse(storage.containsEdge(edgeId))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    // endregion

    // region Metadata

    @Test
    fun `getMeta returns stored value or null for absent key`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertNull(storage.getMeta("absent"))
    }

    @Test
    fun `setMeta with null deletes metadata entry`() {
        storage.setMeta("key", "value".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `metaNames returns all metadata keys`() {
        storage.setMeta("k1", "v1".strVal)
        storage.setMeta("k2", "v2".strVal)
        assertEquals(setOf("k1", "k2"), storage.metaNames)
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
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.intVal))
        storage.setMeta("version", "1.0".strVal)

        val target = NativeConcurStorageImpl()
        val idMap = storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertTrue(target.containsNode(idMap[n1]!!))
        assertTrue(target.containsNode(idMap[n2]!!))
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
    }

    @Test
    fun `transferTo same instance throws IllegalArgumentException`() {
        storage.addNode()
        assertFailsWith<IllegalArgumentException> { storage.transferTo(storage) }
    }

    // endregion
}
