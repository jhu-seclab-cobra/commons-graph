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
 * Black-box tests for LayeredStorageImpl: property and metadata queries overlaid across layers.
 *
 * - `node property overlay returns active value over frozen` -- active precedence
 * - `node property falls through to frozen when absent in active` -- fallback
 * - `edge property overlay returns active value over frozen` -- active precedence
 * - `edge property falls through to frozen when absent in active` -- fallback
 * - `getNodeProperties merges keys from both layers` -- merged property map
 * - `getEdgeProperties merges keys from both layers` -- merged property map
 * - `metaNames merges keys from both layers` -- metadata merge
 * - `getMeta returns active value over frozen` -- metadata overlay
 * - `getMeta falls through to frozen when absent in active` -- metadata fallback
 * - `metaNames returns only frozen names when active metadata is empty` -- only-frozen metadata
 * - `setMeta with null deletes frozen meta` -- frozen metadata delete
 * - `setMeta with null on frozen meta stays deleted after freeze` -- delete survives merge
 * - `setMeta after null delete restores the property` -- delete then re-set
 * - `setMeta with null deletes the property` -- null delete
 * - `metaNames returns only active names when no frozen layer` -- active-only meta
 * - `getEdgeStructure caches frozen edge structure on second access` -- cache hit
 */
internal class LayeredStorageImplQueryTest {
    private lateinit var storage: LayeredStorageImpl

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
    }

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

    @Test
    fun `setMeta with null deletes frozen meta`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()

        storage.setMeta("key", null)

        assertNull(storage.getMeta("key"))
        assertFalse(storage.metaNames.contains("key"))
    }

    @Test
    fun `setMeta with null on frozen meta stays deleted after freeze`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()
        storage.setMeta("key", null)

        storage.freeze()

        assertNull(storage.getMeta("key"))
        assertFalse(storage.metaNames.contains("key"))
    }

    @Test
    fun `setMeta after null delete restores the property`() {
        storage.setMeta("key", "frozen".strVal)
        storage.freeze()
        storage.setMeta("key", null)

        storage.setMeta("key", "restored".strVal)

        assertEquals("restored", (storage.getMeta("key") as StrVal).core)
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
}
