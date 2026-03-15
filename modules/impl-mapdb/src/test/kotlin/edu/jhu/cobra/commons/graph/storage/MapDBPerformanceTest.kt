package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.numVal
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Performance benchmarks for MapDB-based IStorage implementations.
 *
 * Tests both MapDBStorageImpl and MapDBConcurStorageImpl across different
 * MapDB configuration parameters:
 *   - memoryDB():              pure heap memory
 *   - memoryDirectDB():        off-heap direct byte buffers
 *   - tempFileDB():            temp file with default settings
 *   - tempFileDB().fileMmapEnableIfSupported(): temp file with mmap
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

    private data class MapDBConfig(
        val label: String,
        val factory: () -> IStorage,
    )

    private val configs =
        listOf(
            MapDBConfig("MapDB[memoryDB]") { MapDBStorageImpl { memoryDB() } },
            MapDBConfig("MapDB[memoryDirectDB]") { MapDBStorageImpl { memoryDirectDB() } },
            MapDBConfig("MapDB[tempFileDB]") { MapDBStorageImpl { tempFileDB() } },
            MapDBConfig("MapDB[tempFile+mmap]") { MapDBStorageImpl { tempFileDB().fileMmapEnableIfSupported() } },
            MapDBConfig("MapDBConcur[memoryDB]") { MapDBConcurStorageImpl { memoryDB() } },
            MapDBConfig("MapDBConcur[memDirect]") { MapDBConcurStorageImpl { memoryDirectDB() } },
            MapDBConfig("MapDBConcur[tempFile]") { MapDBConcurStorageImpl { tempFileDB() } },
            MapDBConfig("MapDBConcur[tmpFile+mm]") { MapDBConcurStorageImpl { tempFileDB().fileMmapEnableIfSupported() } },
        )

    private fun tracked(s: IStorage): IStorage {
        storages.add(s)
        return s
    }

    private fun populateGraph(
        storage: IStorage,
        nodeCount: Int,
        edgesPerNode: Int,
    ): List<String> {
        val nodeIds = mutableListOf<String>()
        for (i in 0 until nodeCount) nodeIds.add(storage.addNode(mapOf("idx" to i.numVal)))
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                storage.addEdge(nodeIds[i], nodeIds[dst], "e$j", mapOf("w" to j.numVal))
            }
        }
        return nodeIds
    }

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int = 1,
        measured: Int = 3,
        setup: () -> Unit = {},
        crossinline op: (Int) -> Unit,
    ): Double {
        for (w in 0 until warmup) {
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
        warmup: Int = 1,
        measured: Int = 3,
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
    // BENCHMARK: CONFIG COMPARISON -- GRAPH POPULATION
    // ========================================================================

    @Test
    fun `benchmark graph population across configs`() {
        val nodeCount = 5_000
        val edgesPerNode = 3
        println("\n=== MapDB Graph Population by Config (median ms, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-28s %14s", "Config", "ms"))
        println("-".repeat(44))

        for (cfg in configs) {
            val ms =
                benchmarkMs(warmup = 1, measured = 3) {
                    val s = tracked(cfg.factory())
                    populateGraph(s, nodeCount, edgesPerNode)
                    s.close()
                }
            println(String.format("%-28s %14s", cfg.label, fmtMs(ms)))
        }
    }

    // ========================================================================
    // BENCHMARK: CONFIG COMPARISON -- NODE LOOKUP
    // ========================================================================

    @Test
    fun `benchmark node lookup across configs`() {
        val nodeCount = 5_000
        val lookups = 50_000
        println("\n=== MapDB Node Lookup by Config (median ops/sec, $lookups lookups on $nodeCount nodes) ===")
        println(String.format("%-28s %14s", "Config", "ops/sec"))
        println("-".repeat(44))

        for (cfg in configs) {
            val s = tracked(cfg.factory())
            val nodeIds = mutableListOf<String>()
            for (i in 0 until nodeCount) nodeIds.add(s.addNode())
            val ops = benchmarkOpsPerSec(lookups) { i -> s.containsNode(nodeIds[i % nodeCount]) }
            println(String.format("%-28s %14s", cfg.label, fmt(ops)))
            s.close()
        }
    }

    // ========================================================================
    // BENCHMARK: CONFIG COMPARISON -- PROPERTY READ/WRITE
    // ========================================================================

    @Test
    fun `benchmark property read and write across configs`() {
        val nodeCount = 5_000
        val count = 20_000
        println("\n=== MapDB Property Read/Write by Config (median ops/sec, $count ops on $nodeCount nodes) ===")
        println(String.format("%-28s %14s %14s", "Config", "read", "write"))
        println("-".repeat(58))

        for (cfg in configs) {
            val s = tracked(cfg.factory())
            val nodeIds = mutableListOf<String>()
            for (i in 0 until nodeCount) nodeIds.add(s.addNode(mapOf("v" to i.numVal)))
            val readOps = benchmarkOpsPerSec(count) { i -> s.getNodeProperties(nodeIds[i % nodeCount]) }
            val writeOps =
                benchmarkOpsPerSec(count) { i ->
                    s.setNodeProperties(nodeIds[i % nodeCount], mapOf("v" to i.numVal))
                }
            println(String.format("%-28s %14s %14s", cfg.label, fmt(readOps), fmt(writeOps)))
            s.close()
        }
    }

    // ========================================================================
    // BENCHMARK: CONFIG COMPARISON -- EDGE QUERY
    // ========================================================================

    @Test
    fun `benchmark edge query across configs`() {
        val nodeCount = 2_000
        val edgesPerNode = 5
        val queries = 10_000
        println("\n=== MapDB Edge Query by Config (median ops/sec, $queries queries, ${nodeCount}n/${nodeCount * edgesPerNode}e) ===")
        println(String.format("%-28s %14s %14s", "Config", "outgoing", "incoming"))
        println("-".repeat(58))

        for (cfg in configs) {
            val s = tracked(cfg.factory())
            val nodeIds = populateGraph(s, nodeCount, edgesPerNode)
            val outOps = benchmarkOpsPerSec(queries) { i -> s.getOutgoingEdges(nodeIds[i % nodeCount]) }
            val inOps = benchmarkOpsPerSec(queries) { i -> s.getIncomingEdges(nodeIds[i % nodeCount]) }
            println(String.format("%-28s %14s %14s", cfg.label, fmt(outOps), fmt(inOps)))
            s.close()
        }
    }
}
