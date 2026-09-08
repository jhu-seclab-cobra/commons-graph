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
 * Black-box tests for Neo4jConcurStorageImpl: node operations under locking.
 *
 * - `addNode with properties returns valid ID and stores properties`
 * - `addNode without properties returns valid ID with empty property map`
 * - `containsNode returns true for existing node`
 * - `nodeIDs returns all added node IDs`
 * - `getNodeProperties returns stored properties`
 * - `getNodeProperty returns value for existing property`
 * - `setNodeProperties updates existing and adds new properties`
 * - `setNodeProperties with null value removes that property`
 * - `deleteNode removes node and cascades edge deletion`
 * - `deleteNode throws EntityNotExistException for missing node`
 */
internal class Neo4jConcurStorageImplNodeTest {
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

    // -- addNode --

    @Test
    fun `addNode with properties returns valid ID and stores properties`() {
        val id = storage.addNode(mapOf("k" to "v".strVal))
        assertTrue(storage.containsNode(id))
        assertEquals("v", (storage.getNodeProperties(id)["k"] as StrVal).core)
    }

    @Test
    fun `addNode without properties returns valid ID with empty property map`() {
        val id = storage.addNode()
        assertTrue(storage.getNodeProperties(id).isEmpty())
    }

    // -- containsNode --

    @Test
    fun `containsNode returns true for existing node`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
    }

    // -- nodeIDs --

    @Test
    fun `nodeIDs returns all added node IDs`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        assertEquals(setOf(n1, n2), storage.nodeIDs)
    }

    // -- getNodeProperties --

    @Test
    fun `getNodeProperties returns stored properties`() {
        val id = storage.addNode(mapOf("a" to "v1".strVal, "b" to 42.intVal))
        val props = storage.getNodeProperties(id)
        assertEquals("v1", (props["a"] as StrVal).core)
        assertEquals(42, (props["b"] as IntVal).core)
    }

    // -- getNodeProperty --

    @Test
    fun `getNodeProperty returns value for existing property`() {
        val id = storage.addNode(mapOf("name" to "hello".strVal))
        assertEquals("hello", (storage.getNodeProperty(id, "name") as StrVal).core)
    }

    // -- setNodeProperties --

    @Test
    fun `setNodeProperties updates existing and adds new properties`() {
        val id = storage.addNode(mapOf("a" to "v1".strVal))
        storage.setNodeProperties(id, mapOf("a" to "updated".strVal, "b" to 42.intVal))
        val props = storage.getNodeProperties(id)
        assertEquals("updated", (props["a"] as StrVal).core)
        assertEquals(42, (props["b"] as IntVal).core)
    }

    @Test
    fun `setNodeProperties with null value removes that property`() {
        val id = storage.addNode(mapOf("a" to 1.intVal, "b" to 2.intVal))
        storage.setNodeProperties(id, mapOf("a" to null))
        assertNull(storage.getNodeProperties(id)["a"])
    }

    // -- deleteNode --

    @Test
    fun `deleteNode removes node and cascades edge deletion`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e")
        storage.deleteNode(n1)
        assertFalse(storage.containsNode(n1))
        assertFalse(storage.containsEdge(e))
        assertTrue(storage.containsNode(n2))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }
}
