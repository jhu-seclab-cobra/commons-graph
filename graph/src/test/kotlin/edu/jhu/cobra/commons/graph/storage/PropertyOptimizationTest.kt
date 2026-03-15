package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.*

/**
 * Functional and performance tests for property access optimizations:
 * - [IStorage.getNodeProperty] / [IStorage.getEdgeProperty] single-property access
 * - Property key string interning
 *
 * Run with: ./gradlew :graph:test --tests "*.PropertyOptimizationTest"
 */
class PropertyOptimizationTest {
    private lateinit var storage: IStorage
    private val storages = mutableListOf<IStorage>()

    @AfterTest
    fun cleanup() {
        storages.forEach { runCatching { it.close() } }
        storages.clear()
        if (::storage.isInitialized) runCatching { storage.close() }
    }

    private fun createStorage(name: String): IStorage {
        val s =
            when (name) {
                "NativeStorageImpl" -> NativeStorageImpl()
                "NativeConcurStorageImpl" -> NativeConcurStorageImpl()
                "LayeredStorageImpl" -> LayeredStorageImpl()
                else -> throw IllegalArgumentException("Unknown: $name")
            }
        storages.add(s)
        return s
    }

    private val implNames =
        listOf(
            "NativeStorageImpl",
            "NativeConcurStorageImpl",
            "LayeredStorageImpl",
        )

    // ========================================================================
    // FUNCTIONAL: getNodeProperty
    // ========================================================================

    @Test
    fun `test getNodeProperty_existingProperty_returnsValue`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode(mapOf("weight" to 1.5.numVal, "name" to "a".strVal))

            assertEquals(1.5.numVal, s.getNodeProperty(node1, "weight"), "Failed for $name")
            assertEquals("a".strVal, s.getNodeProperty(node1, "name"), "Failed for $name")
        }
    }

    @Test
    fun `test getNodeProperty_absentProperty_returnsNull`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode(mapOf("weight" to 1.5.numVal))

            assertNull(s.getNodeProperty(node1, "nonexistent"), "Failed for $name")
        }
    }

    @Test
    fun `test getNodeProperty_nonexistentNode_throwsEntityNotExist`() {
        for (name in implNames) {
            val s = createStorage(name)

            assertFailsWith<EntityNotExistException>("Failed for $name") {
                s.getNodeProperty(-1, "weight")
            }
        }
    }

    @Test
    fun `test getNodeProperty_afterPropertyUpdate_returnsNewValue`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode(mapOf("weight" to 1.0.numVal))
            s.setNodeProperties(node1, mapOf("weight" to 2.0.numVal))

            assertEquals(2.0.numVal, s.getNodeProperty(node1, "weight"), "Failed for $name")
        }
    }

    @Test
    fun `test getNodeProperty_afterPropertyDelete_returnsNull`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode(mapOf("weight" to 1.0.numVal))
            s.setNodeProperties(node1, mapOf("weight" to null))

            assertNull(s.getNodeProperty(node1, "weight"), "Failed for $name")
        }
    }

    @Test
    fun `test getNodeProperty_emptyProperties_returnsNull`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode()

            assertNull(s.getNodeProperty(node1, "anything"), "Failed for $name")
        }
    }

    // ========================================================================
    // FUNCTIONAL: getEdgeProperty
    // ========================================================================

    @Test
    fun `test getEdgeProperty_existingProperty_returnsValue`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode()
            val node2 = s.addNode()
            val edge1 = s.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1, mapOf("weight" to 3.0.numVal))

            assertEquals(3.0.numVal, s.getEdgeProperty(edge1, "weight"), "Failed for $name")
        }
    }

    @Test
    fun `test getEdgeProperty_absentProperty_returnsNull`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode()
            val node2 = s.addNode()
            val edge1 = s.addEdge(node1, node2, StorageTestUtils.EDGE_TYPE_1)

            assertNull(s.getEdgeProperty(edge1, "nonexistent"), "Failed for $name")
        }
    }

    @Test
    fun `test getEdgeProperty_nonexistentEdge_throwsEntityNotExist`() {
        for (name in implNames) {
            val s = createStorage(name)
            s.addNode()
            s.addNode()

            assertFailsWith<EntityNotExistException>("Failed for $name") {
                s.getEdgeProperty(-1, "weight")
            }
        }
    }

    // ========================================================================
    // FUNCTIONAL: LayeredStorage cross-layer single-property access
    // ========================================================================

    @Test
    fun `test getNodeProperty_layeredStorage_activeOverridesFrozen`() {
        val layered = LayeredStorageImpl()
        storages.add(layered)
        val node1 = layered.addNode(mapOf("v" to 1.numVal))
        layered.freeze()
        layered.setNodeProperties(node1, mapOf("v" to 2.numVal))

        assertEquals(2.numVal, layered.getNodeProperty(node1, "v"))
    }

    @Test
    fun `test getNodeProperty_layeredStorage_frozenOnlyProperty_returnsValue`() {
        val layered = LayeredStorageImpl()
        storages.add(layered)
        val node1 = layered.addNode(mapOf("frozen" to "yes".strVal, "shared" to 1.numVal))
        layered.freeze()
        layered.setNodeProperties(node1, mapOf("shared" to 2.numVal))

        assertEquals("yes".strVal, layered.getNodeProperty(node1, "frozen"))
        assertEquals(2.numVal, layered.getNodeProperty(node1, "shared"))
    }

    // ========================================================================
    // FUNCTIONAL: getNodeProperty consistency with getNodeProperties
    // ========================================================================

    @Test
    fun `test getNodeProperty_consistentWithGetNodeProperties`() {
        for (name in implNames) {
            val s = createStorage(name)
            val node1 = s.addNode(mapOf("a" to 1.numVal, "b" to "x".strVal, "c" to 3.numVal))

            val allProps = s.getNodeProperties(node1)
            for ((key, value) in allProps) {
                assertEquals(value, s.getNodeProperty(node1, key), "Mismatch for key=$key in $name")
            }
            assertNull(s.getNodeProperty(node1, "nonexistent"), "Failed for $name")
        }
    }

    // ========================================================================
    // PERFORMANCE: single-property read vs full-map read
    // ========================================================================

    @Test
    fun `benchmark single property read vs full map read`() {
        val nodeCount = 50_000
        val opCount = 200_000
        val warmup = 3
        val measured = 5

        println("\n=== Single Property Read vs Full Map Read ===")
        println("$opCount reads on $nodeCount nodes, each with 5 properties")
        println(String.format("%-24s %16s %16s %10s", "Implementation", "getNodeProps[k]", "getNodeProperty", "speedup"))
        println("-".repeat(68))

        for (name in implNames) {
            val s = createStorage(name)
            val nodeIds =
                Array(nodeCount) { i ->
                    s.addNode(
                        mapOf(
                            "name" to "node$i".strVal,
                            "idx" to i.numVal,
                            "type" to "default".strVal,
                            "weight" to 1.0.numVal,
                            "active" to 1.numVal,
                        ),
                    )
                }

            val fullMapOps =
                benchmarkOpsPerSec(opCount, warmup, measured) { i ->
                    s.getNodeProperties(nodeIds[i % nodeCount])["weight"]
                }
            val singleOps =
                benchmarkOpsPerSec(opCount, warmup, measured) { i ->
                    s.getNodeProperty(nodeIds[i % nodeCount], "weight")
                }
            val speedup = singleOps / fullMapOps
            println(
                String.format(
                    "%-24s %16s %16s %9.2fx",
                    name,
                    fmt(fullMapOps),
                    fmt(singleOps),
                    speedup,
                ),
            )
            s.close()
        }
    }

    // ========================================================================
    // PERFORMANCE: Memory — property key interning effect
    // ========================================================================

    @Test
    fun `benchmark memory usage with property storage`() {
        val nodeCount = 100_000
        val propsPerNode = 5
        val propNames = (0 until propsPerNode).map { "prop_$it" }

        println("\n=== Memory Usage: Property Storage ($nodeCount nodes x $propsPerNode props) ===")
        println(String.format("%-24s %16s %16s %16s", "Implementation", "before (MB)", "after (MB)", "delta (MB)"))
        println("-".repeat(74))

        for (name in listOf("NativeStorageImpl", "NativeConcurStorageImpl")) {
            System.gc()
            Thread.sleep(100)
            System.gc()
            val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val s = createStorage(name)
            for (i in 0 until nodeCount) {
                val props = propNames.associateWith { "$it-value-$i".strVal }
                s.addNode(props)
            }

            System.gc()
            Thread.sleep(100)
            System.gc()
            val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val delta = after - before

            println(
                String.format(
                    "%-24s %16.1f %16.1f %16.1f",
                    name,
                    before / 1_048_576.0,
                    after / 1_048_576.0,
                    delta / 1_048_576.0,
                ),
            )
            s.close()
        }
    }

    // ========================================================================
    // PERFORMANCE: LayeredStorage single-property read (avoids full merge)
    // ========================================================================

    @Test
    fun `benchmark layered storage single property read avoids full scan`() {
        val nodesPerLayer = 10_000
        val layers = 5
        val opCount = 100_000
        val warmup = 3
        val measured = 5

        println("\n=== Layered Storage: Single Property Read ($layers layers, $nodesPerLayer nodes/layer) ===")
        println(String.format("%-20s %16s %10s", "Method", "ops/sec", ""))
        println("-".repeat(48))

        val layered = LayeredStorageImpl()
        storages.add(layered)
        val allNodeIds = mutableListOf<Int>()
        for (layer in 0 until layers) {
            for (i in 0 until nodesPerLayer) {
                allNodeIds.add(
                    layered.addNode(
                        mapOf("layer" to layer.numVal, "idx" to i.numVal, "tag" to "l$layer".strVal),
                    ),
                )
            }
            if (layer < layers - 1) layered.freeze()
        }
        val totalNodes = allNodeIds.size

        val fullOps =
            benchmarkOpsPerSec(opCount, warmup, measured) { i ->
                layered.getNodeProperties(allNodeIds[i % totalNodes])["tag"]
            }
        val singleOps =
            benchmarkOpsPerSec(opCount, warmup, measured) { i ->
                layered.getNodeProperty(allNodeIds[i % totalNodes], "tag")
            }

        println(String.format("%-20s %16s", "getNodeProperties", fmt(fullOps)))
        println(String.format("%-20s %16s", "getNodeProperty", fmt(singleOps)))
        println(String.format("%-20s %15.2fx", "speedup", singleOps / fullOps))
        layered.close()
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private inline fun benchmarkOpsPerSec(
        ops: Int,
        warmup: Int,
        measured: Int,
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

    private fun fmt(ops: Double): String =
        when {
            ops >= 1_000_000 -> String.format("%.2fM", ops / 1_000_000)
            ops >= 1_000 -> String.format("%.1fK", ops / 1_000)
            else -> String.format("%.0f", ops)
        }
}
