package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.storage.IStorage.EdgeStructure
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Black-box tests for the RevertNotice a LayeredStorageImpl sends (design-storage.md Revert Notice;
 * spec-graph.md Revert).
 *
 * - `clearActiveLayer names active-only entities as removed` -- removed nodes and edges
 * - `clearActiveLayer names promoted nodes and edges as restored` -- restored, not removed
 * - `clearActiveLayer names a tombstoned edge as restored without a before structure` -- reappeared edge
 * - `clearActiveLayer keeps a deleted promoted edge restored once` -- promoted then deleted
 * - `clearActiveLayer reports the before structures of removed and surviving edges` -- snapshot contents
 * - `clearActiveLayer sets are disjoint` -- removed and restored never overlap
 * - `clearActiveLayer without a frozen layer restores nothing` -- boundary
 * - `clearActiveLayer sends an empty notice when nothing changed` -- empty revert still notifies
 * - `clearActiveLayer notice is final when the listener reads storage` -- restored image readable
 * - `clear names every visible entity as removed with its structure` -- full clear
 * - `each revert sends one notice` -- one notice per revert
 */
internal class LayeredStorageImplNoticeTest {
    private lateinit var storage: LayeredStorageImpl
    private var src = -1
    private var dst = -1
    private var edge = -1
    private val notices = mutableListOf<RevertNotice>()

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
        src = storage.addNode()
        dst = storage.addNode()
        edge = storage.addEdge(src, dst, "rel", mapOf("k" to "frozen".strVal))
        storage.freeze()
        storage.addRevertListener { notices += it }
    }

    @Test
    fun `clearActiveLayer names active-only entities as removed`() {
        val node = storage.addNode()
        val activeEdge = storage.addEdge(src, node, "rel")

        storage.clearActiveLayer()

        val notice = notices.single()
        assertEquals(StorageEntityIds(setOf(node), setOf(activeEdge)), notice.removed)
    }

    @Test
    fun `clearActiveLayer names promoted nodes and edges as restored`() {
        storage.setNodeProperties(src, mapOf("k" to "active".strVal))
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))

        storage.clearActiveLayer()

        val notice = notices.single()
        assertEquals(StorageEntityIds(setOf(src, dst), setOf(edge)), notice.restored)
        assertEquals(StorageEntityIds(emptySet(), emptySet()), notice.removed)
    }

    @Test
    fun `clearActiveLayer names a tombstoned edge as restored without a before structure`() {
        storage.deleteEdge(edge)

        storage.clearActiveLayer()

        val notice = notices.single()
        assertEquals(StorageEntityIds(emptySet(), setOf(edge)), notice.restored)
        assertEquals(emptyMap(), notice.edgeStructuresBefore)
    }

    @Test
    fun `clearActiveLayer keeps a deleted promoted edge restored once`() {
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))
        storage.deleteEdge(edge)

        storage.clearActiveLayer()

        val notice = notices.single()
        assertEquals(setOf(edge), notice.restored.edges)
        assertEquals(emptyMap(), notice.edgeStructuresBefore)
    }

    @Test
    fun `clearActiveLayer reports the before structures of removed and surviving edges`() {
        val node = storage.addNode()
        val activeEdge = storage.addEdge(dst, node, "later")
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))

        storage.clearActiveLayer()

        val expected = mapOf(activeEdge to EdgeStructure(dst, node, "later"), edge to EdgeStructure(src, dst, "rel"))
        assertEquals(expected, notices.single().edgeStructuresBefore)
    }

    @Test
    fun `clearActiveLayer sets are disjoint`() {
        storage.setNodeProperties(src, mapOf("k" to "active".strVal))
        storage.deleteEdge(edge)
        val node = storage.addNode()
        storage.addEdge(src, node, "rel")

        storage.clearActiveLayer()

        val notice = notices.single()
        assertTrue(notice.removed.nodes.intersect(notice.restored.nodes).isEmpty())
        assertTrue(notice.removed.edges.intersect(notice.restored.edges).isEmpty())
    }

    @Test
    fun `clearActiveLayer without a frozen layer restores nothing`() {
        val fresh = LayeredStorageImpl()
        val received = mutableListOf<RevertNotice>()
        fresh.addRevertListener { received += it }
        val a = fresh.addNode()
        val b = fresh.addNode()
        val added = fresh.addEdge(a, b, "rel")

        fresh.clearActiveLayer()

        val notice = received.single()
        assertEquals(StorageEntityIds(setOf(a, b), setOf(added)), notice.removed)
        assertEquals(StorageEntityIds(emptySet(), emptySet()), notice.restored)
        assertEquals(mapOf(added to EdgeStructure(a, b, "rel")), notice.edgeStructuresBefore)
    }

    @Test
    fun `clearActiveLayer sends an empty notice when nothing changed`() {
        storage.clearActiveLayer()

        val empty = StorageEntityIds(emptySet(), emptySet())
        assertEquals(listOf(RevertNotice(empty, empty, emptyMap())), notices)
    }

    @Test
    fun `clearActiveLayer notice is final when the listener reads storage`() {
        storage.setEdgeProperties(edge, mapOf("k" to "active".strVal))
        storage.deleteEdge(edge)
        val seen = mutableListOf<Any?>()
        storage.addRevertListener {
            seen.add(storage.getEdgeProperty(edge, "k"))
            seen.add(storage.getOutgoingEdges(src))
        }

        storage.clearActiveLayer()

        assertEquals(listOf<Any?>("frozen".strVal, setOf(edge)), seen)
    }

    @Test
    fun `clear names every visible entity as removed with its structure`() {
        val node = storage.addNode()

        storage.clear()

        val notice = notices.single()
        assertEquals(StorageEntityIds(setOf(src, dst, node), setOf(edge)), notice.removed)
        assertEquals(StorageEntityIds(emptySet(), emptySet()), notice.restored)
        assertEquals(mapOf(edge to EdgeStructure(src, dst, "rel")), notice.edgeStructuresBefore)
    }

    @Test
    fun `each revert sends one notice`() {
        storage.addNode()

        storage.clearActiveLayer()
        storage.clearActiveLayer()

        assertEquals(2, notices.size)
    }
}
