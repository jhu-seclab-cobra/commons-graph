package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.AccessClosedStorageException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.EntityAlreadyExistException
import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Neo4jStorageImplWhiteBoxTest {
    private lateinit var storage: Neo4jStorageImpl
    private lateinit var graphDir: Path

    @BeforeTest
    fun setup() {
        graphDir = Files.createTempDirectory("neo4j-wb-test")
        storage = Neo4jStorageImpl(graphDir)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        graphDir.toFile().deleteRecursively()
    }

    // -- node2ElementIdMap cache consistency --

    @Test
    fun `test addNode populates node2ElementIdMap cache`() {
        val n = NodeID("test")
        storage.addNode(n)

        assertTrue(storage.containsNode(n))
        assertEquals(setOf(n), storage.nodeIDs)
    }

    @Test
    fun `test deleteNode removes from node2ElementIdMap cache`() {
        val n = NodeID("test")
        storage.addNode(n)
        storage.deleteNode(n)

        assertFalse(storage.containsNode(n))
        assertEquals(0, storage.nodeIDs.size)
    }

    // -- edge2ElementIdMap cache consistency --

    @Test
    fun `test addEdge populates edge2ElementIdMap cache`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        val e = EdgeID(n1, n2, "rel")
        storage.addEdge(e)

        assertTrue(storage.containsEdge(e))
        assertEquals(setOf(e), storage.edgeIDs)
    }

    @Test
    fun `test deleteEdge removes from edge2ElementIdMap cache`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        val e = EdgeID(n1, n2, "rel")
        storage.addEdge(e)
        storage.deleteEdge(e)

        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    // -- deleteNode cascades edge removal from cache --

    @Test
    fun `test deleteNode removes associated edges from edge2ElementIdMap`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        val n3 = NodeID("n3")
        storage.addNode(n1)
        storage.addNode(n2)
        storage.addNode(n3)
        val e12 = EdgeID(n1, n2, "e12")
        val e13 = EdgeID(n1, n3, "e13")
        val e23 = EdgeID(n2, n3, "e23")
        storage.addEdge(e12)
        storage.addEdge(e13)
        storage.addEdge(e23)

        storage.deleteNode(n1)

        assertFalse(storage.containsEdge(e12))
        assertFalse(storage.containsEdge(e13))
        assertTrue(storage.containsEdge(e23))
    }

    // -- readTx/writeTx transaction semantics --

    @Test
    fun `test writeTx rolls back on exception`() {
        val n = NodeID("rollback-test")
        storage.addNode(n, mapOf("before" to "original".strVal))

        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(n, mapOf("__meta_id__" to "hack".strVal))
        }

        val props = storage.getNodeProperties(n)
        assertEquals("original", (props["before"] as StrVal).core)
        assertNull(props["__meta_id__"])
    }

    @Test
    fun `test readTx succeeds for read operations`() {
        val n = NodeID("read-test")
        storage.addNode(n, mapOf("key" to "value".strVal))

        val props = storage.getNodeProperties(n)
        assertEquals("value", (props["key"] as StrVal).core)
    }

    // -- Lazy database initialization --

    @Test
    fun `test storage works with fresh empty directory`() {
        assertEquals(0, storage.nodeIDs.size)

        storage.addNode(NodeID("test"))
        assertEquals(1, storage.nodeIDs.size)
    }

    // -- Init block loads existing data --

    @Test
    fun `test init block loads existing nodes and edges from database`() {
        val n1 = NodeID("persistent1")
        val n2 = NodeID("persistent2")
        storage.addNode(n1, mapOf("data" to "d1".strVal))
        storage.addNode(n2, mapOf("data" to "d2".strVal))
        val e = EdgeID(n1, n2, "link")
        storage.addEdge(e, mapOf("weight" to 1.numVal))
        storage.close()

        val reloaded = Neo4jStorageImpl(graphDir)

        assertTrue(reloaded.containsNode(n1))
        assertTrue(reloaded.containsNode(n2))
        assertTrue(reloaded.containsEdge(e))
        assertEquals("d1", (reloaded.getNodeProperties(n1)["data"] as StrVal).core)
        assertEquals(1, (reloaded.getEdgeProperties(e)["weight"] as NumVal).core)

        reloaded.close()
    }

    // -- META_ID property filtering in keys extension --

    @Test
    fun `test getNodeProperties excludes META_ID property`() {
        val n = NodeID("meta-test")
        storage.addNode(n, mapOf("visible" to "yes".strVal))

        val props = storage.getNodeProperties(n)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
        assertEquals("yes", (props["visible"] as StrVal).core)
    }

    @Test
    fun `test getEdgeProperties excludes META_ID property`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        val e = EdgeID(n1, n2, "rel")
        storage.addEdge(e, mapOf("visible" to "yes".strVal))

        val props = storage.getEdgeProperties(e)
        assertNull(props["__meta_id__"])
        assertEquals(1, props.size)
    }

    // -- InvalidPropNameException for reserved __meta_id__ --

    @Test
    fun `test setNodeProperties throws InvalidPropNameException for META_ID`() {
        val n = NodeID("test")
        storage.addNode(n)

        assertFailsWith<InvalidPropNameException> {
            storage.setNodeProperties(n, mapOf("__meta_id__" to "value".strVal))
        }
    }

    @Test
    fun `test setEdgeProperties throws InvalidPropNameException for META_ID`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        val e = EdgeID(n1, n2, "rel")
        storage.addEdge(e)

        assertFailsWith<InvalidPropNameException> {
            storage.setEdgeProperties(e, mapOf("__meta_id__" to "value".strVal))
        }
    }

    // -- setNodeProperties null removes property --

    @Test
    fun `test setNodeProperties with null removes property from Neo4j`() {
        val n = NodeID("test")
        storage.addNode(n, mapOf("a" to 1.numVal, "b" to 2.numVal))

        storage.setNodeProperties(n, mapOf("a" to null))

        val props = storage.getNodeProperties(n)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as NumVal).core)
    }

    @Test
    fun `test setEdgeProperties with null removes property from Neo4j`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        val e = EdgeID(n1, n2, "rel")
        storage.addEdge(e, mapOf("x" to "y".strVal, "z" to "w".strVal))

        storage.setEdgeProperties(e, mapOf("x" to null))

        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    // -- Self-loop edges --

    @Test
    fun `test self loop edge appears in both incoming and outgoing`() {
        val n = NodeID("self")
        storage.addNode(n)
        val selfEdge = EdgeID(n, n, "loop")
        storage.addEdge(selfEdge)

        assertTrue(selfEdge in storage.getOutgoingEdges(n))
        assertTrue(selfEdge in storage.getIncomingEdges(n))
    }

    @Test
    fun `test deleteNode removes self loop edge from cache`() {
        val n = NodeID("self")
        storage.addNode(n)
        val selfEdge = EdgeID(n, n, "loop")
        storage.addEdge(selfEdge)

        storage.deleteNode(n)

        assertFalse(storage.containsEdge(selfEdge))
    }

    // -- Metadata stored in ConcurrentHashMap (not Neo4j) --

    @Test
    fun `test meta operations use in-memory ConcurrentHashMap`() {
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

        val reloaded = Neo4jStorageImpl(graphDir)
        assertNull(reloaded.getMeta("key"))
        reloaded.close()
    }

    // -- close sets isClosed then operations throw --

    @Test
    fun `test close sets isClosed and all operations throw`() {
        storage.addNode(NodeID("n"))
        storage.close()

        assertFailsWith<AccessClosedStorageException> { storage.nodeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.edgeIDs }
        assertFailsWith<AccessClosedStorageException> { storage.containsNode(NodeID("n")) }
        assertFailsWith<AccessClosedStorageException> { storage.addNode(NodeID("new")) }
        assertFailsWith<AccessClosedStorageException> { storage.metaNames }
        assertFailsWith<AccessClosedStorageException> { storage.getMeta("x") }
        assertFailsWith<AccessClosedStorageException> { storage.setMeta("x", "v".strVal) }
    }

    // -- clear clears cache maps and Neo4j data --

    @Test
    fun `test clear empties node and edge caches and database`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        storage.addEdge(EdgeID(n1, n2, "e"))
        storage.setMeta("key", "val".strVal)

        assertTrue(storage.clear())
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
        assertTrue(storage.metaNames.isEmpty())
    }

    // -- Entity existence contracts --

    @Test
    fun `test addNode duplicate throws EntityAlreadyExistException`() {
        storage.addNode(NodeID("dup"))
        assertFailsWith<EntityAlreadyExistException> { storage.addNode(NodeID("dup")) }
    }

    @Test
    fun `test addEdge missing src throws EntityNotExistException`() {
        storage.addNode(NodeID("dst"))
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(EdgeID(NodeID("missing"), NodeID("dst"), "e"))
        }
    }

    @Test
    fun `test addEdge missing dst throws EntityNotExistException`() {
        storage.addNode(NodeID("src"))
        assertFailsWith<EntityNotExistException> {
            storage.addEdge(EdgeID(NodeID("src"), NodeID("missing"), "e"))
        }
    }

    @Test
    fun `test deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(NodeID("missing")) }
    }

    @Test
    fun `test deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.deleteEdge(EdgeID(NodeID("a"), NodeID("b"), "e"))
        }
    }

    @Test
    fun `test getNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(NodeID("missing")) }
    }

    @Test
    fun `test getIncomingEdges nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getIncomingEdges(NodeID("missing")) }
    }

    @Test
    fun `test getOutgoingEdges nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getOutgoingEdges(NodeID("missing")) }
    }

    // -- RelationshipType uses eType --

    @Test
    fun `test edge type stored as Neo4j RelationshipType`() {
        val n1 = NodeID("n1")
        val n2 = NodeID("n2")
        storage.addNode(n1)
        storage.addNode(n2)
        val e1 = EdgeID(n1, n2, "KNOWS")
        val e2 = EdgeID(n1, n2, "LIKES")
        storage.addEdge(e1)
        storage.addEdge(e2)

        assertTrue(storage.containsEdge(e1))
        assertTrue(storage.containsEdge(e2))
        assertEquals(2, storage.getOutgoingEdges(n1).size)
    }

    // -- Concurrent access via ConcurrentHashMap caches --

    @Test
    fun `test concurrent add nodes uses ConcurrentHashMap safely`() {
        val threads = List(10) { threadId ->
            Thread {
                val nodeId = NodeID("node-$threadId")
                storage.addNode(nodeId, mapOf("prop" to "value-$threadId".strVal))
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(10, storage.nodeIDs.size)
        for (i in 0 until 10) {
            assertTrue(storage.containsNode(NodeID("node-$i")))
        }
    }
}
