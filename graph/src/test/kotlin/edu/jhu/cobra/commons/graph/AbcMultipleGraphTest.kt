package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestMultipleGraph
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeType1
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeType2
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeType3
import edu.jhu.cobra.commons.graph.GraphTestUtils.edgeType4
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId1
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId2
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId3
import edu.jhu.cobra.commons.graph.GraphTestUtils.nodeId4
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.*

class AbcMultipleGraphTest {
    private lateinit var graph: AbcMultipleGraph<GraphTestUtils.TestNode, GraphTestUtils.TestEdge>
    private lateinit var storage: NativeStorageImpl
    private lateinit var posetStorage: NativeStorageImpl

    @BeforeTest
    fun setup() {
        storage = NativeStorageImpl()
        posetStorage = NativeStorageImpl()
        graph = createTestMultipleGraph(storage, posetStorage)
    }

    @AfterTest
    fun cleanup() {
        storage.close()
        posetStorage.close()
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
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        graph.delNode(nodeId1)

        assertFalse(graph.getAllEdges().any { it.srcNid == nodeId1 && it.dstNid == nodeId2 && it.eType == edgeType1 })
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

        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertEquals(nodeId1, edge.srcNid)
        assertEquals(nodeId2, edge.dstNid)
    }

    @Test
    fun `test addEdge_duplicateTypeAndEndpoints_allowedInMultiGraph`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val e1 = graph.addEdge(nodeId1, nodeId2, edgeType1)
        val e2 = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertNotEquals(e1.internalId, e2.internalId)
    }

    @Test
    fun `test addEdge_multipleEdgesSameNodePair_allowed`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)

        val e1 = graph.addEdge(nodeId1, nodeId2, "rel1")
        val e2 = graph.addEdge(nodeId1, nodeId2, "rel2")

        assertTrue(graph.containEdge(e1.srcNid, e1.dstNid, e1.eType))
        assertTrue(graph.containEdge(e2.srcNid, e2.dstNid, e2.eType))
    }

    @Test
    fun `test getEdge_existing_returnsEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        val retrieved = graph.getEdge(edge.srcNid, edge.dstNid, edge.eType)

        assertNotNull(retrieved)
        assertEquals(edge.id, retrieved.id)
    }

    @Test
    fun `test getEdge_nonExistent_returnsNull`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        assertNull(graph.getEdge(nodeId1, nodeId2, "nonexistent"))
    }

    @Test
    fun `test containEdge_existing_returnsTrue`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertTrue(graph.containEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    @Test
    fun `test containEdge_nonExistent_returnsFalse`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        assertFalse(graph.containEdge(nodeId1, nodeId2, "nonexistent"))
    }

    @Test
    fun `test delEdge_existing_removesEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        graph.delEdge(edge.srcNid, edge.dstNid, edge.eType)

        assertFalse(graph.containEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    @Test
    fun `test delEdge_preservesNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        graph.delEdge(edge.srcNid, edge.dstNid, edge.eType)

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
        val e1 = graph.addEdge(nodeId1, nodeId2, edgeType1)
        val e2 = graph.addEdge(nodeId2, nodeId3, edgeType2)

        val ids = graph.getAllEdges().map { it.id }.toSet()

        assertEquals(setOf(e1.id, e2.id), ids)
    }

    @Test
    fun `test getAllEdges_withPredicate_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val e1 = graph.addEdge(nodeId1, nodeId2, edgeType1)
        graph.addEdge(nodeId2, nodeId3, edgeType2)

        val ids = graph.getAllEdges { it.srcNid == nodeId1 }.map { it.id }.toList()

        assertEquals(listOf(e1.id), ids)
    }

    // endregion

    // region Graph structure queries

    @Test
    fun `test getOutgoingEdges_existingNode_returnsOutgoing`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val e1 = graph.addEdge(nodeId1, nodeId2, edgeType1)
        val e3 = graph.addEdge(nodeId1, nodeId3, edgeType3)

        val ids = graph.getOutgoingEdges(nodeId1).map { it.id }.toSet()

        assertEquals(setOf(e1.id, e3.id), ids)
    }

    @Test
    fun `test getOutgoingEdges_noOutgoing_returnsEmpty`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(nodeId1, nodeId2, edgeType1)

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
        val e1 = graph.addEdge(nodeId1, nodeId2, edgeType1)
        val e2 = graph.addEdge(nodeId3, nodeId2, "rel")

        val ids = graph.getIncomingEdges(nodeId2).map { it.id }.toSet()

        assertEquals(setOf(e1.id, e2.id), ids)
    }

    @Test
    fun `test getIncomingEdges_noIncoming_returnsEmpty`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(nodeId1, nodeId2, edgeType1)

        val edges = graph.getIncomingEdges(nodeId1).toList()

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test getChildren_returnsChildNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, edgeType1)
        graph.addEdge(nodeId1, nodeId3, edgeType3)

        val ids = graph.getChildren(nodeId1).map { it.id }.toSet()

        assertEquals(setOf(nodeId2, nodeId3), ids)
    }

    @Test
    fun `test getChildren_withEdgeCondition_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, "typeA")
        graph.addEdge(nodeId1, nodeId3, "typeB")

        val ids = graph.getChildren(nodeId1) { it.eType == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getParents_returnsParentNodes`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, edgeType1)
        graph.addEdge(nodeId3, nodeId2, "rel")

        val ids = graph.getParents(nodeId2).map { it.id }.toSet()

        assertEquals(setOf(nodeId1, nodeId3), ids)
    }

    @Test
    fun `test getParents_withEdgeCondition_filtersCorrectly`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, "typeA")
        graph.addEdge(nodeId3, nodeId2, "typeB")

        val ids = graph.getParents(nodeId2) { it.eType == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(nodeId1), ids)
    }

    @Test
    fun `test getDescendants_linearChain_returnsAllDescendants`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addNode(nodeId4)
        graph.addEdge(nodeId1, nodeId2, edgeType1)
        graph.addEdge(nodeId2, nodeId3, edgeType2)
        graph.addEdge(nodeId3, nodeId4, edgeType4)

        val ids = graph.getDescendants(nodeId1).map { it.id }.toSet()

        assertEquals(setOf(nodeId2, nodeId3, nodeId4), ids)
    }

    @Test
    fun `test getDescendants_withEdgeCondition_stopsAtFilteredEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, "typeA")
        graph.addEdge(nodeId2, nodeId3, "typeB")

        val ids = graph.getDescendants(nodeId1) { it.eType == "typeA" }.map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getDescendants_cycle_terminatesWithoutDuplicates`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(nodeId1, nodeId2, "fwd")
        graph.addEdge(nodeId2, nodeId1, "back")

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
        graph.addEdge(nodeId1, nodeId2, edgeType1)
        graph.addEdge(nodeId2, nodeId3, edgeType2)
        graph.addEdge(nodeId3, nodeId4, edgeType4)

        val ids = graph.getAncestors(nodeId4).map { it.id }.toSet()

        assertEquals(setOf(nodeId1, nodeId2, nodeId3), ids)
    }

    @Test
    fun `test getAncestors_withEdgeCondition_stopsAtFilteredEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, "typeA")
        graph.addEdge(nodeId2, nodeId3, "typeB")

        val ids = graph.getAncestors(nodeId3) { it.eType == "typeB" }.map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getAncestors_cycle_terminatesWithoutDuplicates`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addEdge(nodeId1, nodeId2, "fwd")
        graph.addEdge(nodeId2, nodeId1, "back")

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

        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1, label)

        assertTrue(edge.labels.contains(label))
    }

    @Test
    fun `test addEdge_withLabel_existingEdge_addsLabel`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val labelA = Label("a")
        val labelB = Label("b")
        val e = graph.addEdge(nodeId1, nodeId2, edgeType1, labelA)

        graph.addEdge(nodeId1, nodeId2, edgeType1, labelB)

        val edge = graph.getEdge(e.srcNid, e.dstNid, e.eType)!!
        assertTrue(edge.labels.containsAll(setOf(labelA, labelB)))
    }

    @Test
    fun `test delEdge_withLabel_removesOnlyThatLabel`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val labelA = Label("a")
        val labelB = Label("b")
        val e = graph.addEdge(nodeId1, nodeId2, edgeType1, labelA)
        graph.addEdge(nodeId1, nodeId2, edgeType1, labelB)

        graph.delEdge(e.srcNid, e.dstNid, e.eType, labelA)

        assertTrue(graph.containEdge(e.srcNid, e.dstNid, e.eType))
        val edge = graph.getEdge(e.srcNid, e.dstNid, e.eType)!!
        assertFalse(edge.labels.contains(labelA))
        assertTrue(edge.labels.contains(labelB))
    }

    @Test
    fun `test delEdge_lastLabel_removesEdgeEntirely`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("only")
        val e = graph.addEdge(nodeId1, nodeId2, edgeType1, label)

        graph.delEdge(e.srcNid, e.dstNid, e.eType, label)

        assertFalse(graph.containEdge(e.srcNid, e.dstNid, e.eType))
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
        graph.addEdge(nodeId1, nodeId2, "rel1", labelA)
        graph.addEdge(nodeId1, nodeId3, "rel2", labelB)

        val edges = graph.getOutgoingEdges(nodeId1, labelA).toList()

        assertEquals(1, edges.size)
        assertEquals(nodeId2, edges.first().dstNid)
    }

    @Test
    fun `test getOutgoingEdges_withSupremum_returnsAll`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, "rel1", Label("a"))
        graph.addEdge(nodeId1, nodeId3, "rel2", Label("b"))

        val edges = graph.getOutgoingEdges(nodeId1, Label.SUPREMUM).toList()

        assertEquals(2, edges.size)
    }

    @Test
    fun `test getIncomingEdges_withLabel_filtersVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(nodeId1, nodeId3, "rel1", label)
        graph.addEdge(nodeId2, nodeId3, "rel2", Label("other"))

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
        graph.addEdge(nodeId1, nodeId2, "rel1", label)
        graph.addEdge(nodeId1, nodeId3, "rel2", Label("other"))

        val ids = graph.getChildren(nodeId1, label).map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getParents_withLabel_returnsVisibleParents`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(nodeId1, nodeId3, "rel1", label)
        graph.addEdge(nodeId2, nodeId3, "rel2", Label("other"))

        val ids = graph.getParents(nodeId3, label).map { it.id }.toList()

        assertEquals(listOf(nodeId1), ids)
    }

    @Test
    fun `test getDescendants_withLabel_traversesOnlyVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(nodeId1, nodeId2, "rel1", label)
        graph.addEdge(nodeId2, nodeId3, "rel2", Label("other"))

        val ids = graph.getDescendants(nodeId1, label).map { it.id }.toList()

        assertEquals(listOf(nodeId2), ids)
    }

    @Test
    fun `test getAncestors_withLabel_traversesOnlyVisibleEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        val label = Label("v1")
        graph.addEdge(nodeId1, nodeId2, "rel1", Label("other"))
        graph.addEdge(nodeId2, nodeId3, "rel2", label)

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
        graph.addEdge(nodeId1, nodeId2, edgeType1, child)

        val edges = graph.getOutgoingEdges(nodeId1, parent).toList()

        assertEquals(1, edges.size)
    }

    @Test
    fun `test labelChanges_tracksEdgeAssignment`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("v1")

        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1, label)

        with(graph) { assertTrue(label.changes.contains(edge.internalId)) }
    }

    @Test
    fun `test labelParents_writeThroughToStorage`() {
        val parent = Label("parent")
        val child = Label("child")
        with(graph) { child.parents = mapOf("rel" to parent) }

        val newGraph = createTestMultipleGraph(storage, posetStorage)
        with(newGraph) {
            assertEquals(mapOf("rel" to parent), child.parents)
        }
    }

    @Test
    fun `test labelCompareTo_cachedResult_returnsFromCache`() {
        val parent = Label("p")
        val child = Label("c")
        with(graph) {
            child.parents = mapOf("up" to parent)
            val first = parent.compareTo(child)
            val second = parent.compareTo(child)
            assertEquals(first, second)
            assertNotNull(second)
            assertTrue(second!! > 0)
        }
    }

    @Test
    fun `test labelCompareTo_reverseCacheHit_returnsNegated`() {
        val parent = Label("rp")
        val child = Label("rc")
        with(graph) {
            child.parents = mapOf("up" to parent)
            parent.compareTo(child)
            val reversed = child.compareTo(parent)
            assertNotNull(reversed)
            assertTrue(reversed!! < 0)
        }
    }

    @Test
    fun `test labelAncestors_withCycle_terminates`() {
        val a = Label("cycA")
        val b = Label("cycB")
        with(graph) {
            a.parents = mapOf("up" to b)
            b.parents = mapOf("up" to a)
            val ancestors = a.ancestors.toList()
            assertTrue(ancestors.contains(b))
            assertTrue(ancestors.contains(a))
        }
    }

    @Test
    fun `test labelChanges_writeThroughToStorage`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("wt")
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1, label)

        val newGraph = createTestMultipleGraph(storage, posetStorage)
        with(newGraph) {
            assertTrue(label.changes.contains(edge.internalId))
        }
    }

    @Test
    fun `test delEdge_nonExistentEdge_noOp`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.delEdge(nodeId1, nodeId2, "nonexistent")
        assertFalse(graph.containEdge(nodeId1, nodeId2, "nonexistent"))
    }

    @Test
    fun `test delEdge_withLabel_nonExistentEdge_noOp`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.delEdge(nodeId1, nodeId2, "nonexistent", Label("a"))
        assertFalse(graph.containEdge(nodeId1, nodeId2, "nonexistent"))
    }

    @Test
    fun `test getAncestors_nonExistentNode_returnsEmpty`() {
        assertTrue(graph.getAncestors(nodeId1).toList().isEmpty())
    }

    @Test
    fun `test filterVisitable_coveredLabelsEliminated`() {
        val grandparent = Label("gp2")
        val parent = Label("p2")
        val child = Label("c2")
        with(graph) {
            child.parents = mapOf("up" to parent)
            parent.parents = mapOf("up" to grandparent)
        }
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        graph.addNode(nodeId3)
        graph.addEdge(nodeId1, nodeId2, "r1", child)
        graph.addEdge(nodeId1, nodeId3, "r2", parent)

        val edges = graph.getOutgoingEdges(nodeId1, grandparent).toList()
        assertTrue(edges.isNotEmpty())
    }

    @Test
    fun `test addNode_alreadyInStorage_throwsEntityAlreadyExist`() {
        graph.addNode(nodeId1)

        assertFailsWith<EntityAlreadyExistException> { graph.addNode(nodeId1) }
    }

    @Test
    fun `test addEdge_duplicateInStorage_allowedInMultiGraph`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val e1 = graph.addEdge(nodeId1, nodeId2, edgeType1)
        val e2 = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertNotEquals(e1.internalId, e2.internalId)
    }

    @Test
    fun `test getIncomingEdges_nonExistentNode_returnsEmpty`() {
        val edges = graph.getIncomingEdges(nodeId1).toList()
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `test transferTo_copiesLatticeData`() {
        val parent = Label("tp")
        val child = Label("tc")
        with(graph) { child.parents = mapOf("up" to parent) }
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1, child)

        val targetStorage = NativeStorageImpl()
        val targetPosetStorage = NativeStorageImpl()
        storage.transferTo(targetStorage)
        posetStorage.transferTo(targetPosetStorage)

        val newGraph = createTestMultipleGraph(targetStorage, targetPosetStorage)
        with(newGraph) {
            assertEquals(mapOf("up" to parent), child.parents)
        }
        targetStorage.close()
        targetPosetStorage.close()
    }

    @Test
    fun `test getDescendants_withLabel_cycle_terminates`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("cyc")
        graph.addEdge(nodeId1, nodeId2, "fwd", label)
        graph.addEdge(nodeId2, nodeId1, "back", label)

        val descendants = graph.getDescendants(nodeId1, label).toList()
        assertTrue(descendants.any { it.id == nodeId2 })
        assertEquals(descendants.distinctBy { it.id }.size, descendants.size)
    }

    @Test
    fun `test getAncestors_withLabel_cycle_terminates`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val label = Label("cyc2")
        graph.addEdge(nodeId1, nodeId2, "fwd", label)
        graph.addEdge(nodeId2, nodeId1, "back", label)

        val ancestors = graph.getAncestors(nodeId2, label).toList()
        assertTrue(ancestors.any { it.id == nodeId1 })
        assertEquals(ancestors.distinctBy { it.id }.size, ancestors.size)
    }

    @Test
    fun `test allLabels_reflectsRegisteredLabels`() {
        val root = Label("root")
        val label1 = Label("phase1")
        val label2 = Label("phase2")
        with(graph) {
            root.parents = emptyMap()
            label1.parents = mapOf("up" to root)
            label2.parents = mapOf("up" to root)
        }

        with(graph) {
            assertTrue(allLabels.contains(label1))
            assertTrue(allLabels.contains(label2))
            assertTrue(allLabels.contains(root))
        }
    }

    // endregion

    // region Storage delegation

    @Test
    fun `test containNode_afterGraphAddNode`() {
        graph.addNode(nodeId1)

        assertTrue(graph.containNode(nodeId1))
    }

    @Test
    fun `test containEdge_afterGraphAddEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertTrue(graph.containEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    @Test
    fun `test getNode_afterGraphAddNode`() {
        graph.addNode(nodeId1)

        assertNotNull(graph.getNode(nodeId1))
    }

    @Test
    fun `test getEdge_afterGraphAddEdge`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertNotNull(graph.getEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    @Test
    fun `test nodeIDs_reflectsGraphState`() {
        graph.addNode(nodeId1)

        assertTrue(graph.nodeIDs.contains(nodeId1))
    }

    @Test
    fun `test edgeIDs_reflectsGraphState`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        assertTrue(graph.getAllEdges().any { it.id == edge.id })
    }

    @Test
    fun `test getOutgoingEdges_returnsEdges`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        val edges = graph.getOutgoingEdges(nodeId1).toList()

        assertEquals(1, edges.size)
        assertEquals(edge.id, edges.first().id)
    }

    @Test
    fun `test delNode_deletesFromStorage`() {
        graph.addNode(nodeId1)

        graph.delNode(nodeId1)

        assertFalse(graph.containNode(nodeId1))
    }

    @Test
    fun `test delEdge_deletesFromStorage`() {
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1)

        graph.delEdge(edge.srcNid, edge.dstNid, edge.eType)

        assertFalse(graph.containEdge(edge.srcNid, edge.dstNid, edge.eType))
    }

    // endregion

    // region Close

    @Test
    fun `test latticeState_persistedWithoutClose`() {
        val label = Label("closeTest")
        with(graph) { label.parents = mapOf("up" to Label("root")) }
        graph.addNode(nodeId1)
        graph.addNode(nodeId2)
        val edge = graph.addEdge(nodeId1, nodeId2, edgeType1, label)

        val newGraph = createTestMultipleGraph(storage, posetStorage)
        with(newGraph) {
            assertEquals(mapOf<String, Label>("up" to Label("root")), label.parents)
            assertTrue(label.changes.contains(edge.internalId))
        }
    }

    // endregion

    // region Performance

    @Test
    fun `test bulkNodeInsertion_completesInTime`() {
        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }
            }

        assertEquals(PERF_NODE_COUNT, graph.nodeIDs.size)
        println("MultipleGraph: $PERF_NODE_COUNT nodes inserted in $elapsed")
    }

    @Test
    fun `test bulkEdgeInsertion_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_EDGE_COUNT) { i ->
                    val src = "n${i % PERF_NODE_COUNT}"
                    val dst = "n${(i + 1) % PERF_NODE_COUNT}"
                    graph.addEdge(src, dst, "e$i")
                }
            }

        assertEquals(PERF_EDGE_COUNT, graph.getAllEdges().count())
        println("MultipleGraph: $PERF_EDGE_COUNT edges inserted in $elapsed")
    }

    @Test
    fun `test nodeLookup_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.getNode("n$i") }
            }

        println("MultipleGraph: $PERF_NODE_COUNT node lookups in $elapsed")
    }

    @Test
    fun `test edgeLookup_completesInTime`() {
        data class EdgeKey(
            val src: NodeID,
            val dst: NodeID,
            val type: String,
        )

        val edgeKeys = mutableListOf<EdgeKey>()
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }
        repeat(PERF_EDGE_COUNT) { i ->
            val src = "n${i % PERF_NODE_COUNT}"
            val dst = "n${(i + 1) % PERF_NODE_COUNT}"
            val type = "e$i"
            graph.addEdge(src, dst, type)
            edgeKeys.add(EdgeKey(src, dst, type))
        }

        val elapsed =
            kotlin.time.measureTime {
                edgeKeys.forEach { graph.getEdge(it.src, it.dst, it.type) }
            }

        println("MultipleGraph: $PERF_EDGE_COUNT edge lookups in $elapsed")
    }

    @Test
    fun `test outgoingEdgeQuery_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }
        repeat(PERF_EDGE_COUNT) { i ->
            graph.addEdge("n${i % PERF_NODE_COUNT}", "n${(i + 1) % PERF_NODE_COUNT}", "e$i")
        }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i ->
                    graph.getOutgoingEdges("n$i").toList()
                }
            }

        println("MultipleGraph: outgoing edge queries for $PERF_NODE_COUNT nodes in $elapsed")
    }

    @Test
    fun `test descendantTraversal_completesInTime`() {
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode("c$i") }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            graph.addEdge("c$i", "c${i + 1}", "next")
        }

        val elapsed =
            kotlin.time.measureTime {
                val descendants = graph.getDescendants("c0").toList()
                assertEquals(PERF_CHAIN_LENGTH - 1, descendants.size)
            }

        println("MultipleGraph: descendant traversal of chain($PERF_CHAIN_LENGTH) in $elapsed")
    }

    @Test
    fun `test ancestorTraversal_completesInTime`() {
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode("c$i") }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            graph.addEdge("c$i", "c${i + 1}", "next")
        }

        val elapsed =
            kotlin.time.measureTime {
                val ancestors = graph.getAncestors("c${PERF_CHAIN_LENGTH - 1}").toList()
                assertEquals(PERF_CHAIN_LENGTH - 1, ancestors.size)
            }

        println("MultipleGraph: ancestor traversal of chain($PERF_CHAIN_LENGTH) in $elapsed")
    }

    @Test
    fun `test bulkNodeDeletion_completesInTime`() {
        repeat(PERF_NODE_COUNT) { i -> graph.addNode("n$i") }
        repeat(PERF_EDGE_COUNT) { i ->
            graph.addEdge("n${i % PERF_NODE_COUNT}", "n${(i + 1) % PERF_NODE_COUNT}", "e$i")
        }

        val elapsed =
            kotlin.time.measureTime {
                repeat(PERF_NODE_COUNT) { i -> graph.delNode("n$i") }
            }

        assertEquals(0, graph.nodeIDs.size)
        assertEquals(0, graph.getAllEdges().count())
        println("MultipleGraph: $PERF_NODE_COUNT node deletions (with edges) in $elapsed")
    }

    @Test
    fun `test labelFilteredTraversal_completesInTime`() {
        val labelA = Label("a")
        val labelB = Label("b")
        repeat(PERF_CHAIN_LENGTH) { i -> graph.addNode("c$i") }
        repeat(PERF_CHAIN_LENGTH - 1) { i ->
            val label = if (i % 2 == 0) labelA else labelB
            graph.addEdge("c$i", "c${i + 1}", "next", label)
        }

        val elapsed =
            kotlin.time.measureTime {
                graph.getDescendants("c0", labelA).toList()
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
