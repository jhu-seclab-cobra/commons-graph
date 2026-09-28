package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.intVal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Black-box tests for LayeredStorageImpl returned collections: each is a copy taken at the
 * call (design-storage.md LayeredStorageImpl Thread safety; Concurrent use per implementation).
 *
 * - `nodeIDs taken before freeze keeps its nodes` -- freeze does not empty a returned ID set
 * - `edgeIDs taken before clearActiveLayer keeps its edges` -- revert does not empty a returned ID set
 * - `metaNames taken before a write excludes the new name` -- later write not visible
 * - `getNodeProperties taken before freeze keeps its values` -- freeze does not empty a returned map
 * - `getNodeProperties taken before a write excludes the new key` -- get agrees with the copy
 * - `getEdgeProperties taken before clearActiveLayer keeps its values` -- revert does not empty a returned map
 * - `getOutgoingEdges taken before a write excludes the new edge` -- later edge not visible
 * - `getIncomingEdges taken before a write excludes the new edge` -- later edge not visible
 * - `getNodeProperties read outside the lock while another thread writes raises nothing` -- no concurrent modification
 */
internal class LayeredStorageImplSnapshotTest {
    private lateinit var storage: LayeredStorageImpl
    private var src = -1
    private var dst = -1
    private var edge = -1

    @BeforeTest
    fun setUp() {
        storage = LayeredStorageImpl()
        src = storage.addNode(mapOf("x" to 1.intVal))
        dst = storage.addNode()
        edge = storage.addEdge(src, dst, "rel", mapOf("w" to 2.intVal))
    }

    @Test
    fun `nodeIDs taken before freeze keeps its nodes`() {
        val ids = storage.nodeIDs

        storage.freeze()

        assertEquals(setOf(src, dst), ids)
    }

    @Test
    fun `edgeIDs taken before clearActiveLayer keeps its edges`() {
        val ids = storage.edgeIDs

        storage.clearActiveLayer()

        assertEquals(setOf(edge), ids)
    }

    @Test
    fun `metaNames taken before a write excludes the new name`() {
        storage.setMeta("a", 1.intVal)
        val names = storage.metaNames

        storage.setMeta("b", 2.intVal)

        assertEquals(setOf("a"), names)
    }

    @Test
    fun `getNodeProperties taken before freeze keeps its values`() {
        val props = storage.getNodeProperties(src)

        storage.freeze()

        assertEquals(1.intVal, props["x"])
    }

    @Test
    fun `getNodeProperties taken before a write excludes the new key`() {
        val props = storage.getNodeProperties(src)
        props.size

        storage.setNodeProperties(src, mapOf("y" to 3.intVal))

        assertNull(props["y"])
    }

    @Test
    fun `getEdgeProperties taken before clearActiveLayer keeps its values`() {
        val props = storage.getEdgeProperties(edge)

        storage.clearActiveLayer()

        assertEquals(2.intVal, props["w"])
    }

    @Test
    fun `getOutgoingEdges taken before a write excludes the new edge`() {
        val out = storage.getOutgoingEdges(src)

        storage.addEdge(src, dst, "other")

        assertEquals(setOf(edge), out)
    }

    @Test
    fun `getIncomingEdges taken before a write excludes the new edge`() {
        val incoming = storage.getIncomingEdges(dst)

        storage.addEdge(src, dst, "other")

        assertEquals(setOf(edge), incoming)
    }

    @Test
    fun `getNodeProperties read outside the lock while another thread writes raises nothing`() {
        val stop = AtomicBoolean(false)
        val errors = CopyOnWriteArrayList<Throwable>()
        val writer =
            thread {
                var round = 0
                while (!stop.get()) {
                    storage.setNodeProperties(src, mapOf("k${round % DISTINCT_KEYS}" to round.intVal))
                    round++
                }
            }
        repeat(READS) {
            runCatching { storage.getNodeProperties(src).toMap() }.onFailure { errors.add(it) }
        }
        stop.set(true)
        writer.join()

        assertTrue(errors.isEmpty(), "Unexpected errors: ${errors.map { it::class.simpleName }.toSet()}")
    }

    private companion object {
        // Enough reads to hit a concurrent column insertion; unguarded views failed ~0.3% of reads.
        const val READS = 50_000

        // Keeps the writer adding new columns, which rehashes the column map under a reader.
        const val DISTINCT_KEYS = 5_000
    }
}
