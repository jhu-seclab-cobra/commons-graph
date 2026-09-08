package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.JgraphtStorageImpl
import edu.jhu.cobra.commons.value.BoolVal
import edu.jhu.cobra.commons.value.floatVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Tests for JgraphtGmlIOImpl: import, round-trip fidelity, filtering, and attribute decoding.
 *
 * - `import requires file to exist`
 * - `import returns target storage`
 * - `export and import empty storage round-trip`
 * - `export with nodes and edges creates non-empty file`
 * - `import with node filter excludes node and its edges` -- node predicate skips the node
 *   and every edge referencing it
 * - `export with node filter skips edges of filtered nodes` -- no NPE on dangling edges
 * - `import decodes short serialized attribute values` -- 5-char frames such as `True:` survive
 */
internal class JgraphtGmlIOImplImportTest {
    private lateinit var srcStorage: JgraphtStorageImpl

    private lateinit var tempFile: Path

    @BeforeTest
    fun setUp() {
        srcStorage = JgraphtStorageImpl()
        tempFile = Files.createTempFile("gml-test", ".gml")
        tempFile.deleteIfExists()
    }

    @AfterTest
    fun tearDown() {
        tempFile.deleteIfExists()
    }

    // -- import --

    @Test
    fun `import requires file to exist`() {
        val badPath = Paths.get("/tmp/nonexistent_${System.nanoTime()}.gml")
        val dstStorage = JgraphtStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            JgraphtGmlIOImpl.import(badPath, dstStorage)
        }
    }

    @Test
    fun `import returns target storage`() {
        JgraphtGmlIOImpl.export(tempFile, srcStorage)
        val dstStorage = JgraphtStorageImpl()
        val result = JgraphtGmlIOImpl.import(tempFile, dstStorage)
        assertSame(dstStorage, result)
    }

    // -- round-trip --

    @Test
    fun `export and import empty storage round-trip`() {
        JgraphtGmlIOImpl.export(tempFile, srcStorage)
        val dstStorage = JgraphtStorageImpl()
        JgraphtGmlIOImpl.import(tempFile, dstStorage)
        assertEquals(0, dstStorage.nodeIDs.size)
        assertEquals(0, dstStorage.edgeIDs.size)
    }

    @Test
    fun `export with nodes and edges creates non-empty file`() {
        val n1 = srcStorage.addNode(mapOf("name" to "NodeAlpha".strVal))
        val n2 = srcStorage.addNode(mapOf("name" to "NodeBeta".strVal))
        srcStorage.addEdge(n1, n2, "depends_on", mapOf("weight" to 1.5.floatVal))

        JgraphtGmlIOImpl.export(tempFile, srcStorage)

        assertTrue(Files.exists(tempFile))
        val content = Files.readString(tempFile)
        assertTrue(content.contains("graph"))
        assertTrue(content.contains("node"))
        assertTrue(content.contains("edge"))
    }

    // -- filtering --

    @Test
    fun `import with node filter excludes node and its edges`() {
        Files.writeString(
            tempFile,
            """
            graph [
              node [ id 1 nid "Str:1:0" name "Str:1:a" ]
              node [ id 2 nid "Str:1:1" name "Str:1:b" ]
              node [ id 3 nid "Str:1:2" name "Str:1:c" ]
              edge [ source 1 target 2 esrc "Str:1:0" edst "Str:1:1" etype "Str:4:keep" ]
              edge [ source 1 target 3 esrc "Str:1:0" edst "Str:1:2" etype "Str:8:dangling" ]
            ]
            """.trimIndent(),
        )

        val dstStorage = JgraphtStorageImpl()
        JgraphtGmlIOImpl.import(tempFile, dstStorage) { it != 2 }

        assertEquals(2, dstStorage.nodeIDs.size)
        assertEquals(1, dstStorage.edgeIDs.size)
        assertEquals("keep", dstStorage.getEdgeStructure(dstStorage.edgeIDs.single()).tag)
    }

    @Test
    fun `export with node filter skips edges of filtered nodes`() {
        val n1 = srcStorage.addNode(mapOf("name" to "a".strVal))
        val n2 = srcStorage.addNode(mapOf("name" to "b".strVal))
        srcStorage.addEdge(n1, n2, "dangling")

        JgraphtGmlIOImpl.export(tempFile, srcStorage) { it != n2 }

        val content = Files.readString(tempFile)
        assertEquals(1, Regex("""node\s*\[""").findAll(content).count())
        assertFalse(content.contains("edge"))
    }

    // -- attribute value decoding --

    @Test
    fun `import decodes short serialized attribute values`() {
        Files.writeString(
            tempFile,
            """
            graph [
              node [ id 1 nid "Str:1:0" flag "True:" ]
            ]
            """.trimIndent(),
        )

        val dstStorage = JgraphtStorageImpl()
        JgraphtGmlIOImpl.import(tempFile, dstStorage)

        val imported = dstStorage.nodeIDs.single()
        assertEquals(BoolVal.T, dstStorage.getNodeProperty(imported, "flag"))
    }
}
