/*
 * Storage capability adapter acceptance.
 * - should expire scopes and iterators on every exit: lifetime and thread confinement.
 * - should reject every mutation path in a read scope: no lock upgrade or mutation.
 * - should keep captured nested snapshots independent: admission, read and layer snapshots.
 * - should exclude concurrent compound updates: one guard across a read-modify-write sequence.
 * - should reject unsupported guards and invalidate after failed close: construction and cleanup errors.
 * - should preserve lifecycle and reject closed access: Freeze, deletion, revert and repeated close.
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.NullVal
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.Lock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class StorageAccessTest {
    @Test
    fun `should expire scopes and iterators on every exit`() {
        StorageAccess(LayeredStorageImpl()).use { access ->
            val id = access.writer.write { it.addNode() }
            val edge = access.writer.write { it.addEdge(id, id, "selected") }
            lateinit var saved: StorageReadScope
            lateinit var iterator: Iterator<Int>
            access.reader.read { read ->
                saved = read
                iterator = read.getOutgoingEdges(id)
                assertTrue(iterator.hasNext())
                assertEquals(edge, iterator.next())
                assertFalse(iterator.hasNext())
                assertFailsWith<NoSuchElementException> { iterator.next() }
                assertEquals(listOf(edge), read.getIncomingEdges(id, "selected").asSequence().toList())
                assertFalse(read.getOutgoingEdges(id, "missing").hasNext())
                assertEquals(edge, read.findEdge(id, id, "selected"))
                assertEquals(setOf(edge), read.getEdges(id, id))
                access.reader.read { assertTrue(it.containsNode(id)) }
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor.submit(Callable {
                        assertFailsWith<IllegalStateException> { read.containsNode(id) }
                    }).get()
                } finally {
                    executor.shutdownNow()
                }
            }
            assertFailsWith<IllegalStateException> { saved.containsNode(id) }
            assertFailsWith<IllegalStateException> { iterator.hasNext() }
            assertFailsWith<IllegalStateException> { iterator.next() }
            assertFailsWith<IllegalArgumentException> {
                access.reader.read { saved = it; throw IllegalArgumentException("action") }
            }
            assertFailsWith<IllegalStateException> { saved.containsNode(id) }
            val writer = access.writer.write { it }
            assertFailsWith<IllegalStateException> { writer.addNode() }
        }
    }

    @Test
    fun `should reject every mutation path in a read scope`() {
        StorageAccess(LayeredStorageImpl()).use { access ->
            val id = access.writer.write { it.addNode() }
            access.reader.read { read ->
                assertFailsWith<IllegalStateException> { access.writer.write { it.addNode() } }
                assertFailsWith<IllegalStateException> { access.compatibility.addNode() }
                assertFailsWith<IllegalStateException> { access.compatibility.criticalSection { } }
                assertFailsWith<IllegalStateException> { access.writer.freeze() }
                assertFailsWith<IllegalStateException> { access.writer.clearActiveLayer() }
                assertFailsWith<IllegalStateException> { access.writer.clear() }
                assertFailsWith<IllegalStateException> { access.close() }
                assertEquals(setOf(id), read.getNodeIDs())
            }
        }
    }

    @Test
    fun `should keep captured nested snapshots independent`() {
        StorageAccess(LayeredStorageImpl()).use { access ->
            val expected = ListVal(mutableListOf(ListVal(mutableListOf(IntVal(1)))))
            val source = expected.deepCopy()
            val id = access.writer.write { it.addNode(mapOf("list" to source, "null" to NullVal)) }
            (source[0] as ListVal).add(IntVal(2))
            val before = access.reader.read { it.getNodeProperties(id) }
            assertEquals(expected, before["list"])
            val selected = access.compatibility.getNodeProperty(id, "list") as ListVal
            (selected[0] as ListVal).add(IntVal(3))
            assertEquals(before, access.compatibility.getNodeProperties(id))
            val edge = access.compatibility.addEdge(id, id, "transfer", mapOf("list" to expected))
            access.compatibility.setMeta("list", expected)
            val target = NativeStorageImpl()
            val transferred = access.compatibility.transferTo(target)
            (target.getNodeProperty(transferred.getValue(id), "list") as ListVal).add(IntVal(9))
            (target.getEdgeProperty(target.edgeIDs.single(), "list") as ListVal).add(IntVal(9))
            (target.getMeta("list") as ListVal).add(IntVal(9))
            assertEquals(expected, access.compatibility.getNodeProperty(id, "list"))
            assertEquals(expected, access.compatibility.getEdgeProperty(edge, "list"))
            assertEquals(expected, access.compatibility.getMeta("list"))
            access.writer.freeze()
            access.writer.write { it.setNodeProperties(id, mapOf("list" to ListVal(), "null" to null)) }
            assertNull(access.reader.read { it.getNodeProperty(id, "null") })
            assertEquals(NullVal, before["null"])
            assertEquals(expected, before["list"])
            access.writer.clearActiveLayer()
            assertEquals(before, access.compatibility.getNodeProperties(id))
            val cyclic = ListVal()
            cyclic.add(cyclic)
            assertFailsWith<IllegalArgumentException> {
                access.writer.write { it.setNodeProperties(id, mapOf("first" to IntVal(9), "bad" to cyclic)) }
            }
            assertNull(access.compatibility.getNodeProperty(id, "first"))
        }
    }

    @Test
    fun `should exclude concurrent compound updates`() {
        StorageAccess(LayeredStorageImpl()).use { access ->
            val id = access.writer.write { it.addNode(mapOf("count" to IntVal(0))) }
            val executor = Executors.newFixedThreadPool(4)
            try {
                val tasks = List(4) { Callable {
                    repeat(100) {
                        access.writer.write { write ->
                            val before = access.reader.read { it.getNodeProperty(id, "count") as IntVal }
                            write.setNodeProperties(id, mapOf("count" to IntVal(before.core + 1)))
                        }
                    }
                } }
                executor.invokeAll(tasks).forEach { it.get() }
            } finally {
                executor.shutdownNow()
            }
            assertEquals(IntVal(400), access.compatibility.getNodeProperty(id, "count"))
        }
    }

    @Test
    fun `should reject unsupported guards and invalidate after failed close`() {
        val base = LayeredStorageImpl()
        val unsupported = object : LayeredStorage by base {
            override val criticalSectionLock: Lock? get() = null
        }
        assertFailsWith<IllegalArgumentException> { StorageAccess(unsupported) }
        var closes = 0
        val failing = object : LayeredStorage by base {
            override fun close() {
                closes++
                base.close()
                throw IllegalArgumentException("cleanup")
            }
        }
        val access = StorageAccess(failing)
        assertFailsWith<IllegalArgumentException> { access.close() }
        access.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { access.reader.read { it.getNodeIDs() } }
    }

    @Test
    fun `should preserve lifecycle and reject closed access`() {
        val access = StorageAccess(LayeredStorageImpl())
        val storage = access.compatibility
        val source = storage.addNode()
        val target = storage.addNode()
        val edge = storage.addEdge(source, target, "opaque:@tag")
        access.writer.freeze()
        val frozen = storage.getEdgeProperties(edge, StorageLayer.FROZEN)
        storage.deleteEdge(edge)
        assertFalse(storage.containsEdge(edge))
        assertEquals(frozen, storage.getEdgeProperties(edge, StorageLayer.FROZEN))
        access.writer.clearActiveLayer()
        assertTrue(storage.containsEdge(edge))
        access.writer.freeze()
        assertEquals(listOf(edge), storage.getOutgoingEdges(source).toList())
        assertFailsWith<IllegalArgumentException> { storage.transferTo(storage) }
        access.writer.clear()
        access.writer.freeze()
        access.writer.clear()
        access.close()
        access.close()
        assertFailsWith<IllegalStateException> { storage.addNode() }
        assertFailsWith<IllegalStateException> { storage.nodeIDs }
        assertFailsWith<IllegalStateException> { access.reader.read { it.getNodeIDs() } }
        assertFailsWith<IllegalStateException> { access.writer.write { it.addNode() } }
    }
}
