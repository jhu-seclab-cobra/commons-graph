package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl: ID sets and adjacency merged across layers.
 *
 * - `nodeIDs includes nodes from both layers` -- merged ID set
 * - `edgeIDs includes edges from both layers` -- merged ID set
 * - `getOutgoingEdges merges edges from both layers` -- adjacency merge
 * - `getIncomingEdges merges edges from both layers` -- adjacency merge
 * - `nodeIDs returns only frozen IDs when active layer is empty` -- only-frozen node IDs
 * - `edgeIDs returns only frozen IDs when active layer is empty` -- only-frozen edge IDs
 * - `nodeIDs returns only active IDs before any freeze` -- only-active node IDs
 * - `getOutgoingEdges returns only active edges when node not in frozen` -- adjacency only-active
 * - `getIncomingEdges returns only active edges when node not in frozen` -- adjacency only-active
 * - `getOutgoingEdges returns only frozen edges when active set is empty` -- adjacency only-frozen
 * - `getIncomingEdges returns only frozen edges when active set is empty` -- adjacency only-frozen
 * - `getNodeProperty reads frozen-copied property on promoted node` -- promotion copy
 * - `getEdgeProperty reads frozen-copied property on promoted edge` -- promotion copy
 * - `getOutgoingEdges returns empty when active node has no edges and not frozen` -- empty active
 * - `getIncomingEdges returns empty when active node has no edges and not frozen` -- empty active
 * - `containsNode returns true for frozen-only node` -- frozen-only path
 * - `edgeIDs returns only active IDs before any freeze` -- active-only edges
 */
internal class LayeredStorageImplAdjacencyTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

    // region Query layering -- ID sets and adjacency merge

    @Test
    fun `nodeIDs includes nodes from both layers`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        val ids = storage.nodeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(n1))
        assertTrue(ids.contains(n2))
    }

    @Test
    fun `edgeIDs includes edges from both layers`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel1")
        storage.freeze()
        val n3 = storage.addNode()
        val e2 = storage.addEdge(n1, n3, "rel2")
        val ids = storage.edgeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(e1))
        assertTrue(ids.contains(e2))
    }

    @Test
    fun `getOutgoingEdges merges edges from both layers`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel1")
        storage.freeze()
        val n3 = storage.addNode()
        val e2 = storage.addEdge(n1, n3, "rel2")
        val outgoing = storage.getOutgoingEdges(n1)
        assertEquals(2, outgoing.size)
        assertTrue(outgoing.contains(e1))
        assertTrue(outgoing.contains(e2))
    }

    @Test
    fun `getIncomingEdges merges edges from both layers`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel1")
        storage.freeze()
        val n3 = storage.addNode()
        val e2 = storage.addEdge(n3, n2, "rel2")
        val incoming = storage.getIncomingEdges(n2)
        assertEquals(2, incoming.size)
        assertTrue(incoming.contains(e1))
        assertTrue(incoming.contains(e2))
    }

    @Test
    fun `nodeIDs returns only frozen IDs when active layer is empty`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.freeze()
        val ids = storage.nodeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(n1))
        assertTrue(ids.contains(n2))
    }

    @Test
    fun `edgeIDs returns only frozen IDs when active layer is empty`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        val ids = storage.edgeIDs
        assertEquals(1, ids.size)
        assertTrue(ids.contains(e1))
    }

    @Test
    fun `nodeIDs returns only active IDs before any freeze`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val ids = storage.nodeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(n1))
        assertTrue(ids.contains(n2))
    }

    @Test
    fun `getOutgoingEdges returns only active edges when node not in frozen`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        val outgoing = storage.getOutgoingEdges(n1)
        assertEquals(1, outgoing.size)
        assertTrue(outgoing.contains(e))
    }

    @Test
    fun `getIncomingEdges returns only active edges when node not in frozen`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        val incoming = storage.getIncomingEdges(n2)
        assertEquals(1, incoming.size)
        assertTrue(incoming.contains(e))
    }

    @Test
    fun `getOutgoingEdges returns only frozen edges when active set is empty`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        // After freeze, n1 is only in frozen layer with empty active edge sets.
        val outgoing = storage.getOutgoingEdges(n1)
        assertEquals(1, outgoing.size)
        assertTrue(outgoing.contains(e))
    }

    @Test
    fun `getIncomingEdges returns only frozen edges when active set is empty`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        val incoming = storage.getIncomingEdges(n2)
        assertEquals(1, incoming.size)
        assertTrue(incoming.contains(e))
    }

    @Test
    fun `getNodeProperty reads frozen-copied property on promoted node`() {
        val node = storage.addNode(mapOf("frozen_key" to "frozen_val".strVal))
        storage.freeze()
        // Promote node to active layer by writing a different property
        storage.setNodeProperties(node, mapOf("active_key" to "active_val".strVal))
        // Promotion copied "frozen_key" into the active columns; reads never leave the active copy
        assertEquals("frozen_val", (storage.getNodeProperty(node, "frozen_key") as StrVal).core)
        assertEquals("active_val", (storage.getNodeProperty(node, "active_key") as StrVal).core)
        assertNull(storage.getNodeProperty(node, "nonexistent"))
    }

    @Test
    fun `getEdgeProperty reads frozen-copied property on promoted edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("frozen_key" to "frozen_val".strVal))
        storage.freeze()
        // Promote edge to active layer by writing a different property
        storage.setEdgeProperties(edge, mapOf("active_key" to "active_val".strVal))
        // Promotion copied "frozen_key" into the active columns; reads never leave the active copy
        assertEquals("frozen_val", (storage.getEdgeProperty(edge, "frozen_key") as StrVal).core)
        assertEquals("active_val", (storage.getEdgeProperty(edge, "active_key") as StrVal).core)
        assertNull(storage.getEdgeProperty(edge, "nonexistent"))
    }

    // endregion

    // region Adjacency edge cases

    @Test
    fun `getOutgoingEdges returns empty when active node has no edges and not frozen`() {
        val node = storage.addNode()
        val outgoing = storage.getOutgoingEdges(node)
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun `getIncomingEdges returns empty when active node has no edges and not frozen`() {
        val node = storage.addNode()
        val incoming = storage.getIncomingEdges(node)
        assertTrue(incoming.isEmpty())
    }

    // endregion

    // region containsNode frozen-only

    @Test
    fun `containsNode returns true for frozen-only node`() {
        val node = storage.addNode()
        storage.freeze()
        assertTrue(storage.containsNode(node))
        assertFalse(storage.containsNode(999))
    }

    // endregion

    // region edgeIDs active-only

    @Test
    fun `edgeIDs returns only active IDs before any freeze`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "r1")
        val e2 = storage.addEdge(n1, n2, "r2")
        val ids = storage.edgeIDs
        assertEquals(2, ids.size)
        assertTrue(ids.contains(e1))
        assertTrue(ids.contains(e2))
    }

    // endregion
}
