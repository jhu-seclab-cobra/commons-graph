package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
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
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        tempDirs.clear()
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

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int = 1,
        measured: Int = 3,
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
        warmup: Int = 1,
        measured: Int = 3,
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
            val nodeIds = mutableListOf<Int>()
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
            val nodeIds = mutableListOf<Int>()
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

    private val tempDirs = mutableListOf<Path>()

    private fun directMemoryMB(): Double {
        val pools = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java)
        return pools.filter { it.name == "direct" }.sumOf { it.memoryUsed } / (1024.0 * 1024.0)
    }

    private fun diskSizeMB(dir: Path): Double =
        Files.walk(dir).filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum() / (1024.0 * 1024.0)

    private fun populateWithProps(
        storage: IStorage,
        nodeCount: Int,
        edgesPerNode: Int,
        propsPerEntity: Int,
    ) {
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until nodeCount) {
            val props = (1..propsPerEntity).associate { "p$it" to "val_${i}_$it".strVal }
            nodeIds.add(storage.addNode(props))
        }
        for (i in 0 until nodeCount) {
            for (j in 1..edgesPerNode) {
                val dst = (i + j) % nodeCount
                val props = (1..propsPerEntity).associate { "p$it" to "val_${i}_${j}_$it".strVal }
                storage.addEdge(nodeIds[i], nodeIds[dst], "e$j", props)
            }
        }
    }

    @Test
    fun `benchmark memory footprint across configs`() {
        val nodeCount = 10_000
        val edgesPerNode = 3
        val propsPerEntity = 5
        println(
            "\n=== MapDB Memory Footprint ($nodeCount nodes, ${nodeCount * edgesPerNode} edges, $propsPerEntity props/entity) ===",
        )
        println(String.format("%-28s %14s %14s %14s", "Config", "heap MB", "direct MB", "disk MB"))
        println("-".repeat(72))

        data class MemConfig(
            val label: String,
            val factory: (Path) -> IStorage,
            val hasDisk: Boolean,
        )

        val memConfigs =
            listOf(
                MemConfig("MapDB[memoryDB]", { MapDBStorageImpl { memoryDB() } }, false),
                MemConfig("MapDB[memoryDirectDB]", { MapDBStorageImpl { memoryDirectDB() } }, false),
                MemConfig("MapDB[fileDB]", { dir -> MapDBStorageImpl { fileDB(dir.resolve("db").toFile()) } }, true),
                MemConfig(
                    "MapDB[fileDB+mmap]",
                    { dir -> MapDBStorageImpl { fileDB(dir.resolve("db").toFile()).fileMmapEnableIfSupported() } },
                    true,
                ),
                MemConfig("MapDBConcur[memoryDB]", { MapDBConcurStorageImpl { memoryDB() } }, false),
                MemConfig("MapDBConcur[memDirect]", { MapDBConcurStorageImpl { memoryDirectDB() } }, false),
                MemConfig(
                    "MapDBConcur[fileDB]",
                    { dir -> MapDBConcurStorageImpl { fileDB(dir.resolve("db").toFile()) } },
                    true,
                ),
                MemConfig(
                    "MapDBConcur[file+mmap]",
                    { dir ->
                        MapDBConcurStorageImpl { fileDB(dir.resolve("db").toFile()).fileMmapEnableIfSupported() }
                    },
                    true,
                ),
            )

        for (cfg in memConfigs) {
            val dir = Files.createTempDirectory("mapdb-mem")
            tempDirs.add(dir)

            System.gc()
            Thread.sleep(200)
            System.gc()
            val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val directBefore = directMemoryMB()

            val s = tracked(cfg.factory(dir))
            populateWithProps(s, nodeCount, edgesPerNode, propsPerEntity)

            System.gc()
            Thread.sleep(200)
            System.gc()
            val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val directAfter = directMemoryMB()
            val heapDelta = (heapAfter - heapBefore) / (1024.0 * 1024.0)
            val directDelta = directAfter - directBefore
            val diskMB = if (cfg.hasDisk) diskSizeMB(dir) else 0.0

            println(
                String.format(
                    "%-28s %14.1f %14.1f %14s",
                    cfg.label,
                    heapDelta,
                    directDelta,
                    if (cfg.hasDisk) String.format("%.1f", diskMB) else "—",
                ),
            )
            s.close()
        }
    }

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
