/* Tag group adjacency tests:
 * selected buckets ignore unrelated groups and preserve complete-tag identity.
 * duplicate lookup work grows linearly for same-source insertion.
 * duplicate storage rows remain independently removable.
 */
package edu.jhu.cobra.commons.graph.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class TagAdjacencyIndexTest {
    @Test
    fun `selected buckets ignore unrelated groups and preserve complete tags`() {
        for (size in listOf(1, 4, 16, 64)) {
            var classifications = 0
            val inspected = ArrayList<Int>()
            val index = TagAdjacencyIndex(tagGroup = { classifications++; it.substringBefore('@') },
                onRead = inspected::add)
            val pairs = EdgePairIndex()
            val wanted = IStorage.EdgeStructure(0, 1, "BIND_WITH@n:w1")
            index.add(0, wanted)
            pairs.add(0, wanted)
            repeat(size) { index.add(it + 1, IStorage.EdgeStructure(it + 2, 1, "OTHER")) }
            val before = classifications
            assertEquals(setOf(0), index.incoming(1, "BIND_WITH"))
            assertEquals(setOf(0), index.outgoing(0, "BIND_WITH"))
            assertEquals(0, pairs.find(wanted))
            assertNull(pairs.find(wanted.copy(tag = "BIND_WITH")))
            assertEquals(before, classifications)
            assertEquals(listOf(0, 0), inspected)
        }
    }

    @Test
    fun `duplicate lookup work is linear across same source insertions`() {
        val counts = listOf(1, 4, 16, 64).map { size ->
            var probes = 0
            val index = EdgePairIndex { probes++ }
            var inspected = 0
            repeat(size) { target ->
                val structure = IStorage.EdgeStructure(0, target + 1, "tag")
                val before = probes
                assertNull(index.find(structure))
                inspected += probes - before
                index.add(target, structure)
            }
            println("sameSourceEdges=$size duplicateLookupProbes=$inspected")
            kotlin.test.assertTrue(inspected <= 4 * size, "Hash probes must stay linear: $size -> $inspected")
            inspected
        }
        assertEquals(1, counts.first())
    }

    @Test
    fun `duplicate rows remain independently removable`() {
        val index = TagAdjacencyIndex()
        val pairs = EdgePairIndex()
        val edge = IStorage.EdgeStructure(0, 1, "tag")
        index.add(0, edge)
        pairs.add(0, edge)
        index.add(1, edge)
        pairs.add(1, edge)
        index.remove(0, edge)
        pairs.remove(0, edge)
        assertEquals(1, pairs.find(edge))
        assertEquals(setOf(1), index.incoming(1, "tag"))
        index.remove(1, edge)
        pairs.remove(1, edge)
        assertNull(pairs.find(edge))
        assertEquals(emptySet(), index.outgoing(0, "tag"))
    }
}
