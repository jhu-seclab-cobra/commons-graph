package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.numVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Performance benchmarks for Neo4j-based IStorage implementation.
 *
 * Neo4j startup is heavyweight, so scale tiers are smaller than in-memory
 * implementations: 1K/3K, 5K/15K, 10K/30K (nodes/edges).
 *
 * Run with: ./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest"
 */
class Neo4jPerformanceTest {
    private val tempDirs = mutableListOf<Path>()
    private val storages = mutableListOf<IStorage>()

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        tempDirs.clear()
    }

    private fun createStorage(): Neo4jStorageImpl {
        val dir = Files.createTempDirectory("neo4j-perf")
        tempDirs.add(dir)
        val s = Neo4jStorageImpl(dir)
        storages.add(s)
        return s
    }

    private fun nodeId(i: Int): NodeID = NodeID("n$i")
    private fun edgeId(src: Int, dst: Int, type: String): EdgeID = EdgeID(nodeId(src), nodeId(dst), type)

    private fun populateGraph(storage: IStorage, nodeCount: Int, edgesPerNode: Int) {
        for (i in 0 until nodeCount) storage.addNode(nodeId(i), mapOf("idx" to i.numVal))
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                storage.addEdge(edgeId(i, dst, "e$j"), mapOf("w" to j.numVal))
            }
        }
    }

    private companion object {
        const val WARMUP = 2
        const val MEASURED = 3
    }

    private inline fun benchmarkOpsPerSec(
        ops: Int, warmup: Int = WARMUP, measured: Int = MEASURED,
        crossinline op: (Int) -> Unit,
    ): Double {
        for (w in 0 until warmup) { for (i in 0 until ops) op(i) }
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            System.gc(); val start = System.nanoTime()
            for (i in 0 until ops) op(i)
            val sec = (System.nanoTime() - start) / 1_000_000_000.0
            samples[r] = if (sec > 0) ops / sec else Double.MAX_VALUE
        }
        samples.sort(); return samples[measured / 2]
    }

    private inline fun benchmarkMs(
        warmup: Int = WARMUP, measured: Int = MEASURED, crossinline block: () -> Unit,
    ): Double {
        for (w in 0 until warmup) block()
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            System.gc(); val start = System.nanoTime(); block()
            samples[r] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort(); return samples[measured / 2]
    }

    private fun fmt(ops: Double): String = when {
        ops >= 1_000_000 -> String.format("%.2fM", ops / 1_000_000)
        ops >= 1_000 -> String.format("%.1fK", ops / 1_000)
        else -> String.format("%.0f", ops)
    }

    private fun fmtMs(ms: Double): String = String.format("%.1f", ms)

    // ========================================================================

    @Test
    fun `benchmark graph population at scale`() {
        data class Scale(val label: String, val nodes: Int, val epn: Int)
        val scales = listOf(
            Scale("1K/3K", 1_000, 3),
            Scale("5K/15K", 5_000, 3),
            Scale("10K/30K", 10_000, 3),
        )
        println("\n=== Neo4j Graph Population (median ms) ===")
        println(String.format("%-20s %14s %14s %14s", "Scale", "1K/3K", "5K/15K", "10K/30K"))
        println("-".repeat(64))

        val results = scales.map { (_, n, epn) ->
            benchmarkMs(warmup = 1, measured = 3) {
                val s = createStorage()
                populateGraph(s, n, epn)
                s.close()
            }
        }
        println(String.format("%-20s %14s %14s %14s", "Neo4jStorageImpl", fmtMs(results[0]), fmtMs(results[1]), fmtMs(results[2])))
    }

    @Test
    fun `benchmark node operations on large graph`() {
        val nodeCount = 5_000
        val lookups = 20_000
        println("\n=== Neo4j Node Operations (median ops/sec, $nodeCount nodes) ===")

        val storage = createStorage()
        for (i in 0 until nodeCount) storage.addNode(nodeId(i), mapOf("idx" to i.numVal))

        val lookupOps = benchmarkOpsPerSec(lookups) { i -> storage.containsNode(nodeId(i % nodeCount)) }
        println(String.format("  containsNode (%d lookups): %s ops/sec", lookups, fmt(lookupOps)))
    }

    @Test
    fun `benchmark property read and write`() {
        val nodeCount = 2_000
        val count = 10_000
        println("\n=== Neo4j Property Read/Write (median ops/sec, $count ops on $nodeCount nodes) ===")

        val storage = createStorage()
        for (i in 0 until nodeCount) storage.addNode(nodeId(i), mapOf("v" to i.numVal))

        val readOps = benchmarkOpsPerSec(count) { i -> storage.getNodeProperties(nodeId(i % nodeCount)) }
        println(String.format("  getNodeProperties: %s ops/sec", fmt(readOps)))

        val writeOps = benchmarkOpsPerSec(count) { i ->
            storage.setNodeProperties(nodeId(i % nodeCount), mapOf("v" to i.numVal))
        }
        println(String.format("  setNodeProperties: %s ops/sec", fmt(writeOps)))
    }

    @Test
    fun `benchmark edge query`() {
        val nodeCount = 2_000
        val edgesPerNode = 3
        val queries = 10_000
        println("\n=== Neo4j Edge Query (median ops/sec, $queries queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")

        val storage = createStorage()
        populateGraph(storage, nodeCount, edgesPerNode)

        val outOps = benchmarkOpsPerSec(queries) { i -> storage.getOutgoingEdges(nodeId(i % nodeCount)) }
        println(String.format("  getOutgoingEdges: %s ops/sec", fmt(outOps)))

        val inOps = benchmarkOpsPerSec(queries) { i -> storage.getIncomingEdges(nodeId(i % nodeCount)) }
        println(String.format("  getIncomingEdges: %s ops/sec", fmt(inOps)))
    }
}
