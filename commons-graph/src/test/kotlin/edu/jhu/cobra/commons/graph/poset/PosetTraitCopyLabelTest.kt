package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait.copyLabel (design-label.md; spec-poset-label-index.md, Copy).
 *
 * - `copyLabel adds to beside from and keeps other labels` -- add-only step
 * - `copyLabel makes edge a carrier of both labels` -- index updated
 * - `copyLabel skips edges failing cond` -- condition
 * - `copyLabel from equal to is no-op` -- boundary
 * - `copyLabel leaves an edge already carrying to unrecorded` -- no repeated record
 * - `copyLabel since its cursor copies only later records` -- cursor
 * - `copyLabel copies an edge recorded under from again after the cursor` -- re-record
 * - `copyLabel skips a deleted edge` -- nonexistent edge dropped
 * - `copyLabel copies a carrier a revert restores after the cursor` -- layered revert
 * - `copyLabel matches full edge scan` -- equivalence with a full scan
 * - `copyLabel since its cursor reads only the later records` -- cost: storage reads per copy
 */
internal class PosetTraitCopyLabelTest {
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
    fun `copyLabel adds to beside from and keeps other labels`() {
        graph.addEdge("a", "b", "rel", c)
        val ab = graph.addEdge("a", "b", "rel", other)

        graph.copyLabel(c, p)

        assertEquals(setOf(c, p, other), ab.labels)
    }

    @Test
    fun `copyLabel makes edge a carrier of both labels`() {
        val ab = graph.addEdge("a", "b", "rel", c)

        graph.copyLabel(c, p)

        assertEquals(listOf(ab), graph.getLabeledEdges(c).toList())
        assertEquals(listOf(ab), graph.getLabeledEdges(p).toList())
    }

    @Test
    fun `copyLabel skips edges failing cond`() {
        val ab = graph.addEdge("a", "b", "keep", c)
        val bd = graph.addEdge("b", "d", "copy", c)

        graph.copyLabel(c, p) { it.eTag == "copy" }

        assertEquals(setOf(c), ab.labels)
        assertEquals(setOf(c, p), bd.labels)
    }

    @Test
    fun `copyLabel from equal to is no-op`() {
        val ab = graph.addEdge("a", "b", "rel", c)

        graph.copyLabel(c, c)

        assertEquals(setOf(c), ab.labels)
    }

    @Test
    fun `copyLabel leaves an edge already carrying to unrecorded`() {
        graph.addEdge("a", "b", "rel", c)
        graph.addEdge("a", "b", "rel", p)
        val count = graph.labelIndex.recordCount

        val cursor = graph.copyLabel(c, p)

        assertEquals(count, cursor)
    }

    @Test
    fun `copyLabel since its cursor copies only later records`() {
        val ab = graph.addEdge("a", "b", "rel", c)
        val cursor = graph.copyLabel(c, p)
        graph.delEdge("a", "b", "rel", p)
        val bd = graph.addEdge("b", "d", "rel", c)

        graph.copyLabel(c, p, since = cursor)

        assertEquals(setOf(c), ab.labels)
        assertEquals(setOf(c, p), bd.labels)
    }

    @Test
    fun `copyLabel copies an edge recorded under from again after the cursor`() {
        val ab = graph.addEdge("a", "b", "rel", c)
        val cursor = graph.copyLabel(c, p)
        graph.delEdge("a", "b", "rel", p)
        graph.addEdge("a", "b", "rel", c)

        graph.copyLabel(c, p, since = cursor)

        assertEquals(setOf(c, p), ab.labels)
    }

    @Test
    fun `copyLabel skips a deleted edge`() {
        graph.addEdge("a", "b", "rel", c)
        graph.delEdge("a", "b", "rel")

        graph.copyLabel(c, p)

        assertTrue(graph.getLabeledEdges(p).none())
        assertTrue(graph.labelIndex.candidates(c).isEmpty())
    }

    @Test
    fun `copyLabel copies a carrier a revert restores after the cursor`() {
        val storage = LayeredStorageImpl()
        val layered = TestGraph(storage)
        listOf("a", "b").forEach { layered.addNode(it) }
        layered.addEdge("a", "b", "rel", c)
        storage.freeze()
        layered.delEdge("a", "b", "rel", c)
        val cursor = layered.copyLabel(c, p)
        storage.clearActiveLayer()

        layered.copyLabel(c, p, since = cursor)

        assertEquals(setOf(c, p), layered.getEdge("a", "b", "rel")!!.labels)
    }

    @Test
    fun `copyLabel matches full edge scan`() {
        val labels = listOf(c, p, other)
        val nodes = listOf("a", "b", "d")
        for ((i, src) in nodes.withIndex()) {
            for ((j, dst) in nodes.withIndex()) {
                graph.addEdge(src, dst, "t$i$j", labels[(i + j) % labels.size])
                if (i == j) graph.addEdge(src, dst, "t$i$j", labels[(i + 1) % labels.size])
            }
        }
        val cursor = graph.copyLabel(other, c)
        graph.delEdge("a", "b", "t01", p)
        graph.addEdge("b", "a", "t10", other)

        graph.copyLabel(other, c, since = cursor)
        graph.copyLabel(c, p)

        assertTrue(graph.getAllEdges { other in it.labels }.all { c in it.labels })
        assertTrue(graph.getAllEdges { c in it.labels }.all { p in it.labels })
        for (label in labels) {
            val scanned = graph.getAllEdges { label in it.labels }.map { it.id }.toSet()
            assertEquals(scanned, graph.getLabeledEdges(label).map { it.id }.toSet(), "label ${label.core}")
        }
    }

    // A copy reads one storage membership check per candidate it visits: the cursor bounds the reads by the
    // records of `from` made after it, not by the edges of the graph or the earlier records of `from`.
    @Test
    fun `copyLabel since its cursor reads only the later records`() {
        val storage = CountingStorage()
        val counted = TestGraph(storage)
        val nodes = (0 until 60).map { "n$it" }
        nodes.forEach { counted.addNode(it) }
        nodes.zipWithNext().forEach { (src, dst) -> counted.addEdge(src, dst, "earlier", c) }
        nodes.zipWithNext().forEach { (src, dst) -> counted.addEdge(dst, src, "unrelated", other) }
        val cursor = counted.copyLabel(c, p)
        listOf("n0" to "n2", "n1" to "n3").forEach { (src, dst) -> counted.addEdge(src, dst, "later", c) }

        val laterReads = storage.membershipChecksDuring { counted.copyLabel(c, p, since = cursor) }
        val fullReads = storage.membershipChecksDuring { counted.copyLabel(c, p) }

        assertEquals(2, laterReads)
        assertEquals(nodes.size - 1 + 2, fullReads)
        assertEquals(nodes.size - 1 + 2, counted.getLabeledEdges(p).count())
    }
}

/** A native storage that counts edge membership checks, the read [PosetTrait] makes once per candidate it visits. */
private class CountingStorage(
    private val native: NativeStorageImpl = NativeStorageImpl(),
) : IStorage by native {
    private var membershipChecks = 0

    override fun containsEdge(id: Int): Boolean {
        membershipChecks++
        return native.containsEdge(id)
    }

    fun membershipChecksDuring(action: () -> Unit): Int {
        val before = membershipChecks
        action()
        return membershipChecks - before
    }
}
