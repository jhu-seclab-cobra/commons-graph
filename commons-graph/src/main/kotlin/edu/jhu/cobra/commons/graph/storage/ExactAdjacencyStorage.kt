package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * One fixed relation projection, selected before storage is populated.
 *
 * Matching edges are selected by source and the destination's [kindProperty] and [keyProperty]. Those destination
 * fields must remain unchanged while a matching edge exists. Values supplied to storage must not be mutated in place.
 */
public data class AdjacencyProjection(
    val edgeTag: String,
    val kindProperty: String,
    val keyProperty: String,
)

/**
 * Storage with a constructor-selected exact adjacency projection.
 *
 * Full adjacency and raw edge allocation retain [IStorage] semantics. Exact operations require the constructor's
 * projection; they neither install a projection after population nor rebuild an index on first use.
 */
public interface ExactAdjacencyReads : IStorage {
    /** The constructor-fixed projection, or null when exact operations are not configured. */
    public val adjacencyProjection: AdjacencyProjection?
        get() = null

    /** Returns existing edge IDs for one source, destination kind and complete typed key. */
    public fun getExactOutgoingEdges(source: Int, kind: IValue, key: IValue): Set<Int>

    /** Selects all requested kinds in one operation; physical result iteration order is unspecified. */
    public fun getExactOutgoingEdges(source: Int, kinds: Set<IValue>, key: IValue): Set<Int>

}

/** Exact relation storage supporting both selected reads and first attachment. */
public interface ExactAdjacencyStorage : ExactAdjacencyReads {
    /** Configured attachment support; a read-only adapter may retain its read projection while returning null. */
    public val attachmentProjection: AdjacencyProjection?
        get() = adjacencyProjection

    /**
     * Returns an existing matching edge to [target], or creates one with the fixed projection's tag.
     *
     * A matching key already attached to a different target fails before mutation. This operation allocates no node.
     * The caller owns [target], including on failure. It does not promise rollback of arbitrary backend or VM failures.
     * Existing edge properties remain unchanged on reuse; [properties] initialize only a newly created edge.
     */
    public fun attachExact(source: Int, target: Int, properties: Map<String, IValue> = emptyMap()): Int
}
