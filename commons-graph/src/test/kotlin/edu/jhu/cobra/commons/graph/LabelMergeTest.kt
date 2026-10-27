package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.poset.PosetTrait
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * Black-box tests for the label merge law: writer-relative additions and removals on top of a base label set
 * (model.md Merge Laws; design-label.md Label merge). Each writer carries its own before-image and after-image.
 *
 * - `label additions of different writers apply on top of the base` -- writer-relative additions
 * - `label removals compare with the writer's own before-image` -- writer-relative removals
 * - `a label another writer's base already holds is kept` -- a stale before-image never overwrites the base
 * - `an unchanged absent label leaves the base label` -- absent versus present is distinguished
 * - `two writers adding one label are idempotent` -- equal additions
 * - `opposing changes to one label fail in either order` -- add against remove
 * - `the label result never depends on writer order` -- every permutation
 * - `the labels property law merges stored label lists` -- property-level adapter
 * - `creating a present empty labels key preserves presence` -- absent key versus present empty list
 * - `deleting a present empty labels key preserves absence` -- present empty list versus absent key
 * - `opposing labels key creation and deletion fail` -- key presence is a change of its own
 * - `another writer holding a label does not cancel its addition` -- cross-writer before-image
 * - `a stale label image neither deletes new base labels nor restores stale labels` -- base differs from before-image
 * - `no label changes preserve the base` -- empty change collections, set and stored list
 */
internal class LabelMergeTest {
    private val x = Label("x")
    private val y = Label("y")
    private val z = Label("z")

    private fun labelChange(
        before: Set<Label>,
        after: Set<Label>,
    ) = WriterChange(before, after)

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

    @Test
    fun `creating a present empty labels key preserves presence`() {
        val law = TestMultipleGraph(NativeStorageImpl()).edgePropertyLaw("labels")
        val empty = ListVal(emptyList())

        assertEquals(empty, law.merge(null, listOf(WriterChange<IValue?>(null, empty))))
    }

    @Test
    fun `deleting a present empty labels key preserves absence`() {
        val law = TestMultipleGraph(NativeStorageImpl()).edgePropertyLaw("labels")
        val empty = ListVal(emptyList())

        assertEquals(null, law.merge(empty, listOf(WriterChange<IValue?>(empty, null))))
    }

    @Test
    fun `opposing labels key creation and deletion fail`() {
        val law = TestMultipleGraph(NativeStorageImpl()).edgePropertyLaw("labels")
        val empty = ListVal(emptyList())
        val changes = listOf(WriterChange<IValue?>(null, empty), WriterChange<IValue?>(empty, null))

        for (order in permutations(changes)) {
            assertFailsWith<MergeConflictException> { law.merge(empty, order) }
        }
    }

    @Test
    fun `another writer holding a label does not cancel its addition`() {
        val changes =
            listOf(labelChange(setOf(z), setOf(z, x)), labelChange(setOf(z, x), setOf(z, x, y)))

        for (order in permutations(changes)) assertEquals(setOf(x, y, z), PosetTrait.mergeLabels(setOf(z), order))
    }

    @Test
    fun `a stale label image neither deletes new base labels nor restores stale labels`() {
        val change = labelChange(setOf(x, y), setOf(x))

        assertEquals(setOf(x, z), PosetTrait.mergeLabels(setOf(x, z), listOf(change)))
    }

    @Test
    fun `no label changes preserve the base`() {
        val law = TestMultipleGraph(NativeStorageImpl()).edgePropertyLaw("labels")
        val present = ListVal(listOf(StrVal(x.core)))

        assertEquals(setOf(x), PosetTrait.mergeLabels(setOf(x), emptyList()))
        assertEquals(null, law.merge(null, emptyList()))
        assertEquals(present, law.merge(present, emptyList()))
    }
}
