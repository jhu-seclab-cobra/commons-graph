/**
 * Behavioral tests for the immediate dominators of a rooted directed graph (Lengauer and Tarjan 1979). A node d
 * dominates n when every path from the root to n passes d; the immediate dominator of n is the dominator of n that
 * every other strict dominator of n dominates.
 *
 * - `a diamond is dominated by its fork` -- root to a and b, both to c: the immediate dominator of c is the root
 * - `a chain is dominated by its predecessor` -- root, a, b, c in a line
 * - `a loop header dominates the loop body` -- root to h, h to b, b back to h, h to x
 * - `a node not reachable from the root has no dominator` -- an isolated node is absent from the result
 * - `an irreducible region is dominated by its fork` -- two nodes that enter each other from the root
 * - `the root alone has no dominators` -- an empty result
 * - `random graphs agree with the definition of dominance` -- 200 seeded graphs of 12 nodes compared with a removal
 *   test: d dominates n when n is unreachable from the root once d is removed
 */
package edu.jhu.cobra.commons.graph.algorithm

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

internal class DominatorTreeTest {

    private fun graph(vararg edges: Pair<String, String>): Map<String, List<String>> =
        edges.groupBy({ it.first }, { it.second })

    @Test
    fun `a diamond is dominated by its fork`() {
        val idom = immediateDominators("r", graph("r" to "a", "r" to "b", "a" to "c", "b" to "c"))
        assertEquals(mapOf("a" to "r", "b" to "r", "c" to "r"), idom)
    }

    @Test
    fun `a chain is dominated by its predecessor`() {
        val idom = immediateDominators("r", graph("r" to "a", "a" to "b", "b" to "c"))
        assertEquals(mapOf("a" to "r", "b" to "a", "c" to "b"), idom)
    }

    @Test
    fun `a loop header dominates the loop body`() {
        val idom = immediateDominators("r", graph("r" to "h", "h" to "b", "b" to "h", "h" to "x"))
        assertEquals(mapOf("h" to "r", "b" to "h", "x" to "h"), idom)
    }

    @Test
    fun `a node not reachable from the root has no dominator`() {
        val idom = immediateDominators("r", graph("r" to "a", "z" to "a"))
        assertEquals(mapOf("a" to "r"), idom)
    }

    @Test
    fun `an irreducible region is dominated by its fork`() {
        val idom = immediateDominators("r", graph("r" to "a", "r" to "b", "a" to "b", "b" to "a", "b" to "x"))
        assertEquals(mapOf("a" to "r", "b" to "r", "x" to "b"), idom)
    }

    @Test
    fun `the root alone has no dominators`() {
        assertEquals(emptyMap(), immediateDominators("r", emptyMap<String, List<String>>()))
    }

    // The nodes reachable from [root] without entering [removed].
    private fun reachable(root: String, successors: Map<String, List<String>>, removed: String?): Set<String> {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node == removed || !seen.add(node)) continue
            stack.addAll(successors[node].orEmpty())
        }
        return seen
    }

    // The immediate dominator by definition: the strict dominator that every other strict dominator dominates.
    private fun byDefinition(root: String, successors: Map<String, List<String>>): Map<String, String> {
        val all = reachable(root, successors, removed = null)
        val dominators = all.associateWith { node ->
            all.filter { it != node && node !in reachable(root, successors, removed = it) }.toSet()
        }
        return (all - root).associateWith { node ->
            val strict = dominators.getValue(node)
            strict.single { candidate -> dominators.getValue(candidate) == strict - candidate }
        }
    }

    @Test
    fun `random graphs agree with the definition of dominance`() {
        val random = Random(2026)
        repeat(200) {
            val names = (0 until 12).map { "n$it" }
            val successors = names.associateWith { names.filter { random.nextInt(100) < 18 } }
            assertEquals(byDefinition("n0", successors), immediateDominators("n0", successors), "graph $successors")
        }
    }
}
