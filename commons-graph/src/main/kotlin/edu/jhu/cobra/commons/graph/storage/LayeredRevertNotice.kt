package edu.jhu.cobra.commons.graph.storage

/**
 * The notice of dropping [active]'s data over [frozen], read before the drop.
 *
 * An active row of the frozen layer is a promoted copy that the drop restores; any other active row is removed. A
 * tombstone masks a frozen edge that the drop restores. Every active edge is removed or promoted, so the active
 * structures are exactly the edge structures visible before the drop among the affected edges. Without a frozen
 * layer nothing is restored.
 */
internal fun activeLayerDropNotice(
    active: ActiveLayer,
    frozen: FrozenLayer?,
): RevertNotice {
    val removedNodes = HashSet<Int>()
    val restoredNodes = HashSet<Int>()
    for (id in active.nodeIds) (if (frozen?.containsNode(id) == true) restoredNodes else removedNodes).add(id)
    val removedEdges = HashSet<Int>()
    val restoredEdges = HashSet<Int>(active.deletedEdgeIds)
    for (id in active.edgeEndpoints.keys) (if (frozen?.containsEdge(id) == true) restoredEdges else removedEdges).add(id)
    return RevertNotice(
        removed = StorageEntityIds(removedNodes, removedEdges),
        restored = StorageEntityIds(restoredNodes, restoredEdges),
        edgeStructuresBefore = HashMap(active.edgeEndpoints),
    )
}
