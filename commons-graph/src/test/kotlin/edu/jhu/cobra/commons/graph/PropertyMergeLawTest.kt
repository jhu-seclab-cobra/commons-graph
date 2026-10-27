package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_NODE_ID
import edu.jhu.cobra.commons.graph.GraphFixtures.TestEdge
import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.GraphFixtures.permutations
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/*
 * Black-box tests for the property laws of a graph: the default law, the immutable law and a law a graph
 * variant declares (model.md Merge Laws; design-graph.md GraphMergeLaws). No test reads or writes storage.
 *
 * - `equal property writes are idempotent` -- default law
 * - `differing property writes without a declared law fail` -- default law
 * - `deleting and setting one property differ` -- absent versus present
 * - `a write against a base another writer moved fails unless equal` -- stale before-image
 * - `an immutable property cannot be overwritten` -- immutable law
 * - `a declared pure law is applied in every order` -- graph-declared law
 * - `deletion against an absent property stays absent` -- idempotent absence
 * - `equal stale writes to the current base are idempotent` -- differing before-images
 * - `an empty change collection preserves a property` -- no selected changes
 */
internal class PropertyMergeLawTest {
    private class DeclaringGraph(
        override val storage: IStorage,
    ) : AbcMultipleGraph<TestNode, TestEdge>() {
        override val graphId: String = "Declaring"

        override fun newNodeObj() = TestNode()

        override fun newEdgeObj() = TestEdge()

        override fun nodePropertyLaw(name: String): PropertyMergeLaw =
            when (name) {
                "count" -> MAXIMUM
                "kind" -> PropertyMergeLaw.IMMUTABLE
                else -> super.nodePropertyLaw(name)
            }
    }

    private val graph = TestMultipleGraph(NativeStorageImpl())
    private val declaring = DeclaringGraph(NativeStorageImpl())

    private fun change(
        before: Int?,
        after: Int?,
    ) = WriterChange<IValue?>(before?.intVal, after?.intVal)

    @Test
    fun `equal property writes are idempotent`() {
        val law = graph.nodePropertyLaw("p")

        assertEquals(2.intVal, law.merge(null, listOf(change(null, 2), change(null, 2))))
        assertEquals(2.intVal, law.merge(2.intVal, listOf(change(1, 2))))
    }

    @Test
    fun `differing property writes without a declared law fail`() {
        val law = graph.nodePropertyLaw("p")

        for (order in permutations(listOf(change(null, 1), change(null, 2)))) {
            assertFailsWith<MergeConflictException> { law.merge(null, order) }
        }
    }

    @Test
    fun `deleting and setting one property differ`() {
        val law = graph.edgePropertyLaw("p")

        assertFailsWith<MergeConflictException> { law.merge(1.intVal, listOf(change(1, null), change(1, 2))) }
        assertNull(law.merge(1.intVal, listOf(change(1, null), change(1, null))))
        assertEquals(1.intVal, law.merge(1.intVal, listOf(change(1, 1))))
        assertNull(law.merge(null, listOf(change(null, null))))
    }

    @Test
    fun `a write against a base another writer moved fails unless equal`() {
        val law = graph.nodePropertyLaw("p")

        assertFailsWith<MergeConflictException> { law.merge(3.intVal, listOf(change(1, 2))) }
        assertEquals(3.intVal, law.merge(3.intVal, listOf(change(1, 3))))
    }

    @Test
    fun `an immutable property cannot be overwritten`() {
        val idLaw = graph.nodePropertyLaw(PROP_NODE_ID)
        val kindLaw = declaring.nodePropertyLaw("kind")

        assertFailsWith<MergeConflictException> {
            idLaw.merge("a".strVal, listOf(WriterChange<IValue?>("a".strVal, "b".strVal)))
        }
        assertFailsWith<MergeConflictException> { kindLaw.merge(1.intVal, listOf(change(1, 2))) }
        assertFailsWith<MergeConflictException> { kindLaw.merge(1.intVal, listOf(change(1, null))) }
        assertEquals(1.intVal, kindLaw.merge(1.intVal, listOf(change(null, 1))))
        assertEquals(1.intVal, kindLaw.merge(null, listOf(change(null, 1), change(null, 1))))
    }

    @Test
    fun `a declared pure law is applied in every order`() {
        val law = declaring.nodePropertyLaw("count")
        val changes = listOf(change(1, 4), change(1, 7), change(1, 2))

        val results = permutations(changes).map { law.merge(1.intVal, it) }.toSet()

        assertEquals(setOf<IValue?>(7.intVal), results)
    }

    @Test
    fun `deletion against an absent property stays absent`() {
        val law = graph.nodePropertyLaw("p")

        assertNull(law.merge(null, listOf(change(1, null), change(null, null))))
    }

    @Test
    fun `equal stale writes to the current base are idempotent`() {
        val law = graph.nodePropertyLaw("p")

        for (order in permutations(listOf(change(1, 3), change(2, 3)))) {
            assertEquals(3.intVal, law.merge(3.intVal, order))
        }
    }

    @Test
    fun `an empty change collection preserves a property`() {
        val law = graph.nodePropertyLaw("p")

        assertEquals(3.intVal, law.merge(3.intVal, emptyList()))
        assertNull(law.merge(null, emptyList()))
        assertEquals(3.intVal, PropertyMergeLaw.IMMUTABLE.merge(3.intVal, emptyList()))
    }

    private companion object {
        val MAXIMUM =
            PropertyMergeLaw { base, changes ->
                val values = listOfNotNull(base as? IntVal) + changes.mapNotNull { it.after as? IntVal }
                values.maxByOrNull { it.core }
            }
    }
}
