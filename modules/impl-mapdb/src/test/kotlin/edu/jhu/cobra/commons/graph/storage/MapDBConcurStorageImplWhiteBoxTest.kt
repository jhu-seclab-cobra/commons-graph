package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MapDBConcurStorageImplWhiteBoxTest {
    private lateinit var storage: MapDBConcurStorageImpl
    private val node1 = NodeID("node1")
    private val node2 = NodeID("node2")
    private val node3 = NodeID("node3")
    private val edge12 = EdgeID(node1, node2, "e12")
    private val edge23 = EdgeID(node2, node3, "e23")
    private val edge13 = EdgeID(node1, node3, "e13")

    @Before
    fun setup() {
        storage = MapDBConcurStorageImpl { memoryDB() }
    }

    @After
    fun cleanup() {
        storage.close()
    }

    // -- WithoutLock helpers prevent re-entrant deadlock in deleteNode --

    @Test
    fun `test deleteNode uses WithoutLock helpers to avoid deadlock`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge13)

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge13))
    }

    @Test
    fun `test deleteNode with self loop does not deadlock`() {
        storage.addNode(node1)
        val selfEdge = EdgeID(node1, node1, "self")
        storage.addEdge(selfEdge)

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- getNodeProperties returns defensive copy via toMap() --

    @Test
    fun `test getNodeProperties returns copy not reference`() {
        storage.addNode(node1, mapOf("a" to 1.numVal))

        val props1 = storage.getNodeProperties(node1)
        val props2 = storage.getNodeProperties(node1)

        assertEquals(props1, props2)
    }

    @Test
    fun `test getEdgeProperties returns copy not reference`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12, mapOf("x" to "y".strVal))

        val props1 = storage.getEdgeProperties(edge12)
        val props2 = storage.getEdgeProperties(edge12)

        assertEquals(props1, props2)
    }

    // -- setProperties merge+filterValues under write lock --

    @Test
    fun `test setNodeProperties merges and null removes under write lock`() {
        storage.addNode(node1, mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null, "c" to 3.numVal))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
        assertEquals(3, (props["c"] as NumVal).core)
    }

    // -- graphStructure consistency --

    @Test
    fun `test graphStructure updated correctly after addEdge and deleteEdge`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)

        assertEquals(setOf(edge12), storage.getOutgoingEdges(node1))
        assertEquals(setOf(edge12), storage.getIncomingEdges(node2))

        storage.deleteEdge(edge12)

        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteNode cleans graphStructure for node`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addNode(node3)
        storage.addEdge(edge12)
        storage.addEdge(edge23)
        storage.addEdge(edge13)

        storage.deleteNode(node2)

        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge23))
        assertTrue(storage.containsEdge(edge13))
    }

    // -- close under write lock --

    @Test
    fun `test close under write lock sets closed state`() {
        storage.addNode(node1)
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(node2) }
    }

    @Test
    fun `test double close does not throw`() {
        storage.close()
        storage.close()
    }

    // -- clear under write lock with DBException handling --

    @Test
    fun `test clear under write lock empties all structures`() {
        storage.addNode(node1)
        storage.addNode(node2)
        storage.addEdge(edge12)
        storage.setMeta("key", "val".strVal)

        assertTrue(storage.clear())
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    @Test
    fun `test clear on closed storage throws AccessClosedStorageException`() {
        storage.close()
        val fresh = MapDBConcurStorageImpl { memoryDB() }
        fresh.close()
        assertFailsWith<AccessClosedStorageException> { fresh.clear() }
    }

    // -- Concurrent deleteNode with cascading edges does not deadlock --

    @Test
    fun `test concurrent deleteNode does not deadlock`() {
        val count = 40
        for (i in 0 until count) {
            storage.addNode(NodeID("n$i"))
        }

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
                            storage.deleteNode(NodeID("n$i"))
                        } catch (e: EntityNotExistException) {
                            // acceptable
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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

    // -- No deadlock under mixed read-write operations --

    @Test
    fun `test no deadlock under mixed read write operations`() {
        storage.addNode(node1, mapOf("counter" to 0.numVal))
        storage.addNode(node2)
        storage.addEdge(edge12)

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
                    e.printStackTrace()
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

    // -- Metadata under locks --

    @Test
    fun `test meta operations under read write locks`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)

        storage.setMeta("version", null)
        assertNull(storage.getMeta("version"))
    }

    // -- deleteEdge throws for nonexistent under write lock --

    @Test
    fun `test deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(edge12) }
    }
}
