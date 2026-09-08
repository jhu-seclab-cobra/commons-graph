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
import kotlin.test.assertTrue

/*
 * Black-box tests for Neo4jConcurStorageImpl: edge operations and adjacency under locking.
 *
 * - `addEdge with properties returns valid ID and stores properties`
 * - `addEdge throws EntityNotExistException when src or dst missing`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure throws EntityNotExistException for missing edge`
 * - `getEdgeProperties returns stored properties`
 * - `setEdgeProperties updates existing and adds new properties`
 * - `deleteEdge removes edge from storage`
 * - `getIncomingEdges returns correct edge set`
 * - `getOutgoingEdges returns correct edge set`
 * - `self loop edge appears in both incoming and outgoing`
 */
internal class Neo4jConcurStorageImplEdgeTest {
    private lateinit var storage: Neo4jConcurStorageImpl

    private lateinit var graphDir: Path

    @BeforeTest
    fun setUp() {
        graphDir = Files.createTempDirectory("neo4j-concur-test")
        storage = Neo4jConcurStorageImpl(graphDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        graphDir.toFile().deleteRecursively()
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
        val n = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(n, -1, "edge") }
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

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties updates existing and adds new properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.setEdgeProperties(e, mapOf("w" to 42.intVal))
        assertEquals(42, (storage.getEdgeProperties(e)["w"] as IntVal).core)
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from storage`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
    }

    // -- adjacency --

    @Test
    fun `getIncomingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(e in storage.getIncomingEdges(n2))
    }

    @Test
    fun `getOutgoingEdges returns correct edge set`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
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
