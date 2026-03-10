package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.TestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeId1
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeId2
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeId3
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeId4
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId1
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId2
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId3
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId4
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

class AbcMultipleGraphTest {
    private lateinit var graph: AbcMultipleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        graph = createTestMultipleGraph(storage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
    }

    // region Node CRUD

    @Test
    fun `test addNode_newNode_returnsNodeWithCorrectID`() {
        val node = graph.addNode(nodeId1)

        assertEquals(nodeId1, node.id)
    }

    @Test
    fun `test addNode_newNode_appearsInNodeIDs`() {
        graph.addNode(nodeId1)

        assertTrue(graph.nodeIDs.contains(nodeId1))
    }

    @Test
    fun `test addNode_duplicate_throwsEntityAlreadyExist`() {
        graph.addNode(nodeId1)

        assertFailsWith<EntityAlreadyExistException> { graph.addNode(nodeId1) }
    }

    @Test
    fun `test getNode_existing_returnsNode`() {
        graph.addNode(nodeId1)

        val node = graph.getNode(nodeId1)

        assertNotNull(node)
        assertEquals(nodeId1, node.id)
    }

    @Test
    fun `test getNode_nonExistent_returnsNull`() {
        assertNull(graph.getNode(nodeId1))
    }

    @Test
    fun `test containNode_existing_returnsTrue`() {
        graph.addNode(nodeId1)

        assertTrue(graph.containNode(nodeId1))
    }

    @Test
    fun `test containNode_nonExistent_returnsFalse`() {
        assertFalse(graph.containNode(nodeId1))
    }

    @Test
    fun `test delNode_existing_removesNode`() {
        graph.addNode(nodeId1)

        graph.delNode(nodeId1)

        assertFalse(graph.containNode(nodeId1))
    }

    @Test
    fun `test delNode_withEdges_removesAssociatedEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        graph.delNode(nodeId1)

        assertFalse(graph.containEdge(edgeId1))
        assertTrue(graph.containNode(nodeId2))
    }

    @Test
    fun `test delNode_nonExistent_noOp`() {
        graph.delNode(nodeId1)

        assertFalse(graph.containNode(nodeId1))
    }

    @Test
    fun `test getAllNodes_empty_returnsEmptySequence`() {
        assertEquals(0, graph.getAllNodes().count())
    }

    @Test
    fun `test getAllNodes_multipleNodes_returnsAll`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)

        val ids = graph.getAllNodes().map { it.id }.toSet()

        assertEquals(setOf(nodeId1, nodeId2, nodeId3), ids)
    }

    @Test
    fun `test getAllNodes_withPredicate_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)

        val ids = graph.getAllNodes { it.id == nodeId1 }.map { it.id }.toList()

        assertEquals(listOf(nodeId1), ids)
    }

    // endregion

    // region Edge CRUD

    @Test
    fun `test addEdge_newEdge_returnsEdgeWithCorrectEndpoints`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)

        val edge = graph.addEdge(edgeId1)

        assertEquals(nodeId1, edge.srcNid)
        assertEquals(nodeId2, edge.dstNid)
    }

    @Test
    fun `test addEdge_duplicate_throwsEntityAlreadyExist`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        assertFailsWith<EntityAlreadyExistException> { graph.addEdge(edgeId1) }
    }

    @Test
    fun `test addEdge_multipleEdgesSameNodePair_allowed`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val eid1 = EdgeID(nodeId1, nodeId2, "rel1")
        val eid2 = EdgeID(nodeId1, nodeId2, "rel2")

        graph.addEdge(eid1)
        graph.addEdge(eid2)

        assertTrue(graph.containEdge(eid1))
        assertTrue(graph.containEdge(eid2))
    }

    @Test
    fun `test getEdge_existing_returnsEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        val edge = graph.getEdge(edgeId1)

        assertNotNull(edge)
        assertEquals(edgeId1, edge.id)
    }

    @Test
    fun `test getEdge_nonExistent_returnsNull`() {
        assertNull(graph.getEdge(edgeId1))
    }

    @Test
    fun `test containEdge_existing_returnsTrue`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        assertTrue(graph.containEdge(edgeId1))
    }

    @Test
    fun `test containEdge_nonExistent_returnsFalse`() {
        assertFalse(graph.containEdge(edgeId1))
    }

    @Test
    fun `test delEdge_existing_removesEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        graph.delEdge(edgeId1)

        assertFalse(graph.containEdge(edgeId1))
    }

    @Test
    fun `test delEdge_preservesNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        graph.delEdge(edgeId1)

        assertTrue(graph.containNode(nodeId1))
        assertTrue(graph.containNode(nodeId2))
    }

    @Test
    fun `test getAllEdges_empty_returnsEmptySequence`() {
        assertEquals(0, graph.getAllEdges().count())
    }

    @Test
    fun `test getAllEdges_multipleEdges_returnsAll`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(edgeId1)
        graph.addEdge(edgeId2)

        val ids = graph.getAllEdges().map { it.id }.toSet()

        assertEquals(setOf(edgeId1, edgeId2), ids)
    }

    @Test
    fun `test getAllEdges_withPredicate_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(edgeId1)
        graph.addEdge(edgeId2)

        val ids = graph.getAllEdges { it.srcNid == nodeId1 }.map { it.id }.toList()

        assertEquals(listOf(edgeId1), ids)
    }

    // endregion

    // region Graph structure queries

    @Test
    fun `test getOutgoingEdges_existingNode_returnsOutgoing`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(edgeId1) // node1 -> node2
        graph.addEdge(edgeId3) // node1 -> node3

        val ids = graph.getOutgoingEdges(nodeId1).map { it.id }.toSet()

        assertEquals(setOf(edgeId1, edgeId3), ids)
    }

    @Test
    fun `test getOutgoingEdges_noOutgoing_returnsEmpty`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1) // node1 -> node2

        val edges = graph.getOutgoingEdges(nodeId2).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getOutgoingEdges_nonExistentNode_returnsEmpty`() {
        val edges = graph.getOutgoingEdges(nodeId1).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getIncomingEdges_existingNode_returnsIncoming`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(edgeId1) // node1 -> node2
        val eid = EdgeID(nodeId3, nodeId2, "rel")
        graph.addEdge(eid)

        val ids = graph.getIncomingEdges(nodeId2).map { it.id }.toSet()

        assertEquals(setOf(edgeId1, eid), ids)
    }

    @Test
    fun `test getIncomingEdges_noIncoming_returnsEmpty`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1) // node1 -> node2

        val edges = graph.getIncomingEdges(nodeId1).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getChildren_returnsChildNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(edgeId1) // node1 -> node2
        graph.addEdge(edgeId3) // node1 -> node3

        val ids = graph.getChildren(nodeId1).map { it.id }.toSet()

        assertEquals(setOf(nodeId2, nodeId3), ids)
    }

    @Test
    fun `test getChildren_withEdgeCondition_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "typeA"))
        graph.addEdge(EdgeID(nodeId1, nodeId3, "typeB"))

        val ids = graph.getChildren(nodeId1) { it.eType == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getParents_returnsParentNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(edgeId1) // node1 -> node2
        graph.addEdge(EdgeID(nodeId3, nodeId2, "rel"))

        val ids = graph.getParents(nodeId2).map { it.id }.toSet()

        assertEquals(setOf(nodeId1, nodeId3), ids)
    }

    @Test
    fun `test getParents_withEdgeCondition_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "typeA"))
        graph.addEdge(EdgeID(nodeId3, nodeId2, "typeB"))

        val ids = graph.getParents(nodeId2) { it.eType == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(nodeId1), ids)
    }

    @Test
    fun `test getDescendants_linearChain_returnsAllDescendants`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addNode(nodeId4)
        graph.addEdge(edgeId1) // 1->2
        graph.addEdge(edgeId2) // 2->3
        graph.addEdge(edgeId4) // 3->4

        val ids = graph.getDescendants(nodeId1).map { it.id }.toSet()

        assertEquals(setOf(nodeId2, nodeId3, nodeId4), ids)
    }

    @Test
    fun `test getDescendants_withEdgeCondition_stopsAtFilteredEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "typeA"))
        graph.addEdge(EdgeID(nodeId2, nodeId3, "typeB"))

        val ids = graph.getDescendants(nodeId1) { it.eType == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getDescendants_cycle_terminatesWithoutDuplicates`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "fwd"))
        graph.addEdge(EdgeID(nodeId2, nodeId1, "back"))

        val descendants = graph.getDescendants(nodeId1).toList()

        assertTrue(descendants.any { it.id == nodeId2 })
        assertEquals(descendants.distinctBy { it.id }.size, descendants.size)
    }

    @Test
    fun `test getDescendants_nonExistentNode_returnsEmpty`() {
        assertTrue(graph.getDescendants(nodeId1).toList().isEmpty())
    }

    @Test
    fun `test getAncestors_linearChain_returnsAllAncestors`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addNode(nodeId4)
        graph.addEdge(edgeId1) // 1->2
        graph.addEdge(edgeId2) // 2->3
        graph.addEdge(edgeId4) // 3->4

        val ids = graph.getAncestors(nodeId4).map { it.id }.toSet()

        assertEquals(setOf(nodeId1, nodeId2, nodeId3), ids)
    }

    @Test
    fun `test getAncestors_withEdgeCondition_stopsAtFilteredEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "typeA"))
        graph.addEdge(EdgeID(nodeId2, nodeId3, "typeB"))

        val ids = graph.getAncestors(nodeId3) { it.eType == "typeB" }.map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getAncestors_cycle_terminatesWithoutDuplicates`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "fwd"))
        graph.addEdge(EdgeID(nodeId2, nodeId1, "back"))

        val ancestors = graph.getAncestors(nodeId1).toList()

        assertTrue(ancestors.any { it.id == nodeId2 })
        assertEquals(ancestors.distinctBy { it.id }.size, ancestors.size)
    }

    // endregion

    // region Label lattice

    @Test
    fun `test addEdge_withLabel_assignsLabel`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("v1")

        val edge = graph.addEdge(edgeId1, label)

        with(graph) { assertTrue(edge.labels.contains(label)) }
    }

    @Test
    fun `test addEdge_withLabel_existingEdge_addsLabel`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val labelA = Label("a")
        val labelB = Label("b")
        graph.addEdge(edgeId1, labelA)

        graph.addEdge(edgeId1, labelB)

        with(graph) {
            val edge = getEdge(edgeId1)!!
            assertTrue(edge.labels.containsAll(setOf(labelA, labelB)))
        }
    }

    @Test
    fun `test delEdge_withLabel_removesOnlyThatLabel`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val labelA = Label("a")
        val labelB = Label("b")
        graph.addEdge(edgeId1, labelA)
        graph.addEdge(edgeId1, labelB)

        graph.delEdge(edgeId1, labelA)

        assertTrue(graph.containEdge(edgeId1))
        with(graph) {
            val edge = getEdge(edgeId1)!!
            assertFalse(edge.labels.contains(labelA))
            assertTrue(edge.labels.contains(labelB))
        }
    }

    @Test
    fun `test delEdge_lastLabel_removesEdgeEntirely`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("only")
        graph.addEdge(edgeId1, label)

        graph.delEdge(edgeId1, label)

        assertFalse(graph.containEdge(edgeId1))
    }

    @Test
    fun `test labelParents_setAndGet_roundTrips`() {
        val child = Label("child")
        val parent = Label("parent")

        with(graph) {
            child.parents = mapOf("rel" to parent)
            assertEquals(mapOf("rel" to parent), child.parents)
        }
    }

    @Test
    fun `test labelAncestors_multiLevel_returnsAll`() {
        val grandparent = Label("gp")
        val parent = Label("p")
        val child = Label("c")

        with(graph) {
            child.parents = mapOf("up" to parent)
            parent.parents = mapOf("up" to grandparent)

            val ancestors = child.ancestors.toSet()

            assertTrue(ancestors.contains(parent))
            assertTrue(ancestors.contains(grandparent))
        }
    }

    @Test
    fun `test labelCompareTo_equal_returnsZero`() {
        val label = Label("same")
        with(graph) { assertEquals(0, label.compareTo(label)) }
    }

    @Test
    fun `test labelCompareTo_childVsParent_returnsNegative`() {
        val parent = Label("parent")
        val child = Label("child")
        with(graph) {
            child.parents = mapOf("up" to parent)
            val result = child.compareTo(parent)
            assertNotNull(result)
            assertTrue(result < 0)
        }
    }

    @Test
    fun `test labelCompareTo_parentVsChild_returnsPositive`() {
        val parent = Label("parent")
        val child = Label("child")
        with(graph) {
            child.parents = mapOf("up" to parent)
            val result = parent.compareTo(child)
            assertNotNull(result)
            assertTrue(result > 0)
        }
    }

    @Test
    fun `test labelCompareTo_incomparable_returnsNull`() {
        val a = Label("a")
        val b = Label("b")
        with(graph) { assertNull(a.compareTo(b)) }
    }

    @Test
    fun `test labelCompareTo_supremum_greaterThanAny`() {
        val label = Label("any")
        with(graph) {
            assertEquals(1, Label.SUPREMUM.compareTo(label))
            assertEquals(-1, label.compareTo(Label.SUPREMUM))
        }
    }

    @Test
    fun `test labelCompareTo_infimum_lessThanAny`() {
        val label = Label("any")
        with(graph) {
            assertEquals(-1, Label.INFIMUM.compareTo(label))
            assertEquals(1, label.compareTo(Label.INFIMUM))
        }
    }

    @Test
    fun `test allLabels_includesInfimumAndSupremum`() {
        with(graph) {
            assertTrue(allLabels.contains(Label.INFIMUM))
            assertTrue(allLabels.contains(Label.SUPREMUM))
        }
    }

    @Test
    fun `test getOutgoingEdges_withLabel_filtersVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val labelA = Label("a")
        val labelB = Label("b")
        graph.addEdge(EdgeID(nodeId1, nodeId2, "rel1"), labelA)
        graph.addEdge(EdgeID(nodeId1, nodeId3, "rel2"), labelB)

        val edges = graph.getOutgoingEdges(nodeId1, labelA).toList()

        assertEquals(1, edges.size)
        assertEquals(nodeId2, edges.first().dstNid)
    }

    @Test
    fun `test getOutgoingEdges_withSupremum_returnsAll`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(EdgeID(nodeId1, nodeId2, "rel1"), Label("a"))
        graph.addEdge(EdgeID(nodeId1, nodeId3, "rel2"), Label("b"))

        val edges = graph.getOutgoingEdges(nodeId1, Label.SUPREMUM).toList()

        assertEquals(2, edges.size)
    }

    @Test
    fun `test getIncomingEdges_withLabel_filtersVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(EdgeID(nodeId1, nodeId3, "rel1"), label)
        graph.addEdge(EdgeID(nodeId2, nodeId3, "rel2"), Label("other"))

        val edges = graph.getIncomingEdges(nodeId3, label).toList()

        assertEquals(1, edges.size)
        assertEquals(nodeId1, edges.first().srcNid)
    }

    @Test
    fun `test getChildren_withLabel_returnsVisibleChildren`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(EdgeID(nodeId1, nodeId2, "rel1"), label)
        graph.addEdge(EdgeID(nodeId1, nodeId3, "rel2"), Label("other"))

        val ids = graph.getChildren(nodeId1, label).map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getParents_withLabel_returnsVisibleParents`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(EdgeID(nodeId1, nodeId3, "rel1"), label)
        graph.addEdge(EdgeID(nodeId2, nodeId3, "rel2"), Label("other"))

        val ids = graph.getParents(nodeId3, label).map { it.id }.toList()

        assertEquals(listOf(nodeId1), ids)
    }

    @Test
    fun `test getDescendants_withLabel_traversesOnlyVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(EdgeID(nodeId1, nodeId2, "rel1"), label)
        graph.addEdge(EdgeID(nodeId2, nodeId3, "rel2"), Label("other"))

        val ids = graph.getDescendants(nodeId1, label).map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getAncestors_withLabel_traversesOnlyVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(EdgeID(nodeId1, nodeId2, "rel1"), Label("other"))
        graph.addEdge(EdgeID(nodeId2, nodeId3, "rel2"), label)

        val ids = graph.getAncestors(nodeId3, label).map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test labelVisibility_parentLabelSeesChildLabelEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val parent = Label("parent")
        val child = Label("child")
        with(graph) { child.parents = mapOf("up" to parent) }
        graph.addEdge(edgeId1, child)

        val edges = graph.getOutgoingEdges(nodeId1, parent).toList()

        assertEquals(1, edges.size)
    }

    @Test
    fun `test labelChanges_tracksEdgeAssignment`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("v1")

        graph.addEdge(edgeId1, label)

        with(graph) { assertTrue(label.changes.contains(edgeId1)) }
    }

    @Test
    fun `test storeLattice_loadLattice_roundTrips`() {
        val parent = Label("parent")
        val child = Label("child")
        with(graph) { child.parents = mapOf("rel" to parent) }
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1, child)

        val targetStorage = NativeStorageImpl()
        graph.storeLattice(targetStorage)

        val newGraph = createTestMultipleGraph(NativeStorageImpl())
        newGraph.loadLattice(targetStorage)

        with(newGraph) {
            assertEquals(mapOf("rel" to parent), child.parents)
        }
        targetStorage.close()
    }

    // endregion

    // region Cache/storage consistency

    @Test
    fun `test containNode_inStorageButNotCache_returnsFalse`() {
        storage.addNode(nodeId1)

        assertFalse(graph.containNode(nodeId1))
    }

    @Test
    fun `test containNode_inCacheButNotStorage_returnsFalse`() {
        val testGraph = TestMultipleGraph(storage)
        testGraph.exposeNodeIDs().add(nodeId1)

        assertFalse(testGraph.containNode(nodeId1))
    }

    @Test
    fun `test containEdge_inStorageButNotCache_returnsFalse`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        storage.addEdge(edgeId1)

        assertFalse(graph.containEdge(edgeId1))
    }

    @Test
    fun `test containEdge_inCacheButNotStorage_returnsFalse`() {
        val testGraph = TestMultipleGraph(storage)
        testGraph.exposeEdgeIDs().add(edgeId1)

        assertFalse(testGraph.containEdge(edgeId1))
    }

    @Test
    fun `test getNode_inStorageButNotCache_returnsNull`() {
        storage.addNode(nodeId1)

        assertNull(graph.getNode(nodeId1))
    }

    @Test
    fun `test getEdge_inStorageButNotCache_returnsNull`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        storage.addEdge(edgeId1)

        assertNull(graph.getEdge(edgeId1))
    }

    @Test
    fun `test getAllNodes_cacheOnlyEntry_filteredOut`() {
        val testGraph = TestMultipleGraph(storage)
        testGraph.addNode(nodeId1)
        testGraph.exposeNodeIDs().add(nodeId2)

        val ids = testGraph.getAllNodes().map { it.id }.toSet()

        assertEquals(setOf(nodeId1), ids)
    }

    @Test
    fun `test getAllEdges_cacheOnlyEntry_filteredOut`() {
        val testGraph = TestMultipleGraph(storage)
        testGraph.addNode(nodeId1)
        testGraph.addNode(nodeId2)
        testGraph.addEdge(edgeId1)
        val eid2 = EdgeID(nodeId1, nodeId2, "phantom")
        testGraph.exposeEdgeIDs().add(eid2)

        val ids = testGraph.getAllEdges().map { it.id }.toSet()

        assertEquals(setOf(edgeId1), ids)
    }

    @Test
    fun `test getOutgoingEdges_storageOnlyEdge_filteredOut`() {
        val testGraph = TestMultipleGraph(storage)
        testGraph.addNode(nodeId1)
        testGraph.addNode(nodeId2)
        storage.addEdge(edgeId1)

        val edges = testGraph.getOutgoingEdges(nodeId1).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test delNode_notInCache_doesNotRemoveFromStorage`() {
        storage.addNode(nodeId1)

        graph.delNode(nodeId1)

        assertTrue(storage.containsNode(nodeId1))
    }

    @Test
    fun `test delEdge_notInCache_doesNotRemoveFromStorage`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        storage.addEdge(edgeId1)

        graph.delEdge(edgeId1)

        assertTrue(storage.containsEdge(edgeId1))
    }

    // endregion

    // region Close

    @Test
    fun `test close_clearsNodeAndEdgeCaches`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(edgeId1)

        graph.close()

        assertTrue(graph.nodeIDs.isEmpty())
        assertTrue(graph.edgeIDs.isEmpty())
    }

    // endregion

    // region Performance

    @Test
    fun `test bulkNodeInsertion_completesInTime`() {
        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }
            }

        assertEquals(PERF_NODE_COUNT, graph.nodeIDs.size)
        println("MultipleGraph: $PERF_NODE_COUNT nodes inserted in $elapsed")
    }

    @Test
    fun `test bulkEdgeInsertion_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_EDGE_COUNT) { i ->
                    val src = NodeID("n${i % PERF_NODE_COUNT}")
                    val dst = NodeID("n${(i + 1) % PERF_NODE_COUNT}")
                    graph.addEdge(EdgeID(src, dst, "e$i"))
                }
            }

        assertEquals(PERF_EDGE_COUNT, graph.edgeIDs.size)
        println("MultipleGraph: $PERF_EDGE_COUNT edges inserted in $elapsed")
    }

    @Test
    fun `test nodeLookup_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.getNode(NodeID("n$i")) }
            }

        println("MultipleGraph: $PERF_NODE_COUNT node lookups in $elapsed")
    }

    @Test
    fun `test edgeLookup_completesInTime`() {
        val edgeIds = mutableListOf<EdgeID>()
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }
        repeat(PERF_EDGE_COUNT) { i ->
            val eid = EdgeID(NodeID("n${i % PERF_NODE_COUNT}"), NodeID("n${(i + 1) % PERF_NODE_COUNT}"), "e$i")
            graph.addEdge(eid)
            edgeIds.add(eid)
        }

        val elapsed =
            kotlin.time.measureTime {
                edgeIds.forEach { graph.getEdge(it) }
            }

        println("MultipleGraph: $PERF_EDGE_COUNT edge lookups in $elapsed")
    }

    @Test
    fun `test outgoingEdgeQuery_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }
        repeat(PERF_EDGE_COUNT) { i ->
            graph.addEdge(EdgeID(NodeID("n${i % PERF_NODE_COUNT}"), NodeID("n${(i + 1) % PERF_NODE_COUNT}"), "e$i"))
        }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i ->
                    graph.getOutgoingEdges(NodeID("n$i")).toList()
                }
            }

        println("MultipleGraph: outgoing edge queries for $PERF_NODE_COUNT nodes in $elapsed")
    }

    @Test
    fun `test descendantTraversal_completesInTime`() {
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode(NodeID("c$i")) }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            graph.addEdge(EdgeID(NodeID("c$i"), NodeID("c${i + 1}"), "next"))
        }

        val elapsed =
            kotlin.time.measureTime {
                val descendants = graph.getDescendants(NodeID("c0")).toList()
                assertEquals(PERF_CHAIN_LENGTH - 1, descendants.size)
            }

        println("MultipleGraph: descendant traversal of chain($PERF_CHAIN_LENGTH) in $elapsed")
    }

    @Test
    fun `test ancestorTraversal_completesInTime`() {
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode(NodeID("c$i")) }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            graph.addEdge(EdgeID(NodeID("c$i"), NodeID("c${i + 1}"), "next"))
        }

        val elapsed =
            kotlin.time.measureTime {
                val ancestors = graph.getAncestors(NodeID("c${PERF_CHAIN_LENGTH - 1}")).toList()
                assertEquals(PERF_CHAIN_LENGTH - 1, ancestors.size)
            }

        println("MultipleGraph: ancestor traversal of chain($PERF_CHAIN_LENGTH) in $elapsed")
    }

    @Test
    fun `test bulkNodeDeletion_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode(NodeID("n$i")) }
        repeat(PERF_EDGE_COUNT) { i ->
            graph.addEdge(EdgeID(NodeID("n${i % PERF_NODE_COUNT}"), NodeID("n${(i + 1) % PERF_NODE_COUNT}"), "e$i"))
        }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.delNode(NodeID("n$i")) }
            }

        assertEquals(0, graph.nodeIDs.size)
        assertEquals(0, graph.edgeIDs.size)
        println("MultipleGraph: $PERF_NODE_COUNT node deletions (with edges) in $elapsed")
    }

    @Test
    fun `test labelFilteredTraversal_completesInTime`() {
        val labelA = Label("a")
        val labelB = Label("b")
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode(NodeID("c$i")) }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            val label = if (i % 2 == 0) labelA else labelB
            graph.addEdge(EdgeID(NodeID("c$i"), NodeID("c${i + 1}"), "next"), label)
        }

        val elapsed =
            kotlin.time.measureTime {
                graph.getDescendants(NodeID("c0"), labelA).toList()
            }

        println("MultipleGraph: label-filtered descendant traversal of chain($PERF_CHAIN_LENGTH) in $elapsed")
    }

    // endregion

    companion object {
        private const val PERF_NODE_COUNT = 1000
        private const val PERF_EDGE_COUNT = 5000
        private const val PERF_CHAIN_LENGTH = 500
    }
}
