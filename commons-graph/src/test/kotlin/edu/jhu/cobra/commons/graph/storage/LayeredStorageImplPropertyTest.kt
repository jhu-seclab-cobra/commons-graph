package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl: property access edge cases and null-delete cleanup.
 *
 * - `getNodeProperty returns null for absent property on active-only node` -- null return
 * - `getEdgeProperty returns null for absent property on active-only edge` -- null return
 * - `getNodeProperties returns empty map for node with no properties` -- empty props
 * - `getEdgeProperties returns empty map for edge with no properties` -- empty props
 * - `setNodeProperties throws EntityNotExistException for absent node` -- missing entity
 * - `setEdgeProperties throws EntityNotExistException for absent edge` -- missing entity
 * - `setNodeProperties with null value removes property from active` -- null delete
 * - `setEdgeProperties with null value removes property from active` -- null delete
 * - `getNodeProperties throws EntityNotExistException for absent node` -- missing entity
 * - `getEdgeProperties throws EntityNotExistException for absent edge` -- missing entity
 * - `getNodeProperty throws EntityNotExistException for absent node` -- missing entity
 * - `getEdgeProperty throws EntityNotExistException for absent edge` -- missing entity
 * - `getEdgeStructure throws EntityNotExistException for absent edge` -- missing entity
 * - `getIncomingEdges throws EntityNotExistException for absent node` -- missing entity
 * - `getOutgoingEdges throws EntityNotExistException for absent node` -- missing entity
 * - `setNodeProperties with null removes last property and cleans column` -- column cleanup
 * - `setEdgeProperties with null removes last property and cleans column` -- column cleanup
 */
internal class LayeredStorageImplPropertyTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

    // region Property access edge cases

    @Test
    fun `getNodeProperty returns null for absent property on active-only node`() {
        val node = storage.addNode()
        assertNull(storage.getNodeProperty(node, "nonexistent"))
    }

    @Test
    fun `getEdgeProperty returns null for absent property on active-only edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(edge, "nonexistent"))
    }

    @Test
    fun `getNodeProperties returns empty map for node with no properties`() {
        val node = storage.addNode()
        val props = storage.getNodeProperties(node)
        assertTrue(props.isEmpty())
    }

    @Test
    fun `getEdgeProperties returns empty map for edge with no properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel")
        val props = storage.getEdgeProperties(edge)
        assertTrue(props.isEmpty())
    }

    @Test
    fun `setNodeProperties throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.setNodeProperties(999, mapOf("k" to "v".strVal)) }
    }

    @Test
    fun `setEdgeProperties throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(999, mapOf("k" to "v".strVal)) }
    }

    @Test
    fun `setNodeProperties with null value removes property from active`() {
        val node = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        storage.setNodeProperties(node, mapOf("a" to null))
        assertNull(storage.getNodeProperty(node, "a"))
        assertEquals("v2", (storage.getNodeProperty(node, "b") as StrVal).core)
    }

    @Test
    fun `setEdgeProperties with null value removes property from active`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        storage.setEdgeProperties(edge, mapOf("a" to null))
        assertNull(storage.getEdgeProperty(edge, "a"))
        assertEquals("v2", (storage.getEdgeProperty(edge, "b") as StrVal).core)
    }

    @Test
    fun `getNodeProperties throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(999) }
    }

    @Test
    fun `getEdgeProperties throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(999) }
    }

    @Test
    fun `getNodeProperty throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(999, "k") }
    }

    @Test
    fun `getEdgeProperty throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(999, "k") }
    }

    @Test
    fun `getEdgeStructure throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(999) }
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

    // region Property null-delete cleanup

    @Test
    fun `setNodeProperties with null removes last property and cleans column`() {
        val node = storage.addNode(mapOf("only" to "v".strVal))
        storage.setNodeProperties(node, mapOf("only" to null))
        assertNull(storage.getNodeProperty(node, "only"))
        assertTrue(storage.getNodeProperties(node).isEmpty())
    }

    @Test
    fun `setEdgeProperties with null removes last property and cleans column`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("only" to "v".strVal))
        storage.setEdgeProperties(edge, mapOf("only" to null))
        assertNull(storage.getEdgeProperty(edge, "only"))
        assertTrue(storage.getEdgeProperties(edge).isEmpty())
    }

    // endregion
}
