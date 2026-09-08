package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Tests for NativeCsvWriter: header management and streaming row output.
 *
 * - `constructor creates the directory and writes fixed headers` -- initial layout
 * - `close rewrites node header with property columns in first-seen order` -- header update
 * - `writeNode emits empty cells for properties absent on later rows` -- sparse cells
 * - `close rewrites edge header after structural columns` -- edge header update
 * - `writeMeta emits escaped name and serialized value` -- meta row
 * - `constructor rejects a non-empty existing nodes csv` -- overwrite guard
 * - `write after close throws IllegalArgumentException` -- closed writer guard
 */
internal class NativeCsvWriterTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("csv-writer-test")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun nodeLines(dir: Path) = dir.resolve(NativeCsvFormat.NODE_FILE).readLines()

    private fun edgeLines(dir: Path) = dir.resolve(NativeCsvFormat.EDGE_FILE).readLines()

    @Test
    fun `constructor creates the directory and writes fixed headers`() {
        val dir = tempDir.resolve("fresh")

        NativeCsvWriter(dir).close()

        assertTrue(dir.exists())
        assertEquals(listOf(NativeCsvFormat.NODE_ID_COL), nodeLines(dir))
        assertEquals(listOf("__eid__,__src__,__dst__,__tag__"), edgeLines(dir))
        assertEquals(listOf("name,value"), dir.resolve(NativeCsvFormat.META_FILE).readLines())
    }

    @Test
    fun `close rewrites node header with property columns in first-seen order`() {
        NativeCsvWriter(tempDir).use { writer ->
            writer.writeNode("1", mapOf("b" to 1.intVal))
            writer.writeNode("2", mapOf("a" to 2.intVal))
        }

        assertEquals("__nid__,b,a", nodeLines(tempDir).first())
    }

    @Test
    fun `writeNode emits empty cells for properties absent on later rows`() {
        NativeCsvWriter(tempDir).use { writer ->
            writer.writeNode("1", mapOf("p" to "x".strVal))
            writer.writeNode("2", emptyMap())
        }

        val rows = nodeLines(tempDir).drop(1)
        assertEquals(2, rows.size)
        assertTrue(rows[0].startsWith("1,"))
        assertEquals("2,", rows[1])
    }

    @Test
    fun `close rewrites edge header after structural columns`() {
        NativeCsvWriter(tempDir).use { writer ->
            writer.writeNode("1", emptyMap())
            writer.writeNode("2", emptyMap())
            writer.writeEdge("10", "1", "2", "t", mapOf("w" to 1.intVal))
        }

        val lines = edgeLines(tempDir)
        assertEquals("__eid__,__src__,__dst__,__tag__,w", lines[0])
        assertTrue(lines[1].startsWith("10,1,2,t,"))
    }

    @Test
    fun `writeMeta emits escaped name and serialized value`() {
        NativeCsvWriter(tempDir).use { writer ->
            writer.writeMeta("a,b", "v".strVal)
        }

        val lines = tempDir.resolve(NativeCsvFormat.META_FILE).readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("a\\,b,"))
    }

    @Test
    fun `constructor rejects a non-empty existing nodes csv`() {
        tempDir.resolve(NativeCsvFormat.NODE_FILE).writeText("__nid__\n1\n")

        assertFailsWith<IllegalArgumentException> { NativeCsvWriter(tempDir) }
    }

    @Test
    fun `write after close throws IllegalArgumentException`() {
        val writer = NativeCsvWriter(tempDir)
        writer.close()

        assertFailsWith<IllegalArgumentException> { writer.writeNode("1", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { writer.writeEdge("1", "1", "1", "t", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { writer.writeMeta("m", 1.intVal) }
    }
}
