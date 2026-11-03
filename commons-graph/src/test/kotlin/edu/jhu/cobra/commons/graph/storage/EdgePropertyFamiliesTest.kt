/** Layer-local property family selection.
 * - `freeze replaces a family and preserves nonfamily properties` — physical snapshot and repeated freezes.
 * - `explicit null and rollback retain their layer meaning` — null presence, deletion, rollback.
 * - `nonfamily edits and other tags do not claim a family` — unchanged sparse fallback.
 * - `selected reads and writes do not visit unrelated frozen properties` — fixed-target classification counts.
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EdgePropertyFamiliesTest {
    private val families = EdgePropertyFamilies { tag, name ->
        if (tag == "call" && (name == "members" || name.startsWith("input:"))) "members" else null
    }

    private fun edge(storage: LayeredStorageImpl, tag: String = "call"): Int =
        storage.addEdge(storage.addNode(), storage.addNode(), tag,
            mapOf("members" to "A".strVal, "input:A" to "a".strVal, "certainty" to "fixed".strVal))

    @Test
    fun `freeze replaces a family and preserves nonfamily properties`() {
        val storage = LayeredStorageImpl(propertyFamilies = families)
        val id = edge(storage)
        storage.freeze()
        storage.setEdgeProperties(id, mapOf("members" to "B".strVal, "input:B" to "b".strVal))
        assertNull(storage.getEdgeProperty(id, "input:A"))
        assertEquals("a".strVal, storage.getEdgeProperty(id, "input:A", StorageLayer.FROZEN))
        assertNull(storage.getEdgeProperty(id, "input:A", StorageLayer.ACTIVE))
        assertEquals("fixed".strVal, storage.getEdgeProperty(id, "certainty"))
        val expected = mapOf("members" to "B".strVal, "input:B" to "b".strVal, "certainty" to "fixed".strVal)
        assertEquals(expected, storage.getEdgeProperties(id))
        storage.freeze()
        assertEquals(expected, storage.getEdgeProperties(id, StorageLayer.FROZEN))
        assertNull(storage.getEdgeProperty(id, "input:A", StorageLayer.FROZEN))
        storage.setEdgeProperties(id, mapOf("members" to "B".strVal, "input:B" to "changed".strVal))
        storage.freeze()
        assertEquals("changed".strVal, storage.getEdgeProperty(id, "input:B", StorageLayer.FROZEN))
        storage.freeze()
        assertEquals("changed".strVal, storage.getEdgeProperty(id, "input:B", StorageLayer.FROZEN))
    }

    @Test
    fun `explicit null and rollback retain their layer meaning`() {
        val storage = LayeredStorageImpl(propertyFamilies = families)
        val id = edge(storage)
        storage.freeze()
        storage.setEdgeProperties(id, mapOf("members" to null))
        assertTrue(storage.hasActiveEdgeProperty(id, "members"))
        assertNull(storage.getEdgeProperty(id, "input:A"))
        assertEquals("a".strVal, storage.getEdgeProperty(id, "input:A", StorageLayer.FROZEN))
        storage.clearActiveLayer()
        assertFalse(storage.hasActiveEdgeProperty(id, "members"))
        assertEquals("a".strVal, storage.getEdgeProperty(id, "input:A"))
        storage.deleteEdge(id)
        assertNull(storage.getEdgeProperty(id, "input:A", StorageLayer.VISIBLE))
        assertEquals("a".strVal, storage.getEdgeProperty(id, "input:A", StorageLayer.FROZEN))
        storage.clearActiveLayer()
        storage.setEdgeProperties(id, mapOf("members" to null))
        storage.freeze()
        assertEquals(mapOf("certainty" to "fixed".strVal), storage.getEdgeProperties(id))
    }

    @Test
    fun `nonfamily edits and other tags do not claim a family`() {
        val storage = LayeredStorageImpl(propertyFamilies = families)
        val call = edge(storage)
        val other = edge(storage, "other")
        storage.freeze()
        storage.setEdgeProperties(call, mapOf("certainty" to "dynamic".strVal))
        storage.setEdgeProperties(other, mapOf("members" to "B".strVal))
        assertEquals("a".strVal, storage.getEdgeProperty(call, "input:A"))
        assertEquals("a".strVal, storage.getEdgeProperty(other, "input:A"))
        storage.freeze()
        assertEquals("a".strVal, storage.getEdgeProperty(call, "input:A"))
        assertEquals("a".strVal, storage.getEdgeProperty(other, "input:A"))
    }

    @Test
    fun `selected reads and writes do not visit unrelated frozen properties`() {
        val rows = listOf(0, 2, 4, 8).map { unrelated ->
            var classifications = 0
            var points = 0
            var rowReads = 0
            val counted = EdgePropertyFamilies { tag, name ->
                classifications++
                families.anchor(tag, name)
            }
            val storage = LayeredStorageImpl(propertyFamilies = counted, frozenLayerFactory = {
                val base = NativeStorageImpl()
                object : IStorage by base {
                    override fun getEdgeProperty(id: Int, name: String): IValue? =
                        base.getEdgeProperty(id, name).also { points++ }
                    override fun getEdgeProperties(id: Int): Map<String, IValue> =
                        base.getEdgeProperties(id).also { rowReads++ }
                }
            })
            val id = edge(storage)
            repeat(unrelated) { storage.setEdgeProperties(id, mapOf("input:other_$it" to "x".strVal)) }
            storage.freeze()
            classifications = 0; points = 0; rowReads = 0
            assertEquals("a".strVal, storage.getEdgeProperty(id, "input:A"))
            storage.setEdgeProperties(id, mapOf("members" to "B".strVal, "input:B" to "b".strVal))
            assertNull(storage.getEdgeProperty(id, "input:A"))
            assertEquals("b".strVal, storage.getEdgeProperty(id, "input:B"))
            listOf(classifications, points, rowReads)
        }
        println("EDGE_FAMILY_FIXED $rows")
        assertEquals(List(rows.size) { rows.first() }, rows)
        assertEquals(0, rows.first().last())
    }
}
