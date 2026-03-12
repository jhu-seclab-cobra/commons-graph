package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Benchmarks for LayeredStorage optimization baselines.
 *
 * Measures the current performance of bottleneck operations (B4/B5/B6)
 * so that future optimizations (LazyMergedMap, ConcatenatedSet, merge-on-freeze)
 * can be compared against these baselines.
 *
 * Run with: ./gradlew :graph:test --tests "*.OptimizationBenchmarkTest" -PincludePerformanceTests
 */
class OptimizationBenchmarkTest {
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

    private fun buildProperties(
        i: Int,
        propsPerEntity: Int,
    ): Map<String, IValue> {
        val props = HashMap<String, IValue>(propsPerEntity)
        for (p in 0 until propsPerEntity) {
            props["prop_$p"] =
                when (p % 3) {
                    0 -> i.numVal
                    1 -> "val_${i}_$p".strVal
                    else -> (i * 0.1).numVal
                }
        }
        return props
    }

    private fun populateStorage(
        storage: IStorage,
        nodeCount: Int,
        edgesPerNode: Int,
        propsPerEntity: Int,
    ) {
        for (i in 0 until nodeCount) {
            storage.addNode(nodeId(i), buildProperties(i, propsPerEntity))
        }
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                storage.addEdge(edgeId(i, dst, "e$j"), buildProperties(i * edgesPerNode + j, propsPerEntity))
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

    private fun fmt(ops: Double): String =
        when {
            ops >= 1_000_000 -> String.format("%.2fM", ops / 1_000_000)
            ops >= 1_000 -> String.format("%.1fK", ops / 1_000)
            else -> String.format("%.0f", ops)
        }

    private fun fmtMB(mb: Double): String = String.format("%.1f", mb)

    // ========================================================================
    // BASELINE: NativeStorage read speed (reference for all optimizations)
    // ========================================================================

    @Test
    fun `benchmark baseline NativeStorage read speed`() {
        val nodeCount = 50_000
        val edgesPerNode = 3
        val propsPerEntity = 5
        val opCount = 200_000

        println("\n=== Baseline: NativeStorage Read Speed ===")
        println("$nodeCount nodes, $edgesPerNode edges/node, $propsPerEntity props/entity, $opCount ops")
        println(String.format("%-22s %14s %14s %14s %14s", "Implementation", "getNodeProps", "getNodeProp", "containsNode", "getOutEdges"))
        println("-".repeat(80))

        for (name in listOf("NativeStorageImpl", "NativeConcurStorageImpl")) {
            val storage: IStorage =
                when (name) {
                    "NativeStorageImpl" -> NativeStorageImpl()
                    else -> NativeConcurStorageImpl()
                }
            closeables.add(storage)
            populateStorage(storage, nodeCount, edgesPerNode, propsPerEntity)

            val propsOps = benchmarkOpsPerSec(opCount) { i -> storage.getNodeProperties(nodeId(i % nodeCount)) }
            val propOps = benchmarkOpsPerSec(opCount) { i -> storage.getNodeProperty(nodeId(i % nodeCount), "prop_0") }
            val containsOps = benchmarkOpsPerSec(opCount) { i -> storage.containsNode(nodeId(i % nodeCount)) }
            val edgeOps = benchmarkOpsPerSec(opCount) { i -> storage.getOutgoingEdges(nodeId(i % nodeCount)) }

            println(String.format("%-22s %14s %14s %14s %14s", name, fmt(propsOps), fmt(propOps), fmt(containsOps), fmt(edgeOps)))
            storage.close()
            closeables.remove(storage)
        }
    }

    // ========================================================================
    // B4/B5: LayeredStorage — CROSS-LAYER QUERY OVERHEAD
    // ========================================================================

    @Test
    fun `benchmark B4 B5 cross-layer query overhead`() {
        val nodesPerLayer = 10_000
        val propsPerEntity = 5
        val layerCounts = listOf(1, 2, 5, 10)
        val queryCount = 200_000

        println("\n=== B4/B5: Cross-Layer Query Overhead (baseline for LazyMergedMap, merge-on-freeze) ===")
        println("$nodesPerLayer nodes/layer, $propsPerEntity props/entity, $queryCount queries")
        println(String.format("%-10s %14s %14s %14s %14s", "Layers", "containsNode", "getNodeProps", "getNodeProp", "getOutEdges"))
        println("-".repeat(68))

        for (layers in layerCounts) {
            val storage = LayeredStorageImpl()
            closeables.add(storage)
            var totalNodes = 0
            for (layer in 0 until layers) {
                for (i in 0 until nodesPerLayer) {
                    val nodeIdx = totalNodes + i
                    storage.addNode(nodeId(nodeIdx), buildProperties(nodeIdx, propsPerEntity))
                    if (nodeIdx > 0) {
                        storage.addEdge(edgeId(nodeIdx, nodeIdx - 1, "e"), buildProperties(nodeIdx, 2))
                    }
                }
                totalNodes += nodesPerLayer
                if (layer < layers - 1) storage.freeze()
            }

            val containsOps = benchmarkOpsPerSec(queryCount) { i -> storage.containsNode(nodeId(i % totalNodes)) }
            val propsOps = benchmarkOpsPerSec(queryCount) { i -> storage.getNodeProperties(nodeId(i % totalNodes)) }
            val propOps = benchmarkOpsPerSec(queryCount) { i -> storage.getNodeProperty(nodeId(i % totalNodes), "prop_0") }
            val edgeOps = benchmarkOpsPerSec(queryCount) { i -> storage.getOutgoingEdges(nodeId(i % (totalNodes - 1) + 1)) }

            println(String.format("%-10s %14s %14s %14s %14s", "$layers", fmt(containsOps), fmt(propsOps), fmt(propOps), fmt(edgeOps)))
            storage.close()
            closeables.remove(storage)
        }
    }

    // ========================================================================
    // B6: Edge Query Allocation — HashSet copy overhead in LayeredStorage
    // ========================================================================

    @Test
    fun `benchmark B6 edge query allocation overhead`() {
        val nodeCount = 10_000
        val edgesPerNode = 5
        val queryCount = 200_000

        println("\n=== B6: Edge Query — NativeStorage (zero-copy) vs LayeredStorage (HashSet copy) ===")
        println("$nodeCount nodes, $edgesPerNode edges/node, $queryCount queries")
        println(String.format("%-22s %14s %14s", "Implementation", "getOutEdges", "getInEdges"))
        println("-".repeat(52))

        val native = NativeStorageImpl()
        closeables.add(native)
        populateStorage(native, nodeCount, edgesPerNode, 2)

        val nativeOutOps = benchmarkOpsPerSec(queryCount) { i -> native.getOutgoingEdges(nodeId(i % nodeCount)) }
        val nativeInOps = benchmarkOpsPerSec(queryCount) { i -> native.getIncomingEdges(nodeId(i % nodeCount)) }
        println(String.format("%-22s %14s %14s", "NativeStorageImpl", fmt(nativeOutOps), fmt(nativeInOps)))

        val layered = LayeredStorageImpl()
        closeables.add(layered)
        for (i in 0 until nodeCount) {
            layered.addNode(nodeId(i), buildProperties(i, 2))
        }
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode / 2) {
                val dst = (i + j) % nodeCount
                layered.addEdge(edgeId(i, dst, "e$j"), buildProperties(i, 2))
            }
        }
        layered.freeze()
        for (i in 0 until nodeCount) {
            for (j in (edgesPerNode / 2 + 1)..edgesPerNode) {
                val dst = (i + j) % nodeCount
                layered.addEdge(edgeId(i, dst, "e$j"), buildProperties(i, 2))
            }
        }

        val layeredOutOps = benchmarkOpsPerSec(queryCount) { i -> layered.getOutgoingEdges(nodeId(i % nodeCount)) }
        val layeredInOps = benchmarkOpsPerSec(queryCount) { i -> layered.getIncomingEdges(nodeId(i % nodeCount)) }
        println(String.format("%-22s %14s %14s", "LayeredStorage(2 lyr)", fmt(layeredOutOps), fmt(layeredInOps)))
        println(
            String.format(
                "%-22s %14s %14s",
                "slowdown",
                String.format("%.1fx", nativeOutOps / layeredOutOps),
                String.format("%.1fx", nativeInOps / layeredInOps),
            ),
        )
    }

    // ========================================================================
    // B2: Memory footprint — NativeStorage at different scales
    // ========================================================================

    @Test
    fun `benchmark B2 memory footprint at scale`() {
        val scales = listOf(10_000, 50_000)
        val edgesPerNode = 3
        val propsPerEntity = 5

        println("\n=== B2: NativeStorage Memory Footprint (baseline for schema array optimization) ===")
        println("$edgesPerNode edges/node, $propsPerEntity props/entity")
        println(String.format("%-14s %14s", "Nodes", "heap delta MB"))
        println("-".repeat(30))

        for (nodeCount in scales) {
            closeables.forEach { runCatching { it.close() } }
            closeables.clear()

            val heapMB =
                measureHeapMB {
                    val storage = NativeStorageImpl()
                    closeables.add(storage)
                    populateStorage(storage, nodeCount, edgesPerNode, propsPerEntity)
                }
            println(String.format("%-14s %14s", "${nodeCount / 1000}K", fmtMB(heapMB)))
        }
    }

    // ========================================================================
    // FREEZE COST: LayeredStorage freeze overhead
    // ========================================================================

    @Test
    fun `benchmark freeze cost`() {
        val scales = listOf(10_000 to 3, 50_000 to 3)
        val propsPerEntity = 5

        println("\n=== Freeze Cost: LayeredStorage.freeze() (median ms) ===")
        println("$propsPerEntity props/entity")
        println(String.format("%-14s %14s", "Scale", "freeze ms"))
        println("-".repeat(30))

        for ((nodeCount, edgesPerNode) in scales) {
            val samples = DoubleArray(3)
            for (r in 0 until 3) {
                val storage = LayeredStorageImpl()
                populateStorage(storage, nodeCount, edgesPerNode, propsPerEntity)
                System.gc()
                val start = System.nanoTime()
                storage.freeze()
                samples[r] = (System.nanoTime() - start) / 1_000_000.0
                storage.close()
            }
            samples.sort()
            println(
                String.format(
                    "%-14s %14s",
                    "${nodeCount / 1000}K/${nodeCount * edgesPerNode / 1000}K",
                    String.format("%.1f", samples[1]),
                ),
            )
        }
    }
}
