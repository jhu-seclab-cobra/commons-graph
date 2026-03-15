package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
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
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge13 = storage.addEdge(node1, node3, "e13")

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge13))
    }

    @Test
    fun `test deleteNode with self loop does not deadlock`() {
        val node1 = storage.addNode()
        val selfEdge = storage.addEdge(node1, node1, "self")

        storage.deleteNode(node1)

        assertFalse(storage.containsNode(node1))
        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- getNodeProperties returns defensive copy via toMap() --

    @Test
    fun `test getNodeProperties returns copy not reference`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal))

        val props1 = storage.getNodeProperties(node1)
        val props2 = storage.getNodeProperties(node1)

        assertEquals(props1, props2)
    }

    @Test
    fun `test getEdgeProperties returns copy not reference`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12", mapOf("x" to "y".strVal))

        val props1 = storage.getEdgeProperties(edge12)
        val props2 = storage.getEdgeProperties(edge12)

        assertEquals(props1, props2)
    }

    // -- setProperties merge+filterValues under write lock --

    @Test
    fun `test setNodeProperties merges and null removes under write lock`() {
        val node1 = storage.addNode(mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(node1, mapOf("a" to null, "c" to 3.numVal))

        val props = storage.getNodeProperties(node1)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
        assertEquals(3, (props["c"] as NumVal).core)
    }

    // -- graphStructure consistency --

    @Test
    fun `test graphStructure updated correctly after addEdge and deleteEdge`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")

        assertEquals(setOf(edge12), storage.getOutgoingEdges(node1))
        assertEquals(setOf(edge12), storage.getIncomingEdges(node2))

        storage.deleteEdge(edge12)

        assertTrue(storage.getOutgoingEdges(node1).isEmpty())
        assertTrue(storage.getIncomingEdges(node2).isEmpty())
    }

    @Test
    fun `test deleteNode cleans graphStructure for node`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        val node3 = storage.addNode()
        val edge12 = storage.addEdge(node1, node2, "e12")
        val edge23 = storage.addEdge(node2, node3, "e23")
        val edge13 = storage.addEdge(node1, node3, "e13")

        storage.deleteNode(node2)

        assertFalse(storage.containsEdge(edge12))
        assertFalse(storage.containsEdge(edge23))
        assertTrue(storage.containsEdge(edge13))
    }

    // -- close under write lock --

    @Test
    fun `test close under write lock sets closed state`() {
        val node1 = storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(node1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    @Test
    fun `test double close does not throw`() {
        storage.close()
        storage.close()
    }

    // -- clear under write lock with DBException handling --

    @Test
    fun `test clear under write lock empties all structures`() {
        val node1 = storage.addNode()
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "e12")
        storage.setMeta("key", "val".strVal)

        storage.clear()
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
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until count) {
            nodeIds.add(storage.addNode())
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
                            storage.deleteNode(nodeIds[i])
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
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    // -- getNodeProperty --

    @Test
    fun `test getNodeProperty returns existing property`() {
        val nodeId = storage.addNode(mapOf("color" to "red".strVal))

        val result = storage.getNodeProperty(nodeId, "color")

        assertEquals("red", (result as StrVal).core)
    }

    @Test
    fun `test getNodeProperty returns null for absent property`() {
        val nodeId = storage.addNode(mapOf("color" to "red".strVal))

        val result = storage.getNodeProperty(nodeId, "missing")

        assertNull(result)
    }

    @Test
    fun `test getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(-1, "key") }
    }

    // -- getEdgeProperty --

    @Test
    fun `test getEdgeProperty returns existing property`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel", mapOf("weight" to 42.numVal))

        val result = storage.getEdgeProperty(edgeId, "weight")

        assertEquals(42, (result as NumVal).core)
    }

    @Test
    fun `test getEdgeProperty returns null for absent property`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel", mapOf("weight" to 42.numVal))

        val result = storage.getEdgeProperty(edgeId, "missing")

        assertNull(result)
    }

    @Test
    fun `test getEdgeProperty throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperty(-1, "key") }
    }

    // -- transferTo --

    @Test
    fun `test transferTo copies all data`() {
        val node1 = storage.addNode(mapOf("x" to 1.numVal))
        val node2 = storage.addNode()
        storage.addEdge(node1, node2, "link", mapOf("w" to 2.numVal))
        storage.setMeta("version", "1".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("1", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    @Test
    fun `test transferTo throws AccessClosedStorageException when closed`() {
        storage.close()
        val target = NativeStorageImpl()
        assertFailsWith<AccessClosedStorageException> { storage.transferTo(target) }
        target.close()
    }

    // -- getEdgeSrc, getEdgeDst, getEdgeType --

    @Test
    fun `test getEdgeSrc returns correct source`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")

        assertEquals(src, storage.getEdgeSrc(edgeId))
    }

    @Test
    fun `test getEdgeDst returns correct destination`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")

        assertEquals(dst, storage.getEdgeDst(edgeId))
    }

    @Test
    fun `test getEdgeType returns correct type`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "myType")

        assertEquals("myType", storage.getEdgeType(edgeId))
    }

    // -- Closed-state checks for remaining operations --

    @Test
    fun `test getNodeProperty throws AccessClosedStorageException when closed`() {
        val nodeId = storage.addNode(mapOf("x" to 1.numVal))
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperty(nodeId, "x") }
    }

    @Test
    fun `test getEdgeProperty throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperty(edgeId, "k") }
    }

    @Test
    fun `test setEdgeProperties throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setEdgeProperties(edgeId, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteEdge throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.deleteEdge(edgeId) }
    }

    @Test
    fun `test getEdgeSrc throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeSrc(edgeId) }
    }

    @Test
    fun `test getEdgeDst throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeDst(edgeId) }
    }

    @Test
    fun `test getEdgeType throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeType(edgeId) }
    }

    @Test
    fun `test getIncomingEdges throws AccessClosedStorageException when closed`() {
        val nodeId = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getIncomingEdges(nodeId) }
    }

    @Test
    fun `test getOutgoingEdges throws AccessClosedStorageException when closed`() {
        val nodeId = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getOutgoingEdges(nodeId) }
    }

    @Test
    fun `test getEdgeProperties throws AccessClosedStorageException when closed`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val edgeId = storage.addEdge(src, dst, "rel")
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getEdgeProperties(edgeId) }
    }

    @Test
    fun `test getNodeProperties throws AccessClosedStorageException when closed`() {
        val nodeId = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getNodeProperties(nodeId) }
    }

    @Test
    fun `test setNodeProperties throws AccessClosedStorageException when closed`() {
        val nodeId = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setNodeProperties(nodeId, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteNode throws AccessClosedStorageException when closed`() {
        val nodeId = storage.addNode()
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.deleteNode(nodeId) }
    }

    @Test
    fun `test metaNames throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
    }

    @Test
    fun `test getMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("key") }
    }

    @Test
    fun `test setMeta throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("key", 1.numVal) }
    }

    @Test
    fun `test edgeIDs throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
    }

    @Test
    fun `test containsEdge throws AccessClosedStorageException when closed`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.containsEdge(0) }
    }

    // -- Entity-not-exist branches --

    @Test
    fun `test getNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(999) }
    }

    @Test
    fun `test getEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(999) }
    }

    @Test
    fun `test setEdgeProperties throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.setEdgeProperties(999, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test getEdgeSrc throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeSrc(999) }
    }

    @Test
    fun `test getEdgeDst throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeDst(999) }
    }

    @Test
    fun `test getEdgeType throws EntityNotExistException for missing edge`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeType(999) }
    }

    @Test
    fun `test getIncomingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(999) }
    }

    @Test
    fun `test getOutgoingEdges throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(999) }
    }

    @Test
    fun `test setNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.setNodeProperties(999, mapOf("k" to 1.numVal)) }
    }

    @Test
    fun `test deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when src missing`() {
        val dst = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(999, dst, "rel") }
    }

    @Test
    fun `test addEdge throws EntityNotExistException when dst missing`() {
        val src = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(src, 999, "rel") }
    }

    // -- transferTo id remapping: edges connect via remapped node IDs --

    @Test
    fun `test transferTo remaps node IDs and connects edges correctly`() {
        val src = storage.addNode(mapOf("a" to 1.numVal))
        val dst = storage.addNode(mapOf("b" to 2.numVal))
        storage.addEdge(src, dst, "link", mapOf("w" to 3.numVal))
        storage.setMeta("meta", "val".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("val".strVal, target.getMeta("meta"))
        val tEdge = target.edgeIDs.first()
        assertTrue(target.getEdgeSrc(tEdge) in target.nodeIDs)
        assertTrue(target.getEdgeDst(tEdge) in target.nodeIDs)
        target.close()
    }
}
