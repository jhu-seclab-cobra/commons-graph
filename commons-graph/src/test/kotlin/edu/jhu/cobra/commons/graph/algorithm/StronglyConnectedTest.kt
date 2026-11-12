/**
 * Behavioral tests for the strongly connected components of a directed graph (Tarjan 1972). Components come out
 * in reverse topological order: a component precedes every component that reaches it.
 *
 * - `an acyclic chain gives singleton components callees first` -- a to b to c yields c, b, a
 * - `a cycle is one component` -- a to b to c to a yields one component of three nodes
 * - `a self loop is a singleton component` -- a to a yields one component of one node
 * - `a component precedes the components that reach it` -- a to the cycle b, c to d yields d, then the cycle, then a
 * - `a node that is not a key is a singleton component` -- a to z with no entry for z yields z, a
 * - `keys of another type are grouped the same way` -- 1 and 2 reach each other and 3 yields 3, then 1 and 2
 * - `a chain of 100000 nodes runs on the default stack` -- no recursion per node
 */
package edu.jhu.cobra.commons.graph.algorithm

import kotlin.test.Test
import kotlin.test.assertEquals

internal class StronglyConnectedTest {

    @Test
    fun `an acyclic chain gives singleton components callees first`() {
        val components = tarjanScc(mapOf("a" to setOf("b"), "b" to setOf("c"), "c" to emptySet()))
        assertEquals(listOf(setOf("c"), setOf("b"), setOf("a")), components)
    }

    @Test
    fun `a cycle is one component`() {
        val components = tarjanScc(mapOf("a" to setOf("b"), "b" to setOf("c"), "c" to setOf("a")))
        assertEquals(listOf(setOf("a", "b", "c")), components)
    }

    @Test
    fun `a self loop is a singleton component`() {
        assertEquals(listOf(setOf("a")), tarjanScc(mapOf("a" to setOf("a"))))
    }

    @Test
    fun `a component precedes the components that reach it`() {
        val graph = mapOf("a" to setOf("b"), "b" to setOf("c", "d"), "c" to setOf("b"), "d" to emptySet())
        assertEquals(listOf(setOf("d"), setOf("b", "c"), setOf("a")), tarjanScc(graph))
    }

    @Test
    fun `a node that is not a key is a singleton component`() {
        assertEquals(listOf(setOf("z"), setOf("a")), tarjanScc(mapOf("a" to setOf("z"))))
    }

    @Test
    fun `keys of another type are grouped the same way`() {
        assertEquals(listOf(setOf(3), setOf(1, 2)), tarjanScc(mapOf(1 to setOf(2), 2 to setOf(1, 3))))
    }

    @Test
    fun `a chain of 100000 nodes runs on the default stack`() {
        val length = 100_000
        val graph = (0 until length).associate { "n$it" to setOf("n${it + 1}") }
        assertEquals(length + 1, tarjanScc(graph).size)
    }
}
