package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.storage.JgraphtStorageImpl
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
import kotlin.test.assertTrue

/*
 * Tests for JgraphtGmlIOImpl: file validation, export, reserved attribute names, and foreign file rejection.
 *
 * - `isValidFile returns false for nonexistent file`
 * - `isValidFile returns false for empty file`
 * - `isValidFile returns false for directory`
 * - `isValidFile returns true for exported gml file` -- unknown content type does not veto
 * - `export throws when destination file is non-empty`
 * - `export accepts existing empty file`
 * - `export creates file at destination path`
 * - `export returns destination path`
 * - `export throws when node property uses reserved attribute name` -- "nid" would clobber meta
 * - `export throws when edge property uses reserved attribute name` -- "etype" would clobber meta
 * - `import throws when node lacks nid attribute` -- foreign node record is not skipped silently
 * - `import throws when edge lacks structural attributes` -- foreign edge record is not skipped silently
 * - `import throws when file carries no serialized attributes` -- plain GML is rejected, not imported empty
 * - `import throws on corrupt serialized attribute value` -- known type tag with broken payload
 */
internal class JgraphtGmlIOImplTest {
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

    // -- foreign file rejection --

    @Test
    fun `import throws when node lacks nid attribute`() {
        Files.writeString(
            tempFile,
            """
            graph [
              node [ id 1 name "Str:1:a" ]
            ]
            """.trimIndent(),
        )

        val dstStorage = JgraphtStorageImpl()
        assertFailsWith<IllegalStateException> {
            JgraphtGmlIOImpl.import(tempFile, dstStorage)
        }
    }

    @Test
    fun `import throws when edge lacks structural attributes`() {
        Files.writeString(
            tempFile,
            """
            graph [
              node [ id 1 nid "Str:1:0" ]
              node [ id 2 nid "Str:1:1" ]
              edge [ source 1 target 2 note "Str:1:w" ]
            ]
            """.trimIndent(),
        )

        val dstStorage = JgraphtStorageImpl()
        assertFailsWith<IllegalStateException> {
            JgraphtGmlIOImpl.import(tempFile, dstStorage)
        }
    }

    @Test
    fun `import throws when file carries no serialized attributes`() {
        Files.writeString(
            tempFile,
            """
            graph [
              node [ id 1 ]
              node [ id 2 ]
              edge [ source 1 target 2 ]
            ]
            """.trimIndent(),
        )

        val dstStorage = JgraphtStorageImpl()
        assertFailsWith<IllegalArgumentException> {
            JgraphtGmlIOImpl.import(tempFile, dstStorage)
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
