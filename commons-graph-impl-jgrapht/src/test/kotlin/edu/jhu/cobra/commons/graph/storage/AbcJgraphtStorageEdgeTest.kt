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
 * Black-box tests for the AbcJgraphtStorage engine through JgraphtStorageImpl: edge operations.
 *
 * - `addEdge with properties returns valid ID and stores properties`
 * - `addEdge without properties returns valid ID with empty property map`
 * - `addEdge throws EntityNotExistException when src missing`
 * - `addEdge throws EntityNotExistException when dst missing`
 * - `addEdge allows parallel edges between same node pair`
 * - `containsEdge returns true for existing edge`
 * - `containsEdge returns false for nonexistent edge`
 * - `edgeIDs returns all added edge IDs`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure throws EntityNotExistException for missing edge`
 * - `getEdgeProperties returns stored properties`
 * - `getEdgeProperties throws EntityNotExistException for missing edge`
 * - `getEdgeProperty returns value for existing property`
 * - `getEdgeProperty returns null for absent property on existing edge`
 * - `getEdgeProperty throws EntityNotExistException for missing edge`
 * - `setEdgeProperties updates existing and adds new properties`
 * - `setEdgeProperties with null value removes that property`
 * - `setEdgeProperties throws EntityNotExistException for missing edge`
 * - `deleteEdge removes edge from storage`
 * - `deleteEdge leaves endpoints intact`
 * - `deleteEdge throws EntityNotExistException for missing edge`
 */
internal class AbcJgraphtStorageEdgeTest {
    private lateinit var storage: IStorage

    @BeforeTest
    fun setUp() {
        storage = JgraphtStorageImpl()
    }

    // -- addEdge --

    @Test
    fun `addEdge with properties returns valid ID and stores properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to 5.intVal))
        assertTrue(storage.containsEdge(e))
        assertEquals(5, (storage.getEdgeProperties(e)["w"] as IntVal).core)
    }

    @Test
    fun `addEdge without properties returns valid ID with empty property map`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.getEdgeProperties(e).isEmpty())
    }

    @Test
    fun `addEdge throws EntityNotExistException when src missing`() {
        val n = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, n, "rel") }
    }

    @Test
    fun `addEdge throws EntityNotExistException when dst missing`() {
        val n = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(n, -1, "rel") }
    }

    @Test
    fun `addEdge allows parallel edges between same node pair`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n2, "b")
        assertTrue(e1 != e2)
        assertEquals(2, storage.getOutgoingEdges(n1).size)
    }

    // -- containsEdge --

    @Test
    fun `containsEdge returns true for existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
    }

    @Test
    fun `containsEdge returns false for nonexistent edge`() {
        assertFalse(storage.containsEdge(-1))
    }

    // -- edgeIDs --

    @Test
    fun `edgeIDs returns all added edge IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n2, "b")
        assertEquals(setOf(e1, e2), storage.edgeIDs)
    }

    // -- getEdgeStructure --

    @Test
    fun `getEdgeStructure returns correct src dst and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "myTag")
        val structure = storage.getEdgeStructure(e)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
        assertEquals("myTag", structure.tag)
    }

    @Test
    fun `getEdgeStructure throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(-1) }
    }

    // -- getEdgeProperties --

    @Test
    fun `getEdgeProperties returns stored properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal))
        assertEquals("y", (storage.getEdgeProperties(e)["x"] as StrVal).core)
    }

    @Test
    fun `getEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(-1) }
    }

    // -- getEdgeProperty --

    @Test
    fun `getEdgeProperty returns value for existing property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.intVal))
        assertEquals(1, (storage.getEdgeProperty(e, "w") as IntVal).core)
    }

    @Test
    fun `getEdgeProperty returns null for absent property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(e, "missing"))
    }

    @Test
    fun `getEdgeProperty throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(-1, "key") }
    }

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties updates existing and adds new properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("a" to 1.intVal))
        storage.setEdgeProperties(e, mapOf("a" to 10.intVal, "b" to 20.intVal))
        val props = storage.getEdgeProperties(e)
        assertEquals(10, (props["a"] as IntVal).core)
        assertEquals(20, (props["b"] as IntVal).core)
    }

    @Test
    fun `setEdgeProperties with null value removes that property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    @Test
    fun `setEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(-1, mapOf("k" to 1.intVal)) }
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from storage`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `deleteEdge leaves endpoints intact`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `deleteEdge throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }
}
