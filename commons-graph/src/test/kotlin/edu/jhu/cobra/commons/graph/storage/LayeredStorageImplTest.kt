package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl: freeze contract and lifecycle.
 *
 * - `layerCount starts at 1 with no frozen layer` -- initial state
 * - `freeze merges active into frozen and creates fresh active` -- merge semantics
 * - `freeze always maintains at most one frozen layer` -- repeated freeze merges
 * - `global IDs remain stable across freezes` -- ID stability
 * - `first freeze with no prior frozen layer preserves nodes and edges` -- null frozen path
 * - `first freeze transfers metadata when no prior frozen layer` -- null frozen metadata path
 * - `freeze merges node with empty active overlay without overwriting frozen props` -- empty overlay
 * - `freeze preserves frozen-only edge without promoting to active` -- frozen-only edge merge
 * - `clear removes frozen and active layers and resets layerCount` -- full clear
 * - `addEdge between frozen src and active dst succeeds` -- cross-layer edge
 * - `addEdge between two frozen nodes succeeds` -- frozen endpoint edge
 * - `freeze resolves edge with active overlay and empty active props` -- empty overlay edge
 * - `freeze with metadata in both active and frozen layers` -- metadata merge
 * - `freeze merges edge properties from both active and frozen layers` -- edge prop merge
 * - `clearActiveLayer preserves frozen layer data` -- frozen retained
 * - `freeze closes previous closeable frozen layer` -- swap closes replaced layer
 * - `clear closes closeable frozen layer` -- clear closes discarded layer
 */
internal class LayeredStorageImplTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
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
        val n1 = storage.addNode(mapOf("v" to 1.intVal))
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "rel")
        storage.freeze()
        storage.freeze()
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
        assertTrue(storage.containsEdge(e1))
        assertEquals(1L, (storage.getNodeProperties(n1)["v"] as IntVal).core)
    }

    @Test
    fun `first freeze with no prior frozen layer preserves nodes and edges`() {
        val n1 = storage.addNode(mapOf("a" to "v1".strVal))
        val n2 = storage.addNode(mapOf("b" to "v2".strVal))
        val e = storage.addEdge(n1, n2, "link", mapOf("w" to 5.intVal))
        assertEquals(1, storage.layerCount)
        storage.freeze()
        assertEquals(2, storage.layerCount)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
        assertTrue(storage.containsEdge(e))
        assertEquals("v1", (storage.getNodeProperty(n1, "a") as StrVal).core)
        assertEquals("v2", (storage.getNodeProperty(n2, "b") as StrVal).core)
        assertEquals(5L, (storage.getEdgeProperty(e, "w") as IntVal).core)
        val structure = storage.getEdgeStructure(e)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
        assertEquals("link", structure.tag)
    }

    @Test
    fun `first freeze transfers metadata when no prior frozen layer`() {
        storage.setMeta("key1", "val1".strVal)
        storage.setMeta("key2", 42.intVal)
        storage.freeze()
        assertEquals("val1", (storage.getMeta("key1") as StrVal).core)
        assertEquals(42L, (storage.getMeta("key2") as IntVal).core)
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

    // region Frozen layer resource release

    private class CloseRecordingStorage(
        private val delegate: IStorage = NativeStorageImpl(),
    ) : IStorage by delegate,
        AutoCloseable {
        var isClosed: Boolean = false
            private set

        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun `freeze closes previous closeable frozen layer`() {
        val created = mutableListOf<CloseRecordingStorage>()
        val layered = LayeredStorageImpl { CloseRecordingStorage().also(created::add) }
        layered.addNode()
        layered.freeze()
        layered.addNode()
        layered.freeze()
        assertEquals(2, created.size)
        assertTrue(created[0].isClosed)
        assertFalse(created[1].isClosed)
    }

    @Test
    fun `clear closes closeable frozen layer`() {
        val created = mutableListOf<CloseRecordingStorage>()
        val layered = LayeredStorageImpl { CloseRecordingStorage().also(created::add) }
        layered.addNode()
        layered.freeze()
        layered.clear()
        assertTrue(created.single().isClosed)
    }

    // endregion
}
