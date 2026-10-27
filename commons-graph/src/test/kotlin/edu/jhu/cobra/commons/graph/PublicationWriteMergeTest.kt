package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_NODE_ID
import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_OWNERS
import edu.jhu.cobra.commons.graph.GraphFixtures.TestEdge
import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Every order of [items]; a merge law's result must be equal for all of them. */
internal fun <T> permutations(items: List<T>): List<List<T>> =
    if (items.size <= 1) {
        listOf(items)
    } else {
        items.indices.flatMap { i -> permutations(items - items[i]).map { listOf(items[i]) + it } }
    }

/*
 * Black-box tests for the owner union and the property laws a graph declares for the prospective changes of
 * several writers (model.md Merge Laws; design-graph.md GraphMergeLaws). Label and edge laws have their own files
 * (LabelMergeTest, EdgeCoalesceTest). No test reads or writes storage unless it says so.
 *
 * - `owner union holds every owner in either order` -- order independence
 * - `owner union of three graphs keeps all three` -- AST, ADG and CCG owner marks on one row
 * - `owner union ignores a stale owner a writer carried unchanged` -- additions only
 * - `owner union on an absent owner set` -- no base set
 * - `a revert after the three-owner union restores the first owner only` -- layered storage fixture
 * - `equal property writes are idempotent` -- default law
 * - `differing property writes without a declared law fail` -- default law
 * - `deleting and setting one property differ` -- absent versus present
 * - `a write against a base another writer moved fails unless equal` -- stale before-image
 * - `an immutable property cannot be overwritten` -- immutable law
 * - `a declared pure law is applied in every order` -- graph-declared law
 */
internal class PublicationWriteMergeTest {
    // region owners

    private fun ownerSet(vararg ids: String): SetVal = SetVal(ids.map { StrVal(it) }.toSet())

    private fun ownerChange(
        before: SetVal?,
        after: SetVal?,
    ) = WriterChange<IValue?>(before, after)

    private val ownerLaw = TestMultipleGraph(NativeStorageImpl()).nodePropertyLaw(PROP_OWNERS)

    @Test
    fun `owner union holds every owner in either order`() {
        val base = ownerSet("ast")
        val adg = ownerChange(base, ownerSet("ast", "adg"))
        val ccg = ownerChange(base, ownerSet("ast", "ccg"))

        assertEquals(ownerSet("ast", "adg", "ccg"), ownerLaw.merge(base, listOf(adg, ccg)))
        assertEquals(ownerSet("ast", "adg", "ccg"), ownerLaw.merge(base, listOf(ccg, adg)))
    }

    @Test
    fun `owner union of three graphs keeps all three`() {
        val base = ownerSet("ast")
        val changes =
            listOf(
                ownerChange(base, ownerSet("ast")),
                ownerChange(base, ownerSet("ast", "adg")),
                ownerChange(base, ownerSet("ast", "ccg")),
            )

        val results = permutations(changes).map { ownerLaw.merge(base, it) }.toSet()

        assertEquals(setOf<IValue?>(ownerSet("ast", "adg", "ccg")), results)
    }

    @Test
    fun `owner union ignores a stale owner a writer carried unchanged`() {
        // The base no longer holds "old"; the writer's before-image and after-image both carry it.
        val merged = ownerLaw.merge(ownerSet("ast"), listOf(ownerChange(ownerSet("ast", "old"), ownerSet("ast", "old", "adg"))))

        assertEquals(ownerSet("ast", "adg"), merged)
    }

    @Test
    fun `owner union on an absent owner set`() {
        val merged = ownerLaw.merge(null, listOf(ownerChange(null, ownerSet("adg")), ownerChange(null, ownerSet("ccg"))))

        assertEquals(ownerSet("adg", "ccg"), merged)
        assertNull(ownerLaw.merge(null, listOf(ownerChange(null, null))))
    }

    @Test
    fun `a revert after the three-owner union restores the first owner only`() {
        val storage = LayeredStorageImpl()
        val ast = TestMultipleGraph(storage, "ast")
        val node = ast.addNode("n")
        storage.freeze()
        val frozen = storage.getNodeProperty(node.storageId, PROP_OWNERS) as SetVal
        val adg = ownerChange(frozen, ownerSet("ast", "adg"))
        val ccg = ownerChange(frozen, ownerSet("ast", "ccg"))

        val merged = ownerLaw.merge(frozen, listOf(adg, ccg))
        storage.setNodeProperties(node.storageId, mapOf(PROP_OWNERS to merged))

        assertEquals(ownerSet("ast", "adg", "ccg"), storage.getNodeProperty(node.storageId, PROP_OWNERS))
        storage.clearActiveLayer()
        assertEquals(ownerSet("ast"), storage.getNodeProperty(node.storageId, PROP_OWNERS))
    }


    // endregion

    // region properties

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

        assertFailsWith<MergeConflictException> { idLaw.merge("a".strVal, listOf(WriterChange<IValue?>("a".strVal, "b".strVal))) }
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


    // endregion

    private companion object {
        val MAXIMUM =
            PropertyMergeLaw { base, changes ->
                val values = listOfNotNull(base as? IntVal) + changes.mapNotNull { it.after as? IntVal }
                values.maxByOrNull { it.core }
            }
    }
}
