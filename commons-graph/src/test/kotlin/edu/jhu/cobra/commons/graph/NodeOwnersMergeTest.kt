package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_OWNERS
import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphFixtures.permutations
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Black-box tests for the owner union law of a node: the base owners plus the owners each writer adds
 * (model.md Merge Laws; design-graph.md GraphMergeLaws). Only the revert test reads and writes storage.
 *
 * - `owner union holds every owner in either order` -- order independence
 * - `owner union of three graphs keeps all three` -- AST, ADG and CCG owner marks on one row
 * - `owner union ignores a stale owner a writer carried unchanged` -- additions only
 * - `owner union on an absent owner set` -- no base set
 * - `owner union with no changes keeps the base` -- empty change collection
 * - `a revert after the three-owner union restores the first owner only` -- layered storage fixture
 */
internal class NodeOwnersMergeTest {
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
        val merged =
            ownerLaw.merge(
                ownerSet("ast"),
                listOf(ownerChange(ownerSet("ast", "old"), ownerSet("ast", "old", "adg"))),
            )

        assertEquals(ownerSet("ast", "adg"), merged)
    }

    @Test
    fun `owner union on an absent owner set`() {
        val merged =
            ownerLaw.merge(null, listOf(ownerChange(null, ownerSet("adg")), ownerChange(null, ownerSet("ccg"))))

        assertEquals(ownerSet("adg", "ccg"), merged)
        assertNull(ownerLaw.merge(null, listOf(ownerChange(null, null))))
    }

    @Test
    fun `owner union with no changes keeps the base`() {
        assertEquals(ownerSet("ast"), ownerLaw.merge(ownerSet("ast"), emptyList()))
        assertNull(ownerLaw.merge(null, emptyList()))
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
}
