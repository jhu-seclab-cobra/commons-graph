package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl edge tombstones and reverts (design-storage.md
 * Edge deletion; spec-graph.md Edge delete, Freeze, Revert).
 *
 * - `deleteEdge on frozen edge hides it from containment and enumeration` -- tombstone masks reads
 * - `deleteEdge on frozen edge hides it from both endpoints' adjacency` -- tombstone masks adjacency
 * - `deleteEdge on promoted edge hides it` -- promoted copy dropped, frozen masked
 * - `getEdgeStructure on deleted frozen edge throws EntityNotExistException` -- masked structure
 * - `setEdgeProperties on deleted frozen edge throws EntityNotExistException` -- no write resurrection
 * - `deleteEdge twice on frozen edge throws EntityNotExistException` -- second delete finds nothing
 * - `clearActiveLayer restores deleted frozen edge` -- revert drops tombstone
 * - `freeze removes deleted frozen edge permanently` -- tombstone applied at merge
 * - `clearActiveLayer notifies active-only nodes and edges` -- promoted entities not reported as removed
 * - `clearActiveLayer notifies the promoted frozen nodes as restored` -- promoted nodes reported separately
 * - `clear notifies every node and edge` -- clear is a revert
 */
internal class LayeredStorageImplRevertTest {
    private lateinit var storage: LayeredStorageImpl
    private var src = -1
    private var dst = -1
    private var edge = -1

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
        src = storage.addNode()
        dst = storage.addNode()
        edge = storage.addEdge(src, dst, "rel", mapOf("k" to "frozen".strVal))
        storage.freeze()
    }

    @Test
    fun `deleteEdge on frozen edge hides it from containment and enumeration`() {
        storage.deleteEdge(edge)

        assertFalse(storage.containsEdge(edge))
        assertFalse(edge in storage.edgeIDs)
    }

    @Test
    fun `deleteEdge on frozen edge hides it from both endpoints' adjacency`() {
        storage.deleteEdge(edge)

        assertEquals(emptySet(), storage.getOutgoingEdges(src) + storage.getIncomingEdges(dst))
    }

    @Test
    fun `deleteEdge on promoted edge hides it`() {
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))

        storage.deleteEdge(edge)

        assertEquals(emptySet(), storage.edgeIDs + storage.getOutgoingEdges(src))
    }

    @Test
    fun `getEdgeStructure on deleted frozen edge throws EntityNotExistException`() {
        storage.deleteEdge(edge)

        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(edge) }
    }

    @Test
    fun `setEdgeProperties on deleted frozen edge throws EntityNotExistException`() {
        storage.deleteEdge(edge)

        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(edge, mapOf("k" to "v".strVal)) }
    }

    @Test
    fun `deleteEdge twice on frozen edge throws EntityNotExistException`() {
        storage.deleteEdge(edge)

        assertFailsWith<EntityNotExistException> { storage.deleteEdge(edge) }
    }

    @Test
    fun `clearActiveLayer restores deleted frozen edge`() {
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))
        storage.deleteEdge(edge)

        storage.clearActiveLayer()

        assertEquals(setOf(edge), storage.getOutgoingEdges(src))
        assertEquals("frozen".strVal, storage.getEdgeProperty(edge, "k"))
    }

    @Test
    fun `freeze removes deleted frozen edge permanently`() {
        storage.deleteEdge(edge)

        storage.freeze()
        storage.clearActiveLayer()

        assertEquals(emptySet(), storage.edgeIDs + storage.getOutgoingEdges(src))
    }

    @Test
    fun `clearActiveLayer notifies active-only nodes and edges`() {
        val reverts = mutableListOf<Pair<Set<Int>, Set<Int>>>()
        storage.addRevertListener { nodes, edges, _ -> reverts += nodes to edges }
        storage.setNodeProperties(src, mapOf("k" to "active".strVal))
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))
        val node = storage.addNode()
        val activeEdge = storage.addEdge(src, node, "rel")

        storage.clearActiveLayer()

        assertEquals(listOf(setOf(node) to setOf(activeEdge)), reverts)
    }

    @Test
    fun `clearActiveLayer notifies the promoted frozen nodes as restored`() {
        val restored = mutableListOf<Set<Int>>()
        storage.addRevertListener { _, _, nodes -> restored += nodes }
        storage.setNodeProperties(src, mapOf("k" to "active".strVal))
        storage.addNode()

        storage.clearActiveLayer()

        assertEquals(listOf(setOf(src)), restored)
    }

    @Test
    fun `clear notifies every node and edge`() {
        val reverts = mutableListOf<Triple<Set<Int>, Set<Int>, Set<Int>>>()
        storage.addRevertListener { nodes, edges, restored -> reverts += Triple(nodes, edges, restored) }
        val node = storage.addNode()

        storage.clear()

        assertEquals(listOf(Triple(setOf(src, dst, node), setOf(edge), emptySet())), reverts)
    }
}
