/* Indexed simple-graph visibility:
 * same source insertion reads only the indexed pair -- one foreign row per target and unrelated edges
 * give linear matching work, with no full adjacency reads, for every core storage.
 */
package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeConcurStorageImpl
import edu.jhu.cobra.commons.graph.storage.NativeStorageImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class AbcSimpleGraphVisibilityTest {
    private class VisibleSimpleGraph(override val storage: IStorage) :
        AbcSimpleGraph<GraphFixtures.TestNode, GraphFixtures.TestEdge>() {
        override val graphId: String = "visible"
        override fun newNodeObj() = GraphFixtures.TestNode()
        override fun newEdgeObj() = GraphFixtures.TestEdge()
        override fun isEdgeVisible(edge: GraphFixtures.TestEdge): Boolean = edge.eTag == "own"
    }

    @Test
    fun `same source insertion reads only the indexed pair`() {
        for (size in listOf(1, 4, 16, 64)) {
            val stores = listOf(NativeStorageImpl(), NativeConcurStorageImpl(), LayeredStorageImpl())
            for (base in stores) assertInsertionWork(base, size)
        }
    }

    private fun assertInsertionWork(base: IStorage, size: Int) {
        var fullReads = 0
        var pairReads = 0
        var pairRows = 0
        var structureReads = 0
        val counted = object : IStorage by base {
            override fun getOutgoingEdges(id: Int): Set<Int> {
                fullReads++
                return base.getOutgoingEdges(id)
            }
            override fun getEdgeStructure(id: Int): IStorage.EdgeStructure {
                structureReads++
                return base.getEdgeStructure(id)
            }
            override fun findEdge(src: Int, dst: Int): Int? {
                pairReads++
                return base.findEdge(src, dst)
            }
            override fun getEdges(src: Int, dst: Int, tag: String?): Set<Int> =
                base.getEdges(src, dst, tag).also { pairRows += it.size }
        }
        val visible = VisibleSimpleGraph(counted)
        val source = visible.addNode("source")
        val targets = (0 until size).map { visible.addNode("target$it") }
        for (target in targets) base.addEdge(source.storageId, target.storageId, "foreign")
        repeat(size) { base.addEdge(source.storageId, visible.addNode("other$it").storageId, "unrelated") }
        for (target in targets) visible.addEdge(source.id, target.id, "own")
        assertEquals(0, fullReads)
        assertEquals(size, pairReads)
        assertEquals(size, pairRows)
        assertTrue(structureReads <= 4 * size, "structure reads $structureReads for $size insertions")
        val kind = base.javaClass.simpleName
        println("$kind n=$size pair=$pairReads rows=$pairRows structs=$structureReads full=$fullReads")
    }

}
