package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Tests for NativeCsvReader: record decoding, reader lifecycle, and close semantics.
 *
 * - `csv reader close stops partially consumed iterations` -- close() closes the underlying readers,
 *   so abandoned partial iterations hold no open handle
 * - `csv reader read after close throws` -- reads on a closed reader fail
 * - `readNodes decodes properties by header column` -- node record decoding
 * - `readEdges yields structural columns and properties` -- edge record decoding
 * - `readMeta yields nothing when meta csv is absent` -- optional meta file
 * - `constructor rejects a directory with an empty nodes csv` -- non-empty file guard
 * - `close is idempotent` -- repeated close
 */
internal class NativeCsvReaderTest {
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

    // -- CsvReader lifecycle (C5.1) --

    @Test
    fun `csv reader close stops partially consumed iterations`() {
        val n1 = storage.addNode(mapOf("p" to "a".strVal))
        val n2 = storage.addNode(mapOf("p" to "b".strVal))
        storage.addEdge(n1, n2, "t1")
        storage.addEdge(n2, n1, "t2")
        storage.setMeta("m1", "1".strVal)
        storage.setMeta("m2", "2".strVal)
        val dir = tempDir.resolve("reader_close_partial").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val reader = NativeCsvReader(dir)
        val nodes = reader.readNodes()
        val edges = reader.readEdges()
        val meta = reader.readMeta()
        nodes.next()
        edges.next()
        meta.next()
        reader.close()
        assertFailsWith<IllegalStateException> { nodes.next() }
        assertFailsWith<IllegalStateException> { edges.next() }
        assertFailsWith<IllegalStateException> { meta.next() }
    }

    @Test
    fun `csv reader read after close throws`() {
        storage.addNode(mapOf("p" to "a".strVal))
        val dir = tempDir.resolve("reader_read_after_close").createDirectories()
        NativeCsvIOImpl.export(dir, storage)

        val reader = NativeCsvReader(dir)
        reader.close()
        assertFailsWith<IllegalStateException> { reader.readNodes().next() }
    }

    // -- Record decoding --

    @Test
    fun `readNodes decodes properties by header column`() {
        val dir = tempDir.resolve("reader_nodes")
        NativeCsvWriter(dir).use { writer ->
            writer.writeNode("n1", mapOf("p" to "a".strVal))
            writer.writeNode("n2", mapOf("q" to 2.intVal))
        }

        val records = NativeCsvReader(dir).use { reader -> reader.readNodes().asSequence().toList() }

        assertEquals(listOf("n1", "n2"), records.map { it.nodeId })
        assertEquals(mapOf("p" to "a".strVal), records[0].properties)
        assertEquals(mapOf("q" to 2.intVal), records[1].properties)
    }

    @Test
    fun `readEdges yields structural columns and properties`() {
        val dir = tempDir.resolve("reader_edges")
        NativeCsvWriter(dir).use { writer ->
            writer.writeEdge("e1", "n1", "n2", "t,1", mapOf("w" to 1.intVal))
        }

        val records = NativeCsvReader(dir).use { reader -> reader.readEdges().asSequence().toList() }

        assertEquals(1, records.size)
        assertEquals(EdgeRecord("e1", "n1", "n2", "t,1", mapOf("w" to 1.intVal)), records[0])
    }

    @Test
    fun `readMeta yields nothing when meta csv is absent`() {
        val dir = tempDir.resolve("reader_no_meta")
        NativeCsvWriter(dir).use { writer -> writer.writeNode("n1", emptyMap()) }
        dir.resolve(NativeCsvFormat.META_FILE).deleteExisting()

        val meta = NativeCsvReader(dir).use { reader -> reader.readMeta().asSequence().toList() }

        assertTrue(meta.isEmpty())
    }

    @Test
    fun `constructor rejects a directory with an empty nodes csv`() {
        val dir = tempDir.resolve("reader_empty").createDirectories()
        dir.resolve(NativeCsvFormat.NODE_FILE).createFile()
        dir.resolve(NativeCsvFormat.EDGE_FILE).writeText("__eid__,__src__,__dst__,__tag__\n")

        assertFailsWith<IllegalArgumentException> { NativeCsvReader(dir) }
    }

    @Test
    fun `close is idempotent`() {
        val dir = tempDir.resolve("reader_close_twice")
        NativeCsvWriter(dir).use { writer -> writer.writeNode("n1", emptyMap()) }
        val reader = NativeCsvReader(dir)

        reader.close()
        reader.close()

        assertFailsWith<IllegalStateException> { reader.readNodes().next() }
    }
}
