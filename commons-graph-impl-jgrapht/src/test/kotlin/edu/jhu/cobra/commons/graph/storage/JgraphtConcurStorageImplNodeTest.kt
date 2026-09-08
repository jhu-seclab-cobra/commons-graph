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
 * Black-box tests for JgraphtConcurStorageImpl: node operations under locking.
 *
 * - `addNode with properties returns valid ID and stores properties`
 * - `addNode without properties returns valid ID with empty property map`
 * - `addNode returns unique IDs for each call`
 * - `containsNode returns true for existing node`
 * - `containsNode returns false for nonexistent node`
 * - `nodeIDs returns all added node IDs`
 * - `nodeIDs returns empty set on fresh storage`
 * - `getNodeProperties returns stored properties`
 * - `getNodeProperties returns empty map for node with no properties`
 * - `getNodeProperties throws EntityNotExistException for missing node`
 * - `getNodeProperty returns value for existing property`
 * - `getNodeProperty returns null for absent property on existing node`
 * - `getNodeProperty throws EntityNotExistException for missing node`
 * - `setNodeProperties updates existing and adds new properties`
 * - `setNodeProperties with null value removes that property`
 * - `setNodeProperties throws EntityNotExistException for missing node`
 * - `deleteNode removes node from storage`
 * - `deleteNode cascades deletion to all incident edges`
 * - `deleteNode throws EntityNotExistException for missing node`
 */
internal class JgraphtConcurStorageImplNodeTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = JgraphtConcurStorageImpl()
    }

    // -- addNode --

    @Test
    fun `addNode with properties returns valid ID and stores properties`() {
        val id = storage.addNode(mapOf("k" to "v".strVal))
        assertTrue(storage.containsNode(id))
        assertEquals("v", (storage.getNodeProperties(id)["k"] as StrVal).core)
    }

    @Test
    fun `addNode without properties returns valid ID with empty property map`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
        assertTrue(storage.getNodeProperties(id).isEmpty())
    }

    @Test
    fun `addNode returns unique IDs for each call`() {
        val ids = (1..10).map { storage.addNode() }.toSet()
        assertEquals(10, ids.size)
    }

    // -- containsNode --

    @Test
    fun `containsNode returns true for existing node`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
    }

    @Test
    fun `containsNode returns false for nonexistent node`() {
        assertFalse(storage.containsNode(-1))
    }

    // -- nodeIDs --

    @Test
    fun `nodeIDs returns all added node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        assertEquals(setOf(n1, n2), storage.nodeIDs)
    }

    @Test
    fun `nodeIDs returns empty set on fresh storage`() {
        assertTrue(storage.nodeIDs.isEmpty())
    }

    // -- getNodeProperties --

    @Test
    fun `getNodeProperties returns stored properties`() {
        val id = storage.addNode(mapOf("a" to 1.intVal, "b" to "x".strVal))
        val props = storage.getNodeProperties(id)
        assertEquals(1, (props["a"] as IntVal).core)
        assertEquals("x", (props["b"] as StrVal).core)
    }

    @Test
    fun `getNodeProperties returns empty map for node with no properties`() {
        val id = storage.addNode()
        assertTrue(storage.getNodeProperties(id).isEmpty())
    }

    @Test
    fun `getNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(-1) }
    }

    // -- getNodeProperty --

    @Test
    fun `getNodeProperty returns value for existing property`() {
        val id = storage.addNode(mapOf("name" to "hello".strVal))
        assertEquals("hello", (storage.getNodeProperty(id, "name") as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns null for absent property on existing node`() {
        val id = storage.addNode()
        assertNull(storage.getNodeProperty(id, "missing"))
    }

    @Test
    fun `getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(-1, "key") }
    }

    // -- setNodeProperties --

    @Test
    fun `setNodeProperties updates existing and adds new properties`() {
        val id = storage.addNode(mapOf("a" to 1.intVal))
        storage.setNodeProperties(id, mapOf("a" to 10.intVal, "b" to 20.intVal))
        val props = storage.getNodeProperties(id)
        assertEquals(10, (props["a"] as IntVal).core)
        assertEquals(20, (props["b"] as IntVal).core)
    }

    @Test
    fun `setNodeProperties with null value removes that property`() {
        val id = storage.addNode(mapOf("a" to 1.intVal, "b" to 2.intVal))
        storage.setNodeProperties(id, mapOf("a" to null))
        val props = storage.getNodeProperties(id)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as IntVal).core)
    }

    @Test
    fun `setNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("k" to "v".strVal))
        }
    }

    // -- deleteNode --

    @Test
    fun `deleteNode removes node from storage`() {
        val id = storage.addNode()
        storage.deleteNode(id)
        assertFalse(storage.containsNode(id))
    }

    @Test
    fun `deleteNode cascades deletion to all incident edges`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "out")
        val e31 = storage.addEdge(n3, n1, "in")
        val e23 = storage.addEdge(n2, n3, "other")

        storage.deleteNode(n1)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e31))
        assertTrue(storage.containsEdge(e23))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }
}
