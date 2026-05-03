package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.EDGE_TAG_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.TestMultipleGraph
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box tests for shared-storage scenarios: claimNode, flush, rebuild, doUseStorage,
 * and edge isolation between graphs sharing one IStorage.
 *
 * - `claimNode registers existing storage row` — verifies claim creates a typed view
 * - `claimNode already claimed returns existing node` — verifies idempotency
 * - `claimNode shares properties with original graph` — verifies shared property state
 * - `doUseStorage returns true for bound storage` — verifies positive match
 * - `doUseStorage returns false for different storage` — verifies negative match
 * - `flush writes PROP_OWNERS for each node` — verifies ownership persistence
 * - `flush is idempotent` — verifies repeated flush does not corrupt state
 * - `rebuild restores nodes owned by this graph` — verifies selective restoration
 * - `rebuild without prior flush restores all nodes` — verifies fallback behavior
 * - `rebuild clears stale node entries` — verifies clean rebuild
 * - `getAllEdges excludes edges with foreign endpoints` — verifies edge isolation
 * - `getOutgoingEdges excludes edges to unclaimed nodes` — verifies outgoing isolation
 * - `getIncomingEdges excludes edges from unclaimed nodes` — verifies incoming isolation
 * - `getChildren excludes nodes not in this graph` — verifies child isolation
 * - `getParents excludes nodes not in this graph` — verifies parent isolation
 */
internal class AbcMultipleGraphSharedStorageTest {
    private lateinit var storage: NativeStorageImpl
    private lateinit var graphA: TestMultipleGraph
    private lateinit var graphB: TestMultipleGraph

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        graphA = TestMultipleGraph(storage, "GraphA")
        graphB = TestMultipleGraph(storage, "GraphB")
    }

    // region claimNode

    @Test
    fun `claimNode registers existing storage row`() {
        val original = graphA.addNode(NODE_ID_1)

        val claimed = graphB.claimNode(original)

        assertEquals(NODE_ID_1, claimed.id)
        assertTrue(graphB.containNode(NODE_ID_1))
    }

    @Test
    fun `claimNode already claimed returns existing node`() {
        val original = graphA.addNode(NODE_ID_1)
        graphB.claimNode(original)

        val second = graphB.claimNode(original)

        assertEquals(NODE_ID_1, second.id)
        assertEquals(1, graphB.nodeIDs.size)
    }

    @Test
    fun `claimNode shares properties with original graph`() {
        val original = graphA.addNode(NODE_ID_1)
        original["color"] = edu.jhu.cobra.commons.value.StrVal("red")

        val claimed = graphB.claimNode(original)

        assertEquals("red", (claimed["color"] as edu.jhu.cobra.commons.value.StrVal).core)
    }

    // endregion

    // region doUseStorage

    @Test
    fun `doUseStorage returns true for bound storage`() {
        val node = graphA.addNode(NODE_ID_1)

        assertTrue(node.doUseStorage(storage))
    }

    @Test
    fun `doUseStorage returns false for different storage`() {
        val node = graphA.addNode(NODE_ID_1)
        val other = NativeStorageImpl()

        assertFalse(node.doUseStorage(other))
    }

    // endregion

    // region flush

    @Test
    fun `flush writes PROP_OWNERS for each node`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)

        graphA.flush()

        for (sid in storage.nodeIDs) {
            val owners = storage.getNodeProperty(sid, AbcMultipleGraph.PROP_OWNERS)
            assertNotNull(owners)
        }
    }

    @Test
    fun `flush is idempotent`() {
        graphA.addNode(NODE_ID_1)
        graphA.flush()

        graphA.flush()

        val sid = storage.nodeIDs.first()
        val owners = storage.getNodeProperty(sid, AbcMultipleGraph.PROP_OWNERS)
            as edu.jhu.cobra.commons.value.SetVal
        val ownerList = owners.core.map { (it as edu.jhu.cobra.commons.value.StrVal).core }
        assertEquals(1, ownerList.count { it == graphA.graphId })
    }

    // endregion

    // region rebuild

    @Test
    fun `rebuild restores nodes owned by this graph`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
        graphB.addNode(NODE_ID_3)
        graphA.flush()
        graphB.flush()

        val freshA = TestMultipleGraph(storage, "GraphA")
        freshA.doRebuild()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2), freshA.nodeIDs)
    }

    @Test
    fun `rebuild without prior flush restores all nodes`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)

        val fresh = TestMultipleGraph(storage)
        fresh.doRebuild()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2), fresh.nodeIDs)
    }

    @Test
    fun `rebuild clears stale node entries`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
        graphA.flush()
        graphA.delNode(NODE_ID_2)

        val fresh = TestMultipleGraph(storage, "GraphA")
        fresh.doRebuild()

        assertTrue(fresh.containNode(NODE_ID_1))
        assertFalse(fresh.containNode(NODE_ID_2))
    }

    // endregion

    // region Edge isolation

    @Test
    fun `getAllEdges excludes edges with foreign endpoints`() {
        val n1 = graphA.addNode(NODE_ID_1)
        val n2 = graphA.addNode(NODE_ID_2)
        graphA.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        graphB.claimNode(n1)

        assertEquals(0, graphB.getAllEdges().count())
    }

    @Test
    fun `getOutgoingEdges excludes edges to unclaimed nodes`() {
        val n1 = graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
        graphA.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        graphB.claimNode(n1)

        assertEquals(0, graphB.getOutgoingEdges(NODE_ID_1).count())
    }

    @Test
    fun `getIncomingEdges excludes edges from unclaimed nodes`() {
        graphA.addNode(NODE_ID_1)
        val n2 = graphA.addNode(NODE_ID_2)
        graphA.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)

        graphB.claimNode(n2)

        assertEquals(0, graphB.getIncomingEdges(NODE_ID_2).count())
    }

    @Test
    fun `getChildren excludes nodes not in this graph`() {
        val n1 = graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
        graphA.addNode(NODE_ID_3)
        graphA.addEdge(NODE_ID_1, NODE_ID_2, EDGE_TAG_1)
        graphA.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_2)

        graphB.claimNode(n1)
        graphB.claimNode(graphA.getNode(NODE_ID_2)!!)

        val children = graphB.getChildren(NODE_ID_1).map { it.id }.toSet()
        assertEquals(setOf(NODE_ID_2), children)
    }

    @Test
    fun `getParents excludes nodes not in this graph`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
        val n3 = graphA.addNode(NODE_ID_3)
        graphA.addEdge(NODE_ID_1, NODE_ID_3, EDGE_TAG_1)
        graphA.addEdge(NODE_ID_2, NODE_ID_3, EDGE_TAG_2)

        graphB.claimNode(n3)
        graphB.claimNode(graphA.getNode(NODE_ID_1)!!)

        val parents = graphB.getParents(NODE_ID_3).map { it.id }.toSet()
        assertEquals(setOf(NODE_ID_1), parents)
    }

    // endregion
}
