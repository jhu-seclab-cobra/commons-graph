package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Performance benchmarks for core IStorage implementations at varying scales.
 *
 * Scale tiers use node/edge counts as the primary metric:
 *   - Small:  10K nodes / 30K edges
 *   - Medium: 100K nodes / 300K edges
 *   - Large:  1M nodes / 3M edges
 *
 * Each measurement uses warmup iterations (JIT stabilization) followed by
 * multiple measured iterations, reporting median values.
 *
 * Run with: ./gradlew :graph:test --tests "*.StoragePerformanceTest"
 */
class StoragePerformanceTest {
    private val storages = mutableListOf<IStorage>()

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
    }

    private val implNames =
        listOf(
            "NativeStorageImpl",
            "NativeConcurStorageImpl",
            "LayeredStorageImpl",
        )

    private fun createStorage(name: String): IStorage {
        val storage =
            when (name) {
                "NativeStorageImpl" -> NativeStorageImpl()
                "NativeConcurStorageImpl" -> NativeConcurStorageImpl()
                "LayeredStorageImpl" -> LayeredStorageImpl()
                else -> throw IllegalArgumentException("Unknown storage: $name")
            }
        storages.add(storage)
        return storage
    }

    /**
     * Populates a graph with [nodeCount] nodes and [edgesPerNode] edges per node.
     * Returns an array of node IDs for subsequent lookups.
     */
    private fun populateGraph(
        storage: IStorage,
        nodeCount: Int,
        edgesPerNode: Int,
    ): Array<Int> {
        val nodeIds = Array(nodeCount) { i -> storage.addNode(mapOf("idx" to i.numVal)) }
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                storage.addEdge(nodeIds[i], nodeIds[dst], "e$j", mapOf("w" to j.numVal))
            }
        }
        return nodeIds
    }

    private companion object {
        const val WARMUP = 3
        const val MEASURED = 5
    }

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int = WARMUP,
        measured: Int = MEASURED,
        setup: () -> Unit = {},
        crossinline operation: (Int) -> Unit,
    ): Double {
        for (w in 0 until warmup) {
            setup()
            for (i in 0 until ops) operation(i)
        }
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            setup()
            System.gc()
            val start = System.nanoTime()
            for (i in 0 until ops) operation(i)
            val sec = (System.nanoTime() - start) / 1_000_000_000.0
            samples[r] = if (sec > 0) ops / sec else Double.MAX_VALUE
        }
        samples.sort()
        return samples[measured / 2]
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

    private fun fmt(ops: Double): String =
        when {
            ops >= 1_000_000 -> String.format("%.2fM", ops / 1_000_000)
            ops >= 1_000 -> String.format("%.1fK", ops / 1_000)
            else -> String.format("%.0f", ops)
        }

    private fun fmtMs(ms: Double): String = String.format("%.1f", ms)

    // ========================================================================
    // BENCHMARK: GRAPH POPULATION AT SCALE
    // ========================================================================

    @Test
    fun `benchmark graph population at scale`() {
        data class Scale(
            val label: String,
            val nodes: Int,
            val edgesPerNode: Int,
        )

        val scales =
            listOf(
                Scale("10K/30K", 10_000, 3),
                Scale("100K/300K", 100_000, 3),
                Scale("1M/3M", 1_000_000, 3),
            )
        println("\n=== Graph Population (median ms, nodes/edges) ===")
        println(String.format("%-24s %14s %14s %14s", "Implementation", "10K/30K", "100K/300K", "1M/3M"))
        println("-".repeat(68))

        for (name in implNames) {
            val results =
                scales.map { (_, n, epn) ->
                    benchmarkMs(warmup = 1, measured = 3) {
                        val s = createStorage(name)
                        populateGraph(s, n, epn)
                        s.close()
                    }
                }
            println(
                String.format(
                    "%-24s %14s %14s %14s",
                    name,
                    fmtMs(results[0]),
                    fmtMs(results[1]),
                    fmtMs(results[2]),
                ),
            )
        }
    }

    // ========================================================================
    // BENCHMARK: NODE ADD
    // ========================================================================

    @Test
    fun `benchmark node add at scale`() {
        val scales = listOf(10_000, 100_000, 1_000_000)
        println("\n=== Node Add (median ops/sec) ===")
        println(String.format("%-24s %14s %14s %14s", "Implementation", "10K", "100K", "1M"))
        println("-".repeat(68))

        for (name in implNames) {
            val results =
                scales.map { n ->
                    val ref = arrayOfNulls<IStorage>(1)
                    benchmarkOpsPerSec(
                        n,
                        warmup = 1,
                        measured = 3,
                        setup = {
                            ref[0]?.let { runCatching { it.close() } }
                            ref[0] = createStorage(name)
                        },
                        operation = { i -> ref[0]!!.addNode(mapOf("idx" to i.numVal)) },
                    ).also { ref[0]?.let { s -> runCatching { s.close() } } }
                }
            println(
                String.format(
                    "%-24s %14s %14s %14s",
                    name,
                    fmt(results[0]),
                    fmt(results[1]),
                    fmt(results[2]),
                ),
            )
        }
    }

    // ========================================================================
    // BENCHMARK: EDGE ADD
    // ========================================================================

    @Test
    fun `benchmark edge add at scale`() {
        val nodeCount = 10_000
        val edgeCounts = listOf(10_000, 100_000, 1_000_000)
        println("\n=== Edge Add (median ops/sec, $nodeCount pre-loaded nodes) ===")
        println(String.format("%-24s %14s %14s %14s", "Implementation", "10K", "100K", "1M"))
        println("-".repeat(68))

        for (name in implNames) {
            val results =
                edgeCounts.map { edgeCount ->
                    val ref = arrayOfNulls<IStorage>(1)
                    var nodeIds = emptyArray<Int>()
                    benchmarkOpsPerSec(
                        edgeCount,
                        warmup = 1,
                        measured = 3,
                        setup = {
                            ref[0]?.let { runCatching { it.close() } }
                            ref[0] = createStorage(name)
                            nodeIds = Array(nodeCount) { ref[0]!!.addNode() }
                        },
                        operation = { i ->
                            val src = i % nodeCount
                            val dst = (i + 1) % nodeCount
                            ref[0]!!.addEdge(nodeIds[src], nodeIds[dst], "e$i", mapOf("w" to i.numVal))
                        },
                    ).also { ref[0]?.let { s -> runCatching { s.close() } } }
                }
            println(
                String.format(
                    "%-24s %14s %14s %14s",
                    name,
                    fmt(results[0]),
                    fmt(results[1]),
                    fmt(results[2]),
                ),
            )
        }
    }

    // ========================================================================
    // BENCHMARK: NODE LOOKUP on large graph
    // ========================================================================

    @Test
    fun `benchmark node lookup on large graph`() {
        val nodeCount = 100_000
        val lookupCount = 500_000
        println("\n=== Node Lookup (median ops/sec, $lookupCount lookups on $nodeCount nodes) ===")
        println(String.format("%-24s %14s", "Implementation", "ops/sec"))
        println("-".repeat(40))

        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds = Array(nodeCount) { storage.addNode() }
            val ops =
                benchmarkOpsPerSec(lookupCount) { i ->
                    storage.containsNode(nodeIds[i % nodeCount])
                }
            println(String.format("%-24s %14s", name, fmt(ops)))
            storage.close()
        }
    }

    // ========================================================================
    // BENCHMARK: PROPERTY READ/WRITE on large graph
    // ========================================================================

    @Test
    fun `benchmark property read and write on large graph`() {
        val nodeCount = 50_000
        val opCount = 200_000
        println("\n=== Property Read/Write (median ops/sec, $opCount ops on $nodeCount nodes) ===")
        println(String.format("%-24s %14s %14s", "Implementation", "read", "write"))
        println("-".repeat(54))

        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds =
                Array(nodeCount) { i ->
                    storage.addNode(mapOf("name" to "node$i".strVal, "idx" to i.numVal))
                }
            val readOps =
                benchmarkOpsPerSec(opCount) { i ->
                    storage.getNodeProperties(nodeIds[i % nodeCount])
                }
            val writeOps =
                benchmarkOpsPerSec(opCount) { i ->
                    storage.setNodeProperties(nodeIds[i % nodeCount], mapOf("v" to i.numVal))
                }
            println(String.format("%-24s %14s %14s", name, fmt(readOps), fmt(writeOps)))
            storage.close()
        }
    }

    // ========================================================================
    // BENCHMARK: EDGE QUERY on large graph
    // ========================================================================

    @Test
    fun `benchmark edge query on large graph`() {
        val nodeCount = 10_000
        val edgesPerNode = 5
        val queryCount = 100_000
        println("\n=== Edge Query (median ops/sec, $queryCount queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-24s %14s %14s", "Implementation", "outgoing", "incoming"))
        println("-".repeat(54))

        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds = populateGraph(storage, nodeCount, edgesPerNode)
            val outOps =
                benchmarkOpsPerSec(queryCount) { i ->
                    storage.getOutgoingEdges(nodeIds[i % nodeCount])
                }
            val inOps =
                benchmarkOpsPerSec(queryCount) { i ->
                    storage.getIncomingEdges(nodeIds[i % nodeCount])
                }
            println(String.format("%-24s %14s %14s", name, fmt(outOps), fmt(inOps)))
            storage.close()
        }
    }

    // ========================================================================
    // BENCHMARK: NODE DELETE with cascade
    // ========================================================================

    @Test
    fun `benchmark node delete with cascade`() {
        val nodeCount = 10_000
        val edgesPerNode = 3
        val deleteCount = 2_000
        println("\n=== Node Delete (median ops/sec, $deleteCount deletes from ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-24s %14s", "Implementation", "ops/sec"))
        println("-".repeat(40))

        for (name in implNames) {
            val ref = arrayOfNulls<IStorage>(1)
            var nodeIds = emptyArray<Int>()
            val ops =
                benchmarkOpsPerSec(
                    deleteCount,
                    warmup = 1,
                    measured = 3,
                    setup = {
                        ref[0]?.let { runCatching { it.close() } }
                        ref[0] = createStorage(name)
                        nodeIds = populateGraph(ref[0]!!, nodeCount, edgesPerNode)
                    },
                    operation = { i -> ref[0]!!.deleteNode(nodeIds[i]) },
                )
            ref[0]?.let { runCatching { it.close() } }
            println(String.format("%-24s %14s", name, fmt(ops)))
        }
    }

    // ========================================================================
    // BENCHMARK: MIXED WORKLOAD at scale
    // ========================================================================

    @Test
    fun `benchmark mixed workload at scale`() {
        val iterations = 50_000
        println("\n=== Mixed Workload (median ms, $iterations iterations) ===")
        println("Each: addNode + addEdge + getProperties + containsNode + getOutgoingEdges")
        println(String.format("%-24s %14s", "Implementation", "median ms"))
        println("-".repeat(40))

        for (name in implNames) {
            val ms =
                benchmarkMs(warmup = 1, measured = 3) {
                    val storage = createStorage(name)
                    val baseNode = storage.addNode()
                    val nodeIds = mutableListOf(baseNode)
                    for (i in 1..iterations) {
                        val newNode = storage.addNode(mapOf("v" to i.numVal))
                        nodeIds.add(newNode)
                        storage.addEdge(newNode, baseNode, "e$i")
                        storage.getNodeProperties(newNode)
                        storage.containsNode(newNode)
                        storage.getOutgoingEdges(newNode)
                    }
                    storage.close()
                }
            println(String.format("%-24s %14s", name, fmtMs(ms)))
        }
    }

    // ========================================================================
    // BENCHMARK: LAYERED STORAGE — MULTI-LAYER QUERY
    // ========================================================================

    @Test
    fun `benchmark memory footprint`() {
        val nodeCount = 10_000
        val edgesPerNode = 3
        val propsPerEntity = 5
        println(
            "\n=== Memory Footprint ($nodeCount nodes, ${nodeCount * edgesPerNode} edges, $propsPerEntity props/entity) ===",
        )
        println(String.format("%-28s %14s", "Implementation", "heap delta MB"))
        println("-".repeat(44))

        for (name in implNames) {
            System.gc()
            Thread.sleep(200)
            System.gc()
            val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val storage = createStorage(name)
            val nodeIds = Array(nodeCount) { i ->
                val props = (1..propsPerEntity).associate { "p$it" to "val_${i}_$it".strVal }
                storage.addNode(props)
            }
            for (i in 0 until nodeCount) {
                for (j in 1..edgesPerNode) {
                    val dst = (i + j) % nodeCount
                    val props = (1..propsPerEntity).associate { "p$it" to "val_${i}_${j}_$it".strVal }
                    storage.addEdge(nodeIds[i], nodeIds[dst], "e$j", props)
                }
            }

            System.gc()
            Thread.sleep(200)
            System.gc()
            val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val deltaMB = (after - before) / (1024.0 * 1024.0)
            println(String.format("%-28s %14.1f", name, deltaMB))
            storage.close()
        }
    }

    @Test
    fun `benchmark layered storage multi-layer query`() {
        val nodesPerLayer = 10_000
        val layerCounts = listOf(1, 3, 5, 10)
        val queryCount = 100_000
        println("\n=== Layered Storage Multi-Layer Query (median ops/sec, $queryCount queries, $nodesPerLayer nodes/layer) ===")
        println(String.format("%-16s %14s %14s", "Layers", "containsNode", "getProps"))
        println("-".repeat(46))

        for (layers in layerCounts) {
            val storage = LayeredStorageImpl()
            storages.add(storage)
            val allNodeIds = mutableListOf<Int>()
            for (layer in 0 until layers) {
                for (i in 0 until nodesPerLayer) {
                    allNodeIds.add(storage.addNode(mapOf("layer" to layer.numVal, "idx" to i.numVal)))
                }
                if (layer < layers - 1) storage.freeze()
            }
            val totalNodes = allNodeIds.size
            val containsOps =
                benchmarkOpsPerSec(queryCount) { i ->
                    storage.containsNode(allNodeIds[i % totalNodes])
                }
            val propsOps =
                benchmarkOpsPerSec(queryCount) { i ->
                    storage.getNodeProperties(allNodeIds[i % totalNodes])
                }
            println(String.format("%-16s %14s %14s", "$layers layers", fmt(containsOps), fmt(propsOps)))
            storage.close()
        }
    }
}
