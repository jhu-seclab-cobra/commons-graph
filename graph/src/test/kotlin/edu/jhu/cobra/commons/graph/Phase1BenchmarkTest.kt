package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.numVal
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Phase 1 optimization benchmarks (B8 + P0 baselines).
 *
 * Measures:
 * - B8: Graph-layer redundant ID set overhead (dual lookup, memory)
 * - P0: IStorage property access latency vs direct array baseline
 *
 * Run with: ./gradlew :graph:test --tests "*.Phase1BenchmarkTest" -PincludePerformanceTests
 */
class Phase1BenchmarkTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    private val nodeIdPool = Array(100_001) { NodeID("n$it") }

    private fun nodeId(i: Int): NodeID = nodeIdPool[i]

    private fun edgeId(
        src: Int,
        dst: Int,
        type: String = "e",
    ): EdgeID = EdgeID(nodeId(src), nodeId(dst), type)

    private fun createGraph(storage: IStorage): GraphTestUtils.TestMultipleGraph {
        val g = GraphTestUtils.TestMultipleGraph(storage)
        closeables.add(g)
        return g
    }

    private fun populateGraph(
        graph: IGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>,
        nodeCount: Int,
        edgesPerNode: Int,
    ) {
        for (i in 0 until nodeCount) graph.addNode(nodeId(i))
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                graph.addEdge(edgeId(i, dst, "e$j"))
            }
        }
    }

    private companion object {
        const val WARMUP = 3
        const val MEASURED = 5
    }

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int = WARMUP,
        measured: Int = MEASURED,
        crossinline operation: (Int) -> Unit,
    ): Double {
        for (w in 0 until warmup) {
            for (i in 0 until ops) operation(i)
        }
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            System.gc()
            val start = System.nanoTime()
            for (i in 0 until ops) operation(i)
            val sec = (System.nanoTime() - start) / 1_000_000_000.0
            samples[r] = if (sec > 0) ops / sec else Double.MAX_VALUE
        }
        samples.sort()
        return samples[measured / 2]
    }

    private fun fmt(ops: Double): String =
        when {
            ops >= 1_000_000 -> String.format("%.2fM", ops / 1_000_000)
            ops >= 1_000 -> String.format("%.1fK", ops / 1_000)
            else -> String.format("%.0f", ops)
        }

    private fun fmtMB(mb: Double): String = String.format("%.1f", mb)

    private fun measureHeapMB(block: () -> Unit): Double {
        System.gc()
        Thread.sleep(100)
        System.gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        block()
        System.gc()
        Thread.sleep(100)
        System.gc()
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        return (after - before) / 1_048_576.0
    }

    // ========================================================================
    // B8: Graph-layer containNode — dual lookup vs storage-only
    // ========================================================================

    @Test
    fun `benchmark B8 graph-level containNode vs storage containsNode`() {
        val nodeCount = 50_000
        val edgesPerNode = 3
        val queryCount = 200_000

        println("\n=== B8: Graph containNode (dual lookup) vs Storage containsNode (single) ===")
        println("$nodeCount nodes, $edgesPerNode edges/node, $queryCount queries")
        println(String.format("%-28s %14s", "Operation", "ops/sec"))
        println("-".repeat(44))

        val storage = NativeStorageImpl()
        closeables.add(storage)
        val graph = createGraph(storage)
        populateGraph(graph, nodeCount, edgesPerNode)

        val graphContainOps =
            benchmarkOpsPerSec(queryCount) { i ->
                graph.containNode(nodeId(i % nodeCount))
            }
        val storageContainOps =
            benchmarkOpsPerSec(queryCount) { i ->
                storage.containsNode(nodeId(i % nodeCount))
            }

        println(String.format("%-28s %14s", "graph.containNode (dual)", fmt(graphContainOps)))
        println(String.format("%-28s %14s", "storage.containsNode (single)", fmt(storageContainOps)))
        println(String.format("%-28s %13.2fx", "overhead", storageContainOps / graphContainOps))
    }

    // ========================================================================
    // B8: Graph-level getNode / getOutgoingEdges with edgeID filtering
    // ========================================================================

    @Test
    fun `benchmark B8 graph-level query overhead from ID set filtering`() {
        val nodeCount = 10_000
        val edgesPerNode = 5
        val queryCount = 100_000

        println("\n=== B8: Graph-Level Query Overhead (edgeID filter) ===")
        println("$nodeCount nodes, $edgesPerNode edges/node, $queryCount queries")
        println(String.format("%-28s %14s %14s", "Operation", "graph-level", "storage-level"))
        println("-".repeat(58))

        val storage = NativeStorageImpl()
        closeables.add(storage)
        val graph = createGraph(storage)
        populateGraph(graph, nodeCount, edgesPerNode)

        // getNode
        val graphGetNodeOps =
            benchmarkOpsPerSec(queryCount) { i ->
                graph.getNode(nodeId(i % nodeCount))
            }
        val storageGetNodeOps =
            benchmarkOpsPerSec(queryCount) { i ->
                storage.containsNode(nodeId(i % nodeCount))
            }

        // getOutgoingEdges (graph filters by edgeIDs)
        val graphOutEdgeOps =
            benchmarkOpsPerSec(queryCount) { i ->
                graph.getOutgoingEdges(nodeId(i % nodeCount)).count()
            }
        val storageOutEdgeOps =
            benchmarkOpsPerSec(queryCount) { i ->
                storage.getOutgoingEdges(nodeId(i % nodeCount)).size
            }

        println(
            String.format(
                "%-28s %14s %14s",
                "getNode / containsNode",
                fmt(graphGetNodeOps),
                fmt(storageGetNodeOps),
            ),
        )
        println(
            String.format(
                "%-28s %14s %14s",
                "getOutgoingEdges",
                fmt(graphOutEdgeOps),
                fmt(storageOutEdgeOps),
            ),
        )
    }

    // ========================================================================
    // B8: Memory overhead from redundant nodeIDs + edgeIDs sets
    // ========================================================================

    @Test
    fun `benchmark B8 memory overhead from redundant ID sets`() {
        val nodeCount = 50_000
        val edgesPerNode = 3
        val totalEdges = nodeCount * edgesPerNode

        println("\n=== B8: Memory Overhead — Redundant ID Sets ===")
        println("$nodeCount nodes, $totalEdges edges")
        println(String.format("%-28s %14s", "Metric", "MB"))
        println("-".repeat(44))

        // Storage only
        val storageMB =
            measureHeapMB {
                val s = NativeStorageImpl()
                closeables.add(s)
                for (i in 0 until nodeCount) s.addNode(nodeId(i))
                for (i in 0 until nodeCount) {
                    for (j in 1..edgesPerNode) {
                        s.addEdge(edgeId(i, (i + j) % nodeCount, "e$j"))
                    }
                }
            }

        closeables.forEach { runCatching { (it as? IStorage)?.close() } }
        closeables.clear()

        // Storage + graph (with redundant sets)
        val graphMB =
            measureHeapMB {
                val s = NativeStorageImpl()
                closeables.add(s)
                val g = createGraph(s)
                populateGraph(g, nodeCount, edgesPerNode)
            }

        println(String.format("%-28s %14s", "storage only", fmtMB(storageMB)))
        println(String.format("%-28s %14s", "storage + graph (dual sets)", fmtMB(graphMB)))
        println(String.format("%-28s %14s", "graph overhead (ID sets)", fmtMB(graphMB - storageMB)))
    }

    // ========================================================================
    // P0: IStorage property read/write baseline (fixpoint simulation)
    // ========================================================================

    @Test
    fun `benchmark P0 IStorage property access baseline for fixpoint`() {
        val nodeCount = 50_000
        val iterCount = 200_000

        println("\n=== P0: IStorage Property Access — Fixpoint Baseline ===")
        println("$nodeCount nodes, $iterCount iterations (read + conditional write)")
        println(String.format("%-28s %14s %14s", "Approach", "read ops/sec", "write ops/sec"))
        println("-".repeat(58))

        // IStorage approach: store analysis state as node properties
        val storage = NativeStorageImpl()
        closeables.add(storage)
        for (i in 0 until nodeCount) {
            storage.addNode(nodeId(i), mapOf("state" to 0.numVal))
        }

        val storageReadOps =
            benchmarkOpsPerSec(iterCount) { i ->
                storage.getNodeProperty(nodeId(i % nodeCount), "state")
            }
        val storageWriteOps =
            benchmarkOpsPerSec(iterCount) { i ->
                storage.setNodeProperties(nodeId(i % nodeCount), mapOf("state" to i.numVal))
            }

        // Direct array approach: O(1) indexed access without HashMap overhead
        val states = arrayOfNulls<IValue>(nodeCount)
        for (i in 0 until nodeCount) states[i] = 0.numVal

        val arrayReadOps =
            benchmarkOpsPerSec(iterCount) { i ->
                states[i % nodeCount]
            }
        val arrayWriteOps =
            benchmarkOpsPerSec(iterCount) { i ->
                states[i % nodeCount] = i.numVal
            }

        println(String.format("%-28s %14s %14s", "IStorage (HashMap)", fmt(storageReadOps), fmt(storageWriteOps)))
        println(String.format("%-28s %14s %14s", "Direct array (simulated P0)", fmt(arrayReadOps), fmt(arrayWriteOps)))
        println(
            String.format(
                "%-28s %13.1fx %13.1fx",
                "speedup",
                arrayReadOps / storageReadOps,
                arrayWriteOps / storageWriteOps,
            ),
        )
    }

    // ========================================================================
    // P0: Memory comparison — IStorage state vs direct array
    // ========================================================================

    @Test
    fun `benchmark P0 memory IStorage state vs direct array`() {
        val nodeCount = 100_000

        println("\n=== P0: Memory — IStorage State vs Direct Array ===")
        println("$nodeCount nodes, 1 state property per node")
        println(String.format("%-28s %14s", "Approach", "MB"))
        println("-".repeat(44))

        val storageMB =
            measureHeapMB {
                val s = NativeStorageImpl()
                closeables.add(s)
                for (i in 0 until nodeCount) {
                    s.addNode(nodeId(i), mapOf("state" to i.numVal))
                }
            }

        closeables.forEach { runCatching { (it as? IStorage)?.close() } }
        closeables.clear()

        val arrayMB =
            measureHeapMB {
                val states = Array<IValue>(nodeCount) { it.numVal }
                // Keep reference alive
                closeables.add(AutoCloseable { states.size })
            }

        println(String.format("%-28s %14s", "IStorage (HashMap per node)", fmtMB(storageMB)))
        println(String.format("%-28s %14s", "Direct Array<IValue>", fmtMB(arrayMB)))
        println(String.format("%-28s %13.1fx", "savings", storageMB / arrayMB))
    }
}
