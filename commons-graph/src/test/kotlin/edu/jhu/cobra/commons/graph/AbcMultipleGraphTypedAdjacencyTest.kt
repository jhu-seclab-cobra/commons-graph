package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_4
import edu.jhu.cobra.commons.graph.GraphFixtures.TestEdge
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Black-box tests for typed adjacency: two graphs share one storage and each owns a tag
 * set; its traversals read only the edges whose tag it owns.
 *
 * - `getDescendants ignores an edge of another graph` -- verifies outgoing walk
 * - `getAncestors ignores an edge of another graph` -- verifies incoming walk
 * - `traversal follows an edge of its own tag` -- verifies the owned edges still walk
 * - `a foreign edge parallel to an own edge does not hide the own edge` -- yields the target once
 * - `a foreign edge in the middle of a chain stops the walk there` -- own edges on both sides
 */
internal class AbcMultipleGraphTypedAdjacencyTest {
    private class TaggedGraph(
        override val storage: IStorage,
        override val graphId: String,
        private val ownTags: Set<String>,
    ) : AbcMultipleGraph<TestNode, TestEdge>() {
        override fun newNodeObj() = TestNode()

        override fun newEdgeObj() = TestEdge()

        override fun getOutgoingEdges(of: NodeID): Sequence<TestEdge> = super.getOutgoingEdges(of).filter { it.eTag in ownTags }

        override fun getIncomingEdges(of: NodeID): Sequence<TestEdge> = super.getIncomingEdges(of).filter { it.eTag in ownTags }
    }

    private lateinit var graphA: TaggedGraph
    private lateinit var graphB: TaggedGraph

    @BeforeTest
    fun setUp() {
        val storage = NativeStorageImpl()
        graphA = TaggedGraph(storage, "GraphA", setOf("a"))
        graphB = TaggedGraph(storage, "GraphB", setOf("b"))
        listOf(NODE_ID_1, NODE_ID_2, NODE_ID_3).forEach { graphB.claimNode(graphA.addNode(it)) }
        graphA.addEdge(NODE_ID_1, NODE_ID_2, "a")
        graphB.addEdge(NODE_ID_2, NODE_ID_3, "b")
    }

    @Test
    fun `getDescendants ignores an edge of another graph`() {
        assertEquals(setOf(NODE_ID_2), graphA.getDescendants(NODE_ID_1).map { it.id }.toSet())
        assertEquals(emptySet(), graphA.getDescendants(NODE_ID_2).map { it.id }.toSet())
    }

    @Test
    fun `getAncestors ignores an edge of another graph`() {
        assertEquals(emptySet(), graphA.getAncestors(NODE_ID_3).map { it.id }.toSet())
        assertEquals(setOf(NODE_ID_1), graphA.getAncestors(NODE_ID_2).map { it.id }.toSet())
    }

    @Test
    fun `traversal follows an edge of its own tag`() {
        assertEquals(setOf(NODE_ID_3), graphB.getDescendants(NODE_ID_2).map { it.id }.toSet())
        assertEquals(setOf(NODE_ID_2), graphB.getAncestors(NODE_ID_3).map { it.id }.toSet())
    }

    @Test
    fun `a foreign edge parallel to an own edge does not hide the own edge`() {
        graphB.addEdge(NODE_ID_1, NODE_ID_2, "b")

        assertEquals(listOf(NODE_ID_2), graphA.getDescendants(NODE_ID_1).map { it.id }.toList())
        assertEquals(listOf(NODE_ID_1), graphA.getAncestors(NODE_ID_2).map { it.id }.toList())
    }

    @Test
    fun `a foreign edge in the middle of a chain stops the walk there`() {
        graphB.claimNode(graphA.addNode(NODE_ID_4))
        graphA.addEdge(NODE_ID_3, NODE_ID_4, "a")

        assertEquals(setOf(NODE_ID_2), graphA.getDescendants(NODE_ID_1).map { it.id }.toSet())
        assertEquals(setOf(NODE_ID_3), graphA.getAncestors(NODE_ID_4).map { it.id }.toSet())
    }
}
