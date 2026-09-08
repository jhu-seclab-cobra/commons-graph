package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
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
 * White-box tests for Neo4jStorageImpl: mapping caches, transactions, initialization, and edge structure.
 *
 * - `addNode populates node mapping cache`
 * - `deleteNode removes from node mapping cache`
 * - `addEdge populates edge mapping cache`
 * - `deleteEdge removes from edge mapping cache`
 * - `deleteNode removes associated edges from edge mapping`
 * - `writeTx rolls back on exception`
 * - `readTx succeeds for read operations`
 * - `storage works with fresh empty directory`
 * - `init block loads existing nodes and edges from database`
 * - `self loop edge appears in both incoming and outgoing`
 * - `deleteNode removes self loop edge from cache`
 * - `clear empties node and edge caches and database`
 * - `edge type stored as Neo4j RelationshipType`
 * - `transferTo copies all data`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure nonexistent throws EntityNotExistException`
 */
internal class Neo4jStorageImplTest {
    private lateinit var storage: Neo4jStorageImpl

    private lateinit var graphDir: Path

    @BeforeTest
    fun setUp() {
        graphDir = Files.createTempDirectory("neo4j-wb-test")
        storage = Neo4jStorageImpl(graphDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    // -- Node mapping cache consistency --

    @Test
    fun `addNode populates node mapping cache`() {
        val n = storage.addNode()
        assertTrue(storage.containsNode(n))
        assertEquals(setOf(n), storage.nodeIDs)
    }

    @Test
    fun `deleteNode removes from node mapping cache`() {
        val n = storage.addNode()
        storage.deleteNode(n)
        assertFalse(storage.containsNode(n))
        assertEquals(0, storage.nodeIDs.size)
    }

    // -- Edge mapping cache consistency --

    @Test
    fun `addEdge populates edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
        assertEquals(setOf(e), storage.edgeIDs)
    }

    @Test
    fun `deleteEdge removes from edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `deleteNode removes associated edges from edge mapping`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e13 = storage.addEdge(n1, n3, "e13")
        val e23 = storage.addEdge(n2, n3, "e23")

        storage.deleteNode(n1)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e13))
        assertTrue(storage.containsEdge(e23))
    }

    // -- Transaction semantics --

    @Test
    fun `writeTx rolls back on exception`() {
        val n = storage.addNode(mapOf("before" to "original".strVal))
        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(n, mapOf("__meta_id__" to "hack".strVal))
        }
        val props = storage.getNodeProperties(n)
        assertEquals("original", (props["before"] as StrVal).core)
        assertNull(props["__meta_id__"])
    }

    @Test
    fun `readTx succeeds for read operations`() {
        val n = storage.addNode(mapOf("key" to "value".strVal))
        assertEquals("value", (storage.getNodeProperties(n)["key"] as StrVal).core)
    }

    // -- Lazy database initialization --

    @Test
    fun `storage works with fresh empty directory`() {
        assertEquals(0, storage.nodeIDs.size)
        storage.addNode()
        assertEquals(1, storage.nodeIDs.size)
    }

    // -- Init block loads existing data --

    @Test
    fun `init block loads existing nodes and edges from database`() {
        val n1 = storage.addNode(mapOf("data" to "d1".strVal))
        val n2 = storage.addNode(mapOf("data" to "d2".strVal))
        val e = storage.addEdge(n1, n2, "link", mapOf("weight" to 1.intVal))
        storage.close()

        val reloaded = Neo4jStorageImpl(graphDir)
        assertTrue(reloaded.containsNode(n1))
        assertTrue(reloaded.containsNode(n2))
        assertTrue(reloaded.containsEdge(e))
        assertEquals("d1", (reloaded.getNodeProperties(n1)["data"] as StrVal).core)
        assertEquals(1, (reloaded.getEdgeProperties(e)["weight"] as IntVal).core)
        reloaded.close()
    }

    // -- Self-loop edges --

    @Test
    fun `self loop edge appears in both incoming and outgoing`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "loop")
        assertTrue(selfEdge in storage.getOutgoingEdges(n))
        assertTrue(selfEdge in storage.getIncomingEdges(n))
    }

    @Test
    fun `deleteNode removes self loop edge from cache`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "loop")
        storage.deleteNode(n)
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- clear --

    @Test
    fun `clear empties node and edge caches and database`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e")
        storage.setMeta("key", "val".strVal)
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- RelationshipType uses tag --

    @Test
    fun `edge type stored as Neo4j RelationshipType`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "KNOWS")
        val e2 = storage.addEdge(n1, n2, "LIKES")
        assertTrue(storage.containsEdge(e1))
        assertTrue(storage.containsEdge(e2))
        assertEquals(2, storage.getOutgoingEdges(n1).size)
    }

    // -- transferTo --

    @Test
    fun `transferTo copies all data`() {
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

    // -- getEdgeStructure --

    @Test
    fun `getEdgeStructure returns correct src dst and tag`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val e = storage.addEdge(src, dst, "FOLLOWS")
        val structure = storage.getEdgeStructure(e)
        assertEquals(src, structure.src)
        assertEquals(dst, structure.dst)
        assertEquals("FOLLOWS", structure.tag)
    }

    @Test
    fun `getEdgeStructure nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeStructure(-1) }
    }
}
