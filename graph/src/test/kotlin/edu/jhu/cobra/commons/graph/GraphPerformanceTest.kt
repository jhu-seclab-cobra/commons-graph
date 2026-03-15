package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeConcurStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Performance benchmarks for IGraph implementations (AbcMultipleGraph, AbcSimpleGraph)
 * backed by different IStorage implementations, including lattice operations.
 *
 * Scale tiers:
 *   - Small:  10K nodes / 30K edges
 *   - Medium: 100K nodes / 300K edges
 *   - Large:  1M nodes / 3M edges
 *
 * Run with: ./gradlew :graph:test --tests "*.GraphPerformanceTest"
 */
class GraphPerformanceTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    // -- Storage factories for graph backends ---------------------------------

    private val storageNames =
        listOf(
            "NativeStorage",
            "NativeConcurStorage",
            "LayeredStorage",
        )

    private fun createStorage(name: String): IStorage {
        val storage =
            when (name) {
                "NativeStorage" -> NativeStorageImpl()
                "NativeConcurStorage" -> NativeConcurStorageImpl()
                "LayeredStorage" -> LayeredStorageImpl()
                else -> throw IllegalArgumentException("Unknown: $name")
            }
        closeables.add(storage)
        return storage
    }

    // -- Graph helpers --------------------------------------------------------

    private fun createMultipleGraph(storageName: String): AbcMultipleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge> {
        val s = createStorage(storageName)
        val ps = NativeStorageImpl()
        closeables.add(ps)
        val g = GraphTestUtils.createTestMultipleGraph(s, ps)
        closeables.add(g)
        return g
    }

    private fun createSimpleGraph(storageName: String): AbcSimpleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge> {
        val s = createStorage(storageName)
        val ps = NativeStorageImpl()
        closeables.add(ps)
        val g = GraphTestUtils.createTestSimpleGraph(s, ps)
        closeables.add(g)
        return g
    }

    private val nodeIdPool = Array(100_001) { "n$it" }

    private fun nodeId(i: Int): NodeID = nodeIdPool[i]

    private fun populateMultipleGraph(
        graph: IGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>,
        nodeCount: Int,
        edgesPerNode: Int,
    ) {
        for (i in 0 until nodeCount) graph.addNode(nodeId(i))
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                graph.addEdge(nodeId(i), nodeId(dst), "e$j")
            }
        }
    }

    // -- Measurement helpers --------------------------------------------------

    private companion object {
        const val WARMUP = 2
        const val MEASURED = 3
    }

    private inline fun benchmarkMs(
        warmup: Int = WARMUP,
        measured: Int = MEASURED,
        crossinline block: () -> Unit,
    ): Double {
        for (w in 0 until warmup) block()
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            System.gc()
            val start = System.nanoTime()
            block()
            samples[r] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        return samples[measured / 2]
    }

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int = WARMUP,
        measured: Int = MEASURED,
        crossinline op: (Int) -> Unit,
    ): Double {
        for (w in 0 until warmup) {
            for (i in 0 until ops) op(i)
        }
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            System.gc()
            val start = System.nanoTime()
            for (i in 0 until ops) op(i)
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

    private fun fmtMs(ms: Double): String = String.format("%.1f", ms)

    // ========================================================================
    // BENCHMARK: AbcMultipleGraph POPULATION (different storages)
    // ========================================================================

    @Test
    fun `benchmark multiple graph population with different storages`() {
        data class Scale(
            val label: String,
            val nodes: Int,
            val epn: Int,
        )
        val scales =
            listOf(
                Scale("10K/30K", 10_000, 3),
                Scale("100K/300K", 100_000, 3),
            )
        println("\n=== AbcMultipleGraph Population (median ms, nodes/edges) ===")
        println(String.format("%-20s %14s %14s", "Storage", "10K/30K", "100K/300K"))
        println("-".repeat(50))

        for (name in storageNames) {
            val results =
                scales.map { (_, n, epn) ->
                    benchmarkMs(warmup = 1, measured = 3) {
                        val g = createMultipleGraph(name)
                        populateMultipleGraph(g, n, epn)
                        g.close()
                    }
                }
            println(
                String.format(
                    "%-20s %14s %14s",
                    name,
                    fmtMs(results[0]),
                    fmtMs(results[1]),
                ),
            )
        }
    }

    // ========================================================================
    // BENCHMARK: AbcSimpleGraph POPULATION (different storages)
    // ========================================================================

    @Test
    fun `benchmark simple graph population with different storages`() {
        val nodeCount = 100_000
        val edgesPerNode = 3
        println("\n=== AbcSimpleGraph Population (median ms, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-20s %14s", "Storage", "ms"))
        println("-".repeat(36))

        for (name in storageNames) {
            val ms =
                benchmarkMs(warmup = 1, measured = 3) {
                    val g = createSimpleGraph(name)
                    populateMultipleGraph(g, nodeCount, edgesPerNode)
                    g.close()
                }
            println(String.format("%-20s %14s", name, fmtMs(ms)))
        }
    }

    // ========================================================================
    // BENCHMARK: GRAPH-LEVEL NODE/EDGE QUERIES
    // ========================================================================

    @Test
    fun `benchmark graph-level queries with different storages`() {
        val nodeCount = 10_000
        val edgesPerNode = 5
        val queryCount = 100_000
        println("\n=== Graph-Level Queries (median ops/sec, $queryCount queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-20s %12s %12s %12s %12s", "Storage", "getNode", "getOutEdges", "getChildren", "getDescend"))
        println("-".repeat(70))

        for (name in storageNames) {
            val g = createMultipleGraph(name)
            populateMultipleGraph(g, nodeCount, edgesPerNode)

            val getNodeOps = benchmarkOpsPerSec(queryCount) { i -> g.getNode(nodeId(i % nodeCount)) }
            val outEdgeOps = benchmarkOpsPerSec(queryCount) { i -> g.getOutgoingEdges(nodeId(i % nodeCount)).count() }
            val childrenOps = benchmarkOpsPerSec(queryCount) { i -> g.getChildren(nodeId(i % nodeCount)).count() }
            // Descendants is expensive — use fewer iterations
            val descendOps = benchmarkOpsPerSec(1_000) { i -> g.getDescendants(nodeId(i % nodeCount)).take(20).count() }

            println(
                String.format(
                    "%-20s %12s %12s %12s %12s",
                    name,
                    fmt(getNodeOps),
                    fmt(outEdgeOps),
                    fmt(childrenOps),
                    fmt(descendOps),
                ),
            )
            g.close()
        }
    }

    // ========================================================================
    // BENCHMARK: LATTICE OPERATIONS
    // ========================================================================

    @Test
    fun `benchmark lattice label assignment and filtered queries`() {
        val nodeCount = 5_000
        val edgesPerNode = 3
        val labelCount = 5
        val queryCount = 50_000
        println("\n=== Lattice Operations (${nodeCount}n/${nodeCount * edgesPerNode}e, $labelCount labels) ===")
        println(String.format("%-20s %14s %14s", "Storage", "assignLabels", "filteredQuery"))
        println("-".repeat(50))

        for (name in storageNames) {
            val g = createMultipleGraph(name)
            populateMultipleGraph(g, nodeCount, edgesPerNode)

            // Build a simple label hierarchy: L0 < L1 < L2 < ... < L(n-1)
            val labels = (0 until labelCount).map { Label("L$it") }
            with(g) {
                for (i in 1 until labelCount) {
                    labels[i].parents = mapOf("parent" to labels[i - 1])
                }
            }

            // Assign labels to edges
            val assignMs =
                benchmarkMs(warmup = 1, measured = 3) {
                    var idx = 0
                    for (edge in g.getAllEdges().take(nodeCount * edgesPerNode)) {
                        edge.labels = setOf(labels[idx % labelCount])
                        idx++
                    }
                }

            // Filtered edge query using mid-level label
            val midLabel = labels[labelCount / 2]
            val filteredOps =
                benchmarkOpsPerSec(queryCount) { i ->
                    g.getOutgoingEdges(nodeId(i % nodeCount), midLabel).count()
                }

            println(
                String.format(
                    "%-20s %14s %14s",
                    name,
                    fmtMs(assignMs),
                    fmt(filteredOps),
                ),
            )
            g.close()
        }
    }

    // ========================================================================
    // BENCHMARK: COLD QUERY (each node accessed only 1-2 times)
    // ========================================================================

    @Test
    fun `benchmark cold query pattern - each node accessed once`() {
        val nodeCount = 50_000
        val edgesPerNode = 3
        println("\n=== Cold Query (each of $nodeCount nodes accessed 1-2 times, ${edgesPerNode}e/n) ===")
        println(String.format("%-20s %12s %12s %12s", "Storage", "getNode", "getOutEdges", "getChildren"))
        println("-".repeat(58))

        for (name in storageNames) {
            val g = createMultipleGraph(name)
            populateMultipleGraph(g, nodeCount, edgesPerNode)

            val getNodeOps =
                benchmarkOpsPerSec(nodeCount, warmup = 1) { i ->
                    g.getNode(nodeId(i))
                }

            val outEdgeOps =
                benchmarkOpsPerSec(nodeCount, warmup = 1) { i ->
                    g.getOutgoingEdges(nodeId(i)).count()
                }

            val childrenOps =
                benchmarkOpsPerSec(nodeCount, warmup = 1) { i ->
                    g.getChildren(nodeId(i)).count()
                }

            println(
                String.format(
                    "%-20s %12s %12s %12s",
                    name,
                    fmt(getNodeOps),
                    fmt(outEdgeOps),
                    fmt(childrenOps),
                ),
            )
            g.close()
        }
    }

    // ========================================================================
    // BENCHMARK: MIXED ACCESS (most cold, some hot) + MEMORY
    // ========================================================================

    @Test
    fun `benchmark mixed access pattern and memory usage`() {
        val nodeCount = 50_000
        val edgesPerNode = 3
        val totalOps = 200_000
        val hotNodeCount = 100
        println("\n=== Mixed Access ($hotNodeCount hot nodes + ${nodeCount - hotNodeCount} cold, $totalOps ops, ${edgesPerNode}e/n) ===")
        println(String.format("%-20s %12s %12s %14s", "Storage", "mixedOps/s", "getChild/s", "heapUsed(MB)"))
        println("-".repeat(60))

        // 80% of accesses go to hotNodeCount nodes, 20% spread across all
        val rng = java.util.Random(42)
        val accessPattern =
            IntArray(totalOps) { i ->
                if (rng.nextDouble() < 0.8) rng.nextInt(hotNodeCount) else rng.nextInt(nodeCount)
            }

        for (name in storageNames) {
            val g = createMultipleGraph(name)
            populateMultipleGraph(g, nodeCount, edgesPerNode)

            val mixedGetNodeOps =
                benchmarkOpsPerSec(totalOps, warmup = 1) { i ->
                    g.getNode(nodeId(accessPattern[i]))
                }

            val mixedChildrenOps =
                benchmarkOpsPerSec(totalOps, warmup = 1) { i ->
                    g.getChildren(nodeId(accessPattern[i])).count()
                }

            // Measure heap after full access
            System.gc()
            Thread.sleep(100)
            System.gc()
            val runtime = Runtime.getRuntime()
            val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

            println(
                String.format(
                    "%-20s %12s %12s %14s",
                    name,
                    fmt(mixedGetNodeOps),
                    fmt(mixedChildrenOps),
                    String.format("%.1f", usedMB),
                ),
            )
            g.close()
        }
    }
}
