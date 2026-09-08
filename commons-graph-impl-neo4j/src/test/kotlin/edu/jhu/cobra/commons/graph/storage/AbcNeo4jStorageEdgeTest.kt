package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for the AbcNeo4jStorage engine through Neo4jStorageImpl: edge operations and adjacency.
 *
 * - `addEdge with properties returns valid ID and stores properties`
 * - `addEdge throws EntityNotExistException when src or dst missing`
 * - `containsEdge returns true for existing edge`
 * - `containsEdge returns false for nonexistent edge`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure throws EntityNotExistException for missing edge`
 * - `getEdgeProperties returns stored properties`
 * - `getEdgeProperty returns value for existing property`
 * - `getEdgeProperty returns null for absent property on existing edge`
 * - `setEdgeProperties updates existing and adds new properties`
 * - `setEdgeProperties with null value removes that property`
 * - `deleteEdge removes edge from storage`
 * - `deleteEdge throws EntityNotExistException for missing edge`
 * - `getIncomingEdges returns correct edge set`
 * - `getIncomingEdges throws EntityNotExistException for missing node`
 * - `getOutgoingEdges returns correct edge set`
 * - `self loop edge appears in both incoming and outgoing`
 */
internal class AbcNeo4jStorageEdgeTest {
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

    // -- addEdge --

    @Test
    fun `addEdge with properties returns valid ID and stores properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to "v".strVal))
        assertTrue(storage.containsEdge(e))
        assertEquals("v", (storage.getEdgeProperties(e)["w"] as StrVal).core)
    }

    @Test
    fun `addEdge throws EntityNotExistException when src or dst missing`() {
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, -2, "edge") }
    }

    // -- containsEdge --

    @Test
    fun `containsEdge returns true for existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
    }

    @Test
    fun `containsEdge returns false for nonexistent edge`() {
        assertFalse(storage.containsEdge(-1))
    }

    // -- getEdgeStructure --

    @Test
    fun `getEdgeStructure returns correct src dst and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "FOLLOWS")
        val s = storage.getEdgeStructure(e)
        assertEquals(n1, s.src)
        assertEquals(n2, s.dst)
        assertEquals("FOLLOWS", s.tag)
    }

    @Test
    fun `getEdgeStructure throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(-1) }
    }

    // -- getEdgeProperties --

    @Test
    fun `getEdgeProperties returns stored properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to "v".strVal))
        assertEquals("v", (storage.getEdgeProperties(e)["w"] as StrVal).core)
    }

    // -- getEdgeProperty --

    @Test
    fun `getEdgeProperty returns value for existing property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("w" to "heavy".strVal))
        assertEquals("heavy", (storage.getEdgeProperty(e, "w") as StrVal).core)
    }

    @Test
    fun `getEdgeProperty returns null for absent property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertNull(storage.getEdgeProperty(e, "missing"))
    }

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties updates existing and adds new properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.setEdgeProperties(e, mapOf("w" to 42.intVal))
        assertEquals(42, (storage.getEdgeProperties(e)["w"] as IntVal).core)
    }

    @Test
    fun `setEdgeProperties with null value removes that property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        assertNull(storage.getEdgeProperties(e)["x"])
        assertEquals("w", (storage.getEdgeProperties(e)["z"] as StrVal).core)
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from storage`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `deleteEdge throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    // -- adjacency --

    @Test
    fun `getIncomingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertEquals(1, storage.getIncomingEdges(n2).size)
        assertTrue(e in storage.getIncomingEdges(n2))
    }

    @Test
    fun `getIncomingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(-1) }
    }

    @Test
    fun `getOutgoingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertEquals(1, storage.getOutgoingEdges(n1).size)
        assertTrue(e in storage.getOutgoingEdges(n1))
    }

    @Test
    fun `self loop edge appears in both incoming and outgoing`() {
        val n = storage.addNode()
        val e = storage.addEdge(n, n, "self")
        assertTrue(e in storage.getOutgoingEdges(n))
        assertTrue(e in storage.getIncomingEdges(n))
    }
}
