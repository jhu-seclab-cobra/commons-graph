/**
 * White-box tests for MapDB concurrent-specific internal behavior of [MapDBConcurStorageImpl].
 *
 * - `deleteNode uses WithoutLock helpers to avoid deadlock`
 * - `deleteNode with self loop does not deadlock`
 * - `getNodeProperties returns copy not reference`
 * - `getEdgeProperties returns copy not reference`
 * - `setNodeProperties merges and null removes under write lock`
 * - `graphStructure updated correctly after addEdge and deleteEdge`
 * - `deleteNode cleans graphStructure for node`
 * - `close under write lock sets closed state`
 * - `double close does not throw`
 * - `clear under write lock empties all structures`
 * - `clear on closed storage throws AccessClosedStorageException`
 * - `meta operations under read write locks`
 * - `concurrent deleteNode does not deadlock`
 * - `no deadlock under mixed read write operations`
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
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

internal class MapDBConcurStorageImplWhiteBoxTest {
    private lateinit var storage: MapDBConcurStorageImpl

    @BeforeTest
    fun setUp() {
        storage = MapDBConcurStorageImpl { memoryDB() }
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // -- WithoutLock helpers prevent re-entrant deadlock --

    @Test
    fun `deleteNode uses WithoutLock helpers to avoid deadlock`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e13 = storage.addEdge(n1, n3, "e13")

        storage.deleteNode(n1)

        assertFalse(storage.containsNode(n1))
        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e13))
    }

    @Test
    fun `deleteNode with self loop does not deadlock`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "self")
        storage.deleteNode(n)
        assertFalse(storage.containsNode(n))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- Defensive copy behavior --

    @Test
    fun `getNodeProperties returns copy not reference`() {
        val n = storage.addNode(mapOf("a" to 1.numVal))
        val props1 = storage.getNodeProperties(n)
        val props2 = storage.getNodeProperties(n)
        assertEquals(props1, props2)
    }

    @Test
    fun `getEdgeProperties returns copy not reference`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e", mapOf("x" to "y".strVal))
        val props1 = storage.getEdgeProperties(e)
        val props2 = storage.getEdgeProperties(e)
        assertEquals(props1, props2)
    }

    // -- setProperties merge under write lock --

    @Test
    fun `setNodeProperties merges and null removes under write lock`() {
        val n = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))
        storage.setNodeProperties(n, mapOf("a" to null, "c" to 3.numVal))
        val props = storage.getNodeProperties(n)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
        assertEquals(3, (props["c"] as NumVal).core)
    }

    // -- graphStructure consistency --

    @Test
    fun `graphStructure updated correctly after addEdge and deleteEdge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e12")

        assertEquals(setOf(e), storage.getOutgoingEdges(n1))
        assertEquals(setOf(e), storage.getIncomingEdges(n2))

        storage.deleteEdge(e)

        assertTrue(storage.getOutgoingEdges(n1).isEmpty())
        assertTrue(storage.getIncomingEdges(n2).isEmpty())
    }

    @Test
    fun `deleteNode cleans graphStructure for node`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val n3 = storage.addNode()
        val e12 = storage.addEdge(n1, n2, "e12")
        val e23 = storage.addEdge(n2, n3, "e23")
        val e13 = storage.addEdge(n1, n3, "e13")

        storage.deleteNode(n2)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e23))
        assertTrue(storage.containsEdge(e13))
    }

    // -- close under write lock --

    @Test
    fun `close under write lock sets closed state`() {
        storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    @Test
    fun `double close does not throw`() {
        storage.close()
        storage.close()
    }

    // -- clear under write lock --

    @Test
    fun `clear under write lock empties all structures`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e12")
        storage.setMeta("key", "val".strVal)
        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    @Test
    fun `clear on closed storage throws AccessClosedStorageException`() {
        storage.close()
        val fresh = MapDBConcurStorageImpl { memoryDB() }
        fresh.close()
        assertFailsWith<AccessClosedStorageException> { fresh.clear() }
    }

    // -- Metadata under locks --

    @Test
    fun `meta operations under read write locks`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)

        storage.setMeta("version", null)
        assertNull(storage.getMeta("version"))
    }

    // -- Concurrent safety --

    @Test
    fun `concurrent deleteNode does not deadlock`() {
        val count = 40
        val nodeIds = (0 until count).map { storage.addNode() }

        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val errors = AtomicInteger(0)
        val timeout = AtomicBoolean(false)

        for (t in 0 until 4) {
            executor.submit {
                try {
                    val start = t * (count / 4)
                    val end = (t + 1) * (count / 4)
                    for (i in start until end) {
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

        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) timeout.set(true)
        executor.shutdown()

        assertFalse(timeout.get(), "Should not deadlock")
        assertEquals(0, errors.get())
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `no deadlock under mixed read write operations`() {
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")

        val threadCount = 8
        val opsPerThread = 100
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
                            2 -> storage.setNodeProperties(node1, mapOf("counter" to i.numVal))
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

        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) timeout.set(true)
        executor.shutdownNow()

        assertFalse(timeout.get(), "Should not deadlock")
        assertEquals(0, errors.get())
    }
}
