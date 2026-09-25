package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait label-carrier operations: getLabeledEdges and relabel
 * (design-label.md; spec-poset.md, Label Carrier Index).
 *
 * - `getLabeledEdges returns edges carrying label directly` -- direct carriers only
 * - `getLabeledEdges excludes edges only covered by label` -- no covering
 * - `getLabeledEdges excludes edge after delEdge removes label` -- removal verified on read
 * - `getLabeledEdges excludes deleted edge` -- nonexistent edge dropped
 * - `getLabeledEdges sees labels present before index build` -- initial build
 * - `getLabeledEdges on unknown label is empty` -- boundary
 * - `relabel replaces from with to and keeps other labels` -- relabel step
 * - `relabel skips edges failing cond` -- condition
 * - `relabel from equal to is no-op` -- boundary
 * - `relabel makes edge a carrier of to` -- index updated
 * - `relabel matches full edge scan` -- equivalence with a full scan
 * - `getLabeledEdges sees label restored by active layer clear` -- layered revert
 * - `getLabeledEdges excludes edge removed by active layer clear` -- layered revert
 */
internal class PosetTraitRelabelTest {
    private lateinit var graph: TestGraph

    private val c = Label("c")
    private val p = Label("p")
    private val other = Label("other")

    @BeforeTest
    fun setUp() {
        graph = TestGraph()
        listOf("a", "b", "d").forEach { graph.addNode(it) }
    }

    @Test
    fun `getLabeledEdges returns edges carrying label directly`() {
        val ab = graph.addEdge("a", "b", "rel", c)
        val bd = graph.addEdge("b", "d", "rel", c)
        graph.addEdge("a", "d", "rel", other)

        assertEquals(setOf(ab, bd), graph.getLabeledEdges(c).toSet())
    }

    @Test
    fun `getLabeledEdges excludes edges only covered by label`() {
        graph.poset.setParents(c, mapOf("up" to p))
        graph.addEdge("a", "b", "rel", c)

        assertTrue(graph.getLabeledEdges(p).none())
    }

    @Test
    fun `getLabeledEdges excludes edge after delEdge removes label`() {
        graph.addEdge("a", "b", "rel", c)
        graph.addEdge("a", "b", "rel", other)

        graph.delEdge("a", "b", "rel", c)

        assertTrue(graph.getLabeledEdges(c).none())
    }

    @Test
    fun `getLabeledEdges excludes deleted edge`() {
        graph.addEdge("a", "b", "rel", c)

        graph.delEdge("a", "b", "rel")

        assertTrue(graph.getLabeledEdges(c).none())
    }

    @Test
    fun `getLabeledEdges sees labels present before index build`() {
        graph.addEdge("a", "b", "rel", c)
        val sameStorage = TestGraph(graph.storage)
        listOf("a", "b").forEach { sameStorage.claimNode(graph.getNode(it)!!) }

        assertEquals(listOf("a-rel-b"), sameStorage.getLabeledEdges(c).map { it.id }.toList())
    }

    @Test
    fun `getLabeledEdges on unknown label is empty`() {
        assertTrue(graph.getLabeledEdges(Label("none")).none())
    }

    @Test
    fun `relabel replaces from with to and keeps other labels`() {
        graph.addEdge("a", "b", "rel", c)
        val ab = graph.addEdge("a", "b", "rel", other)

        graph.relabel(c, p)

        assertEquals(setOf(p, other), ab.labels)
    }

    @Test
    fun `relabel skips edges failing cond`() {
        val ab = graph.addEdge("a", "b", "keep", c)
        val bd = graph.addEdge("b", "d", "move", c)

        graph.relabel(c, p) { it.eTag == "move" }

        assertEquals(setOf(c), ab.labels)
        assertEquals(setOf(p), bd.labels)
    }

    @Test
    fun `relabel from equal to is no-op`() {
        val ab = graph.addEdge("a", "b", "rel", c)

        graph.relabel(c, c)

        assertEquals(setOf(c), ab.labels)
    }

    @Test
    fun `relabel makes edge a carrier of to`() {
        val ab = graph.addEdge("a", "b", "rel", c)

        graph.relabel(c, p)

        assertEquals(listOf(ab), graph.getLabeledEdges(p).toList())
        assertTrue(graph.getLabeledEdges(c).none())
    }

    @Test
    fun `relabel matches full edge scan`() {
        val labels = listOf(c, p, other)
        val nodes = listOf("a", "b", "d")
        for ((i, src) in nodes.withIndex()) {
            for ((j, dst) in nodes.withIndex()) {
                graph.addEdge(src, dst, "t$i$j", labels[(i + j) % labels.size])
                if (i == j) graph.addEdge(src, dst, "t$i$j", labels[(i + 1) % labels.size])
            }
        }
        graph.relabel(other, c)
        graph.delEdge("a", "b", "t01", p)

        graph.relabel(c, p)

        for (label in labels) {
            val scanned = graph.getAllEdges { label in it.labels }.map { it.id }.toSet()
            assertEquals(scanned, graph.getLabeledEdges(label).map { it.id }.toSet(), "label ${label.core}")
        }
    }

    @Test
    fun `getLabeledEdges sees label restored by active layer clear`() {
        val storage = LayeredStorageImpl()
        val layered = TestGraph(storage)
        listOf("a", "b").forEach { layered.addNode(it) }
        layered.addEdge("a", "b", "rel", c)
        storage.freeze()
        layered.relabel(c, p)

        storage.clearActiveLayer()

        assertEquals(listOf("a-rel-b"), layered.getLabeledEdges(c).map { it.id }.toList())
        assertTrue(layered.getLabeledEdges(p).none())
    }

    @Test
    fun `getLabeledEdges excludes edge removed by active layer clear`() {
        val storage = LayeredStorageImpl()
        val layered = TestGraph(storage)
        listOf("a", "b").forEach { layered.addNode(it) }
        storage.freeze()
        layered.addEdge("a", "b", "rel", c)

        storage.clearActiveLayer()

        assertTrue(layered.getLabeledEdges(c).none())
    }
}
