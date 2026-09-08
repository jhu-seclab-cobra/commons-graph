package edu.jhu.cobra.commons.graph.storage

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
 * Black-box tests for LayeredStorageImpl: ColumnViewMap property views and promoted-entity reads.
 *
 * - `getNodeProperties for active-only node returns ColumnViewMap` -- active-only path
 * - `ColumnViewMap get returns null when column exists but lacks entity` -- column miss
 * - `ColumnViewMap containsKey returns false when column exists but lacks entity` -- column miss
 * - `ColumnViewMap isEmpty returns true when columns exist but none contain entity` -- empty view
 * - `ColumnViewMap entries caches result on second access` -- caching
 * - `ColumnViewMap size delegates to entries` -- size via entries
 * - `getEdgeProperties for active-only edge returns ColumnViewMap` -- active-only edge path
 * - `promoted node with no new writes keeps frozen property count` -- copy-only size
 * - `promoted node without frozen props counts only active writes` -- active-only size
 * - `promoted node property count spans copied and new keys` -- combined size
 * - `promoted node with no properties reads empty map` -- empty copy
 * - `promoted node with frozen props reads non-empty map` -- non-empty copy
 * - `promoted node retains frozen-copied key` -- copied key
 * - `promoted node containsKey returns false for absent key` -- absent key
 * - `promoted node reads frozen-copied value not overwritten in active` -- copied value
 * - `promoted node get returns null for absent key` -- absent key
 * - `promoted node entries span copied and new keys` -- combined entries
 */
internal class LayeredStorageImplPropertyViewTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

    // region ColumnViewMap view

    @Test
    fun `getNodeProperties for active-only node returns ColumnViewMap`() {
        val node = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals(2, props.size)
        assertEquals("v1", (props["a"] as StrVal).core)
        assertEquals("v2", (props["b"] as StrVal).core)
    }

    @Test
    fun `ColumnViewMap get returns null when column exists but lacks entity`() {
        val n1 = storage.addNode(mapOf("shared_col" to "val1".strVal))
        val n2 = storage.addNode()
        // n2 has no properties, but "shared_col" column exists from n1
        val props = storage.getNodeProperties(n2)
        assertNull(props["shared_col"])
    }

    @Test
    fun `ColumnViewMap containsKey returns false when column exists but lacks entity`() {
        val n1 = storage.addNode(mapOf("col" to "val".strVal))
        val n2 = storage.addNode()
        val props = storage.getNodeProperties(n2)
        assertFalse(props.containsKey("col"))
    }

    @Test
    fun `ColumnViewMap isEmpty returns true when columns exist but none contain entity`() {
        val n1 = storage.addNode(mapOf("col" to "val".strVal))
        val n2 = storage.addNode()
        val props = storage.getNodeProperties(n2)
        assertTrue(props.isEmpty())
    }

    @Test
    fun `ColumnViewMap entries caches result on second access`() {
        val node = storage.addNode(mapOf("k" to "v".strVal))
        val props = storage.getNodeProperties(node)
        val entries1 = props.entries
        val entries2 = props.entries
        // Cached: same object reference
        assertTrue(entries1 === entries2)
    }

    @Test
    fun `ColumnViewMap size delegates to entries`() {
        val node = storage.addNode(mapOf("a" to 1.intVal, "b" to 2.intVal))
        val props = storage.getNodeProperties(node)
        assertEquals(2, props.size)
    }

    @Test
    fun `getEdgeProperties for active-only edge returns ColumnViewMap`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edge = storage.addEdge(n1, n2, "rel", mapOf("x" to "v".strVal))
        val props = storage.getEdgeProperties(edge)
        assertEquals(1, props.size)
        assertEquals("v", (props["x"] as StrVal).core)
    }

    // endregion

    // region Promoted-entity property reads

    @Test
    fun `promoted node with no new writes keeps frozen property count`() {
        val node = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        storage.freeze()
        // Promote node to active without adding any active properties
        storage.setNodeProperties(node, emptyMap())
        val props = storage.getNodeProperties(node)
        assertEquals(2, props.size)
    }

    @Test
    fun `promoted node without frozen props counts only active writes`() {
        val node = storage.addNode()
        storage.freeze()
        // Node is in frozen with no properties; add active-only properties
        storage.setNodeProperties(node, mapOf("x" to "v".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals(1, props.size)
    }

    @Test
    fun `promoted node property count spans copied and new keys`() {
        val node = storage.addNode(mapOf("a" to "f1".strVal, "b" to "f2".strVal))
        storage.freeze()
        // Override "a" and add new "c"
        storage.setNodeProperties(node, mapOf("a" to "a1".strVal, "c" to "a3".strVal))
        val props = storage.getNodeProperties(node)
        // Keys: a (overwritten), b (copied), c (new) = 3
        assertEquals(3, props.size)
    }

    @Test
    fun `promoted node with no properties reads empty map`() {
        val node = storage.addNode()
        storage.freeze()
        // Promote to active without properties
        storage.setNodeProperties(node, emptyMap())
        val props = storage.getNodeProperties(node)
        assertTrue(props.isEmpty())
    }

    @Test
    fun `promoted node with frozen props reads non-empty map`() {
        val node = storage.addNode(mapOf("k" to "v".strVal))
        storage.freeze()
        // Promote to active without adding active properties
        storage.setNodeProperties(node, emptyMap())
        val props = storage.getNodeProperties(node)
        assertFalse(props.isEmpty())
    }

    @Test
    fun `promoted node retains frozen-copied key`() {
        val node = storage.addNode(mapOf("base_key" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("overlay_key" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertTrue(props.containsKey("base_key"))
    }

    @Test
    fun `promoted node containsKey returns false for absent key`() {
        val node = storage.addNode(mapOf("a" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("b" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertFalse(props.containsKey("nonexistent"))
    }

    @Test
    fun `promoted node reads frozen-copied value not overwritten in active`() {
        val node = storage.addNode(mapOf("base_only" to "base_val".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("other" to "v".strVal))
        val props = storage.getNodeProperties(node)
        assertEquals("base_val", (props["base_only"] as StrVal).core)
    }

    @Test
    fun `promoted node get returns null for absent key`() {
        val node = storage.addNode(mapOf("a" to "v".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("b" to "v2".strVal))
        val props = storage.getNodeProperties(node)
        assertNull(props["nonexistent"])
    }

    @Test
    fun `promoted node entries span copied and new keys`() {
        val node = storage.addNode(mapOf("a" to "base_a".strVal, "b" to "base_b".strVal))
        storage.freeze()
        storage.setNodeProperties(node, mapOf("a" to "overlay_a".strVal, "c" to "overlay_c".strVal))
        val props = storage.getNodeProperties(node)
        val keys = props.entries.map { it.key }.toSet()
        assertEquals(setOf("a", "b", "c"), keys)
        assertEquals("overlay_a", (props.entries.first { it.key == "a" }.value as StrVal).core)
    }

    // endregion
}
