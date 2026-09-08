package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/*
 * Black-box tests for the AbcNeo4jStorage engine through Neo4jStorageImpl: metadata, clear, transferTo, and reserved property names.
 *
 * - `setMeta stores and getMeta retrieves value`
 * - `setMeta with null removes metadata entry`
 * - `getMeta returns null for nonexistent key`
 * - `clear removes all nodes edges and metadata`
 * - `transferTo copies nodes edges and metadata to target`
 * - `transferTo same instance throws IllegalArgumentException`
 * - `invalid property name throws InvalidPropNameException`
 */
internal class AbcNeo4jStorageTest {
    private lateinit var storage: Neo4jStorageImpl

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("neo4j-test")
        storage = Neo4jStorageImpl(tempDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        tempDir.toFile().deleteRecursively()
    }

    // -- metadata --

    @Test
    fun `setMeta stores and getMeta retrieves value`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
    }

    @Test
    fun `setMeta with null removes metadata entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `getMeta returns null for nonexistent key`() {
        assertNull(storage.getMeta("nonexistent"))
    }

    // -- clear --

    @Test
    fun `clear removes all nodes edges and metadata`() {
        storage.addNode()
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and metadata to target`() {
        val n1 = storage.addNode(mapOf("label" to "A".strVal))
        val n2 = storage.addNode(mapOf("label" to "B".strVal))
        storage.addEdge(n1, n2, "CONNECTS", mapOf("since" to "2024".strVal))
        storage.setMeta("version", "1".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1", target.getMeta("version")?.core)
    }

    @Test
    fun `transferTo same instance throws IllegalArgumentException`() {
        storage.addNode()
        assertFailsWith<IllegalArgumentException> { storage.transferTo(storage) }
    }

    // -- Neo4j-specific: reserved property name --

    @Test
    fun `invalid property name throws InvalidPropNameException`() {
        val id = storage.addNode()
        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(id, mapOf("__meta_id__" to "value".strVal))
        }
    }
}
