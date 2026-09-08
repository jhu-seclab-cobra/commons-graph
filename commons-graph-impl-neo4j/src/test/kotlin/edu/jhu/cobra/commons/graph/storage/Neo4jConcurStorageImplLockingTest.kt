package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * White-box tests for Neo4jConcurStorageImpl: mapping caches, transactions, metadata under lock, and concurrent safety.
 *
 * - `addNode populates node mapping cache`
 * - `deleteNode removes from node mapping cache`
 * - `addEdge populates edge mapping cache`
 * - `deleteNode removes associated edges from edge mapping`
 * - `writeTx rolls back on exception`
 * - `init block loads existing nodes and edges from database`
 * - `getNodeProperties excludes META_ID property`
 * - `meta operations under lock`
 * - `setMeta null removes entry`
 * - `meta persists across storage instances` -- meta survives close and reopen via the meta node
 * - `meta node does not appear as graph node` -- meta storage never leaks into nodeIDs
 * - `clear empties all structures under write lock`
 * - `self loop edge appears in both incoming and outgoing`
 * - `concurrent deleteNode does not cause errors`
 * - `no deadlock under mixed read-write operations`
 * - `nodeIDs returns snapshot not live view`
 */
internal class Neo4jConcurStorageImplLockingTest {
    private lateinit var storage: Neo4jConcurStorageImpl

    private lateinit var graphDir: Path

    @BeforeTest
    fun setUp() {
        graphDir = Files.createTempDirectory("neo4j-concur-wb-test")
        storage = Neo4jConcurStorageImpl(graphDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    // -- Node/edge mapping cache consistency --

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

    @Test
    fun `addEdge populates edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
        assertEquals(setOf(e), storage.edgeIDs)
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

    // -- Init block loads existing data --

    @Test
    fun `init block loads existing nodes and edges from database`() {
        val n1 = storage.addNode(mapOf("data" to "d1".strVal))
        val n2 = storage.addNode(mapOf("data" to "d2".strVal))
        val e = storage.addEdge(n1, n2, "link", mapOf("weight" to 1.intVal))
        storage.close()

        val reloaded = Neo4jConcurStorageImpl(graphDir)
        assertTrue(reloaded.containsNode(n1))
        assertTrue(reloaded.containsNode(n2))
        assertTrue(reloaded.containsEdge(e))
        assertEquals("d1", (reloaded.getNodeProperties(n1)["data"] as StrVal).core)
        assertEquals(1, (reloaded.getEdgeProperties(e)["weight"] as IntVal).core)
        reloaded.close()
    }

    // -- META_ID property filtering --

    @Test
    fun `getNodeProperties excludes META_ID property`() {
        val n = storage.addNode(mapOf("visible" to "yes".strVal))
        val props = storage.getNodeProperties(n)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
    }

    // -- Metadata under lock --

    @Test
    fun `meta operations under lock`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)
    }

    @Test
    fun `setMeta null removes entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `meta persists across storage instances`() {
        storage.setMeta("key", "val".strVal)
        storage.close()
        val reloaded = Neo4jConcurStorageImpl(graphDir)
        assertEquals("val".strVal, reloaded.getMeta("key"))
        assertTrue("key" in reloaded.metaNames)
        reloaded.close()
    }

    @Test
    fun `meta node does not appear as graph node`() {
        storage.setMeta("key", "val".strVal)
        assertTrue(storage.nodeIDs.isEmpty())
    }

    // -- Clear under write lock --

    @Test
    fun `clear empties all structures under write lock`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e")
        storage.setMeta("key", "val".strVal)
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- Self-loop edges --

    @Test
    fun `self loop edge appears in both incoming and outgoing`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "loop")
        assertTrue(selfEdge in storage.getOutgoingEdges(n))
        assertTrue(selfEdge in storage.getIncomingEdges(n))
    }

    // -- Concurrent safety --

    @Test
    fun `concurrent deleteNode does not cause errors`() {
        val nodeCount = 100
        val nodeIds = (0 until nodeCount).map { storage.addNode() }
        for (i in 0 until nodeCount - 1) {
            storage.addEdge(nodeIds[i], nodeIds[i + 1], "e$i")
        }

        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val errors = AtomicInteger(0)

        for (t in 0 until 4) {
            executor.submit {
                try {
                    for (i in (t * 25) until ((t + 1) * 25)) {
                        try {
                            storage.deleteNode(nodeIds[i])
                        } catch (e: EntityNotExistException) {
                            // acceptable
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()
        assertEquals(0, errors.get())
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `no deadlock under mixed read-write operations`() {
        val node1 = storage.addNode(mapOf("counter" to 0.intVal))
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")

        val threadCount = 10
        val opsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val timeout = AtomicBoolean(false)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) { i ->
                        when (i % 4) {
                            0 -> storage.nodeIDs
                            1 -> storage.getNodeProperties(node1)
                            2 -> storage.setNodeProperties(node1, mapOf("counter" to i.intVal))
                            3 -> storage.getOutgoingEdges(node1)
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) timeout.set(true)
        executor.shutdownNow()

        assertFalse(timeout.get(), "Should not deadlock")
        assertEquals(0, errors.get())
    }

    // -- Snapshot behavior --

    @Test
    fun `nodeIDs returns snapshot not live view`() {
        storage.addNode()
        val snapshot = storage.nodeIDs
        storage.addNode()
        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }
}
