package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/*
 * Tests for the IStorage interface defaults and the EdgeStructure data holder.
 *
 * - `getNodeProperty default returns the named entry of getNodeProperties` -- default delegation
 * - `getNodeProperty default returns null for absent name` -- absent name
 * - `getEdgeProperty default returns the named entry of getEdgeProperties` -- default delegation
 * - `getEdgeProperty default returns null for absent name` -- absent name
 * - `EdgeStructure equals compares src dst and tag` -- value equality
 * - `EdgeStructure differs when any component differs` -- inequality per component
 */
internal class IStorageTest {
    // Minimal IStorage exposing only the full property maps the defaults delegate to.
    private class MapOnlyStorage(
        private val nodeProps: Map<String, IValue>,
        private val edgeProps: Map<String, IValue>,
    ) : IStorage {
        override val nodeIDs: Set<Int> get() = error("unused")
        override val edgeIDs: Set<Int> get() = error("unused")
        override val metaNames: Set<String> get() = error("unused")

        override fun containsNode(id: Int): Boolean = error("unused")

        override fun addNode(properties: Map<String, IValue>): Int = error("unused")

        override fun getNodeProperties(id: Int): Map<String, IValue> = nodeProps

        override fun setNodeProperties(
            id: Int,
            properties: Map<String, IValue?>,
        ) = error("unused")

        override fun deleteNode(id: Int) = error("unused")

        override fun containsEdge(id: Int): Boolean = error("unused")

        override fun addEdge(
            src: Int,
            dst: Int,
            tag: String,
            properties: Map<String, IValue>,
        ): Int = error("unused")

        override fun getEdgeStructure(id: Int): IStorage.EdgeStructure = error("unused")

        override fun getEdgeProperties(id: Int): Map<String, IValue> = edgeProps

        override fun setEdgeProperties(
            id: Int,
            properties: Map<String, IValue?>,
        ) = error("unused")

        override fun deleteEdge(id: Int) = error("unused")

        override fun getIncomingEdges(id: Int): Set<Int> = error("unused")

        override fun getOutgoingEdges(id: Int): Set<Int> = error("unused")

        override fun getMeta(name: String): IValue? = error("unused")

        override fun setMeta(
            name: String,
            value: IValue?,
        ) = error("unused")

        override fun clear() = error("unused")

        override fun transferTo(target: IStorage): Map<Int, Int> = error("unused")

        override fun flush() = error("unused")
    }

    private val storage =
        MapOnlyStorage(
            nodeProps = mapOf("name" to "n".strVal, "weight" to 3.intVal),
            edgeProps = mapOf("kind" to "e".strVal),
        )

    @Test
    fun `getNodeProperty default returns the named entry of getNodeProperties`() {
        assertEquals("n".strVal, storage.getNodeProperty(1, "name"))
        assertEquals(3.intVal, storage.getNodeProperty(1, "weight"))
    }

    @Test
    fun `getNodeProperty default returns null for absent name`() {
        assertNull(storage.getNodeProperty(1, "missing"))
    }

    @Test
    fun `getEdgeProperty default returns the named entry of getEdgeProperties`() {
        assertEquals("e".strVal, storage.getEdgeProperty(1, "kind"))
    }

    @Test
    fun `getEdgeProperty default returns null for absent name`() {
        assertNull(storage.getEdgeProperty(1, "missing"))
    }

    @Test
    fun `EdgeStructure equals compares src dst and tag`() {
        val a = IStorage.EdgeStructure(1, 2, "t")
        val b = IStorage.EdgeStructure(1, 2, "t")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, a.src)
        assertEquals(2, a.dst)
        assertEquals("t", a.tag)
    }

    @Test
    fun `EdgeStructure differs when any component differs`() {
        val base = IStorage.EdgeStructure(1, 2, "t")

        assertNotEquals(base, IStorage.EdgeStructure(9, 2, "t"))
        assertNotEquals(base, IStorage.EdgeStructure(1, 9, "t"))
        assertNotEquals(base, IStorage.EdgeStructure(1, 2, "u"))
    }
}
