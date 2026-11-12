package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.criticalSection

/**
 * Abstract simple directed graph where at most one edge exists between any
 * two nodes in a given direction within the visible graph view.
 *
 * @param N The node type.
 * @param E The edge type.
 */
public abstract class AbcSimpleGraph<N : AbcNode, E : AbcEdge> : AbcMultipleGraph<N, E>() {
    override fun addEdge(
        src: NodeID,
        dst: NodeID,
        tag: String,
    ): E =
        storage.criticalSection {
            val source = getNode(src)?.storageId
            val target = getNode(dst)?.storageId
            val existing = source != null && target != null && hasVisibleEdge(source, target)
            if (existing) throw EntityAlreadyExistException("$src->$dst")
            super.addEdge(src, dst, tag)
        }

    // Only a hidden first row requires a matching-pair snapshot; no unrelated incidence is visited.
    private fun hasVisibleEdge(source: Int, target: Int): Boolean {
        val first = storage.findEdge(source, target) ?: return false
        if (edgeByStorageId(first) != null) return true
        return storage.getEdges(source, target).any { it != first && edgeByStorageId(it) != null }
    }

    /** The pair key: at most one edge joins a source and a destination, whatever the tag. */
    override fun edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>> =
        mapOf(PAIR_KEY to listOf(edge.src, edge.dst))

    private companion object {
        // Name of the unique-key declaration that limits a source and a destination to one edge.
        const val PAIR_KEY = "pair"
    }
}
