package edu.jhu.cobra.commons.graph.poset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * White-box tests for LabelEdgeIndex: candidate bookkeeping per spec-poset.md (Label Carrier Index).
 *
 * - `ensureBuilt records every label of every scanned edge` -- build step
 * - `ensureBuilt scans only once` -- build runs once per index
 * - `record adds edge to label candidates` -- record step
 * - `record same edge twice keeps one candidate` -- set semantics
 * - `candidates of unknown label is empty` -- boundary
 * - `drop removes only that label candidate` -- nonexistent-edge pruning
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
}
