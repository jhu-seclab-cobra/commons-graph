package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphFixtures.TestEdge
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Tests for GraphEntityCache: the NodeID-to-storage-ID index and soft-referenced entity wrappers.
 *
 * - `register indexes the node by both NodeID and storage ID` -- bidirectional index
 * - `entryOf returns null for an unregistered NodeID` -- absent node
 * - `node binds a wrapper to the entry and reuses it on later calls` -- wrapper caching
 * - `node by storage ID resolves through the index` -- storage ID lookup
 * - `node by unknown storage ID throws NoSuchElementException` -- unknown storage ID
 * - `edge binds endpoints and tag from the storage structure and caches the wrapper` -- edge binding
 * - `evictEdge drops the cached wrapper so the next call rebinds` -- edge eviction
 * - `removeNode drops both index entries` -- node removal
 * - `clear empties indexes and edge cache` -- reset
 */
internal class GraphEntityCacheTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var cache: GraphEntityCache<TestNode, TestEdge>

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        cache = GraphEntityCache({ storage }, { TestNode() }, { TestEdge() })
    }

    @Test
    fun `register indexes the node by both NodeID and storage ID`() {
        val sid = storage.addNode()

        val entry = cache.register(NODE_ID_1, sid)

        assertEquals(NODE_ID_1, entry.nodeId)
        assertEquals(sid, entry.storageId)
        assertNull(entry.ref)
        assertTrue(cache.containsNode(NODE_ID_1))
        assertTrue(cache.containsStorageId(sid))
        assertSame(entry, cache.entryOf(NODE_ID_1))
        assertEquals(setOf(NODE_ID_1), cache.nodeIds)
        assertEquals(setOf(sid), cache.storageIds)
        assertEquals(listOf(entry), cache.entries.toList())
    }

    @Test
    fun `entryOf returns null for an unregistered NodeID`() {
        assertNull(cache.entryOf(NODE_ID_1))
        assertFalse(cache.containsNode(NODE_ID_1))
        assertFalse(cache.containsStorageId(0))
    }

    @Test
    fun `node binds a wrapper to the entry and reuses it on later calls`() {
        val sid = storage.addNode()
        val entry = cache.register(NODE_ID_1, sid)

        val first = cache.node(entry)
        val second = cache.node(entry)

        assertEquals(NODE_ID_1, first.id)
        assertEquals(sid, first.storageId)
        assertSame(first, second)
        assertSame(first, entry.ref?.get())
    }

    @Test
    fun `node by storage ID resolves through the index`() {
        val sid = storage.addNode()
        cache.register(NODE_ID_1, sid)

        assertEquals(NODE_ID_1, cache.node(sid).id)
    }

    @Test
    fun `node by unknown storage ID throws NoSuchElementException`() {
        assertFailsWith<NoSuchElementException> { cache.node(42) }
    }

    @Test
    fun `edge binds endpoints and tag from the storage structure and caches the wrapper`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        cache.register(NODE_ID_1, src)
        cache.register(NODE_ID_2, dst)
        val eid = storage.addEdge(src, dst, EDGE_TAG_1)

        val edge = cache.edge(eid)

        assertEquals(NODE_ID_1, edge.srcNid)
        assertEquals(NODE_ID_2, edge.dstNid)
        assertEquals(EDGE_TAG_1, edge.eTag)
        assertEquals(eid, edge.storageId)
        assertSame(edge, cache.edge(eid))
    }

    @Test
    fun `evictEdge drops the cached wrapper so the next call rebinds`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        cache.register(NODE_ID_1, src)
        cache.register(NODE_ID_2, dst)
        val eid = storage.addEdge(src, dst, EDGE_TAG_1)
        val before = cache.edge(eid)

        cache.evictEdge(eid)

        assertNotSame(before, cache.edge(eid))
    }

    @Test
    fun `removeNode drops both index entries`() {
        val sid = storage.addNode()
        val entry = cache.register(NODE_ID_1, sid)

        cache.removeNode(entry)

        assertFalse(cache.containsNode(NODE_ID_1))
        assertFalse(cache.containsStorageId(sid))
        assertTrue(cache.entries.isEmpty())
    }

    @Test
    fun `clear empties indexes and edge cache`() {
        val src = storage.addNode()
        val dst = storage.addNode()
        cache.register(NODE_ID_1, src)
        cache.register(NODE_ID_2, dst)
        val eid = storage.addEdge(src, dst, EDGE_TAG_1)
        val before = cache.edge(eid)

        cache.clear()

        assertTrue(cache.nodeIds.isEmpty())
        assertTrue(cache.storageIds.isEmpty())
        cache.register(NODE_ID_1, src)
        cache.register(NODE_ID_2, dst)
        assertNotSame(before, cache.edge(eid))
    }
}
