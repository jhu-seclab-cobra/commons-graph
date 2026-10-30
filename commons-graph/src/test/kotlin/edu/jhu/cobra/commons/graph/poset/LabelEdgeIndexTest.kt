package edu.jhu.cobra.commons.graph.poset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * White-box tests for LabelEdgeIndex: candidate bookkeeping per spec-poset-label-index.md.
 *
 * - `ensureBuilt records every label of every scanned edge` -- build step
 * - `ensureBuilt scans only once` -- build runs once per index
 * - `record adds edge to label candidates` -- record step
 * - `record same edge twice keeps one candidate` -- set semantics
 * - `candidates of unknown label is empty` -- boundary
 * - `drop removes only that label candidate` -- nonexistent-edge pruning
 * - `re-added edge is a candidate apart from its deleted predecessor` -- keyed by storage ID
 * - `record numbers each record` -- record step, record count
 * - `candidatesSince returns the candidates recorded after the count, latest first` -- query after a count
 * - `candidatesSince returns a candidate recorded again after the count` -- a repeated record moves the candidate
 * - `candidatesSince at the record count is empty` -- boundary
 */
internal class LabelEdgeIndexTest {
    private lateinit var graph: TestGraph
    private lateinit var index: LabelEdgeIndex<TestEdge>

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
        graph.addNode("a")
        graph.addNode("b")
        graph.addNode("c")
        index = LabelEdgeIndex()
    }

    @Test
    fun `ensureBuilt records every label of every scanned edge`() {
        val ab = graph.addEdge("a", "b", "rel", Label("x"))
        graph.addEdge("a", "b", "rel", Label("y"))
        val bc = graph.addEdge("b", "c", "rel", Label("x"))

        index.ensureBuilt { graph.getAllEdges() }

        assertEquals(setOf(ab, bc), index.candidates(Label("x")).toSet())
        assertEquals(setOf(ab), index.candidates(Label("y")).toSet())
    }

    @Test
    fun `ensureBuilt scans only once`() {
        var scans = 0
        index.ensureBuilt {
            scans++
            graph.getAllEdges()
        }
        index.ensureBuilt {
            scans++
            graph.getAllEdges()
        }

        assertEquals(1, scans)
    }

    @Test
    fun `record adds edge to label candidates`() {
        val ab = graph.addEdge("a", "b", "rel")

        index.record(Label("x"), ab)

        assertEquals(listOf(ab), index.candidates(Label("x")).toList())
    }

    @Test
    fun `record same edge twice keeps one candidate`() {
        val ab = graph.addEdge("a", "b", "rel")

        index.record(Label("x"), ab)
        index.record(Label("x"), graph.getEdge("a", "b", "rel")!!)

        assertEquals(1, index.candidates(Label("x")).size)
    }

    @Test
    fun `candidates of unknown label is empty`() {
        assertTrue(index.candidates(Label("none")).isEmpty())
    }

    @Test
    fun `drop removes only that label candidate`() {
        val ab = graph.addEdge("a", "b", "rel")
        index.record(Label("x"), ab)
        index.record(Label("y"), ab)

        index.drop(Label("x"), ab)

        assertTrue(index.candidates(Label("x")).isEmpty())
        assertEquals(listOf(ab), index.candidates(Label("y")).toList())
    }

    @Test
    fun `re-added edge is a candidate apart from its deleted predecessor`() {
        val deleted = graph.addEdge("a", "b", "rel")
        index.record(Label("x"), deleted)
        graph.delEdge("a", "b", "rel")
        val readded = graph.addEdge("a", "b", "rel")

        index.record(Label("x"), readded)

        assertEquals(listOf(deleted.storageId, readded.storageId), index.candidates(Label("x")).map { it.storageId })
    }

    @Test
    fun `record numbers each record`() {
        val ab = graph.addEdge("a", "b", "rel")

        index.record(Label("x"), ab)
        index.record(Label("y"), ab)
        index.record(Label("x"), ab)

        assertEquals(3, index.recordCount)
    }

    @Test
    fun `candidatesSince returns the candidates recorded after the count, latest first`() {
        val ab = graph.addEdge("a", "b", "rel")
        val bc = graph.addEdge("b", "c", "rel")
        val ac = graph.addEdge("a", "c", "rel")
        index.record(Label("x"), ab)
        val count = index.recordCount

        index.record(Label("x"), bc)
        index.record(Label("y"), ab)
        index.record(Label("x"), ac)

        assertEquals(listOf(ac, bc), index.candidatesSince(Label("x"), count))
    }

    @Test
    fun `candidatesSince returns a candidate recorded again after the count`() {
        val ab = graph.addEdge("a", "b", "rel")
        val bc = graph.addEdge("b", "c", "rel")
        index.record(Label("x"), ab)
        index.record(Label("x"), bc)
        val count = index.recordCount

        index.record(Label("x"), ab)

        assertEquals(listOf(ab), index.candidatesSince(Label("x"), count))
        assertEquals(listOf(bc, ab), index.candidates(Label("x")))
    }

    @Test
    fun `candidatesSince at the record count is empty`() {
        index.record(Label("x"), graph.addEdge("a", "b", "rel"))

        assertTrue(index.candidatesSince(Label("x"), index.recordCount).isEmpty())
        assertTrue(index.candidatesSince(Label("none"), 0).isEmpty())
    }
}
