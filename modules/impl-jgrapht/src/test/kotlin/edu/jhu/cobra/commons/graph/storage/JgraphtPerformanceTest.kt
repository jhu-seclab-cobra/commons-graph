package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.numVal
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Performance benchmarks for JGraphT-based IStorage implementations at scale.
 *
 * Scale tiers: 10K/30K, 100K/300K, 1M/3M (nodes/edges).
 *
 * Run with: ./gradlew :modules:impl-jgrapht:test --tests "*.JgraphtPerformanceTest"
 */
class JgraphtPerformanceTest {
    private val storages = mutableListOf<IStorage>()

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
    }

    private val implNames = listOf("JgraphtStorageImpl", "JgraphtConcurStorageImpl")

    private fun createStorage(name: String): IStorage {
        val s =
            when (name) {
                "JgraphtStorageImpl" -> JgraphtStorageImpl()
                "JgraphtConcurStorageImpl" -> JgraphtConcurStorageImpl()
                else -> throw IllegalArgumentException("Unknown: $name")
            }
        storages.add(s)
        return s
    }

    private fun populateGraph(
        storage: IStorage,
        nodeCount: Int,
        edgesPerNode: Int,
    ): List<Int> {
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until nodeCount) nodeIds.add(storage.addNode(mapOf("idx" to i.numVal)))
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                storage.addEdge(nodeIds[i], nodeIds[dst], "e$j", mapOf("w" to j.numVal))
            }
        }
        return nodeIds
    }

    private companion object {
        const val WARMUP = 2
        const val MEASURED = 3
    }

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int = WARMUP,
        measured: Int = MEASURED,
        setup: () -> Unit = {},
        crossinline op: (Int) -> Unit,
    ): Double {
        for (_w in 0 until warmup) {
            setup()
            for (i in 0 until ops) op(i)
        }
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            setup()
            System.gc()
            val start = System.nanoTime()
            for (i in 0 until ops) op(i)
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
        for (_w in 0 until warmup) block()
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

    @Test
    fun `benchmark graph population at scale`() {
        data class Scale(
            val label: String,
            val nodes: Int,
            val epn: Int,
        )
        val scales =
            listOf(
                Scale("10K/30K", 10_000, 3),
                Scale("100K/300K", 100_000, 3),
                Scale("1M/3M", 1_000_000, 3),
            )
        println("\n=== JGraphT Graph Population (median ms) ===")
        println(String.format("%-28s %14s %14s %14s", "Implementation", "10K/30K", "100K/300K", "1M/3M"))
        println("-".repeat(72))
        for (name in implNames) {
            val results =
                scales.map { (_, n, epn) ->
                    benchmarkMs(warmup = 1, measured = 3) {
                        val s = createStorage(name)
                        populateGraph(s, n, epn)
                        s.close()
                    }
                }
            println(String.format("%-28s %14s %14s %14s", name, fmtMs(results[0]), fmtMs(results[1]), fmtMs(results[2])))
        }
    }

    @Test
    fun `benchmark node lookup on large graph`() {
        val nodeCount = 100_000
        val lookups = 500_000
        println("\n=== JGraphT Node Lookup (median ops/sec, $lookups lookups on $nodeCount nodes) ===")
        println(String.format("%-28s %14s", "Implementation", "ops/sec"))
        println("-".repeat(44))
        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds = mutableListOf<Int>()
            for (i in 0 until nodeCount) nodeIds.add(storage.addNode())
            val ops = benchmarkOpsPerSec(lookups) { i -> storage.containsNode(nodeIds[i % nodeCount]) }
            println(String.format("%-28s %14s", name, fmt(ops)))
            storage.close()
        }
    }

    @Test
    fun `benchmark property read and write on large graph`() {
        val nodeCount = 50_000
        val count = 200_000
        println("\n=== JGraphT Property Read/Write (median ops/sec, $count ops on $nodeCount nodes) ===")
        println(String.format("%-28s %14s %14s", "Implementation", "read", "write"))
        println("-".repeat(58))
        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds = mutableListOf<Int>()
            for (i in 0 until nodeCount) nodeIds.add(storage.addNode(mapOf("v" to i.numVal)))
            val readOps = benchmarkOpsPerSec(count) { i -> storage.getNodeProperties(nodeIds[i % nodeCount]) }
            val writeOps =
                benchmarkOpsPerSec(count) { i ->
                    storage.setNodeProperties(nodeIds[i % nodeCount], mapOf("v" to i.numVal))
                }
            println(String.format("%-28s %14s %14s", name, fmt(readOps), fmt(writeOps)))
            storage.close()
        }
    }

    @Test
    fun `benchmark edge query on large graph`() {
        val nodeCount = 10_000
        val edgesPerNode = 5
        val queries = 100_000
        println("\n=== JGraphT Edge Query (median ops/sec, $queries queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-28s %14s %14s", "Implementation", "outgoing", "incoming"))
        println("-".repeat(58))
        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds = populateGraph(storage, nodeCount, edgesPerNode)
            val outOps = benchmarkOpsPerSec(queries) { i -> storage.getOutgoingEdges(nodeIds[i % nodeCount]) }
            val inOps = benchmarkOpsPerSec(queries) { i -> storage.getIncomingEdges(nodeIds[i % nodeCount]) }
            println(String.format("%-28s %14s %14s", name, fmt(outOps), fmt(inOps)))
            storage.close()
        }
    }
}
