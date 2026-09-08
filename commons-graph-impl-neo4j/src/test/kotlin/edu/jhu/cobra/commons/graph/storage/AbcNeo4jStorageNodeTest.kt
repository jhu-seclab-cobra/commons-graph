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
 * Black-box tests for the AbcNeo4jStorage engine through Neo4jStorageImpl: node operations.
 *
 * - `empty storage has no nodes or edges`
 * - `addNode with properties returns valid ID and stores properties`
 * - `addNode without properties returns valid ID with empty property map`
 * - `containsNode returns true for existing node`
 * - `containsNode returns false for nonexistent node`
 * - `nodeIDs returns all added node IDs`
 * - `getNodeProperties returns stored properties`
 * - `getNodeProperties throws EntityNotExistException for missing node`
 * - `getNodeProperty returns value for existing property`
 * - `getNodeProperty returns null for absent property on existing node`
 * - `getNodeProperty throws EntityNotExistException for missing node`
 * - `setNodeProperties updates existing and adds new properties`
 * - `setNodeProperties with null value removes that property`
 * - `setNodeProperties throws EntityNotExistException for missing node`
 * - `deleteNode removes node from storage`
 * - `deleteNode cascades deletion to all incident edges`
 * - `deleteNode throws EntityNotExistException for missing node`
 */
internal class AbcNeo4jStorageNodeTest {
    private lateinit var storage: Neo4jStorageImpl

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("neo4j-test")
        storage = Neo4jStorageImpl(tempDir)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
        tempDir.toFile().deleteRecursively()
    }

    // -- empty storage --

    @Test
    fun `empty storage has no nodes or edges`() {
        assertEquals(0, storage.nodeIDs.size)
        assertEquals(0, storage.edgeIDs.size)
    }

    // -- addNode --

    @Test
    fun `addNode with properties returns valid ID and stores properties`() {
        val id = storage.addNode(mapOf("prop1" to "value1".strVal))
        assertTrue(storage.containsNode(id))
        assertEquals("value1", (storage.getNodeProperties(id)["prop1"] as StrVal).core)
    }

    @Test
    fun `addNode without properties returns valid ID with empty property map`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
        assertTrue(storage.getNodeProperties(id).isEmpty())
    }

    // -- containsNode --

    @Test
    fun `containsNode returns true for existing node`() {
        val id = storage.addNode()
        assertTrue(storage.containsNode(id))
    }

    @Test
    fun `containsNode returns false for nonexistent node`() {
        assertFalse(storage.containsNode(-1))
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
        val id = storage.addNode(mapOf("a" to "v1".strVal, "b" to "v2".strVal))
        val props = storage.getNodeProperties(id)
        assertEquals(2, props.size)
        assertEquals("v1", (props["a"] as StrVal).core)
        assertEquals("v2", (props["b"] as StrVal).core)
    }

    @Test
    fun `getNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperties(-1) }
    }

    // -- getNodeProperty --

    @Test
    fun `getNodeProperty returns value for existing property`() {
        val id = storage.addNode(mapOf("name" to "hello".strVal))
        assertEquals("hello", (storage.getNodeProperty(id, "name") as StrVal).core)
    }

    @Test
    fun `getNodeProperty returns null for absent property on existing node`() {
        val id = storage.addNode()
        assertNull(storage.getNodeProperty(id, "missing"))
    }

    @Test
    fun `getNodeProperty throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.getNodeProperty(-1, "key") }
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
        val props = storage.getNodeProperties(id)
        assertNull(props["a"])
        assertEquals(2, (props["b"] as IntVal).core)
    }

    @Test
    fun `setNodeProperties throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> {
            storage.setNodeProperties(-1, mapOf("k" to "v".strVal))
        }
    }

    // -- deleteNode --

    @Test
    fun `deleteNode removes node from storage`() {
        val id = storage.addNode()
        storage.deleteNode(id)
        assertFalse(storage.containsNode(id))
        assertEquals(0, storage.nodeIDs.size)
    }

    @Test
    fun `deleteNode cascades deletion to all incident edges`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val e = storage.addEdge(n1, n2, "e")
        storage.deleteNode(n1)
        assertFalse(storage.containsEdge(e))
    }

    @Test
    fun `deleteNode throws EntityNotExistException for missing node`() {
        assertFailsWith<EntityNotExistException> { storage.deleteNode(-1) }
    }
}
