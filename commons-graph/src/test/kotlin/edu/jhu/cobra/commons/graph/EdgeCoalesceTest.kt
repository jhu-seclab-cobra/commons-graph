package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * Black-box tests for edge identity and declared unique keys: coalescing by triple and validating the keys a
 * graph declares (model.md Merge Laws; design-graph.md GraphMergeLaws).
 *
 * - `edge additions of one triple coalesce` -- triple identity
 * - `two additions of one triple coalesce on a simple graph` -- the pair key leaves one triple alone
 * - `edges with different tags are distinct` -- tag is part of identity
 * - `two additions on one pair with different tags fail on a simple graph` -- the simple graph's declared pair key
 * - `a multiple graph accepts two tags on one pair` -- the default declares no pair key
 * - `an addition sharing a pair with a present edge fails on a simple graph` -- present edges count
 * - `an addition equal to a present edge coalesces on a simple graph` -- identity before uniqueness
 * - `the edge key of an edge equals its triple` -- AbcEdge identity
 */
internal class EdgeCoalesceTest {
    private val graph = TestMultipleGraph(NativeStorageImpl())
    private val simple = GraphFixtures.createTestSimpleGraph()

    private fun key(
        src: String,
        dst: String,
        tag: String,
    ) = EdgeKey(src, dst, tag)

    @Test
    fun `edge additions of one triple coalesce`() {
        val coalesced = graph.coalesceEdges(listOf(key("a", "b", "t"), key("a", "b", "t"), key("a", "c", "t")))

        assertEquals(setOf(key("a", "b", "t"), key("a", "c", "t")), coalesced)
    }

    @Test
    fun `two additions of one triple coalesce on a simple graph`() {
        val coalesced = simple.coalesceEdges(listOf(key("a", "b", "t"), key("a", "b", "t")))

        assertEquals(setOf(key("a", "b", "t")), coalesced)
    }

    @Test
    fun `edges with different tags are distinct`() {
        val coalesced = graph.coalesceEdges(listOf(key("a", "b", "t1"), key("a", "b", "t2")))

        assertEquals(2, coalesced.size)
    }

    @Test
    fun `two additions on one pair with different tags fail on a simple graph`() {
        val additions = listOf(key("a", "b", "t1"), key("a", "b", "t2"))

        for (order in permutations(additions)) {
            assertFailsWith<MergeConflictException> { simple.coalesceEdges(order) }
        }
    }

    @Test
    fun `a multiple graph accepts two tags on one pair`() {
        val additions = listOf(key("a", "b", "t1"), key("a", "b", "t2"))

        assertEquals(additions.toSet(), graph.coalesceEdges(additions))
    }

    @Test
    fun `an addition sharing a pair with a present edge fails on a simple graph`() {
        val present = listOf(key("a", "b", "t1"))

        assertFailsWith<MergeConflictException> { simple.coalesceEdges(listOf(key("a", "b", "t2")), present) }
        assertEquals(setOf(key("a", "c", "t2")), simple.coalesceEdges(listOf(key("a", "c", "t2")), present))
    }

    @Test
    fun `an addition equal to a present edge coalesces on a simple graph`() {
        val present = listOf(key("a", "b", "t1"))

        assertEquals(setOf(key("a", "b", "t1")), simple.coalesceEdges(listOf(key("a", "b", "t1")), present))
    }

    @Test
    fun `the edge key of an edge equals its triple`() {
        graph.addNode("a")
        graph.addNode("b")
        val edge = graph.addEdge("a", "b", "t")

        assertEquals(key("a", "b", "t"), edge.key)
    }
}
