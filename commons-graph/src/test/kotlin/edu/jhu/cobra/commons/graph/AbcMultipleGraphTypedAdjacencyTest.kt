package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_4
import edu.jhu.cobra.commons.graph.GraphFixtures.TestEdge
import edu.jhu.cobra.commons.graph.GraphFixtures.TestNode
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Black-box tests for typed adjacency: two graphs share one storage and each owns a tag
 * set; its traversals read only the edges whose tag it owns.
 *
 * - `all selection shapes retain graph visibility` -- full, grouped and all-edge reads share one declaration
 * - `restored edge resolution retains graph visibility` -- restored callbacks never resolve foreign edges
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
        var restoredTags: Set<String> = emptySet()
            private set

        override fun onRestoredEdges(ids: Set<Int>) {
            restoredTags = ids.mapNotNull { edgeByStorageId(it) }.mapTo(LinkedHashSet()) { it.eTag }
        }

        override fun newNodeObj() = TestNode()

        override fun newEdgeObj() = TestEdge()

        override fun isEdgeVisible(edge: TestEdge): Boolean = edge.eTag in ownTags
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
    fun `all selection shapes retain graph visibility`() {
        val seen = ArrayList<String>()
        assertEquals(listOf("a"), graphA.getAllEdges { seen += it.eTag; true }.map { it.eTag }.toList())
        assertEquals(listOf("a"), seen)
        for (node in listOf(NODE_ID_1, NODE_ID_2, NODE_ID_3)) {
            for (group in listOf("a", "b")) {
                assertEquals(graphA.getOutgoingEdges(node).filter { it.eTag == group }.toList(),
                    graphA.getOutgoingEdges(node, group).toList())
                assertEquals(graphA.getIncomingEdges(node).filter { it.eTag == group }.toList(),
                    graphA.getIncomingEdges(node, group).toList())
            }
            assertEquals(graphA.getOutgoingEdges(node).toList(),
                graphA.getOutgoingEdges(node, setOf("b", "a")).toList())
            assertEquals(graphA.getIncomingEdges(node).toList(),
                graphA.getIncomingEdges(node, setOf("b", "a")).toList())
            assertEquals(emptyList(), graphA.getOutgoingEdges(node, emptySet()).toList())
        }
    }

    @Test
    fun `restored edge resolution retains graph visibility`() {
        val storage = LayeredStorageImpl()
        val own = TaggedGraph(storage, "own", setOf("a"))
        val foreign = TaggedGraph(storage, "foreign", setOf("b"))
        foreign.claimNode(own.addNode(NODE_ID_1))
        foreign.claimNode(own.addNode(NODE_ID_2))
        val first = own.addEdge(NODE_ID_1, NODE_ID_2, "a")
        val other = foreign.addEdge(NODE_ID_1, NODE_ID_2, "b")
        storage.freeze()
        storage.deleteEdge(first.storageId)
        storage.deleteEdge(other.storageId)
        storage.clearActiveLayer()
        assertEquals(setOf("a"), own.restoredTags)
        assertEquals(setOf("b"), foreign.restoredTags)
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
