package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.JgraphtStorageImpl
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.test.*

class JgraphtGmlIOImplWhiteBoxTest {
    private lateinit var srcStorage: JgraphtStorageImpl
    private lateinit var tempFile: Path

    @Before
    fun setup() {
        srcStorage = JgraphtStorageImpl()
        tempFile = Files.createTempFile("gml-test", ".gml")
        tempFile.deleteIfExists()
    }

    @After
    fun cleanup() {
        srcStorage.close()
        tempFile.deleteIfExists()
    }

    // -- isValidFile --

    @Test
    fun `test isValidFile returns false for nonexistent file`() {
        val nonExistent = Paths.get("/tmp/nonexistent_${System.nanoTime()}.gml")
        assertFalse(JgraphtGmlIOImpl.isValidFile(nonExistent))
    }

    @Test
    fun `test isValidFile returns false for empty file`() {
        val emptyFile = Files.createTempFile("gml-empty", ".gml")
        assertFalse(JgraphtGmlIOImpl.isValidFile(emptyFile))
        emptyFile.deleteIfExists()
    }

    // -- Export precondition --

    @Test
    fun `test export creates file at destination path`() {
        srcStorage.addNode()
        val result = JgraphtGmlIOImpl.export(tempFile, srcStorage)

        assertEquals(tempFile, result)
        assertTrue(Files.exists(tempFile))
        assertTrue(Files.size(tempFile) > 0)
    }

    // -- Import precondition --

    @Test
    fun `test import precondition requires file exist`() {
        val badPath = Paths.get("/tmp/nonexistent_${System.nanoTime()}.gml")
        val dstStorage = JgraphtStorageImpl()

        assertFailsWith<IllegalArgumentException> {
            JgraphtGmlIOImpl.import(badPath, dstStorage)
        }

        dstStorage.close()
    }

    // -- Empty storage export/import --

    @Test
    fun `test export and import empty storage`() {
        JgraphtGmlIOImpl.export(tempFile, srcStorage)

        val dstStorage = JgraphtStorageImpl()
        JgraphtGmlIOImpl.import(tempFile, dstStorage)

        assertEquals(0, dstStorage.nodeIDs.size)
        assertEquals(0, dstStorage.edgeIDs.size)

        dstStorage.close()
    }

    // -- Export returns destination path --

    @Test
    fun `test export returns dstFile path`() {
        val result = JgraphtGmlIOImpl.export(tempFile, srcStorage)
        assertEquals(tempFile, result)
    }

    // -- Import returns storage --

    @Test
    fun `test import returns target storage`() {
        JgraphtGmlIOImpl.export(tempFile, srcStorage)

        val dstStorage = JgraphtStorageImpl()
        val result = JgraphtGmlIOImpl.import(tempFile, dstStorage)

        assertSame(dstStorage, result)
        dstStorage.close()
    }

    // -- Full round-trip with properties --

    @Test
    fun `test export with nodes and edges creates non-empty file`() {
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

    @Test
    fun `test isValidFile returns false for directory`() {
        val dir = Files.createTempDirectory("gml-dir-test")
        assertFalse(JgraphtGmlIOImpl.isValidFile(dir))
        Files.deleteIfExists(dir)
    }

}
