package edu.jhu.cobra.commons.graph.poset

import edu.jhu.cobra.commons.graph.storage.NativeConcurStorageImpl
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for PosetTrait label operations on a thread-safe storage
 * (design-label.md, PosetTrait; spec-poset.md, Label Carrier Index edge cases).
 *
 * - `concurrent labeled addEdge on one edge loses no label` -- label read-modify-write is atomic
 * - `concurrent relabel and labeled addEdge keep every label indexed` -- index and labels stay consistent
 */
internal class PosetTraitConcurrencyTest {
    private lateinit var graph: TestGraph

    @BeforeTest
    fun setUp() {
        graph = TestGraph(NativeConcurStorageImpl())
        graph.addNode("a")
        graph.addNode("b")
    }

    private fun runConcurrently(
        tasks: Int,
        task: (Int) -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(THREADS)
        val errors = CopyOnWriteArrayList<Throwable>()
        for (i in 0 until tasks) {
            executor.submit {
                try {
                    task(i)
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "tasks timed out")
        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
    }

    @Test
    fun `concurrent labeled addEdge on one edge loses no label`() {
        val labels = (0 until TASKS).map { Label("l$it") }

        runConcurrently(TASKS) { graph.addEdge("a", "b", "rel", labels[it]) }

        assertEquals(labels.toSet(), graph.getEdge("a", "b", "rel")!!.labels)
        for (label in labels) assertEquals(1, graph.getLabeledEdges(label).count(), "label ${label.core}")
    }

    @Test
    fun `concurrent relabel and labeled addEdge keep every label indexed`() {
        val labels = (0 until TASKS).map { Label("l$it") }
        val renamed = labels.map { Label("${it.core}-renamed") }
        for (label in labels) graph.addEdge("a", "b", "rel", label)

        runConcurrently(TASKS) {
            graph.relabel(labels[it], renamed[it])
            graph.addEdge("a", "b", "rel", Label("extra$it"))
        }

        val carried = graph.getEdge("a", "b", "rel")!!.labels
        for (label in carried) assertEquals(1, graph.getLabeledEdges(label).count(), "label ${label.core}")
        assertEquals(2 * TASKS, carried.size)
    }

    private companion object {
        // Enough workers to interleave label read-modify-writes on one edge.
        const val THREADS = 8

        // Label writes per test; each targets the same edge.
        const val TASKS = 200
    }
}
