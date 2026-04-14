@file:Suppress("ExplicitGarbageCollectionCall", "ImplicitDefaultLocale")

/**
 * Performance benchmarks for Neo4j-based IStorage implementations.
 *
 * Tests both [Neo4jStorageImpl] and [Neo4jConcurStorageImpl].
 * Neo4j startup is heavyweight, so scale tiers are smaller than in-memory
 * implementations: 1K/3K, 5K/15K, 10K/30K (nodes/edges).
 *
 * Run with: `./gradlew :modules:impl-neo4j:test --tests "*.Neo4jPerformanceTest"`
 *
 * - `benchmark graph population at scale`
 * - `benchmark node operations on large graph`
 * - `benchmark property read and write`
 * - `benchmark memory and disk footprint`
 * - `benchmark edge query`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.test.AfterTest
import kotlin.test.Test

internal class Neo4jPerformanceTest {
    private val tempDirs = mutableListOf<Path>()
    private val storages = mutableListOf<IStorage>()

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        tempDirs.clear()
    }

    private val implNames = listOf("Neo4jStorageImpl", "Neo4jConcurStorageImpl")

    private fun createStorage(name: String): IStorage {
        val dir = Files.createTempDirectory("neo4j-perf")
        tempDirs.add(dir)
        val s =
            when (name) {
                "Neo4jStorageImpl" -> Neo4jStorageImpl(dir)
                "Neo4jConcurStorageImpl" -> Neo4jConcurStorageImpl(dir)
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
        crossinline op: (Int) -> Unit,
    ): Double {
        for (_w in 0 until warmup) {
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
                Scale("1K/3K", 1_000, 3),
                Scale("5K/15K", 5_000, 3),
                Scale("10K/30K", 10_000, 3),
            )
        println("\n=== Neo4j Graph Population (median ms) ===")
        println(String.format("%-28s %14s %14s %14s", "Implementation", "1K/3K", "5K/15K", "10K/30K"))
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
    fun `benchmark node operations on large graph`() {
        val nodeCount = 5_000
        val lookups = 20_000
        println("\n=== Neo4j Node Lookup (median ops/sec, $lookups lookups on $nodeCount nodes) ===")
        println(String.format("%-28s %14s", "Implementation", "ops/sec"))
        println("-".repeat(44))
        for (name in implNames) {
            val storage = createStorage(name)
            val nodeIds = mutableListOf<Int>()
            for (i in 0 until nodeCount) nodeIds.add(storage.addNode(mapOf("idx" to i.numVal)))
            val ops = benchmarkOpsPerSec(lookups) { i -> storage.containsNode(nodeIds[i % nodeCount]) }
            println(String.format("%-28s %14s", name, fmt(ops)))
            storage.close()
        }
    }

    @Test
    fun `benchmark property read and write`() {
        val nodeCount = 2_000
        val count = 10_000
        println("\n=== Neo4j Property Read/Write (median ops/sec, $count ops on $nodeCount nodes) ===")
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
    fun `benchmark memory and disk footprint`() {
        val nodeCount = 10_000
        val edgesPerNode = 3
        val propsPerEntity = 5
        println(
            "\n=== Neo4j Memory & Disk Footprint ($nodeCount nodes, ${nodeCount * edgesPerNode} edges, $propsPerEntity props/entity) ===",
        )
        println(String.format("%-28s %14s %14s", "Implementation", "heap delta MB", "disk MB"))
        println("-".repeat(58))

        for (name in implNames) {
            System.gc()
            Thread.sleep(200)
            System.gc()
            val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val dir = Files.createTempDirectory("neo4j-mem")
            tempDirs.add(dir)
            val storage: IStorage =
                when (name) {
                    "Neo4jStorageImpl" -> Neo4jStorageImpl(dir)
                    "Neo4jConcurStorageImpl" -> Neo4jConcurStorageImpl(dir)
                    else -> throw IllegalArgumentException("Unknown: $name")
                }
            storages.add(storage)

            for (i in 0 until nodeCount) {
                val props = (1..propsPerEntity).associate { "p$it" to "val_${i}_$it".strVal }
                storage.addNode(props)
            }
            val nodeIds = storage.nodeIDs.toList()
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
            val heapDeltaMB = (after - before) / (1024.0 * 1024.0)

            storage.close()
            val diskBytes = Files.walk(dir).filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
            val diskMB = diskBytes / (1024.0 * 1024.0)
            println(String.format("%-28s %14.1f %14.1f", name, heapDeltaMB, diskMB))
        }
    }

    @Test
    fun `benchmark edge query`() {
        val nodeCount = 2_000
        val edgesPerNode = 3
        val queries = 10_000
        println("\n=== Neo4j Edge Query (median ops/sec, $queries queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
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
