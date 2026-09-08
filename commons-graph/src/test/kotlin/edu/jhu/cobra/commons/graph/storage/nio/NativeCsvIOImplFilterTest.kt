package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Black-box tests for NativeCsvIOImpl: node and edge predicates on export and import.
 *
 * - `export with node filter excludes filtered nodes` -- node filter predicate
 * - `export with edge filter excludes filtered edges` -- edge filter predicate
 * - `import with node filter excludes node and its edges` -- node predicate skips the node and
 *   every edge referencing it
 * - `import with edge filter excludes edge but keeps nodes` -- edge predicate skips only the edge
 */
internal class NativeCsvIOImplFilterTest {
    private lateinit var tempDir: Path

    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("csv-io-test")
        storage = NativeStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        if (tempDir.exists()) {
            tempDir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Export with filter predicate
    // ========================================================================
    @Test
    fun `export with node filter excludes filtered nodes`() {
        val n1 = storage.addNode(mapOf("name" to "Keep".strVal))
        val n2 = storage.addNode(mapOf("name" to "Drop".strVal))

        val exportPath = tempDir.resolve("filtered_nodes")
        NativeCsvIOImpl.export(exportPath, storage) { it == n1 }

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        assertEquals(1, target.nodeIDs.size)
        val name = (target.getNodeProperties(target.nodeIDs.first())["name"] as StrVal).core
        assertEquals("Keep", name)
    }

    @Test
    fun `export with edge filter excludes filtered edges`() {
        // Node IDs: 0, 1, 2. Edge IDs: 0 (keep), 1 (drop).
        // Filter { it != 1 } keeps nodes 0, 2 and edge 0.
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        val n3 = storage.addNode(mapOf("name" to "C".strVal))
        storage.addEdge(n1, n3, "keep")
        storage.addEdge(n3, n1, "drop")

        val exportPath = tempDir.resolve("filtered_edges")
        NativeCsvIOImpl.export(exportPath, storage) { it != n2 }

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(exportPath, target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        val tag = target.getEdgeStructure(target.edgeIDs.first()).tag
        assertEquals("keep", tag)
    }

    // -- Import filtering (C5.3) --

    @Test
    fun `import with node filter excludes node and its edges`() {
        val n1 = storage.addNode(mapOf("name" to "a".strVal))
        val n2 = storage.addNode(mapOf("name" to "b".strVal))
        val n3 = storage.addNode(mapOf("name" to "c".strVal))
        storage.addEdge(n1, n2, "keep")
        storage.addEdge(n1, n3, "dangling")
        val dir = tempDir.resolve("import_node_filter").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target) { it != n3 }

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("keep", target.getEdgeStructure(target.edgeIDs.single()).tag)
    }

    @Test
    fun `import with edge filter excludes edge but keeps nodes`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        storage.addEdge(n1, n2, "a")
        storage.addEdge(n2, n3, "b")
        storage.addEdge(n3, n1, "c")
        val dropped = storage.addEdge(n1, n3, "drop")
        val dir = tempDir.resolve("import_edge_filter").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val target = NativeStorageImpl()
        NativeCsvIOImpl.import(dir, target) { it != dropped }

        assertEquals(3, target.nodeIDs.size)
        assertEquals(3, target.edgeIDs.size)
        val tags = target.edgeIDs.map { target.getEdgeStructure(it).tag }.toSet()
        assertEquals(setOf("a", "b", "c"), tags)
    }
}
