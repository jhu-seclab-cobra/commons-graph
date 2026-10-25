package edu.jhu.cobra.commons.graph.poset

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait.setLabels: replace an edge's whole label set and keep the
 * label carrier index complete (design-label.md; spec-poset-label-index.md).
 *
 * - `setLabels replaces the label set` -- previous labels are dropped
 * - `setLabels with an empty set clears the labels and keeps the edge` -- no edge deletion
 * - `getLabeledEdges finds the edge under each new label` -- index records every new label
 * - `getLabeledEdges excludes the edge under a replaced label` -- stale candidate validated on read
 * - `setLabels leaves other carriers of the replaced label indexed` -- only the given edge moves
 * - `setLabels leaves the labels of another edge untouched` -- only the given edge changes
 */
internal class PosetTraitLabelsTest {
    private lateinit var graph: TestGraph

    private val c = Label("c")
    private val p = Label("p")
    private val q = Label("q")

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
        listOf("a", "b").forEach { graph.addNode(it) }
    }

    @Test
    fun `setLabels replaces the label set`() {
        val edge = graph.addEdge("a", "b", "rel", c)

        graph.setLabels(edge, setOf(p, q))

        assertEquals(setOf(p, q), edge.labels)
    }

    @Test
    fun `setLabels with an empty set clears the labels and keeps the edge`() {
        val edge = graph.addEdge("a", "b", "rel", c)

        graph.setLabels(edge, emptySet())

        assertTrue(edge.labels.isEmpty())
        assertEquals(edge.storageId, graph.getEdge("a", "b", "rel")?.storageId)
    }

    @Test
    fun `getLabeledEdges finds the edge under each new label`() {
        val edge = graph.addEdge("a", "b", "rel", c)

        graph.setLabels(edge, setOf(p, q))

        assertEquals(setOf(edge), graph.getLabeledEdges(p).toSet())
        assertEquals(setOf(edge), graph.getLabeledEdges(q).toSet())
    }

    @Test
    fun `getLabeledEdges excludes the edge under a replaced label`() {
        val edge = graph.addEdge("a", "b", "rel", c)
        assertEquals(setOf(edge), graph.getLabeledEdges(c).toSet())

        graph.setLabels(edge, setOf(p))

        assertTrue(graph.getLabeledEdges(c).toList().isEmpty())
    }

    @Test
    fun `setLabels leaves other carriers of the replaced label indexed`() {
        val edge = graph.addEdge("a", "b", "rel", c)
        val other = graph.addEdge("b", "a", "rel", c)

        graph.setLabels(edge, setOf(q))

        assertEquals(setOf(edge), graph.getLabeledEdges(q).toSet())
        assertEquals(setOf(other), graph.getLabeledEdges(c).toSet())
    }

    @Test
    fun `setLabels leaves the labels of another edge untouched`() {
        val ab = graph.addEdge("a", "b", "rel", c)
        val ba = graph.addEdge("b", "a", "rel", c)

        graph.setLabels(ab, setOf(p))

        assertEquals(setOf(c), ba.labels)
        assertEquals(setOf(p), ab.labels)
    }
}
