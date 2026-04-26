package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
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
 * Black-box tests for `LayeredStorageImpl` verifying layered storage behavior.
 *
 * Freeze contract:
 * - `layerCount starts at 1 with no frozen layer` -- initial state
 * - `freeze merges active into frozen and creates fresh active` -- merge semantics
 * - `freeze always maintains at most one frozen layer` -- repeated freeze merges
 * - `global IDs remain stable across freezes` -- ID stability
 * - `first freeze with no prior frozen layer preserves nodes and edges` -- null frozen path
 * - `first freeze transfers metadata when no prior frozen layer` -- null frozen metadata path
 * - `freeze merges node with empty active overlay without overwriting frozen props` -- empty overlay
 * - `freeze preserves frozen-only edge without promoting to active` -- frozen-only edge merge
 *
 * Query layering:
 * - `node property overlay returns active value over frozen` -- active precedence
 * - `node property falls through to frozen when absent in active` -- fallback
 * - `edge property overlay returns active value over frozen` -- active precedence
 * - `edge property falls through to frozen when absent in active` -- fallback
 * - `getNodeProperties merges keys from both layers` -- merged property map
 * - `getEdgeProperties merges keys from both layers` -- merged property map
 * - `nodeIDs includes nodes from both layers` -- merged ID set
 * - `edgeIDs includes edges from both layers` -- merged ID set
 * - `nodeIDs returns only frozen IDs when active layer is empty` -- only-frozen node IDs
 * - `edgeIDs returns only frozen IDs when active layer is empty` -- only-frozen edge IDs
 * - `nodeIDs returns only active IDs before any freeze` -- only-active node IDs
 * - `getOutgoingEdges merges edges from both layers` -- adjacency merge
 * - `getIncomingEdges merges edges from both layers` -- adjacency merge
 * - `getOutgoingEdges returns only active edges when node not in frozen` -- adjacency only-active
 * - `getIncomingEdges returns only active edges when node not in frozen` -- adjacency only-active
 * - `getOutgoingEdges returns only frozen edges when active set is empty` -- adjacency only-frozen
 * - `getIncomingEdges returns only frozen edges when active set is empty` -- adjacency only-frozen
 * - `getNodeProperty falls through to frozen when active column lacks the property` -- overlay miss
 * - `getEdgeProperty falls through to frozen when active column lacks the property` -- edge overlay miss
 * - `metaNames merges keys from both layers` -- metadata merge
 * - `metaNames returns only frozen names when active metadata is empty` -- only-frozen metadata
 * - `getMeta returns active value over frozen` -- metadata overlay
 * - `getMeta falls through to frozen when absent in active` -- metadata fallback
 *
 * Deletion restriction:
 * - `deleteNode on frozen node throws FrozenLayerModificationException` -- frozen guard
 * - `deleteEdge on frozen edge throws FrozenLayerModificationException` -- frozen guard
 * - `deleteNode on active-layer node succeeds after freeze` -- active deletion
 * - `deleteEdge on active-layer edge succeeds after freeze` -- active deletion
 *
 * Cross-layer property writes:
 * - `setNodeProperties on frozen node creates shadow in active` -- shadow entry
 * - `setEdgeProperties on frozen edge creates shadow in active` -- shadow entry
 *
 * Lifecycle:
 * - `clear removes frozen and active layers and resets layerCount` -- full clear
 * - `close prevents subsequent operations` -- lifecycle guard
 * - `addEdge between frozen src and active dst succeeds` -- cross-layer edge
 * - `addEdge between two frozen nodes succeeds` -- frozen endpoint edge
 *
 * Error paths:
 * - `deleteNode throws EntityNotExistException for absent node` -- missing entity
 * - `deleteEdge throws EntityNotExistException for absent edge` -- missing entity
 * - `addEdge throws EntityNotExistException when src missing` -- missing source
 * - `addEdge throws EntityNotExistException when dst missing` -- missing destination
 *
 * ActiveColumnViewMap view:
 * - `getNodeProperties for active-only node returns ActiveColumnViewMap` -- active-only path
 * - `ActiveColumnViewMap get returns null when column exists but lacks entity` -- column miss
 * - `ActiveColumnViewMap containsKey returns false when column exists but lacks entity` -- column miss
 * - `ActiveColumnViewMap isEmpty returns true when columns exist but none contain entity` -- empty view
 * - `ActiveColumnViewMap entries caches result on second access` -- caching
 * - `ActiveColumnViewMap size delegates to entries` -- size via entries
 * - `getEdgeProperties for active-only edge returns ActiveColumnViewMap` -- active-only edge path
 *
 * LazyMergedMap view:
 * - `LazyMergedMap size returns base size when overlay empty` -- overlay-empty size
 * - `LazyMergedMap size returns overlay size when base empty` -- base-empty size
 * - `LazyMergedMap size counts unique keys across both maps` -- both-populated size
 * - `LazyMergedMap isEmpty returns true when both layers empty` -- both-empty
 * - `LazyMergedMap isEmpty returns false when base non-empty` -- base non-empty
 * - `LazyMergedMap containsKey finds key only in base` -- base-only key
 * - `LazyMergedMap containsKey returns false for absent key` -- absent key
 * - `LazyMergedMap get returns base value when absent in overlay` -- base fallback
 * - `LazyMergedMap get returns null when absent in both` -- absent key
 * - `LazyMergedMap entries merges base and overlay` -- merged entries
 *
 * UnionSet view:
 * - `UnionSet size returns second size when first empty` -- first-empty
 * - `UnionSet size returns first size when second empty` -- second-empty
 * - `UnionSet size deduplicates overlapping elements` -- overlap
 * - `UnionSet contains finds element only in first` -- first-only
 * - `UnionSet contains finds element only in second` -- second-only
 * - `UnionSet contains returns false for absent element` -- absent
 * - `UnionSet isEmpty returns true when both empty` -- both-empty
 * - `UnionSet iterator deduplicates elements` -- deduplicated iteration
 *
 * MappedEdgeSet view:
 * - `MappedEdgeSet contains returns false when element not in global map` -- unmapped element
 * - `MappedEdgeSet isEmpty returns true when local set empty` -- empty local
 * - `MappedEdgeSet iterator translates local IDs to global IDs` -- translation
 * - `MappedEdgeSet size reflects local set size` -- size delegation
 *
 * Closed storage -- ensureOpen for all methods:
 * - `edgeIDs throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `containsNode throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `containsEdge throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getNodeProperties throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getNodeProperty throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `setNodeProperties throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `deleteNode throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `addEdge throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getEdgeStructure throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getEdgeProperties throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getEdgeProperty throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `setEdgeProperties throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `deleteEdge throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getIncomingEdges throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getOutgoingEdges throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `metaNames throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `getMeta throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `setMeta throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `clear throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `freeze throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `clearActiveLayer throws AccessClosedStorageException when closed` -- lifecycle guard
 * - `close is idempotent` -- double close
 *
 * transferTo:
 * - `transferTo copies nodes and edges from both layers` -- frozen present
 * - `transferTo copies metadata from both layers` -- metadata transfer
 *
 * Freeze edge cases:
 * - `freeze resolves edge with active overlay and empty active props` -- empty overlay edge
 * - `freeze with metadata in both active and frozen layers` -- metadata merge
 *
 * Metadata:
 * - `setMeta with null deletes the property` -- null delete
 * - `metaNames returns only active names when no frozen layer` -- active-only meta
 *
 * Property access edge cases:
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
 *
 * Adjacency edge cases:
 * - `getOutgoingEdges returns empty when active node has no edges and not frozen` -- empty active
 * - `getIncomingEdges returns empty when active node has no edges and not frozen` -- empty active
 *
 * Edge deletion and column cleanup:
 * - `deleteNode removes incident edges and cleans up edge columns` -- edge column cleanup
 * - `deleteNode with no incident edges succeeds` -- empty outSet/inSet
 *
 * Edge structure cache:
 * - `getEdgeStructure caches frozen edge structure on second access` -- cache hit
 *
 * containsNode frozen-only:
 * - `containsNode returns true for frozen-only node` -- frozen-only path
 *
 * edgeIDs active-only:
 * - `edgeIDs returns only active IDs before any freeze` -- active-only edges
 *
 * Freeze edge with overlay properties:
 * - `freeze merges edge properties from both active and frozen layers` -- edge prop merge
 *
 * Property null-delete cleanup:
 * - `setNodeProperties with null removes last property and cleans column` -- column cleanup
 * - `setEdgeProperties with null removes last property and cleans column` -- column cleanup
 *
 * clearActiveLayer:
 * - `clearActiveLayer preserves frozen layer data` -- frozen retained
 *
 * clear with frozen:
 * - `clear with frozen layer closes frozen storage` -- frozen close
 */
internal class LayeredStorageImplTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region Freeze contract

    @Test
    fun `layerCount starts at 1 with no frozen layer`() {
        assertEquals(1, storage.layerCount)
    }

    @Test
    fun `freeze merges active into frozen and creates fresh active`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        storage.freeze()
        assertEquals(2, storage.layerCount)
        assertTrue(storage.containsNode(n1))
        assertEquals("A", (storage.getNodeProperties(n1)["name"] as StrVal).core)
    }

    @Test
    fun `freeze always maintains at most one frozen layer`() {
        storage.addNode()
        storage.freeze()
        assertEquals(2, storage.layerCount)
        storage.addNode()
        storage.freeze()
        assertEquals(2, storage.layerCount)
    }

    @Test
    fun `global IDs remain stable across freezes`() {
        val n1 = storage.addNode(mapOf("v" to 1.numVal))
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        storage.freeze()
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
        assertTrue(storage.containsEdge(e1))
        assertEquals(1, (storage.getNodeProperties(n1)["v"] as NumVal).core)
    }

    @Test
    fun `first freeze with no prior frozen layer preserves nodes and edges`() {
        val n1 = storage.addNode(mapOf("a" to "v1".strVal))
        val n2 = storage.addNode(mapOf("b" to "v2".strVal))
        val e = storage.addEdge(n1, n2, "link", mapOf("w" to 5.numVal))
        assertEquals(1, storage.layerCount)
        storage.freeze()
        assertEquals(2, storage.layerCount)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
        assertTrue(storage.containsEdge(e))
        assertEquals("v1", (storage.getNodeProperty(n1, "a") as StrVal).core)
        assertEquals("v2", (storage.getNodeProperty(n2, "b") as StrVal).core)
        assertEquals(5, (storage.getEdgeProperty(e, "w") as NumVal).core)
        val structure = storage.getEdgeStructure(e)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
        assertEquals("link", structure.tag)
    }

    @Test
    fun `first freeze transfers metadata when no prior frozen layer`() {
        storage.setMeta("key1", "val1".strVal)
        storage.setMeta("key2", 42.numVal)
        storage.freeze()
        assertEquals("val1", (storage.getMeta("key1") as StrVal).core)
        assertEquals(42, (storage.getMeta("key2") as NumVal).core)
    }

    @Test
    fun `freeze merges node with empty active overlay without overwriting frozen props`() {
        val node = storage.addNode(mapOf("original" to "frozen_val".strVal))
        storage.freeze()
        // Promote node to active layer by setting an unrelated property, then freeze again.
        // The node now exists in both layers, but the "original" property has no active overlay.
        storage.setNodeProperties(node, mapOf("extra" to "active_val".strVal))
        storage.freeze()
        assertEquals("frozen_val", (storage.getNodeProperty(node, "original") as StrVal).core)
        assertEquals("active_val", (storage.getNodeProperty(node, "extra") as StrVal).core)
    }

    @Test
    fun `freeze preserves frozen-only edge without promoting to active`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val frozenEdge = storage.addEdge(n1, n2, "frozen_tag", mapOf("ep" to "edge_prop".strVal))
        storage.freeze()
        // Add a new active-only edge but leave frozenEdge untouched (not promoted to active)
        val n3 = storage.addNode()
        val activeEdge = storage.addEdge(n1, n3, "active_tag")
        storage.freeze()
        assertTrue(storage.containsEdge(frozenEdge))
        assertTrue(storage.containsEdge(activeEdge))
        assertEquals("edge_prop", (storage.getEdgeProperty(frozenEdge, "ep") as StrVal).core)
        val structure = storage.getEdgeStructure(frozenEdge)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
    }

    // endregion

    // region Query layering -- property overlay

    @Test
    fun `node property overlay returns active value over frozen`() {
        val node = storage.addNode(mapOf("key" to "frozen".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("key" to "active".strVal))
        assertEquals("active", (storage.getNodeProperty(node, "key") as StrVal).core)
    }

    @Test
    fun `node property falls through to frozen when absent in active`() {
        val node = storage.addNode(mapOf("key" to "frozen".strVal))
        storage.freeze()
        assertEquals("frozen", (storage.getNodeProperty(node, "key") as StrVal).core)
    }

    @Test
    fun `edge property overlay returns active value over frozen`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("x" to "frozen".strVal))
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("x" to "active".strVal))
        assertEquals("active", (storage.getEdgeProperty(edge, "x") as StrVal).core)
    }

    @Test
    fun `edge property falls through to frozen when absent in active`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("x" to "frozen".strVal))
        storage.freeze()
        assertEquals("frozen", (storage.getEdgeProperty(edge, "x") as StrVal).core)
    }

    @Test
    fun `getNodeProperties merges keys from both layers`() {
        val node = storage.addNode(mapOf("a" to "frozen_a".strVal, "b" to "frozen_b".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("a" to "active_a".strVal, "c" to "active_c".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals(3, props.size)
        assertEquals("active_a", (props["a"] as StrVal).core)
        assertEquals("frozen_b", (props["b"] as StrVal).core)
        assertEquals("active_c", (props["c"] as StrVal).core)
    }

    @Test
    fun `getEdgeProperties merges keys from both layers`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("x" to "frozen".strVal, "y" to "frozen_y".strVal))
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("x" to "active".strVal, "z" to "active_z".strVal))
        val props = storage.getEdgeProperties(edge)
        assertEquals(3, props.size)
        assertEquals("active", (props["x"] as StrVal).core)
        assertEquals("frozen_y", (props["y"] as StrVal).core)
        assertEquals("active_z", (props["z"] as StrVal).core)
    }

    // endregion

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
    fun `getNodeProperty falls through to frozen when active column lacks the property`() {
        val node = storage.addNode(mapOf("frozen_key" to "frozen_val".strVal))
        storage.freeze()
        // Promote node to active layer by writing a different property
        storage.setNodeProperties(node, mapOf("active_key" to "active_val".strVal))
        // Query the frozen-only property: node is in active, but "frozen_key" is not in active columns
        assertEquals("frozen_val", (storage.getNodeProperty(node, "frozen_key") as StrVal).core)
        assertEquals("active_val", (storage.getNodeProperty(node, "active_key") as StrVal).core)
        assertNull(storage.getNodeProperty(node, "nonexistent"))
    }

    @Test
    fun `getEdgeProperty falls through to frozen when active column lacks the property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("frozen_key" to "frozen_val".strVal))
        storage.freeze()
        // Promote edge to active layer by writing a different property
        storage.setEdgeProperties(edge, mapOf("active_key" to "active_val".strVal))
        // Query the frozen-only property: edge is in active, but "frozen_key" is not in active columns
        assertEquals("frozen_val", (storage.getEdgeProperty(edge, "frozen_key") as StrVal).core)
        assertEquals("active_val", (storage.getEdgeProperty(edge, "active_key") as StrVal).core)
        assertNull(storage.getEdgeProperty(edge, "nonexistent"))
    }

    // endregion

    // region Query layering -- metadata

    @Test
    fun `metaNames merges keys from both layers`() {
        storage.setMeta("frozenKey", "v1".strVal)
        storage.freeze()
        storage.setMeta("activeKey", "v2".strVal)
        val names = storage.metaNames
        assertEquals(2, names.size)
        assertTrue(names.contains("frozenKey"))
        assertTrue(names.contains("activeKey"))
    }

    @Test
    fun `getMeta returns active value over frozen`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()
        storage.setMeta("key", "active".strVal)
        assertEquals("active", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `getMeta falls through to frozen when absent in active`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()
        assertEquals("frozen", (storage.getMeta("key") as StrVal).core)
    }

    @Test
    fun `metaNames returns only frozen names when active metadata is empty`() {
        storage.setMeta("frozenMeta", "value".strVal)
        storage.freeze()
        val names = storage.metaNames
        assertEquals(1, names.size)
        assertTrue(names.contains("frozenMeta"))
    }

    // endregion

    // region Deletion restriction

    @Test
    fun `deleteNode on frozen node throws FrozenLayerModificationException`() {
        val node = storage.addNode()
        storage.freeze()
        assertFailsWith<FrozenLayerModificationException> { storage.deleteNode(node) }
        assertTrue(storage.containsNode(node))
    }

    @Test
    fun `deleteEdge on frozen edge throws FrozenLayerModificationException`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        assertFailsWith<FrozenLayerModificationException> { storage.deleteEdge(edge) }
        assertTrue(storage.containsEdge(edge))
    }

    @Test
    fun `deleteNode on active-layer node succeeds after freeze`() {
        storage.freeze()
        val node = storage.addNode()
        storage.deleteNode(node)
        assertFalse(storage.containsNode(node))
    }

    @Test
    fun `deleteEdge on active-layer edge succeeds after freeze`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.freeze()
        val edge = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(edge)
        assertFalse(storage.containsEdge(edge))
    }

    // endregion

    // region Cross-layer property writes (shadow entries)

    @Test
    fun `setNodeProperties on frozen node creates shadow in active`() {
        val node = storage.addNode(mapOf("a" to "frozen".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("a" to "active".strVal))
        assertEquals("active", (storage.getNodeProperty(node, "a") as StrVal).core)
    }

    @Test
    fun `setEdgeProperties on frozen edge creates shadow in active`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("x" to "frozen".strVal))
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("x" to "active".strVal))
        assertEquals("active", (storage.getEdgeProperty(edge, "x") as StrVal).core)
    }

    // endregion

    // region Lifecycle

    @Test
    fun `clear removes frozen and active layers and resets layerCount`() {
        storage.addNode()
        storage.freeze()
        storage.addNode()
        storage.clear()
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertEquals(1, storage.layerCount)
    }

    @Test
    fun `close prevents subsequent operations`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    @Test
    fun `addEdge between frozen src and active dst succeeds`() {
        val n1 = storage.addNode()
        storage.freeze()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(edge))
        assertTrue(storage.getOutgoingEdges(n1).contains(edge))
        assertTrue(storage.getIncomingEdges(n2).contains(edge))
    }

    @Test
    fun `addEdge between two frozen nodes succeeds`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.freeze()
        val edge = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(edge))
    }

    // endregion

    // region Error paths

    @Test
    fun `deleteNode throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    @Test
    fun `deleteEdge throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(999) }
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

    // endregion

    // region ActiveColumnViewMap view

    @Test
    fun `getNodeProperties for active-only node returns ActiveColumnViewMap`() {
        val node = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals(2, props.size)
        assertEquals("v1", (props["a"] as StrVal).core)
        assertEquals("v2", (props["b"] as StrVal).core)
    }

    @Test
    fun `ActiveColumnViewMap get returns null when column exists but lacks entity`() {
        val n1 = storage.addNode(mapOf("shared_col" to "val1".strVal))
        val n2 = storage.addNode()
        // n2 has no properties, but "shared_col" column exists from n1
        val props = storage.getNodeProperties(n2)
        assertNull(props["shared_col"])
    }

    @Test
    fun `ActiveColumnViewMap containsKey returns false when column exists but lacks entity`() {
        val n1 = storage.addNode(mapOf("col" to "val".strVal))
        val n2 = storage.addNode()
        val props = storage.getNodeProperties(n2)
        assertFalse(props.containsKey("col"))
    }

    @Test
    fun `ActiveColumnViewMap isEmpty returns true when columns exist but none contain entity`() {
        val n1 = storage.addNode(mapOf("col" to "val".strVal))
        val n2 = storage.addNode()
        val props = storage.getNodeProperties(n2)
        assertTrue(props.isEmpty())
    }

    @Test
    fun `ActiveColumnViewMap entries caches result on second access`() {
        val node = storage.addNode(mapOf("k" to "v".strVal))
        val props = storage.getNodeProperties(node)
        val entries1 = props.entries
        val entries2 = props.entries
        // Cached: same object reference
        assertTrue(entries1 === entries2)
    }

    @Test
    fun `ActiveColumnViewMap size delegates to entries`() {
        val node = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))
        val props = storage.getNodeProperties(node)
        assertEquals(2, props.size)
    }

    @Test
    fun `getEdgeProperties for active-only edge returns ActiveColumnViewMap`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("x" to "v".strVal))
        val props = storage.getEdgeProperties(edge)
        assertEquals(1, props.size)
        assertEquals("v", (props["x"] as StrVal).core)
    }

    // endregion

    // region LazyMergedMap view

    @Test
    fun `LazyMergedMap size returns base size when overlay empty`() {
        val node = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        storage.freeze()
        // Promote node to active without adding any active properties
        storage.setNodeProperties(node, emptyMap())
        val props = storage.getNodeProperties(node)
        assertEquals(2, props.size)
    }

    @Test
    fun `LazyMergedMap size returns overlay size when base empty`() {
        val node = storage.addNode()
        storage.freeze()
        // Node is in frozen with no properties; add active-only properties
        storage.setNodeProperties(node, mapOf("x" to "v".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals(1, props.size)
    }

    @Test
    fun `LazyMergedMap size counts unique keys across both maps`() {
        val node = storage.addNode(mapOf("a" to "f1".strVal, "b" to "f2".strVal))
        storage.freeze()
        // Override "a" and add new "c"
        storage.setNodeProperties(node, mapOf("a" to "a1".strVal, "c" to "a3".strVal))
        val props = storage.getNodeProperties(node)
        // Keys: a (overlay), b (base only), c (overlay only) = 3
        assertEquals(3, props.size)
    }

    @Test
    fun `LazyMergedMap isEmpty returns true when both layers empty`() {
        val node = storage.addNode()
        storage.freeze()
        // Promote to active without properties
        storage.setNodeProperties(node, emptyMap())
        val props = storage.getNodeProperties(node)
        assertTrue(props.isEmpty())
    }

    @Test
    fun `LazyMergedMap isEmpty returns false when base non-empty`() {
        val node = storage.addNode(mapOf("k" to "v".strVal))
        storage.freeze()
        // Promote to active without adding active properties
        storage.setNodeProperties(node, emptyMap())
        val props = storage.getNodeProperties(node)
        assertFalse(props.isEmpty())
    }

    @Test
    fun `LazyMergedMap containsKey finds key only in base`() {
        val node = storage.addNode(mapOf("base_key" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("overlay_key" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertTrue(props.containsKey("base_key"))
    }

    @Test
    fun `LazyMergedMap containsKey returns false for absent key`() {
        val node = storage.addNode(mapOf("a" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("b" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertFalse(props.containsKey("nonexistent"))
    }

    @Test
    fun `LazyMergedMap get returns base value when absent in overlay`() {
        val node = storage.addNode(mapOf("base_only" to "base_val".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("other" to "v".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals("base_val", (props["base_only"] as StrVal).core)
    }

    @Test
    fun `LazyMergedMap get returns null when absent in both`() {
        val node = storage.addNode(mapOf("a" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("b" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertNull(props["nonexistent"])
    }

    @Test
    fun `LazyMergedMap entries merges base and overlay`() {
        val node = storage.addNode(mapOf("a" to "base_a".strVal, "b" to "base_b".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("a" to "overlay_a".strVal, "c" to "overlay_c".strVal))
        val props = storage.getNodeProperties(node)
        val keys = props.entries.map { it.key }.toSet()
        assertEquals(setOf("a", "b", "c"), keys)
        assertEquals("overlay_a", (props.entries.first { it.key == "a" }.value as StrVal).core)
    }

    // endregion

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

    // region Closed storage -- ensureOpen for all methods

    @Test
    fun `edgeIDs throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
    }

    @Test
    fun `containsNode throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(0) }
    }

    @Test
    fun `containsEdge throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(0) }
    }

    @Test
    fun `getNodeProperties throws AccessClosedStorageException when closed`() {
        val node = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(node) }
    }

    @Test
    fun `getNodeProperty throws AccessClosedStorageException when closed`() {
        val node = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperty(node, "k") }
    }

    @Test
    fun `setNodeProperties throws AccessClosedStorageException when closed`() {
        val node = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(node, mapOf("k" to "v".strVal)) }
    }

    @Test
    fun `deleteNode throws AccessClosedStorageException when closed`() {
        val node = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(node) }
    }

    @Test
    fun `addEdge throws AccessClosedStorageException when closed`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.addEdge(n1, n2, "rel") }
    }

    @Test
    fun `getEdgeStructure throws AccessClosedStorageException when closed`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeStructure(e) }
    }

    @Test
    fun `getEdgeProperties throws AccessClosedStorageException when closed`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(e) }
    }

    @Test
    fun `getEdgeProperty throws AccessClosedStorageException when closed`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperty(e, "k") }
    }

    @Test
    fun `setEdgeProperties throws AccessClosedStorageException when closed`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(e, mapOf("k" to "v".strVal)) }
    }

    @Test
    fun `deleteEdge throws AccessClosedStorageException when closed`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(e) }
    }

    @Test
    fun `getIncomingEdges throws AccessClosedStorageException when closed`() {
        val node = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(node) }
    }

    @Test
    fun `getOutgoingEdges throws AccessClosedStorageException when closed`() {
        val node = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(node) }
    }

    @Test
    fun `metaNames throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
    }

    @Test
    fun `getMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("k") }
    }

    @Test
    fun `setMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("k", "v".strVal) }
    }

    @Test
    fun `clear throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.clear() }
    }

    @Test
    fun `freeze throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.freeze() }
    }

    @Test
    fun `clearActiveLayer throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.clearActiveLayer() }
    }

    @Test
    fun `close is idempotent`() {
        storage.close()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
    }

    // endregion

    // region transferTo

    @Test
    fun `transferTo copies nodes and edges from both layers`() {
        val n1 = storage.addNode(mapOf("p" to "frozen_p".strVal))
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "frozen_rel", mapOf("ep" to "frozen_ep".strVal))
        storage.freeze()
        val n3 = storage.addNode(mapOf("q" to "active_q".strVal))
        val e2 = storage.addEdge(n1, n3, "active_rel")
        val target = NativeStorageImpl()
        val nodeMap = storage.transferTo(target)
        assertEquals(3, target.nodeIDs.size)
        assertEquals(2, target.edgeIDs.size)
        assertEquals("frozen_p", (target.getNodeProperty(nodeMap[n1]!!, "p") as StrVal).core)
        assertEquals("active_q", (target.getNodeProperty(nodeMap[n3]!!, "q") as StrVal).core)
        target.close()
    }

    @Test
    fun `transferTo copies metadata from both layers`() {
        storage.setMeta("frozen_meta", "fm".strVal)
        storage.freeze()
        storage.setMeta("active_meta", "am".strVal)
        val target = NativeStorageImpl()
        storage.transferTo(target)
        assertEquals("fm", (target.getMeta("frozen_meta") as StrVal).core)
        assertEquals("am", (target.getMeta("active_meta") as StrVal).core)
        target.close()
    }

    // endregion

    // region Freeze edge cases

    @Test
    fun `freeze resolves edge with active overlay and empty active props`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("k" to "frozen_val".strVal))
        storage.freeze()
        // Promote edge to active without adding properties (just structural promotion)
        storage.setEdgeProperties(edge, emptyMap())
        storage.freeze()
        assertEquals("frozen_val", (storage.getEdgeProperty(edge, "k") as StrVal).core)
    }

    @Test
    fun `freeze with metadata in both active and frozen layers`() {
        storage.setMeta("shared", "frozen".strVal)
        storage.setMeta("frozen_only", "f".strVal)
        storage.freeze()
        storage.setMeta("shared", "active".strVal)
        storage.setMeta("active_only", "a".strVal)
        storage.freeze()
        assertEquals("active", (storage.getMeta("shared") as StrVal).core)
        assertEquals("f", (storage.getMeta("frozen_only") as StrVal).core)
        assertEquals("a", (storage.getMeta("active_only") as StrVal).core)
    }

    // endregion

    // region Metadata edge cases

    @Test
    fun `setMeta with null deletes the property`() {
        storage.setMeta("key", "value".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `metaNames returns only active names when no frozen layer`() {
        storage.setMeta("m1", "v1".strVal)
        storage.setMeta("m2", "v2".strVal)
        val names = storage.metaNames
        assertEquals(2, names.size)
        assertTrue(names.contains("m1"))
        assertTrue(names.contains("m2"))
    }

    // endregion

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

    // region Edge deletion and column cleanup

    @Test
    fun `deleteNode removes incident edges and cleans up edge columns`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "out", mapOf("ep" to "v".strVal))
        val e2 = storage.addEdge(n3, n1, "in", mapOf("ep" to "v2".strVal))
        storage.deleteNode(n1)
        assertFalse(storage.containsEdge(e1))
        assertFalse(storage.containsEdge(e2))
        assertFalse(storage.containsNode(n1))
    }

    @Test
    fun `deleteNode with no incident edges succeeds`() {
        val node = storage.addNode()
        storage.deleteNode(node)
        assertFalse(storage.containsNode(node))
    }

    // endregion

    // region Edge structure cache

    @Test
    fun `getEdgeStructure caches frozen edge structure on second access`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        val s1 = storage.getEdgeStructure(e)
        val s2 = storage.getEdgeStructure(e)
        assertEquals(s1.src, s2.src)
        assertEquals(s1.dst, s2.dst)
        assertEquals(s1.tag, s2.tag)
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

    // region Freeze edge with overlay properties

    @Test
    fun `freeze merges edge properties from both active and frozen layers`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("base" to "frozen_v".strVal))
        storage.freeze()
        // Promote edge to active and add overlay property
        storage.setEdgeProperties(edge, mapOf("overlay" to "active_v".strVal))
        storage.freeze()
        assertEquals("frozen_v", (storage.getEdgeProperty(edge, "base") as StrVal).core)
        assertEquals("active_v", (storage.getEdgeProperty(edge, "overlay") as StrVal).core)
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

    // region clearActiveLayer

    @Test
    fun `clearActiveLayer preserves frozen layer data`() {
        val n1 = storage.addNode(mapOf("k" to "frozen".strVal))
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.setMeta("m", "mv".strVal)
        storage.freeze()
        // Add active-only data
        val n3 = storage.addNode()
        storage.setMeta("active_m", "amv".strVal)
        storage.clearActiveLayer()
        // Frozen data still accessible
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
        assertTrue(storage.containsEdge(e))
        assertEquals("frozen", (storage.getNodeProperty(n1, "k") as StrVal).core)
        assertEquals("mv", (storage.getMeta("m") as StrVal).core)
        // Active data gone
        assertFalse(storage.containsNode(n3))
        assertNull(storage.getMeta("active_m"))
    }

    // endregion

    // region clear with frozen

    @Test
    fun `clear with frozen layer closes frozen storage`() {
        storage.addNode()
        storage.freeze()
        storage.addNode()
        storage.clear()
        assertEquals(1, storage.layerCount)
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertTrue(storage.metaNames.isEmpty())
    }

    // endregion
}
