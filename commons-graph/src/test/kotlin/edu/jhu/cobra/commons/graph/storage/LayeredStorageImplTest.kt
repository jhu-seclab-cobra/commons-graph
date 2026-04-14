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
 * - `getOutgoingEdges merges edges from both layers` -- adjacency merge
 * - `getIncomingEdges merges edges from both layers` -- adjacency merge
 * - `metaNames merges keys from both layers` -- metadata merge
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
}
