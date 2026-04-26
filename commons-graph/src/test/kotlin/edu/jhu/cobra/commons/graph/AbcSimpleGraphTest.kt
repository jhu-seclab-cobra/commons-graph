package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_1
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_2
import edu.jhu.cobra.commons.graph.GraphTestUtils.NODE_ID_3
import edu.jhu.cobra.commons.graph.GraphTestUtils.createTestSimpleGraph
import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box tests for AbcSimpleGraph: at most one edge per directed (src, dst) pair.
 *
 * - `addEdge first edge between nodes succeeds` — verifies basic add
 * - `addEdge duplicate same tag throws EntityAlreadyExistException` — verifies same-tag rejection
 * - `addEdge same pair different tag throws EntityAlreadyExistException` — verifies uniqueness per direction
 * - `addEdge reverse direction allowed` — verifies (src,dst) vs (dst,src) are independent
 * - `addEdge different node pairs allowed` — verifies distinct pairs are independent
 * - `addEdge after delete allows re-add` — verifies deletion clears constraint
 * - `addEdge with label new edge succeeds` — verifies label-aware add
 * - `addEdge with label same tag adds label` — verifies label accumulation on same edge
 * - `addEdge with label different tag throws EntityAlreadyExistException` — verifies direction conflict
 */
internal class AbcSimpleGraphTest {
    private lateinit var graph: GraphTestUtils.TestSimpleGraphWithPoset
    private lateinit var storage: NativeStorageImpl

    @BeforeTest
    fun setUp() {
        storage = NativeStorageImpl()
        graph = createTestSimpleGraph(storage)
    }

    @AfterTest
    fun tearDown() {
        storage.close()
    }

    // region Single-edge-per-direction constraint

    @Test
    fun `addEdge first edge between nodes succeeds`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertNotNull(edge)
        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, "rel"))
    }

    @Test
    fun `addEdge duplicate same tag throws EntityAlreadyExistException`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        }
    }

    @Test
    fun `addEdge same pair different tag throws EntityAlreadyExistException`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA")

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, "typeB")
        }
    }

    @Test
    fun `addEdge reverse direction allowed`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")

        val rev = graph.addEdge(NODE_ID_2, NODE_ID_1, "rel")

        assertNotNull(rev)
        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, "rel"))
        assertTrue(graph.containEdge(NODE_ID_2, NODE_ID_1, "rel"))
    }

    @Test
    fun `addEdge different node pairs allowed`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addNode(NODE_ID_3)

        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel")
        graph.addEdge(NODE_ID_1, NODE_ID_3, "rel")
        graph.addEdge(NODE_ID_2, NODE_ID_3, "rel")

        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, "rel"))
        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_3, "rel"))
        assertTrue(graph.containEdge(NODE_ID_2, NODE_ID_3, "rel"))
    }

    @Test
    fun `addEdge after delete allows re-add`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "old")
        graph.delEdge(NODE_ID_1, NODE_ID_2, "old")

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "new")

        assertNotNull(edge)
        assertTrue(graph.containEdge(NODE_ID_1, NODE_ID_2, "new"))
    }

    // endregion

    // region Label-aware addEdge

    @Test
    fun `addEdge with label new edge succeeds`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val label = Label("v1")

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel", label)

        assertNotNull(edge)
        assertTrue(label in edge.labels)
    }

    @Test
    fun `addEdge with label same tag adds label`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        val label1 = Label("v1")
        val label2 = Label("v2")
        graph.addEdge(NODE_ID_1, NODE_ID_2, "rel", label1)

        val edge = graph.addEdge(NODE_ID_1, NODE_ID_2, "rel", label2)

        assertTrue(label1 in edge.labels)
        assertTrue(label2 in edge.labels)
    }

    @Test
    fun `addEdge with label different tag throws EntityAlreadyExistException`() {
        graph.addNode(NODE_ID_1)
        graph.addNode(NODE_ID_2)
        graph.addEdge(NODE_ID_1, NODE_ID_2, "typeA", Label("v1"))

        assertFailsWith<EntityAlreadyExistException> {
            graph.addEdge(NODE_ID_1, NODE_ID_2, "typeB", Label("v2"))
        }
    }

    // endregion
}
