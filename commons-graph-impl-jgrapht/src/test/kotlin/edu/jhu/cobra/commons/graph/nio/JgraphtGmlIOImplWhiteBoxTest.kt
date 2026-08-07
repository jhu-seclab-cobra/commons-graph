/**
 * White-box tests for GML export/import round-trip via [JgraphtGmlIOImpl].
 *
 * - `isValidFile returns false for nonexistent file`
 * - `isValidFile returns false for empty file`
 * - `isValidFile returns false for directory`
 * - `isValidFile returns true for exported gml file` — unknown content type does not veto
 * - `export creates file at destination path`
 * - `export throws when destination file is non-empty`
 * - `export accepts existing empty file`
 * - `export returns destination path`
 * - `import requires file to exist`
 * - `import returns target storage`
 * - `export and import empty storage round-trip`
 * - `export with nodes and edges creates non-empty file`
 * - `import with node filter excludes node and its edges` — node predicate skips the node
 *   and every edge referencing it
 * - `export with node filter skips edges of filtered nodes` — no NPE on dangling edges
 * - `import decodes short serialized attribute values` — 5-char frames such as `True:` survive
 * - `import throws on corrupt serialized attribute value` — known type tag with broken payload
 * - `export throws when node property uses reserved attribute name` — "nid" would clobber meta
 * - `export throws when edge property uses reserved attribute name` — "etype" would clobber meta
 *
 * Import tests that need entity attributes use handcrafted GML: the bundled jgrapht 1.4.0
 * GmlExporter has no custom-attribute parameters and exports labels only.
 */
package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.InvalidPropNameException
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

internal class JgraphtGmlIOImplWhiteBoxTest {
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

    // -- isValidFile --

    @Test
    fun `isValidFile returns false for nonexistent file`() {
        val nonExistent = Paths.get("/tmp/nonexistent_${System.nanoTime()}.gml")
        assertFalse(JgraphtGmlIOImpl.isValidFile(nonExistent))
    }

    @Test
    fun `isValidFile returns false for empty file`() {
        val emptyFile = Files.createTempFile("gml-empty", ".gml")
        assertFalse(JgraphtGmlIOImpl.isValidFile(emptyFile))
        emptyFile.deleteIfExists()
    }

    @Test
    fun `isValidFile returns false for directory`() {
        val dir = Files.createTempDirectory("gml-dir-test")
        assertFalse(JgraphtGmlIOImpl.isValidFile(dir))
        Files.deleteIfExists(dir)
    }

    @Test
    fun `isValidFile returns true for exported gml file`() {
        srcStorage.addNode()
        JgraphtGmlIOImpl.export(tempFile, srcStorage)
        assertTrue(JgraphtGmlIOImpl.isValidFile(tempFile))
    }

    // -- export --

    @Test
    fun `export throws when destination file is non-empty`() {
        Files.writeString(tempFile, "existing content")
        assertFailsWith<IllegalArgumentException> {
            JgraphtGmlIOImpl.export(tempFile, srcStorage)
        }
    }

    @Test
    fun `export accepts existing empty file`() {
        Files.createFile(tempFile)
        srcStorage.addNode()
        val result = JgraphtGmlIOImpl.export(tempFile, srcStorage)
        assertEquals(tempFile, result)
        assertTrue(Files.size(tempFile) > 0)
    }

    @Test
    fun `export creates file at destination path`() {
        srcStorage.addNode()
        val result = JgraphtGmlIOImpl.export(tempFile, srcStorage)
        assertEquals(tempFile, result)
        assertTrue(Files.exists(tempFile))
        assertTrue(Files.size(tempFile) > 0)
    }

    @Test
    fun `export returns destination path`() {
        val result = JgraphtGmlIOImpl.export(tempFile, srcStorage)
        assertEquals(tempFile, result)
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

    // -- reserved attribute names --

    @Test
    fun `export throws when node property uses reserved attribute name`() {
        srcStorage.addNode(mapOf("nid" to "boom".strVal))
        assertFailsWith<InvalidPropNameException> {
            JgraphtGmlIOImpl.export(tempFile, srcStorage)
        }
    }

    @Test
    fun `export throws when edge property uses reserved attribute name`() {
        val n1 = srcStorage.addNode()
        val n2 = srcStorage.addNode()
        srcStorage.addEdge(n1, n2, "rel", mapOf("etype" to "boom".strVal))
        assertFailsWith<InvalidPropNameException> {
            JgraphtGmlIOImpl.export(tempFile, srcStorage)
        }
    }

    @Test
    fun `import throws on corrupt serialized attribute value`() {
        Files.writeString(
            tempFile,
            """
            graph [
              node [
                id 0
                nid "Str:1:0"
                bad "IntV:"
              ]
            ]
            """.trimIndent(),
        )

        val dstStorage = JgraphtStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            JgraphtGmlIOImpl.import(tempFile, dstStorage)
        }
    }
}
