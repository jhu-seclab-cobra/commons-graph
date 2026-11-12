/* Backend group defaults: plain and concurrent reads equal full filters through deletion, re-addition and clear. */
package edu.jhu.cobra.commons.graph.storage

import kotlin.test.Test
import kotlin.test.assertEquals

internal class MapDBTagGroupTest {
    @Test
    fun `group defaults preserve backend adjacency and triple identity`() {
        val stores = listOf<IStorage>(MapDBStorageImpl { memoryDB() }, MapDBConcurStorageImpl { memoryDB() })
        try {
            for (store in stores) checkLifecycle(store)
        } finally {
            for (store in stores) (store as? AutoCloseable)?.close()
        }
    }

    private fun checkLifecycle(store: IStorage) {
        val source = store.addNode()
        val target = store.addNode()
        val first = store.addEdge(source, target, "wanted")
        store.addEdge(source, target, "other")
        store.addEdge(source, target, "wanted")
        checkReads(store, source, target)
        store.deleteEdge(first)
        checkReads(store, source, target)
        store.addEdge(source, target, "wanted")
        checkReads(store, source, target)
        store.deleteNode(target)
        assertEquals(emptySet(), store.getOutgoingEdges(source, "wanted"))
        store.clear()
        assertEquals(null, store.findEdge(source, target, "wanted"))
    }

    private fun checkReads(store: IStorage, source: Int, target: Int) {
        for (group in listOf("wanted", "other", "missing")) {
            val incoming = store.getIncomingEdges(target).filter { store.getEdgeStructure(it).tag == group }
            val outgoing = store.getOutgoingEdges(source).filter { store.getEdgeStructure(it).tag == group }
            assertEquals(incoming, store.getIncomingEdges(target, group).toList())
            assertEquals(outgoing, store.getOutgoingEdges(source, group).toList())
            assertEquals(outgoing, store.getEdges(source, target, group).toList())
            assertEquals(outgoing.firstOrNull(), store.findEdge(source, target, group))
        }
        assertEquals(store.getOutgoingEdges(source).toList(),
            store.getOutgoingEdges(source, setOf("wanted", "other")).toList())
    }
}
