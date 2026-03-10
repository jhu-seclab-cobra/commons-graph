package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Performance benchmarks for all IStorage implementations.
 *
 * Measures throughput of core operations at multiple graph scales (small, medium, large).
 * Results are printed to stdout for manual review and README documentation.
 *
 * Covers: NativeStorageImpl, NativeConcurStorageImpl, DeltaStorageImpl,
 * DeltaConcurStorageImpl, PhasedStorageImpl.
 */
class StoragePerformanceTest {
    private val storages = mutableListOf<IStorage>()

    @BeforeTest
    fun setup() {
        storages.clear()
    }

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
    }

    private fun createStorage(name: String): IStorage {
        val storage =
            when (name) {
                "NativeStorageImpl" -> NativeStorageImpl()
                "NativeConcurStorageImpl" -> NativeConcurStorageImpl()
                "DeltaStorageImpl" -> DeltaStorageImpl(NativeStorageImpl())
                "DeltaConcurStorageImpl" -> DeltaConcurStorageImpl(NativeStorageImpl())
                "PhasedStorageImpl" -> PhasedStorageImpl()
                else -> throw IllegalArgumentException("Unknown storage: $name")
            }
        storages.add(storage)
        return storage
    }

    private val implNames =
        listOf(
            "NativeStorageImpl",
            "NativeConcurStorageImpl",
            "DeltaStorageImpl",
            "DeltaConcurStorageImpl",
            "PhasedStorageImpl",
        )

    // ============================================================================
    // HELPERS
    // ============================================================================

    private fun nodeId(i: Int) = NodeID("n$i")

    private fun edgeId(
        src: Int,
        dst: Int,
        type: String = "e",
    ) = EdgeID(nodeId(src), nodeId(dst), type)

    private fun populateGraph(
        storage: IStorage,
        nodeCount: Int,
        edgesPerNode: Int,
    ) {
        for (i in 0 until nodeCount) {
            storage.addNode(nodeId(i), mapOf("idx" to i.numVal))
        }
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                storage.addEdge(edgeId(i, dst, "e$j"), mapOf("w" to j.numVal))
            }
        }
    }

    private inline fun measureMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private inline fun measureOpsPerSec(
        iterations: Int,
        block: (Int) -> Unit,
    ): Double {
        val start = System.nanoTime()
        for (i in 0 until iterations) block(i)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        return if (elapsedMs > 0) iterations / (elapsedMs / 1000.0) else Double.MAX_VALUE
    }

    private fun formatOps(ops: Double): String =
        when {
            ops >= 1_000_000 -> String.format("%.2fM", ops / 1_000_000)
            ops >= 1_000 -> String.format("%.1fK", ops / 1_000)
            else -> String.format("%.0f", ops)
        }

    // ============================================================================
    // BENCHMARK: NODE ADD
    // ============================================================================

    @Test
    fun `benchmark node add across all implementations`() {
        val scales = listOf(1_000, 5_000, 10_000)
        println("\n=== Node Add Performance (ops/sec) ===")
        println(String.format("%-28s %12s %12s %12s", "Implementation", "1K", "5K", "10K"))
        println("-".repeat(66))

        for (name in implNames) {
            val results = mutableListOf<String>()
            for (n in scales) {
                val storage = createStorage(name)
                val ops =
                    measureOpsPerSec(n) { i ->
                        storage.addNode(nodeId(i), mapOf("idx" to i.numVal))
                    }
                results.add(formatOps(ops))
                storage.close()
            }
            println(String.format("%-28s %12s %12s %12s", name, results[0], results[1], results[2]))
        }
    }

    // ============================================================================
    // BENCHMARK: EDGE ADD
    // ============================================================================

    @Test
    fun `benchmark edge add across all implementations`() {
        val nodeCount = 1_000
        val edgeCounts = listOf(1_000, 5_000, 10_000)
        println("\n=== Edge Add Performance (ops/sec, $nodeCount nodes pre-loaded) ===")
        println(String.format("%-28s %12s %12s %12s", "Implementation", "1K", "5K", "10K"))
        println("-".repeat(66))

        for (name in implNames) {
            val results = mutableListOf<String>()
            for (edgeCount in edgeCounts) {
                val storage = createStorage(name)
                for (i in 0 until nodeCount) storage.addNode(nodeId(i))
                val ops =
                    measureOpsPerSec(edgeCount) { i ->
                        val src = i % nodeCount
                        val dst = (i + 1) % nodeCount
                        storage.addEdge(edgeId(src, dst, "e$i"), mapOf("w" to i.numVal))
                    }
                results.add(formatOps(ops))
                storage.close()
            }
            println(String.format("%-28s %12s %12s %12s", name, results[0], results[1], results[2]))
        }
    }

    // ============================================================================
    // BENCHMARK: NODE LOOKUP (containsNode)
    // ============================================================================

    @Test
    fun `benchmark node lookup across all implementations`() {
        val nodeCount = 5_000
        val lookupCount = 50_000
        println("\n=== Node Lookup Performance ($lookupCount lookups, $nodeCount nodes) ===")
        println(String.format("%-28s %12s", "Implementation", "ops/sec"))
        println("-".repeat(42))

        for (name in implNames) {
            val storage = createStorage(name)
            for (i in 0 until nodeCount) storage.addNode(nodeId(i))
            val ops =
                measureOpsPerSec(lookupCount) { i ->
                    storage.containsNode(nodeId(i % nodeCount))
                }
            println(String.format("%-28s %12s", name, formatOps(ops)))
            storage.close()
        }
    }

    // ============================================================================
    // BENCHMARK: PROPERTY READ
    // ============================================================================

    @Test
    fun `benchmark property read across all implementations`() {
        val nodeCount = 2_000
        val readCount = 20_000
        println("\n=== Property Read Performance ($readCount reads, $nodeCount nodes) ===")
        println(String.format("%-28s %12s", "Implementation", "ops/sec"))
        println("-".repeat(42))

        for (name in implNames) {
            val storage = createStorage(name)
            for (i in 0 until nodeCount) {
                storage.addNode(
                    nodeId(i),
                    mapOf("name" to "node$i".strVal, "idx" to i.numVal, "label" to "test".strVal),
                )
            }
            val ops =
                measureOpsPerSec(readCount) { i ->
                    storage.getNodeProperties(nodeId(i % nodeCount))
                }
            println(String.format("%-28s %12s", name, formatOps(ops)))
            storage.close()
        }
    }

    // ============================================================================
    // BENCHMARK: PROPERTY WRITE
    // ============================================================================

    @Test
    fun `benchmark property write across all implementations`() {
        val nodeCount = 2_000
        val writeCount = 20_000
        println("\n=== Property Write Performance ($writeCount writes, $nodeCount nodes) ===")
        println(String.format("%-28s %12s", "Implementation", "ops/sec"))
        println("-".repeat(42))

        for (name in implNames) {
            val storage = createStorage(name)
            for (i in 0 until nodeCount) storage.addNode(nodeId(i), mapOf("v" to 0.numVal))
            val ops =
                measureOpsPerSec(writeCount) { i ->
                    storage.setNodeProperties(nodeId(i % nodeCount), mapOf("v" to i.numVal))
                }
            println(String.format("%-28s %12s", name, formatOps(ops)))
            storage.close()
        }
    }

    // ============================================================================
    // BENCHMARK: EDGE QUERY (getIncomingEdges / getOutgoingEdges)
    // ============================================================================

    @Test
    fun `benchmark edge query across all implementations`() {
        val nodeCount = 1_000
        val edgesPerNode = 5
        val queryCount = 20_000
        println("\n=== Edge Query Performance ($queryCount queries, $nodeCount nodes, ${edgesPerNode} edges/node) ===")
        println(String.format("%-28s %12s %12s", "Implementation", "outgoing", "incoming"))
        println("-".repeat(54))

        for (name in implNames) {
            val storage = createStorage(name)
            populateGraph(storage, nodeCount, edgesPerNode)
            val outOps =
                measureOpsPerSec(queryCount) { i ->
                    storage.getOutgoingEdges(nodeId(i % nodeCount))
                }
            val inOps =
                measureOpsPerSec(queryCount) { i ->
                    storage.getIncomingEdges(nodeId(i % nodeCount))
                }
            println(String.format("%-28s %12s %12s", name, formatOps(outOps), formatOps(inOps)))
            storage.close()
        }
    }

    // ============================================================================
    // BENCHMARK: NODE DELETE (cascading edge removal)
    // ============================================================================

    @Test
    fun `benchmark node delete across all implementations`() {
        val nodeCount = 2_000
        val edgesPerNode = 3
        val deleteCount = 500
        println("\n=== Node Delete Performance ($deleteCount deletes, $nodeCount nodes, ${edgesPerNode} edges/node) ===")
        println(String.format("%-28s %12s", "Implementation", "ops/sec"))
        println("-".repeat(42))

        for (name in implNames) {
            val storage = createStorage(name)
            populateGraph(storage, nodeCount, edgesPerNode)
            val ops =
                measureOpsPerSec(deleteCount) { i ->
                    storage.deleteNode(nodeId(i))
                }
            println(String.format("%-28s %12s", name, formatOps(ops)))
            storage.close()
        }
    }

    // ============================================================================
    // BENCHMARK: MIXED WORKLOAD
    // ============================================================================

    @Test
    fun `benchmark mixed workload across all implementations`() {
        val iterations = 5_000
        println("\n=== Mixed Workload Performance ($iterations iterations) ===")
        println("Each iteration: addNode + addEdge + getProperties + containsNode + getOutgoingEdges")
        println(String.format("%-28s %12s", "Implementation", "ms total"))
        println("-".repeat(42))

        for (name in implNames) {
            val storage = createStorage(name)
            // Pre-add a seed node for edges
            storage.addNode(nodeId(0))
            val elapsed =
                measureMs {
                    for (i in 1..iterations) {
                        storage.addNode(nodeId(i), mapOf("v" to i.numVal))
                        storage.addEdge(edgeId(i, 0, "e$i"))
                        storage.getNodeProperties(nodeId(i))
                        storage.containsNode(nodeId(i))
                        storage.getOutgoingEdges(nodeId(i))
                    }
                }
            println(String.format("%-28s %12.1f", name, elapsed))
            storage.close()
        }
    }

    // ============================================================================
    // BENCHMARK: GRAPH POPULATION AT SCALE
    // ============================================================================

    @Test
    fun `benchmark full graph population at scale`() {
        val configs =
            listOf(
                Triple("Small (500n, 1500e)", 500, 3),
                Triple("Medium (2000n, 6000e)", 2_000, 3),
                Triple("Large (5000n, 15000e)", 5_000, 3),
            )
        println("\n=== Full Graph Population (ms) ===")
        println(String.format("%-28s %16s %16s %16s", "Implementation", "Small", "Medium", "Large"))
        println("-".repeat(78))

        for (name in implNames) {
            val results = mutableListOf<String>()
            for ((_, nodeCount, edgesPerNode) in configs) {
                val storage = createStorage(name)
                val elapsed = measureMs { populateGraph(storage, nodeCount, edgesPerNode) }
                results.add(String.format("%.1f", elapsed))
                storage.close()
            }
            println(String.format("%-28s %16s %16s %16s", name, results[0], results[1], results[2]))
        }
    }

    // ============================================================================
    // BENCHMARK: DELTA-SPECIFIC — BASE vs OVERLAY LOOKUP
    // ============================================================================

    @Test
    fun `benchmark delta storage base vs overlay lookup`() {
        val nodeCount = 2_000
        val lookupCount = 20_000
        println("\n=== Delta Storage: Base vs Overlay Lookup ($lookupCount lookups) ===")
        println(String.format("%-28s %16s %16s", "Scenario", "DeltaStorage", "DeltaConcur"))
        println("-".repeat(62))

        for (scenario in listOf("base-only", "overlay-only", "both-layers")) {
            val results = mutableListOf<String>()
            for (deltaName in listOf("DeltaStorageImpl", "DeltaConcurStorageImpl")) {
                val base = NativeStorageImpl()
                when (scenario) {
                    "base-only" -> {
                        for (i in 0 until nodeCount) base.addNode(nodeId(i), mapOf("v" to i.numVal))
                    }
                    "overlay-only" -> {}
                    "both-layers" -> {
                        for (i in 0 until nodeCount) base.addNode(nodeId(i), mapOf("v" to i.numVal))
                    }
                }
                val storage =
                    if (deltaName == "DeltaStorageImpl") {
                        DeltaStorageImpl(base)
                    } else {
                        DeltaConcurStorageImpl(base)
                    }
                storages.add(storage)

                when (scenario) {
                    "overlay-only" -> {
                        for (i in 0 until nodeCount) storage.addNode(nodeId(i), mapOf("v" to i.numVal))
                    }
                    "both-layers" -> {
                        for (i in 0 until nodeCount) {
                            storage.setNodeProperties(nodeId(i), mapOf("extra" to "overlay".strVal))
                        }
                    }
                }

                val ops =
                    measureOpsPerSec(lookupCount) { i ->
                        storage.getNodeProperties(nodeId(i % nodeCount))
                    }
                results.add(formatOps(ops))
                storage.close()
                base.close()
            }
            println(String.format("%-28s %16s %16s", scenario, results[0], results[1]))
        }
    }

    // ============================================================================
    // BENCHMARK: PHASED STORAGE — MULTI-LAYER QUERY
    // ============================================================================

    @Test
    fun `benchmark phased storage multi-layer query`() {
        val nodesPerLayer = 500
        val layerCounts = listOf(1, 3, 5, 10)
        val queryCount = 10_000
        println("\n=== Phased Storage: Multi-Layer Query ($queryCount queries, $nodesPerLayer nodes/layer) ===")
        println(String.format("%-20s %12s %12s", "Layers", "containsNode", "getProps"))
        println("-".repeat(46))

        for (layers in layerCounts) {
            val storage = PhasedStorageImpl()
            storages.add(storage)
            var totalNodes = 0
            for (layer in 0 until layers) {
                for (i in 0 until nodesPerLayer) {
                    val nid = nodeId(totalNodes + i)
                    storage.addNode(nid, mapOf("layer" to layer.numVal, "idx" to i.numVal))
                }
                totalNodes += nodesPerLayer
                if (layer < layers - 1) storage.freezeAndPushLayer()
            }
            val containsOps =
                measureOpsPerSec(queryCount) { i ->
                    storage.containsNode(nodeId(i % totalNodes))
                }
            val propsOps =
                measureOpsPerSec(queryCount) { i ->
                    storage.getNodeProperties(nodeId(i % totalNodes))
                }
            println(String.format("%-20s %12s %12s", "$layers layers", formatOps(containsOps), formatOps(propsOps)))
            storage.close()
        }
    }
}
