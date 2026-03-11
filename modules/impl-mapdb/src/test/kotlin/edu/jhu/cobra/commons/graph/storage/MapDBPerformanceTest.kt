package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Performance benchmarks for MapDB-based IStorage implementations.
 *
 * Tests both MapDBStorageImpl and MapDBConcurStorageImpl across different
 * MapDB configuration parameters:
 *   - memoryDB():              pure heap memory
 *   - memoryDirectDB():        off-heap direct byte buffers
 *   - tempFileDB() (default):  temp file with mmap
 *   - tempFileDB().fileMmapEnableIfSupported(): temp file with mmap (explicit)
 *
 * Scale tiers: 10K/30K, 100K/300K, 1M/3M (nodes/edges).
 *
 * Run with: ./gradlew :modules:impl-mapdb:test --tests "*.MapDBPerformanceTest"
 */
class MapDBPerformanceTest {
    private val storages = mutableListOf<IStorage>()

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
    }

    // -- Configs: each is a (label, storageFactory) pair ----------------------

    private data class MapDBConfig(
        val label: String,
        val factory: () -> IStorage,
    )

    private val configs = listOf(
        MapDBConfig("MapDB[memoryDB]") { MapDBStorageImpl { memoryDB() } },
        MapDBConfig("MapDB[memoryDirectDB]") { MapDBStorageImpl { memoryDirectDB() } },
        MapDBConfig("MapDB[tempFileDB]") { MapDBStorageImpl { tempFileDB() } },
        MapDBConfig("MapDB[tempFile+mmap]") { MapDBStorageImpl { tempFileDB().fileMmapEnableIfSupported() } },
        MapDBConfig("MapDBConcur[memoryDB]") { MapDBConcurStorageImpl { memoryDB() } },
        MapDBConfig("MapDBConcur[memDirect]") { MapDBConcurStorageImpl { memoryDirectDB() } },
        MapDBConfig("MapDBConcur[tempFile]") { MapDBConcurStorageImpl { tempFileDB() } },
        MapDBConfig("MapDBConcur[tmpFile+mm]") { MapDBConcurStorageImpl { tempFileDB().fileMmapEnableIfSupported() } },
    )

    private fun tracked(s: IStorage): IStorage { storages.add(s); return s }

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
        setup: () -> Unit = {}, crossinline op: (Int) -> Unit,
    ): Double {
        for (w in 0 until warmup) { setup(); for (i in 0 until ops) op(i) }
        val samples = DoubleArray(measured)
        for (r in 0 until measured) {
            setup(); System.gc()
            val start = System.nanoTime()
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
    // BENCHMARK: CONFIG COMPARISON — GRAPH POPULATION
    // ========================================================================

    @Test
    fun `benchmark graph population across configs`() {
        data class Scale(val label: String, val nodes: Int, val epn: Int)
        val scales = listOf(
            Scale("10K/30K", 10_000, 3),
            Scale("100K/300K", 100_000, 3),
        )
        println("\n=== MapDB Graph Population by Config (median ms) ===")
        println(String.format("%-28s %14s %14s", "Config", "10K/30K", "100K/300K"))
        println("-".repeat(58))

        for (cfg in configs) {
            val results = scales.map { (_, n, epn) ->
                benchmarkMs(warmup = 1, measured = 3) {
                    val s = tracked(cfg.factory())
                    populateGraph(s, n, epn)
                    s.close()
                }
            }
            println(String.format("%-28s %14s %14s", cfg.label, fmtMs(results[0]), fmtMs(results[1])))
        }
    }

    // ========================================================================
    // BENCHMARK: CONFIG COMPARISON — NODE LOOKUP
    // ========================================================================

    @Test
    fun `benchmark node lookup across configs`() {
        val nodeCount = 50_000
        val lookups = 200_000
        println("\n=== MapDB Node Lookup by Config (median ops/sec, $lookups lookups on $nodeCount nodes) ===")
        println(String.format("%-28s %14s", "Config", "ops/sec"))
        println("-".repeat(44))

        for (cfg in configs) {
            val s = tracked(cfg.factory())
            for (i in 0 until nodeCount) s.addNode(nodeId(i))
            val ops = benchmarkOpsPerSec(lookups) { i -> s.containsNode(nodeId(i % nodeCount)) }
            println(String.format("%-28s %14s", cfg.label, fmt(ops)))
            s.close()
        }
    }

    // ========================================================================
    // BENCHMARK: CONFIG COMPARISON — PROPERTY READ/WRITE
    // ========================================================================

    @Test
    fun `benchmark property read and write across configs`() {
        val nodeCount = 20_000
        val count = 100_000
        println("\n=== MapDB Property Read/Write by Config (median ops/sec, $count ops on $nodeCount nodes) ===")
        println(String.format("%-28s %14s %14s", "Config", "read", "write"))
        println("-".repeat(58))

        for (cfg in configs) {
            val s = tracked(cfg.factory())
            for (i in 0 until nodeCount) s.addNode(nodeId(i), mapOf("v" to i.numVal))
            val readOps = benchmarkOpsPerSec(count) { i -> s.getNodeProperties(nodeId(i % nodeCount)) }
            val writeOps = benchmarkOpsPerSec(count) { i ->
                s.setNodeProperties(nodeId(i % nodeCount), mapOf("v" to i.numVal))
            }
            println(String.format("%-28s %14s %14s", cfg.label, fmt(readOps), fmt(writeOps)))
            s.close()
        }
    }

    // ========================================================================
    // BENCHMARK: CONFIG COMPARISON — EDGE QUERY
    // ========================================================================

    @Test
    fun `benchmark edge query across configs`() {
        val nodeCount = 5_000
        val edgesPerNode = 5
        val queries = 50_000
        println("\n=== MapDB Edge Query by Config (median ops/sec, $queries queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-28s %14s %14s", "Config", "outgoing", "incoming"))
        println("-".repeat(58))

        for (cfg in configs) {
            val s = tracked(cfg.factory())
            populateGraph(s, nodeCount, edgesPerNode)
            val outOps = benchmarkOpsPerSec(queries) { i -> s.getOutgoingEdges(nodeId(i % nodeCount)) }
            val inOps = benchmarkOpsPerSec(queries) { i -> s.getIncomingEdges(nodeId(i % nodeCount)) }
            println(String.format("%-28s %14s %14s", cfg.label, fmt(outOps), fmt(inOps)))
            s.close()
        }
    }

    // ========================================================================
    // BENCHMARK: LARGE SCALE (1M nodes, MapDB memoryDB only)
    // ========================================================================

    @Test
    fun `benchmark large scale population memoryDB`() {
        val nodeCount = 100_000
        val edgesPerNode = 3
        println("\n=== MapDB Large Scale Population (${nodeCount}n/${nodeCount * edgesPerNode}e, memoryDB) ===")
        println(String.format("%-28s %14s", "Implementation", "median ms"))
        println("-".repeat(44))

        for (implLabel in listOf("MapDBStorageImpl", "MapDBConcurStorageImpl")) {
            val ms = benchmarkMs(warmup = 1, measured = 3) {
                val s = tracked(
                    if (implLabel == "MapDBStorageImpl") MapDBStorageImpl { memoryDB() }
                    else MapDBConcurStorageImpl { memoryDB() }
                )
                populateGraph(s, nodeCount, edgesPerNode)
                s.close()
            }
            println(String.format("%-28s %14s", implLabel, fmtMs(ms)))
        }
    }
}
