package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.numVal
import edu.jhu.cobra.commons.value.strVal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Neo4jConcurStorageImplWhiteBoxTest {
    private lateinit var storage: Neo4jConcurStorageImpl
    private lateinit var graphDir: Path

    @BeforeTest
    fun setup() {
        graphDir = Files.createTempDirectory("neo4j-concur-wb-test")
        storage = Neo4jConcurStorageImpl(graphDir)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    // -- Node/edge mapping cache consistency under lock --

    @Test
    fun `test addNode populates node mapping cache`() {
        val n = storage.addNode()
        assertTrue(storage.containsNode(n))
        assertEquals(setOf(n), storage.nodeIDs)
    }

    @Test
    fun `test deleteNode removes from node mapping cache`() {
        val n = storage.addNode()
        storage.deleteNode(n)
        assertFalse(storage.containsNode(n))
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `test addEdge populates edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        assertTrue(storage.containsEdge(e))
        assertEquals(setOf(e), storage.edgeIDs)
    }

    @Test
    fun `test deleteNode removes associated edges from edge mapping`() {
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
    fun `test writeTx rolls back on exception`() {
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
    fun `test init block loads existing nodes and edges from database`() {
        val n1 = storage.addNode(mapOf("data" to "d1".strVal))
        val n2 = storage.addNode(mapOf("data" to "d2".strVal))
        val e = storage.addEdge(n1, n2, "link", mapOf("weight" to 1.numVal))
        storage.close()

        val reloaded = Neo4jConcurStorageImpl(graphDir)

        assertTrue(reloaded.containsNode(n1))
        assertTrue(reloaded.containsNode(n2))
        assertTrue(reloaded.containsEdge(e))
        assertEquals("d1", (reloaded.getNodeProperties(n1)["data"] as StrVal).core)
        assertEquals(1, (reloaded.getEdgeProperties(e)["weight"] as NumVal).core)

        reloaded.close()
    }

    // -- META_ID property filtering --

    @Test
    fun `test getNodeProperties excludes META_ID property`() {
        val n = storage.addNode(mapOf("visible" to "yes".strVal))
        val props = storage.getNodeProperties(n)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
    }

    // -- Metadata stored in-memory under lock --

    @Test
    fun `test meta operations under lock`() {
        storage.setMeta("version", "1.0".strVal)
        assertEquals("1.0", (storage.getMeta("version") as StrVal).core)
        assertTrue("version" in storage.metaNames)
    }

    @Test
    fun `test setMeta null removes entry`() {
        storage.setMeta("key", "val".strVal)
        storage.setMeta("key", null)
        assertNull(storage.getMeta("key"))
        assertFalse("key" in storage.metaNames)
    }

    @Test
    fun `test meta not persisted across storage instances`() {
        storage.setMeta("key", "val".strVal)
        storage.close()

        val reloaded = Neo4jConcurStorageImpl(graphDir)
        assertNull(reloaded.getMeta("key"))
        reloaded.close()
    }

    // -- Close under write lock --

    @Test
    fun `test close acquires write lock and sets isClosed`() {
        storage.addNode()
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(-1) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode() }
    }

    // -- Clear under write lock --

    @Test
    fun `test clear empties all structures under write lock`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        storage.addEdge(n1, n2, "e")
        storage.setMeta("key", "val".strVal)

        storage.clear()
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- Entity existence contracts --

    @Test
    fun `test addEdge missing src throws EntityNotExistException`() {
        val dst = storage.addNode()
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(-1, dst, "e")
        }
    }

    @Test
    fun `test deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }

    // -- Self-loop edges --

    @Test
    fun `test self loop edge appears in both incoming and outgoing`() {
        val n = storage.addNode()
        val selfEdge = storage.addEdge(n, n, "loop")
        assertTrue(selfEdge in storage.getOutgoingEdges(n))
        assertTrue(selfEdge in storage.getIncomingEdges(n))
    }

    // -- Concurrent deleteNode safety --

    @Test
    fun `test concurrent deleteNode does not cause errors`() {
        val nodeCount = 100
        val nodeIds = mutableListOf<Int>()
        for (i in 0 until nodeCount) {
            nodeIds.add(storage.addNode())
        }
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
                            // acceptable: edge cascade may have triggered from another thread
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

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get())
        assertEquals(0, storage.nodeIDs.size)
    }

    // -- No deadlock under mixed read-write operations --

    @Test
    fun `test no deadlock under mixed read-write operations`() {
        val node1 = storage.addNode(mapOf("counter" to 0.numVal))
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

        val completed = latch.await(30, TimeUnit.SECONDS)
        if (!completed) timeout.set(true)
        executor.shutdownNow()

        assertFalse(timeout.get(), "Should not deadlock")
        assertEquals(0, errors.get())
    }

    // -- nodeIDs returns snapshot under read lock --

    @Test
    fun `test nodeIDs returns snapshot not live view`() {
        val node1 = storage.addNode()
        val snapshot = storage.nodeIDs

        storage.addNode()

        assertEquals(1, snapshot.size)
        assertEquals(2, storage.nodeIDs.size)
    }

    // -- setEdgeProperties --

    @Test
    fun `test setEdgeProperties sets property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")

        storage.setEdgeProperties(e, mapOf("weight" to 42.numVal))

        val props = storage.getEdgeProperties(e)
        assertEquals(42, (props["weight"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties with null removes property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))

        storage.setEdgeProperties(e, mapOf("x" to null))

        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    @Test
    fun `test setEdgeProperties nonexistent edge throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(-1, mapOf("key" to "val".strVal))
        }
    }

    // -- deleteEdge --

    @Test
    fun `test deleteEdge removes edge from edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")

        storage.deleteEdge(e)

        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `test deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.deleteEdge(-1)
        }
    }

    @Test
    fun `test deleteEdge leaves nodes intact`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")

        storage.deleteEdge(e)

        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
    }

    // -- getEdgeSrc / getEdgeDst / getEdgeType --

    @Test
    fun `test getEdgeSrc returns correct source node`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val e = storage.addEdge(src, dst, "rel")

        assertEquals(src, storage.getEdgeSrc(e))
    }

    @Test
    fun `test getEdgeSrc nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeSrc(-1)
        }
    }

    @Test
    fun `test getEdgeDst returns correct destination node`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        val e = storage.addEdge(src, dst, "rel")

        assertEquals(dst, storage.getEdgeDst(e))
    }

    @Test
    fun `test getEdgeDst nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeDst(-1)
        }
    }

    @Test
    fun `test getEdgeType returns correct type string`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "FOLLOWS")

        assertEquals("FOLLOWS", storage.getEdgeType(e))
    }

    @Test
    fun `test getEdgeType nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeType(-1)
        }
    }

    // -- getNodeProperties / getEdgeProperties nonexistent --

    @Test
    fun `test getNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.getNodeProperties(-1)
        }
    }

    @Test
    fun `test getEdgeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.getEdgeProperties(-1)
        }
    }

    @Test
    fun `test setNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("key" to "val".strVal))
        }
    }

    // -- transferTo --

    @Test
    fun `test transferTo copies nodes edges and meta to target`() {
        val n1 = storage.addNode(mapOf("label" to "A".strVal))
        val n2 = storage.addNode(mapOf("label" to "B".strVal))
        storage.addEdge(n1, n2, "LINKS", mapOf("w" to 1.numVal))
        storage.setMeta("version", "2".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("2", (target.getMeta("version") as StrVal).core)
    }

    @Test
    fun `test transferTo throws AccessClosedStorageException when closed`() {
        storage.close()

        assertFailsWith<AccessClosedStorageException> {
            storage.transferTo(NativeStorageImpl())
        }
    }

    // -- double-close is safe --

    @Test
    fun `test double close does not throw`() {
        storage.close()
        storage.close()  // second close must not throw or attempt another shutdown
    }
}
