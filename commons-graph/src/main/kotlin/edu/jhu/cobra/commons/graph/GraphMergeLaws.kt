package edu.jhu.cobra.commons.graph

/**
 * The merge laws a graph declares for the prospective changes of several writers. Every member is pure: it
 * reads no storage, registers nothing and hooks no graph write; a caller that merges writers' changes asks
 * for the law of each property and for the identity of each edge.
 *
 * A graph variant overrides [nodePropertyLaw], [edgePropertyLaw] or [edgeUniqueKeys] to declare its own laws.
 */
public interface GraphMergeLaws {
    /**
     * The law of the node property [name]: the graph's node ID is immutable, the owner set merges by union,
     * and any other property takes the default law.
     */
    public fun nodePropertyLaw(name: String): PropertyMergeLaw =
        when (name) {
            AbcMultipleGraph.PROP_NODE_ID -> PropertyMergeLaw.IMMUTABLE
            AbcMultipleGraph.PROP_OWNERS -> NodeOwners.LAW
            else -> PropertyMergeLaw.EQUAL_OR_FAIL
        }

    /** The law of the edge property [name]: labels merge by writer-relative additions and removals. */
    public fun edgePropertyLaw(name: String): PropertyMergeLaw =
        if (name == AbcEdge.LABELS_PROP) AbcEdge.LABELS_LAW else PropertyMergeLaw.EQUAL_OR_FAIL

    /**
     * The unique keys this graph declares for [edge] besides its `(src, dst, tag)` triple: declaration name
     * to key components. Two distinct edges must not share the components of one declaration.
     */
    public fun edgeUniqueKeys(edge: EdgeKey): Map<String, List<String>> = emptyMap()

    /**
     * Coalesces the prospective edge [additions] by triple and validates every declared unique key of the
     * coalesced edges against each other and against the [present] edges.
     *
     * @return The distinct triples of [additions].
     * @throws MergeConflictException when two distinct edges share a declared unique key.
     */
    public fun coalesceEdges(
        additions: Collection<EdgeKey>,
        present: Collection<EdgeKey> = emptyList(),
    ): Set<EdgeKey> {
        val coalesced = LinkedHashSet(additions)
        val holders = HashMap<Pair<String, List<String>>, EdgeKey>()
        for (edge in present) uniqueKeysOf(edge).forEach { holders.putIfAbsent(it, edge) }
        for (edge in coalesced) {
            for (key in uniqueKeysOf(edge)) {
                val holder = holders.putIfAbsent(key, edge)
                if (holder != null && holder != edge) {
                    throw MergeConflictException("edges $holder and $edge share unique key $key")
                }
            }
        }
        return coalesced
    }

    private fun uniqueKeysOf(edge: EdgeKey): List<Pair<String, List<String>>> =
        edgeUniqueKeys(edge).map { it.key to it.value }
}
