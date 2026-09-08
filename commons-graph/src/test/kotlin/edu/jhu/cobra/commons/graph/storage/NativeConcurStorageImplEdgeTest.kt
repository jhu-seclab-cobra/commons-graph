package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.FloatVal
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.floatVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for NativeConcurStorageImpl: edge CRUD and adjacency.
 *
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
 */
internal class NativeConcurStorageImplEdgeTest {
    private lateinit var storage: NativeConcurStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeConcurStorageImpl()
    }

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
        val (n1, n2, n3) = StorageFixtures.addTestNodes(storage)
        val e1 = storage.addEdge(n1, n2, StorageFixtures.EDGE_TAG_1)
        val e2 = storage.addEdge(n2, n3, StorageFixtures.EDGE_TAG_2)
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
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.intVal))
        assertEquals(1L, (storage.getEdgeProperties(edgeId)["w"] as IntVal).core)
    }

    @Test
    fun `getEdgeProperty returns single value or null for absent key`() {
        val n1 = storage.addNode()
        val n2 = storage.addNode()
        val edgeId = storage.addEdge(n1, n2, "rel", mapOf("w" to 1.5.floatVal))
        assertEquals(1.5, (storage.getEdgeProperty(edgeId, "w") as FloatVal).core)
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
        val (n1, n2, n3) = StorageFixtures.addTestNodes(storage)
        val e1 = storage.addEdge(n1, n3, "a")
        val e2 = storage.addEdge(n2, n3, "b")
        assertEquals(setOf(e1, e2), storage.getIncomingEdges(n3))
    }

    @Test
    fun `getOutgoingEdges returns edges originating from node`() {
        val (n1, n2, n3) = StorageFixtures.addTestNodes(storage)
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
}
