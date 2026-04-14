/**
 * White-box tests for GML export/import round-trip via [JgraphtGmlIOImpl].
 *
 * - `isValidFile returns false for nonexistent file`
 * - `isValidFile returns false for empty file`
 * - `isValidFile returns false for directory`
 * - `export creates file at destination path`
 * - `export returns destination path`
 * - `import requires file to exist`
 * - `import returns target storage`
 * - `export and import empty storage round-trip`
 * - `export with nodes and edges creates non-empty file`
 */
package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.JgraphtStorageImpl
import edu.jhu.cobra.commons.value.numVal
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
        srcStorage.close()
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

    // -- export --

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
        dstStorage.close()
    }

    @Test
    fun `import returns target storage`() {
        JgraphtGmlIOImpl.export(tempFile, srcStorage)
        val dstStorage = JgraphtStorageImpl()
        val result = JgraphtGmlIOImpl.import(tempFile, dstStorage)
        assertSame(dstStorage, result)
        dstStorage.close()
    }

    // -- round-trip --

    @Test
    fun `export and import empty storage round-trip`() {
        JgraphtGmlIOImpl.export(tempFile, srcStorage)
        val dstStorage = JgraphtStorageImpl()
        JgraphtGmlIOImpl.import(tempFile, dstStorage)
        assertEquals(0, dstStorage.nodeIDs.size)
        assertEquals(0, dstStorage.edgeIDs.size)
        dstStorage.close()
    }

    @Test
    fun `export with nodes and edges creates non-empty file`() {
        val n1 = srcStorage.addNode(mapOf("name" to "NodeAlpha".strVal))
        val n2 = srcStorage.addNode(mapOf("name" to "NodeBeta".strVal))
        srcStorage.addEdge(n1, n2, "depends_on", mapOf("weight" to 1.5.numVal))

        JgraphtGmlIOImpl.export(tempFile, srcStorage)

        assertTrue(Files.exists(tempFile))
        val content = Files.readString(tempFile)
        assertTrue(content.contains("graph"))
        assertTrue(content.contains("node"))
        assertTrue(content.contains("edge"))
    }
}
