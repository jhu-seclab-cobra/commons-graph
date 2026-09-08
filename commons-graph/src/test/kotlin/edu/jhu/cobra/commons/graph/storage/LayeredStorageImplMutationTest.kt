package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.FrozenLayerModificationException
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl: deletion restriction, tombstones, error paths, and transferTo.
 *
 * - `deleteNode on frozen node throws FrozenLayerModificationException` -- frozen guard
 * - `deleteEdge on frozen edge throws FrozenLayerModificationException` -- frozen guard
 * - `deleteNode on active-layer node succeeds after freeze` -- active deletion
 * - `deleteEdge on active-layer edge succeeds after freeze` -- active deletion
 * - `deleteNode on promoted node throws FrozenLayerModificationException` -- promoted guard
 * - `deleteEdge on promoted edge throws FrozenLayerModificationException` -- promoted guard
 * - `setNodeProperties on frozen node creates shadow in active` -- shadow entry
 * - `setEdgeProperties on frozen edge creates shadow in active` -- shadow entry
 * - `deleted node property of promoted node stays deleted` -- no frozen resurrection on read
 * - `deleted edge property of promoted edge stays deleted` -- no frozen resurrection on read
 * - `deleted node property stays deleted after freeze` -- no frozen resurrection at merge
 * - `deleted edge property stays deleted after freeze` -- no frozen resurrection at merge
 * - `deleteNode throws EntityNotExistException for absent node` -- missing entity
 * - `deleteEdge throws EntityNotExistException for absent edge` -- missing entity
 * - `addEdge throws EntityNotExistException when src missing` -- missing source
 * - `addEdge throws EntityNotExistException when dst missing` -- missing destination
 * - `transferTo copies nodes and edges from both layers` -- frozen present
 * - `transferTo copies metadata from both layers` -- metadata transfer
 * - `transferTo same instance throws IllegalArgumentException` -- self-transfer guard
 * - `deleteNode removes incident edges and cleans up edge columns` -- edge column cleanup
 * - `deleteNode with no incident edges succeeds` -- empty outSet/inSet
 */
internal class LayeredStorageImplMutationTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

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

    @Test
    fun `deleteNode on promoted node throws FrozenLayerModificationException`() {
        val node = storage.addNode()
        storage.freeze()
        storage.setNodeProperties(node, mapOf("k" to "v".strVal))
        assertFailsWith<FrozenLayerModificationException> { storage.deleteNode(node) }
        assertTrue(storage.containsNode(node))
    }

    @Test
    fun `deleteEdge on promoted edge throws FrozenLayerModificationException`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("k" to "v".strVal))
        assertFailsWith<FrozenLayerModificationException> { storage.deleteEdge(edge) }
        assertTrue(storage.containsEdge(edge))
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

    // region Property deletion tombstones

    @Test
    fun `deleted node property of promoted node stays deleted`() {
        val node = storage.addNode(mapOf("keep" to "kv".strVal, "drop" to "dv".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("drop" to null))
        assertNull(storage.getNodeProperty(node, "drop"))
        assertFalse(storage.getNodeProperties(node).containsKey("drop"))
        assertEquals("kv", (storage.getNodeProperty(node, "keep") as StrVal).core)
    }

    @Test
    fun `deleted edge property of promoted edge stays deleted`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("keep" to "kv".strVal, "drop" to "dv".strVal))
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("drop" to null))
        assertNull(storage.getEdgeProperty(edge, "drop"))
        assertFalse(storage.getEdgeProperties(edge).containsKey("drop"))
        assertEquals("kv", (storage.getEdgeProperty(edge, "keep") as StrVal).core)
    }

    @Test
    fun `deleted node property stays deleted after freeze`() {
        val node = storage.addNode(mapOf("keep" to "kv".strVal, "drop" to "dv".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("drop" to null))
        storage.freeze()
        assertNull(storage.getNodeProperty(node, "drop"))
        assertFalse(storage.getNodeProperties(node).containsKey("drop"))
        assertEquals("kv", (storage.getNodeProperty(node, "keep") as StrVal).core)
    }

    @Test
    fun `deleted edge property stays deleted after freeze`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("keep" to "kv".strVal, "drop" to "dv".strVal))
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("drop" to null))
        storage.freeze()
        assertNull(storage.getEdgeProperty(edge, "drop"))
        assertFalse(storage.getEdgeProperties(edge).containsKey("drop"))
        assertEquals("kv", (storage.getEdgeProperty(edge, "keep") as StrVal).core)
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
    }

    @Test
    fun `transferTo same instance throws IllegalArgumentException`() {
        storage.addNode()
        assertFailsWith<IllegalArgumentException> { storage.transferTo(storage) }
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
}
