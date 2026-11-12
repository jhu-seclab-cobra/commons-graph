/* Tag group adjacency tests:
 * selected buckets ignore unrelated groups and preserve complete-tag identity.
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
            val index = TagAdjacencyIndex { classifications++; it.substringBefore('@') }
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
        }
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
