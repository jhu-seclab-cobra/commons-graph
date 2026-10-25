package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.intVal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl.getEdgeProperties(id, layer): one edge's property
 * snapshot in the visible, active or frozen layer (design-storage.md, Layer snapshots).
 *
 * - `frozen-only edge reads the same in VISIBLE and FROZEN and is absent from ACTIVE` -- unpromoted row
 * - `promoted edge reads the new row in ACTIVE and VISIBLE and the old row in FROZEN` -- write promotes
 * - `active-only edge reads in ACTIVE and VISIBLE and is absent from FROZEN` -- added after freeze
 * - `unknown id is null in every layer` -- absent edge
 * - `deleted frozen edge is null in VISIBLE and ACTIVE and keeps its FROZEN row` -- tombstone hides only VISIBLE
 * - `clearActiveLayer empties ACTIVE and VISIBLE falls back to FROZEN` -- revert drops active rows
 * - `second freeze keeps the global ID readable in FROZEN` -- rows merged across two freezes
 * - `edge after an ID gap reads at its global ID in FROZEN` -- frozen local ID 0 for global ID 1
 * - `survivor of a second freeze reads at its global ID in FROZEN` -- gap opened between two freezes
 * - `a snapshot taken before a write keeps its values` -- returned map is a copy
 * - `a snapshot never mixes two writes of one edge` -- labels and payload come from one read
 */
internal class LayeredEdgePropertiesTest {
    private lateinit var storage: LayeredStorageImpl
    private var edge = -1

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
        val src = storage.addNode()
        val dst = storage.addNode()
        edge = storage.addEdge(src, dst, "rel", mapOf("w" to 1.intVal))
    }

    @Test
    fun `frozen-only edge reads the same in VISIBLE and FROZEN and is absent from ACTIVE`() {
        storage.freeze()

        assertEquals(mapOf("w" to 1.intVal), storage.getEdgeProperties(edge, StorageLayer.VISIBLE))
        assertEquals(mapOf("w" to 1.intVal), storage.getEdgeProperties(edge, StorageLayer.FROZEN))
        assertNull(storage.getEdgeProperties(edge, StorageLayer.ACTIVE))
    }

    @Test
    fun `promoted edge reads the new row in ACTIVE and VISIBLE and the old row in FROZEN`() {
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("w" to 2.intVal))

        assertEquals(mapOf("w" to 2.intVal), storage.getEdgeProperties(edge, StorageLayer.ACTIVE))
        assertEquals(mapOf("w" to 2.intVal), storage.getEdgeProperties(edge, StorageLayer.VISIBLE))
        assertEquals(mapOf("w" to 1.intVal), storage.getEdgeProperties(edge, StorageLayer.FROZEN))
    }

    @Test
    fun `active-only edge reads in ACTIVE and VISIBLE and is absent from FROZEN`() {
        storage.freeze()
        val added = storage.addEdge(storage.addNode(), storage.addNode(), "rel", mapOf("w" to 7.intVal))

        assertEquals(mapOf("w" to 7.intVal), storage.getEdgeProperties(added, StorageLayer.ACTIVE))
        assertEquals(mapOf("w" to 7.intVal), storage.getEdgeProperties(added, StorageLayer.VISIBLE))
        assertNull(storage.getEdgeProperties(added, StorageLayer.FROZEN))
    }

    @Test
    fun `unknown id is null in every layer`() {
        storage.freeze()

        for (layer in StorageLayer.entries) assertNull(storage.getEdgeProperties(edge + 100, layer))
    }

    @Test
    fun `deleted frozen edge is null in VISIBLE and ACTIVE and keeps its FROZEN row`() {
        storage.freeze()
        storage.deleteEdge(edge)

        assertNull(storage.getEdgeProperties(edge, StorageLayer.VISIBLE))
        assertNull(storage.getEdgeProperties(edge, StorageLayer.ACTIVE))
        assertEquals(mapOf("w" to 1.intVal), storage.getEdgeProperties(edge, StorageLayer.FROZEN))
    }

    @Test
    fun `clearActiveLayer empties ACTIVE and VISIBLE falls back to FROZEN`() {
        storage.freeze()
        storage.setEdgeProperties(edge, mapOf("w" to 2.intVal))

        storage.clearActiveLayer()

        assertNull(storage.getEdgeProperties(edge, StorageLayer.ACTIVE))
        assertEquals(mapOf("w" to 1.intVal), storage.getEdgeProperties(edge, StorageLayer.VISIBLE))
        assertEquals(mapOf("w" to 1.intVal), storage.getEdgeProperties(edge, StorageLayer.FROZEN))
    }

    @Test
    fun `second freeze keeps the global ID readable in FROZEN`() {
        storage.freeze()
        val added = storage.addEdge(storage.addNode(), storage.addNode(), "rel", mapOf("w" to 5.intVal))
        storage.setEdgeProperties(edge, mapOf("w" to 3.intVal))

        storage.freeze()

        assertEquals(mapOf("w" to 3.intVal), storage.getEdgeProperties(edge, StorageLayer.FROZEN))
        assertEquals(mapOf("w" to 5.intVal), storage.getEdgeProperties(added, StorageLayer.FROZEN))
        assertNull(storage.getEdgeProperties(added, StorageLayer.ACTIVE))
    }

    @Test
    fun `edge after an ID gap reads at its global ID in FROZEN`() {
        val second = storage.addEdge(storage.addNode(), storage.addNode(), "rel", mapOf("w" to 4.intVal))
        storage.deleteEdge(edge)

        storage.freeze()

        assertEquals(edge + 1, second)
        assertNull(storage.getEdgeProperties(edge, StorageLayer.FROZEN))
        assertEquals(mapOf("w" to 4.intVal), storage.getEdgeProperties(second, StorageLayer.FROZEN))
    }

    @Test
    fun `survivor of a second freeze reads at its global ID in FROZEN`() {
        val second = storage.addEdge(storage.addNode(), storage.addNode(), "rel", mapOf("w" to 4.intVal))
        storage.freeze()
        storage.deleteEdge(edge)

        storage.freeze()

        assertNull(storage.getEdgeProperties(edge, StorageLayer.FROZEN))
        assertEquals(mapOf("w" to 4.intVal), storage.getEdgeProperties(second, StorageLayer.FROZEN))
        assertEquals(mapOf("w" to 4.intVal), storage.getEdgeProperties(second, StorageLayer.VISIBLE))
    }

    @Test
    fun `a snapshot taken before a write keeps its values`() {
        val before = checkNotNull(storage.getEdgeProperties(edge, StorageLayer.ACTIVE))

        storage.setEdgeProperties(edge, mapOf("w" to 9.intVal, "extra" to 1.intVal))

        assertEquals(mapOf("w" to 1.intVal), before)
    }

    @Test
    fun `a snapshot never mixes two writes of one edge`() {
        val torn = CopyOnWriteArrayList<String>()
        val stop = AtomicBoolean(false)
        val writer =
            thread {
                var i = 0
                while (!stop.get()) {
                    i++
                    storage.setEdgeProperties(edge, mapOf("a" to i.intVal, "b" to i.intVal))
                }
            }
        repeat(20_000) {
            val snap = checkNotNull(storage.getEdgeProperties(edge, StorageLayer.ACTIVE))
            val a = snap["a"] as? IntVal
            val b = snap["b"] as? IntVal
            if (a != b) torn += "$a/$b"
        }
        stop.set(true)
        writer.join()

        assertTrue(torn.isEmpty(), "torn snapshots: ${torn.take(3)}")
    }
}
