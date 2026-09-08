package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for FrozenLayer: the immutable merged snapshot with global/local ID translation.
 *
 * - `merge without previous copies active entities under their global IDs` -- first freeze
 * - `edgeStructure translates endpoints to global IDs and caches the result` -- ID translation
 * - `adjacency views expose global edge IDs` -- MappedEdgeSet translation
 * - `lookups on unknown global IDs return null` -- absent entities
 * - `merge with previous keeps frozen entities and applies active overrides wholesale` -- re-freeze
 * - `merge excludes tombstoned meta names and applies active meta` -- meta merge
 * - `close releases an AutoCloseable merged storage` -- resource release
 */
internal class FrozenLayerTest {
    private class CloseRecordingStorage(
        private val inner: NativeStorageImpl = NativeStorageImpl(),
    ) : IStorage by inner,
        AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private lateinit var active: ActiveLayer

    @BeforeTest
    fun setUp() {
        active = ActiveLayer()
        active.addNode(100, mapOf("name" to "a".strVal))
        active.addNode(200, mapOf("name" to "b".strVal))
        active.addEdge(300, IStorage.EdgeStructure(100, 200, "t"), mapOf("w" to 1.intVal))
        active.metaProperties["m"] = 1.intVal
    }

    @Test
    fun `merge without previous copies active entities under their global IDs`() {
        val frozen = FrozenLayer.merge(null, active, NativeStorageImpl())

        assertEquals(setOf(100, 200), frozen.nodeIds)
        assertEquals(setOf(300), frozen.edgeIds)
        assertTrue(frozen.containsNode(100))
        assertTrue(frozen.containsEdge(300))
        assertEquals(mapOf("name" to "a".strVal), frozen.nodeProperties(100))
        assertEquals("b".strVal, frozen.nodeProperty(200, "name"))
        assertEquals(mapOf("w" to 1.intVal), frozen.edgeProperties(300))
        assertEquals(1.intVal, frozen.edgeProperty(300, "w"))
        assertEquals(setOf("m"), frozen.metaNames)
        assertEquals(1.intVal, frozen.meta("m"))
    }

    @Test
    fun `edgeStructure translates endpoints to global IDs and caches the result`() {
        val frozen = FrozenLayer.merge(null, active, NativeStorageImpl())

        val first = frozen.edgeStructure(300)
        val second = frozen.edgeStructure(300)

        assertEquals(IStorage.EdgeStructure(100, 200, "t"), first)
        assertTrue(first === second)
    }

    @Test
    fun `adjacency views expose global edge IDs`() {
        val frozen = FrozenLayer.merge(null, active, NativeStorageImpl())

        assertEquals(setOf(300), frozen.outgoingEdges(100))
        assertEquals(setOf(300), frozen.incomingEdges(200))
        assertEquals(emptySet(), frozen.incomingEdges(100))
    }

    @Test
    fun `lookups on unknown global IDs return null`() {
        val frozen = FrozenLayer.merge(null, active, NativeStorageImpl())

        assertFalse(frozen.containsNode(999))
        assertNull(frozen.nodeProperties(999))
        assertNull(frozen.nodeProperty(999, "name"))
        assertNull(frozen.edgeProperties(999))
        assertNull(frozen.edgeProperty(999, "w"))
        assertNull(frozen.edgeStructure(999))
        assertNull(frozen.outgoingEdges(999))
        assertNull(frozen.incomingEdges(999))
    }

    @Test
    fun `merge with previous keeps frozen entities and applies active overrides wholesale`() {
        val previous = FrozenLayer.merge(null, active, NativeStorageImpl())
        val next = ActiveLayer()
        // Promoted copies: node 100 lost "name" (deleted in the active layer), node 200 is unchanged.
        next.addNode(100, mapOf("extra" to 2.intVal))
        next.addNode(200, mapOf("name" to "b".strVal))
        next.addNode(400, mapOf("name" to "d".strVal))
        next.addEdge(300, IStorage.EdgeStructure(100, 200, "t"), mapOf("w" to 9.intVal))
        next.addEdge(500, IStorage.EdgeStructure(400, 200, "u"), emptyMap())

        val merged = FrozenLayer.merge(previous, next, NativeStorageImpl())

        assertEquals(setOf(100, 200, 400), merged.nodeIds)
        assertEquals(setOf(300, 500), merged.edgeIds)
        assertEquals(mapOf("extra" to 2.intVal), merged.nodeProperties(100))
        assertEquals(mapOf("name" to "b".strVal), merged.nodeProperties(200))
        assertEquals(mapOf("w" to 9.intVal), merged.edgeProperties(300))
        assertEquals(IStorage.EdgeStructure(400, 200, "u"), merged.edgeStructure(500))
        assertEquals(setOf(300, 500), merged.incomingEdges(200))
    }

    @Test
    fun `merge excludes tombstoned meta names and applies active meta`() {
        val previous = FrozenLayer.merge(null, active, NativeStorageImpl())
        val next = ActiveLayer()
        next.deletedMetaNames.add("m")
        next.metaProperties["n"] = 2.intVal

        val merged = FrozenLayer.merge(previous, next, NativeStorageImpl())

        assertEquals(setOf("n"), merged.metaNames)
        assertNull(merged.meta("m"))
        assertEquals(2.intVal, merged.meta("n"))
    }

    @Test
    fun `close releases an AutoCloseable merged storage`() {
        val target = CloseRecordingStorage()
        val frozen = FrozenLayer.merge(null, active, target)

        frozen.close()

        assertTrue(target.closed)
    }
}
