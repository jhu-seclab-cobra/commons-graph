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
 * White-box tests for Neo4jConcurStorageImpl: entity existence guards for edge operations and transferTo.
 *
 * - `addEdge missing src throws EntityNotExistException`
 * - `deleteNode nonexistent throws EntityNotExistException`
 * - `setEdgeProperties sets property on existing edge`
 * - `setEdgeProperties with null removes property`
 * - `setEdgeProperties nonexistent throws EntityNotExistException`
 * - `deleteEdge removes edge from edge mapping cache`
 * - `deleteEdge nonexistent throws EntityNotExistException`
 * - `deleteEdge leaves nodes intact`
 * - `getEdgeStructure returns correct src dst and tag`
 * - `getEdgeStructure nonexistent throws EntityNotExistException`
 * - `getNodeProperties nonexistent throws EntityNotExistException`
 * - `getEdgeProperties nonexistent throws EntityNotExistException`
 * - `setNodeProperties nonexistent throws EntityNotExistException`
 * - `transferTo copies nodes edges and meta to target`
 */
internal class Neo4jConcurStorageImplGuardTest {
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

    // -- Entity existence contracts --

    @Test
    fun `addEdge missing src throws EntityNotExistException`() {
        val dst = storage.addNode()
        assertFailsWith<EntityNotExistException> { storage.addEdge(-1, dst, "e") }
    }

    @Test
    fun `deleteNode nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }

    // -- setEdgeProperties --

    @Test
    fun `setEdgeProperties sets property on existing edge`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.setEdgeProperties(e, mapOf("weight" to 42.intVal))
        assertEquals(42, (storage.getEdgeProperties(e)["weight"] as IntVal).core)
    }

    @Test
    fun `setEdgeProperties with null removes property`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel", mapOf("x" to "y".strVal, "z" to "w".strVal))
        storage.setEdgeProperties(e, mapOf("x" to null))
        val props = storage.getEdgeProperties(e)
        assertNull(props["x"])
        assertEquals("w", (props["z"] as StrVal).core)
    }

    @Test
    fun `setEdgeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.setEdgeProperties(-1, mapOf("key" to "val".strVal))
        }
    }

    // -- deleteEdge --

    @Test
    fun `deleteEdge removes edge from edge mapping cache`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertFalse(storage.containsEdge(e))
        assertEquals(0, storage.edgeIDs.size)
    }

    @Test
    fun `deleteEdge nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.deleteEdge(-1) }
    }

    @Test
    fun `deleteEdge leaves nodes intact`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "rel")
        storage.deleteEdge(e)
        assertTrue(storage.containsNode(n1))
        assertTrue(storage.containsNode(n2))
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

    // -- Entity existence for remaining operations --

    @Test
    fun `getNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(-1) }
    }

    @Test
    fun `getEdgeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> { storage.getEdgeProperties(-1) }
    }

    @Test
    fun `setNodeProperties nonexistent throws EntityNotExistException`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("key" to "val".strVal))
        }
    }

    // -- transferTo --

    @Test
    fun `transferTo copies nodes edges and meta to target`() {
        val n1 = storage.addNode(mapOf("label" to "A".strVal))
        val n2 = storage.addNode(mapOf("label" to "B".strVal))
        storage.addEdge(n1, n2, "LINKS", mapOf("w" to 1.intVal))
        storage.setMeta("version", "2".strVal)

        val target = NativeStorageImpl()
        storage.transferTo(target)

        assertEquals(2, target.nodeIDs.size)
        assertEquals(1, target.edgeIDs.size)
        assertEquals("2", (target.getMeta("version") as StrVal).core)
    }
}
