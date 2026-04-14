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

/**
 * Black-box tests for `NativeConcurStorageImpl` verifying the `IStorage` contract
 * and thread-safety guarantees (ReentrantReadWriteLock).
 *
 * IStorage contract tests:
 * - `addNode returns unique Int ID` -- node creation
 * - `addNode with properties stores initial properties` -- property creation
 * - `containsNode returns true for existing and false for absent` -- lookup
 * - `nodeIDs returns all added node IDs` -- enumeration
 * - `getNodeProperties returns stored properties` -- property retrieval
 * - `getNodeProperty returns single value or null for absent key` -- single-key lookup
 * - `setNodeProperties adds updates and deletes atomically` -- atomic property mutation
 * - `deleteNode removes node and cascades incident edge deletion` -- cascade delete
 * - `deleteNode throws EntityNotExistException for absent node` -- error path
 * - `addEdge returns unique Int ID between existing nodes` -- edge creation
 * - `addEdge throws EntityNotExistException when src or dst missing` -- missing endpoint
 * - `containsEdge returns true for existing and false for absent` -- lookup
 * - `edgeIDs returns all added edge IDs` -- enumeration
 * - `getEdgeStructure returns src dst and tag` -- structural metadata
 * - `getEdgeProperties returns stored properties` -- property retrieval
 * - `getEdgeProperty returns single value or null for absent key` -- single-key lookup
 * - `setEdgeProperties adds updates and deletes atomically` -- atomic property mutation
 * - `deleteEdge removes edge and updates adjacency` -- edge deletion
 * - `deleteEdge throws EntityNotExistException for absent edge` -- error path
 * - `getIncomingEdges returns edges targeting node` -- adjacency
 * - `getOutgoingEdges returns edges originating from node` -- adjacency
 * - `self-loop appears in both incoming and outgoing` -- self-loop adjacency
 * - `getMeta returns stored value or null for absent key` -- metadata
 * - `setMeta with null deletes metadata entry` -- metadata deletion
 * - `metaNames returns all metadata keys` -- metadata enumeration
 * - `clear removes all nodes edges and metadata` -- full reset
 * - `transferTo copies all data and returns node ID mapping` -- transfer
 * - `close prevents subsequent operations` -- lifecycle guard
 *
 * Thread-safety tests:
 * - `concurrent reads do not deadlock` -- parallel read lock acquisition
 * - `concurrent writes produce consistent node count` -- parallel write lock
 * - `concurrent read-write does not corrupt state` -- mixed lock acquisition
 */
internal class NativeConcurStorageImplTest {
    private lateinit var storage: NativeConcurStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeConcurStorageImpl()
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region Node CRUD

    @Test
    fun `addNode returns unique Int ID`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        assertTrue(n1 != n2)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `addNode with properties stores initial properties`() {
        val nodeId = storage.addNode(mapOf("name" to "A".strVal, "age" to 25.numVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("A", (props["name"] as StrVal).core)
        assertEquals(25, (props["age"] as NumVal).core)
    }

    @Test
    fun `containsNode returns true for existing and false for absent`() {
        val nodeId = storage.addNode()
        assertTrue(storage.containsNode(nodeId))
        assertFalse(storage.containsNode(999))
    }

    @Test
    fun `nodeIDs returns all added node IDs`() {
        val (n1, n2, n3) = StorageTestUtils.addTestNodes(storage)
        assertEquals(setOf(n1, n2, n3), storage.nodeIDs)
    }

    @Test
    fun `getNodeProperties returns stored properties`() {
        val nodeId = storage.addNode(mapOf("k" to "v".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("v", (props["k"] as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns single value or null for absent key`() {
        val nodeId = storage.addNode(mapOf("name" to "test".strVal))
        assertEquals("test", (storage.getNodeProperty(nodeId, "name") as StrVal).core)
        assertNull(storage.getNodeProperty(nodeId, "absent"))
    }

    @Test
    fun `setNodeProperties adds updates and deletes atomically`() {
        val nodeId = storage.addNode(mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.setNodeProperties(nodeId, mapOf("a" to "updated".strVal, "b" to null, "c" to "3".strVal))
        val props = storage.getNodeProperties(nodeId)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertFalse(props.containsKey("b"))
        assertEquals("3", (props["c"] as StrVal).core)
    }

    @Test
    fun `deleteNode removes node and cascades incident edge deletion`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        storage.deleteNode(n1)
        assertFalse(storage.containsNode(n1))
        assertFalse(storage.containsEdge(edgeId))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for absent node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(999) }
    }

    // endregion

    // region Edge CRUD

    @Test
    fun `addEdge returns unique Int ID between existing nodes`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e1 = storage.addEdge(n1, n2, "t1")
        val e2 = storage.addEdge(n1, n2, "t2")
        assertTrue(e1 != e2)
        assertTrue(storage.containsEdge(e1))
        assertTrue(storage.containsEdge(e2))
    }

    @Test
    fun `addEdge throws EntityNotExistException when src or dst missing`() {
        val n1 = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(999, n1, "rel") }
        assertFailsWith<EntityNotExistException> { storage.addEdge(n1, 999, "rel") }
    }

    @Test
    fun `containsEdge returns true for existing and false for absent`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(edgeId))
        assertFalse(storage.containsEdge(999))
    }

    @Test
    fun `edgeIDs returns all added edge IDs`() {
        val (n1, n2, n3) = StorageTestUtils.addTestNodes(storage)
        val e1 = storage.addEdge(n1, n2, StorageTestUtils.EDGE_TAG_1)
        val e2 = storage.addEdge(n2, n3, StorageTestUtils.EDGE_TAG_2)
        assertEquals(setOf(e1, e2), storage.edgeIDs)
    }

    @Test
    fun `getEdgeStructure returns src dst and tag`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "tag")
        val structure = storage.getEdgeStructure(edgeId)
        assertEquals(n1, structure.src)
        assertEquals(n2, structure.dst)
        assertEquals("tag", structure.tag)
    }

    @Test
    fun `getEdgeProperties returns stored properties`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        assertEquals(1, (storage.getEdgeProperties(edgeId)["w"] as NumVal).core)
    }

    @Test
    fun `getEdgeProperty returns single value or null for absent key`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.5.numVal))
        assertEquals(1.5, (storage.getEdgeProperty(edgeId, "w") as NumVal).core)
        assertNull(storage.getEdgeProperty(edgeId, "absent"))
    }

    @Test
    fun `setEdgeProperties adds updates and deletes atomically`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("a" to "1".strVal, "b" to "2".strVal))
        storage.setEdgeProperties(edgeId, mapOf("a" to "updated".strVal, "b" to null))
        val props = storage.getEdgeProperties(edgeId)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertFalse(props.containsKey("b"))
    }

    @Test
    fun `deleteEdge removes edge and updates adjacency`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(edgeId)
        assertFalse(storage.containsEdge(edgeId))
        assertTrue(storage.getOutgoingEdges(n1).isEmpty())
        assertTrue(storage.getIncomingEdges(n2).isEmpty())
    }

    @Test
    fun `deleteEdge throws EntityNotExistException for absent edge`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(999) }
    }

    // endregion

    // region Adjacency

    @Test
    fun `getIncomingEdges returns edges targeting node`() {
        val (n1, n2, n3) = StorageTestUtils.addTestNodes(storage)
        val e1 = storage.addEdge(n1, n3, "a")
        val e2 = storage.addEdge(n2, n3, "b")
        assertEquals(setOf(e1, e2), storage.getIncomingEdges(n3))
    }

    @Test
    fun `getOutgoingEdges returns edges originating from node`() {
        val (n1, n2, n3) = StorageTestUtils.addTestNodes(storage)
        val e1 = storage.addEdge(n1, n2, "a")
        val e2 = storage.addEdge(n1, n3, "b")
        assertEquals(setOf(e1, e2), storage.getOutgoingEdges(n1))
    }

    @Test
    fun `self-loop appears in both incoming and outgoing`() {
        val node = storage.addNode()
        val selfEdge = storage.addEdge(node, node, "self")
        assertTrue(storage.getOutgoingEdges(node).contains(selfEdge))
        assertTrue(storage.getIncomingEdges(node).contains(selfEdge))
    }

    // endregion

    // region Metadata

    @Test
    fun `getMeta returns stored value or null for absent key`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertNull(storage.getMeta("absent"))
    }

    @Test
    fun `setMeta with null deletes metadata entry`() {
        storage.setMeta("key", "value".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
    }

    @Test
    fun `metaNames returns all metadata keys`() {
        storage.setMeta("k1", "v1".strVal)
        storage.setMeta("k2", "v2".strVal)
        assertEquals(setOf("k1", "k2"), storage.metaNames)
    }

    // endregion

    // region Lifecycle

    @Test
    fun `clear removes all nodes edges and metadata`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "rel")
        storage.setMeta("k", "v".strVal)
        storage.clear()
        assertTrue(storage.nodeIDs.isEmpty())
        assertTrue(storage.edgeIDs.isEmpty())
        assertNull(storage.getMeta("k"))
    }

    @Test
    fun `transferTo copies all data and returns node ID mapping`() {
        val n1 = storage.addNode(mapOf("name" to "A".strVal))
        val n2 = storage.addNode(mapOf("name" to "B".strVal))
        storage.addEdge(n1, n2, "rel", mapOf("w" to 1.numVal))
        storage.setMeta("version", "1.0".strVal)

        val target = NativeConcurStorageImpl()
        val idMap = storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertTrue(target.containsNode(idMap[n1]!!))
        assertTrue(target.containsNode(idMap[n2]!!))
        assertEquals("1.0", (target.getMeta("version") as StrVal).core)
        target.close()
    }

    @Test
    fun `close prevents subsequent operations`() {
        storage.close()
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.clear() }
    }

    // endregion

    // region Thread safety

    @Test
    fun `concurrent reads do not deadlock`() {
        val (n1, n2, n3) = StorageTestUtils.addTestNodes(storage)
        storage.addEdge(n1, n2, StorageTestUtils.EDGE_TAG_1)
        storage.addEdge(n2, n3, StorageTestUtils.EDGE_TAG_2)

        val threadCount = 10
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)
        val timedOut = AtomicBoolean(false)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        storage.containsNode(n1)
                        storage.getNodeProperties(n1)
                        storage.getOutgoingEdges(n1)
                        storage.getIncomingEdges(n2)
                    }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        executor.shutdown()
        if (!completed) timedOut.set(true)

        assertFalse(timedOut.get(), "Concurrent reads timed out, potential deadlock")
        assertEquals(0, errors.get())
    }

    @Test
    fun `concurrent writes produce consistent node count`() {
        val threadCount = 10
        val nodesPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(nodesPerThread) {
                        storage.addNode(mapOf("thread" to t.numVal))
                    }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        assertEquals(threadCount * nodesPerThread, storage.nodeIDs.size)
    }

    @Test
    fun `concurrent read-write does not corrupt state`() {
        val node = storage.addNode(mapOf("counter" to 0.numVal))
        val threadCount = 4
        val opsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount * 2)
        val latch = CountDownLatch(threadCount * 2)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        val current = storage.getNodeProperties(node)["counter"] as NumVal
                        storage.setNodeProperties(node, mapOf("counter" to (current.core.toInt() + 1).numVal))
                    }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        val value = storage.getNodeProperties(node)["counter"] as NumVal
                        assertTrue(value.core.toInt() >= 0)
                    }
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        val finalValue = (storage.getNodeProperties(node)["counter"] as NumVal).core.toInt()
        assertTrue(finalValue > 0)
    }

    // endregion
}
