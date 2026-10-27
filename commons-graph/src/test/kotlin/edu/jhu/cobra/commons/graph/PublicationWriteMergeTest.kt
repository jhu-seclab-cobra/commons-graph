package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_NODE_ID
import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_OWNERS
import edu.jhu.cobra.commons.graph.GraphFixtures.TestEdge
import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.poset.PosetTrait
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.intVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/*
 * Black-box tests for the pure merge laws a graph declares for the prospective changes of several writers:
 * labels, owners, properties and edge identity (model.md Merge Laws; design-label.md; design-graph.md).
 * Each writer carries its own before-image and after-image; no test reads or writes storage unless it says so.
 *
 * - `label additions of different writers apply on top of the base` -- writer-relative additions
 * - `label removals compare with the writer's own before-image` -- writer-relative removals
 * - `a label another writer's base already holds is kept` -- a stale before-image never overwrites the base
 * - `an unchanged absent label leaves the base label` -- absent versus present is distinguished
 * - `two writers adding one label are idempotent` -- equal additions
 * - `opposing changes to one label fail in either order` -- add against remove
 * - `the label result never depends on writer order` -- every permutation
 * - `the labels property law merges stored label lists` -- property-level adapter
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
 * - `edge additions of one triple coalesce` -- triple identity
 * - `edges with different tags are distinct` -- tag is part of identity
 * - `a declared unique key violation fails the set` -- graph-declared uniqueness
 * - `a declared unique key violation against a present edge fails` -- present edges count
 * - `an addition equal to a present edge coalesces without conflict` -- identity before uniqueness
 * - `the edge key of an edge equals its triple` -- AbcEdge identity
 */
internal class PublicationWriteMergeTest {
    private val x = Label("x")
    private val y = Label("y")
    private val z = Label("z")

    private fun labelChange(
        before: Set<Label>,
        after: Set<Label>,
    ) = WriterChange(before, after)

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.indices.flatMap { i -> permutations(items - items[i]).map { listOf(items[i]) + it } }
        }

    // region labels

    @Test
    fun `label additions of different writers apply on top of the base`() {
        val merged =
            PosetTrait.mergeLabels(
                setOf(z),
                listOf(labelChange(setOf(z), setOf(z, x)), labelChange(setOf(z), setOf(z, y))),
            )

        assertEquals(setOf(x, y, z), merged)
    }

    @Test
    fun `label removals compare with the writer's own before-image`() {
        val merged =
            PosetTrait.mergeLabels(
                setOf(x, y, z),
                listOf(labelChange(setOf(x, y, z), setOf(y, z)), labelChange(setOf(x, y, z), setOf(x, z))),
            )

        assertEquals(setOf(z), merged)
    }

    @Test
    fun `a label another writer's base already holds is kept`() {
        // The base gained z from an accepted contribution; this writer's before-image never saw it.
        val merged = PosetTrait.mergeLabels(setOf(x, z), listOf(labelChange(setOf(x), setOf(x, y))))

        assertEquals(setOf(x, y, z), merged)
    }

    @Test
    fun `an unchanged absent label leaves the base label`() {
        val merged = PosetTrait.mergeLabels(setOf(x), listOf(labelChange(emptySet(), emptySet())))

        assertEquals(setOf(x), merged)
    }

    @Test
    fun `two writers adding one label are idempotent`() {
        val merged =
            PosetTrait.mergeLabels(
                emptySet(),
                listOf(labelChange(emptySet(), setOf(x)), labelChange(emptySet(), setOf(x))),
            )

        assertEquals(setOf(x), merged)
    }

    @Test
    fun `opposing changes to one label fail in either order`() {
        val adds = labelChange(emptySet(), setOf(x))
        val removes = labelChange(setOf(x), emptySet())

        for (order in permutations(listOf(adds, removes))) {
            assertFailsWith<MergeConflictException> { PosetTrait.mergeLabels(setOf(x), order) }
        }
    }

    @Test
    fun `the label result never depends on writer order`() {
        val changes =
            listOf(
                labelChange(setOf(x), setOf(x, y)),
                labelChange(setOf(x, z), setOf(x)),
                labelChange(setOf(x), setOf(x)),
            )

        val results = permutations(changes).map { PosetTrait.mergeLabels(setOf(x, z), it) }.toSet()

        assertEquals(setOf(setOf(x, y)), results)
    }

    @Test
    fun `the labels property law merges stored label lists`() {
        val graph = TestMultipleGraph(NativeStorageImpl())
        val law = graph.edgePropertyLaw("labels")
        val base = ListVal(listOf(StrVal(x.core)))
        val addY = WriterChange<IValue?>(base, ListVal(listOf(StrVal(x.core), StrVal(y.core))))
        val addZ = WriterChange<IValue?>(base, ListVal(listOf(StrVal(x.core), StrVal(z.core))))

        val forward = law.merge(base, listOf(addY, addZ))
        val backward = law.merge(base, listOf(addZ, addY))

        assertEquals(forward, backward)
        assertEquals(setOf(x.core, y.core, z.core), (forward as ListVal).core.map { (it as StrVal).core }.toSet())
    }

    // endregion

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

        override fun edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>> = mapOf("pair" to listOf(edge.src, edge.dst))
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

    // region edge identity

    private fun key(
        src: String,
        dst: String,
        tag: String,
    ) = EdgeKey(src, dst, tag)

    @Test
    fun `edge additions of one triple coalesce`() {
        val coalesced = graph.coalesceEdges(listOf(key("a", "b", "t"), key("a", "b", "t"), key("a", "c", "t")))

        assertEquals(setOf(key("a", "b", "t"), key("a", "c", "t")), coalesced)
    }

    @Test
    fun `edges with different tags are distinct`() {
        val coalesced = graph.coalesceEdges(listOf(key("a", "b", "t1"), key("a", "b", "t2")))

        assertEquals(2, coalesced.size)
    }

    @Test
    fun `a declared unique key violation fails the set`() {
        val additions = listOf(key("a", "b", "t1"), key("a", "b", "t2"))

        for (order in permutations(additions)) {
            assertFailsWith<MergeConflictException> { declaring.coalesceEdges(order) }
        }
        assertEquals(2, graph.coalesceEdges(additions).size)
    }

    @Test
    fun `a declared unique key violation against a present edge fails`() {
        val present = listOf(key("a", "b", "t1"))

        assertFailsWith<MergeConflictException> { declaring.coalesceEdges(listOf(key("a", "b", "t2")), present) }
        assertEquals(setOf(key("a", "c", "t2")), declaring.coalesceEdges(listOf(key("a", "c", "t2")), present))
    }

    @Test
    fun `an addition equal to a present edge coalesces without conflict`() {
        val present = listOf(key("a", "b", "t1"))

        assertEquals(setOf(key("a", "b", "t1")), declaring.coalesceEdges(listOf(key("a", "b", "t1")), present))
    }

    @Test
    fun `the edge key of an edge equals its triple`() {
        graph.addNode("a")
        graph.addNode("b")
        val edge = graph.addEdge("a", "b", "t")

        assertEquals(key("a", "b", "t"), edge.key)
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
