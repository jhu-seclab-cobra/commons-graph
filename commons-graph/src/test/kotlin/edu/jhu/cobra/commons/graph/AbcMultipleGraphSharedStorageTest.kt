package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_1
import edu.jhu.cobra.commons.graph.GraphFixtures.EDGE_TAG_2
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphFixtures.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphFixtures.TestMultipleGraph
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Black-box tests for shared-storage scenarios: claimNode, ownership marks, rebuild, doUseStorage,
 * and edge isolation between graphs sharing one IStorage.
 *
 * - `claimNode registers existing storage row` — verifies claim creates a typed view
 * - `claimNode already claimed returns existing node` — verifies idempotency
 * - `claimNode shares properties with original graph` — verifies shared property state
 * - `doUseStorage returns true for bound storage` — verifies positive match
 * - `doUseStorage returns false for different storage` — verifies negative match
 * - `addNode marks the adder as the only owner` — verifies the mark is written at creation
 * - `claimNode marks the claimer next to the adder` — verifies the claim extends the owner set
 * - `claimNode of a node whose row was deleted raises EntityNotExistException` — verifies a claim needs an existing row
 * - `claimNode of an owned node leaves the owner set unchanged` — verifies a repeated claim adds no second mark
 * - `claimNode of a layered frozen node marks the claimer` — verifies the mark lands in the active layer
 * - `rebuild restores nodes owned by this graph` — verifies selective restoration without any flush
 * - `rebuild skips a row with no owner set` — verifies an unmarked row belongs to no graph
 * - `rebuild restores a claimed node in both owners` — verifies a shared row loads in every owner
 * - `a graph built over another graph's nodes loads only its own` — verifies order independence
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
        original["color"] =
            edu.jhu.cobra.commons.value
                .StrVal("red")

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

    // region ownership marks

    private fun ownersOf(storageId: Int): Set<String> =
        (storage.getNodeProperty(storageId, AbcMultipleGraph.PROP_OWNERS) as SetVal)
            .core
            .map { (it as StrVal).core }
            .toSet()

    @Test
    fun `addNode marks the adder as the only owner`() {
        val node = graphA.addNode(NODE_ID_1)

        assertEquals(setOf("GraphA"), ownersOf(node.storageId))
    }

    @Test
    fun `claimNode marks the claimer next to the adder`() {
        val original = graphA.addNode(NODE_ID_1)

        val claimed = graphB.claimNode(original)

        assertEquals(setOf("GraphA", "GraphB"), ownersOf(claimed.storageId))
    }

    @Test
    fun `claimNode of an owned node leaves the owner set unchanged`() {
        val original = graphA.addNode(NODE_ID_1)
        graphB.claimNode(original)

        graphB.claimNode(original)
        graphA.claimNode(original)

        assertEquals(setOf("GraphA", "GraphB"), ownersOf(original.storageId))
    }

    @Test
    fun `claimNode of a node whose row was deleted raises EntityNotExistException`() {
        val original = graphA.addNode(NODE_ID_1)
        graphA.delNode(NODE_ID_1)

        assertFailsWith<EntityNotExistException> { graphB.claimNode(original) }
        assertFalse(graphB.containNode(NODE_ID_1))
    }

    @Test
    fun `claimNode of a layered frozen node marks the claimer`() {
        val layered = LayeredStorageImpl()
        val adder = TestMultipleGraph(layered, "GraphA")
        val original = adder.addNode(NODE_ID_1)
        layered.freeze()

        val claimed = TestMultipleGraph(layered, "GraphB").claimNode(original)

        val owners = layered.getNodeProperty(claimed.storageId, AbcMultipleGraph.PROP_OWNERS) as SetVal
        assertEquals(setOf("GraphA", "GraphB"), owners.core.map { (it as StrVal).core }.toSet())
    }

    // endregion

    // region rebuild

    @Test
    fun `rebuild restores nodes owned by this graph`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
        graphB.addNode(NODE_ID_3)

        val freshA = TestMultipleGraph(storage, "GraphA")
        freshA.doRebuild()

        assertEquals(setOf(NODE_ID_1, NODE_ID_2), freshA.nodeIDs)
    }

    @Test
    fun `rebuild skips a row with no owner set`() {
        graphA.addNode(NODE_ID_1)
        storage.addNode(mapOf(AbcMultipleGraph.PROP_NODE_ID to NODE_ID_2.strVal))

        val fresh = TestMultipleGraph(storage, "GraphA")
        fresh.doRebuild()

        assertEquals(setOf(NODE_ID_1), fresh.nodeIDs)
    }

    @Test
    fun `rebuild restores a claimed node in both owners`() {
        val original = graphA.addNode(NODE_ID_1)
        graphB.claimNode(original)

        val freshA = TestMultipleGraph(storage, "GraphA").apply { doRebuild() }
        val freshB = TestMultipleGraph(storage, "GraphB").apply { doRebuild() }

        assertEquals(setOf(NODE_ID_1), freshA.nodeIDs)
        assertEquals(setOf(NODE_ID_1), freshB.nodeIDs)
    }

    @Test
    fun `a graph built over another graph's nodes loads only its own`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)

        val other = TestMultipleGraph(storage, "GraphC")
        other.doRebuild()
        other.addNode(NODE_ID_3)

        assertEquals(setOf(NODE_ID_3), other.nodeIDs)
        assertEquals(setOf(NODE_ID_3), TestMultipleGraph(storage, "GraphC").apply { doRebuild() }.nodeIDs)
    }

    @Test
    fun `rebuild clears stale node entries`() {
        graphA.addNode(NODE_ID_1)
        graphA.addNode(NODE_ID_2)
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
