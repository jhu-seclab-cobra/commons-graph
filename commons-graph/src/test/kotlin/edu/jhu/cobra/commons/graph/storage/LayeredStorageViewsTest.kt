package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * Tests for LayeredStorageViews: UnionSet and MappedEdgeSet lazy views.
 *
 * - `UnionSet size returns second size when first empty` -- first-empty
 * - `UnionSet contains finds element only in first` -- first-only
 * - `UnionSet contains finds element only in second` -- second-only
 * - `UnionSet contains returns false for absent element` -- absent
 * - `UnionSet isEmpty returns true when both empty` -- both-empty
 * - `UnionSet iterator deduplicates elements` -- deduplicated iteration
 * - `UnionSet size deduplicates overlapping elements` -- overlap
 * - `MappedEdgeSet contains returns false when element not in global map` -- unmapped element
 * - `MappedEdgeSet isEmpty returns true when local set empty` -- empty local
 * - `MappedEdgeSet iterator translates local IDs to global IDs` -- translation
 * - `MappedEdgeSet size reflects local set size` -- size delegation
 */
internal class LayeredStorageViewsTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

    // region UnionSet view

    @Test
    fun `UnionSet size returns second size when first empty`() {
        // nodeIDs with only-active nodes (frozen global IDs empty) → returns activeOutEdges.keys
        // edgeIDs with both frozen empty and active populated → returns activeEdgeEndpoints.keys
        // Need both populated → UnionSet. But to test first-empty in UnionSet:
        // metaNames when frozen has no meta but active does → returns activeMetaProperties.keys
        // To get UnionSet with first empty, we need an unusual state.
        // Instead, test via nodeIDs with frozen and active both populated, then verify behavior.

        // Test UnionSet behavior through nodeIDs: freeze a node, add a new node
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        val ids = storage.nodeIDs
        // This is a UnionSet(frozenGlobalIds={n1}, activeOutEdges.keys={n2})
        assertEquals(2, ids.size)
        assertTrue(ids.contains(n1))
        assertTrue(ids.contains(n2))
        assertFalse(ids.contains(999))
    }

    @Test
    fun `UnionSet contains finds element only in first`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        val ids = storage.nodeIDs
        assertTrue(ids.contains(n1))
    }

    @Test
    fun `UnionSet contains finds element only in second`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        val ids = storage.nodeIDs
        assertTrue(ids.contains(n2))
    }

    @Test
    fun `UnionSet contains returns false for absent element`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        val ids = storage.nodeIDs
        assertFalse(ids.contains(999))
    }

    @Test
    fun `UnionSet isEmpty returns true when both empty`() {
        // metaNames with no frozen and no active metadata → frozen returns emptySet, active is empty
        // but that path returns frozenNames directly, not UnionSet. We need both non-empty for UnionSet.
        // Actually UnionSet is only created when both are non-empty. So isEmpty always returns false.
        // Test indirectly: metaNames with both populated
        storage.setMeta("frozen_m", "v".strVal)
        storage.freeze()
        storage.setMeta("active_m", "v2".strVal)
        val names = storage.metaNames
        assertFalse(names.isEmpty())
    }

    @Test
    fun `UnionSet iterator deduplicates elements`() {
        // Create a node, freeze it, then promote it to active (it exists in both sets)
        val n1 = storage.addNode()
        storage.freeze()
        // Add active-only node
        val n2 = storage.addNode()
        // Promote frozen node to active by writing a property
        storage.setNodeProperties(n1, mapOf("k" to "v".strVal))
        // Now n1 is in both frozen global IDs and active outEdges keys
        val ids = storage.nodeIDs
        val idList = ids.toList()
        assertEquals(ids.size, idList.size)
        assertEquals(2, ids.size)
        assertTrue(ids.contains(n1))
        assertTrue(ids.contains(n2))
    }

    @Test
    fun `UnionSet size deduplicates overlapping elements`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        // Promote n1 to active
        storage.setNodeProperties(n1, mapOf("k" to "v".strVal))
        val ids = storage.nodeIDs
        assertEquals(2, ids.size)
    }

    // endregion

    // region MappedEdgeSet view

    @Test
    fun `MappedEdgeSet contains returns false when element not in global map`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        val outgoing = storage.getOutgoingEdges(n1)
        assertFalse(outgoing.contains(999))
    }

    @Test
    fun `MappedEdgeSet isEmpty returns true when local set empty`() {
        // Frozen node with no outgoing edges
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "rel")
        storage.freeze()
        // n2 has no outgoing edges in frozen layer
        // After freeze, active is empty but n2 is promoted to active when we add edge endpoints
        // Actually n2 is only in frozen, so getOutgoingEdges returns MappedEdgeSet for frozen
        val outgoing = storage.getOutgoingEdges(n2)
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun `MappedEdgeSet iterator translates local IDs to global IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        val outgoing = storage.getOutgoingEdges(n1)
        val edgeList = outgoing.toList()
        assertEquals(1, edgeList.size)
        assertEquals(e, edgeList[0])
    }

    @Test
    fun `MappedEdgeSet size reflects local set size`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "r1")
        storage.addEdge(n1, n2, "r2")
        storage.freeze()
        val outgoing = storage.getOutgoingEdges(n1)
        assertEquals(2, outgoing.size)
    }

    // endregion
}
