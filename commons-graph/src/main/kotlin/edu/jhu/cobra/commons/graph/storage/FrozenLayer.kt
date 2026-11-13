package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Frozen layer of [LayeredStorageImpl]: a merged [IStorage] snapshot with
 * bidirectional global/local ID mappings and a cache of edge structures
 * translated to global IDs.
 *
 * Instances are built by [merge] and never mutated afterwards.
 */
@Suppress("TooManyFunctions")
internal class FrozenLayer private constructor(
    private val storage: IStorage,
    tagGroup: (String) -> String,
    private val nodeGlobalToLocal: Map<Int, Int>,
    private val nodeLocalToGlobal: Map<Int, Int>,
    private val edgeGlobalToLocal: Map<Int, Int>,
    private val edgeLocalToGlobal: Map<Int, Int>,
) {
    private val tagAdjacency = if (storage.tagGroup === tagGroup) null else indexAdjacency(tagGroup)

    private fun indexAdjacency(tagGroup: (String) -> String): TagAdjacencyIndex {
        val incomingOrder = HashMap<Int, Int>()
        val outgoingOrder = HashMap<Int, Int>()
        val index = TagAdjacencyIndex(tagGroup, incomingOrder::getValue, outgoingOrder::getValue)
        for (node in storage.nodeIDs) {
            for (id in storage.getIncomingEdges(node)) {
                incomingOrder[id] = incomingOrder.size
                index.addIncoming(id, storage.getEdgeStructure(id))
            }
            for (id in storage.getOutgoingEdges(node)) {
                outgoingOrder[id] = outgoingOrder.size
                index.addOutgoing(id, storage.getEdgeStructure(id))
            }
        }
        return index
    }

    private val edgeStructureCache = HashMap<Int, IStorage.EdgeStructure>()

    val nodeIds: Set<Int> get() = nodeGlobalToLocal.keys

    val edgeIds: Set<Int> get() = edgeGlobalToLocal.keys

    val metaNames: Set<String> get() = storage.metaNames

    fun containsNode(globalId: Int): Boolean = globalId in nodeGlobalToLocal

    fun containsEdge(globalId: Int): Boolean = globalId in edgeGlobalToLocal

    fun capturedNodeRow(globalId: Int): CapturedRow? =
        nodeGlobalToLocal[globalId]?.let(storage::nodeCapture)

    fun capturedEdgeRow(globalId: Int): CapturedRow? =
        edgeGlobalToLocal[globalId]?.let(storage::edgeCapture)

    fun capturedNodeProperty(globalId: Int, name: String): CapturedProperty? = nodeGlobalToLocal[globalId]?.let {
        if (storage is NativeStorageImpl) storage.capturedNodeProperty(it, name)
        else storage.getNodeProperty(it, name)?.let(::LegacyProperty)
    }

    fun capturedEdgeProperty(globalId: Int, name: String): CapturedProperty? = edgeGlobalToLocal[globalId]?.let {
        if (storage is NativeStorageImpl) storage.capturedEdgeProperty(it, name)
        else storage.getEdgeProperty(it, name)?.let(::LegacyProperty)
    }

    /** Returns the node's frozen properties, or null when the node is not frozen. */
    fun nodeProperties(globalId: Int): Map<String, IValue>? =
        nodeGlobalToLocal[globalId]?.let { localId -> storage.getNodeProperties(localId) }

    /** Returns the edge's frozen properties, or null when the edge is not frozen. */
    fun edgeProperties(globalId: Int): Map<String, IValue>? =
        edgeGlobalToLocal[globalId]?.let { localId -> storage.getEdgeProperties(localId) }

    fun nodeProperty(
        globalId: Int,
        name: String,
    ): IValue? = nodeGlobalToLocal[globalId]?.let { localId -> storage.getNodeProperty(localId, name) }

    fun edgeProperty(
        globalId: Int,
        name: String,
    ): IValue? = edgeGlobalToLocal[globalId]?.let { localId -> storage.getEdgeProperty(localId, name) }

    fun meta(name: String): IValue? = storage.getMeta(name)

    /** Returns the edge structure translated to global IDs, or null when the edge is not frozen. */
    fun edgeStructure(globalId: Int): IStorage.EdgeStructure? {
        edgeStructureCache[globalId]?.let { return it }
        val localId = edgeGlobalToLocal[globalId] ?: return null
        val local = storage.getEdgeStructure(localId)
        val translated =
            IStorage.EdgeStructure(
                nodeLocalToGlobal.getValue(local.src),
                nodeLocalToGlobal.getValue(local.dst),
                local.tag,
            )
        edgeStructureCache[globalId] = translated
        return translated
    }

    /** Returns the node's incoming edges as global IDs, or null when the node is not frozen. */
    fun incomingEdges(globalId: Int): Set<Int>? =
        nodeGlobalToLocal[globalId]?.let { localId ->
            MappedEdgeSet(storage.getIncomingEdges(localId), edgeLocalToGlobal, edgeGlobalToLocal)
        }

    /** Returns the node's outgoing edges as global IDs, or null when the node is not frozen. */
    fun outgoingEdges(globalId: Int): Set<Int>? =
        nodeGlobalToLocal[globalId]?.let { localId ->
            MappedEdgeSet(storage.getOutgoingEdges(localId), edgeLocalToGlobal, edgeGlobalToLocal)
        }

    fun incomingEdges(globalId: Int, group: String): Set<Int>? = nodeGlobalToLocal[globalId]?.let { local ->
        val selected = tagAdjacency?.incoming(local, group) ?: storage.getIncomingEdges(local, group)
        MappedEdgeSet(selected, edgeLocalToGlobal, edgeGlobalToLocal)
    }

    fun outgoingEdges(globalId: Int, group: String): Set<Int>? = nodeGlobalToLocal[globalId]?.let { local ->
        val selected = tagAdjacency?.outgoing(local, group) ?: storage.getOutgoingEdges(local, group)
        MappedEdgeSet(selected, edgeLocalToGlobal, edgeGlobalToLocal)
    }

    fun incomingEdges(globalId: Int, groups: Set<String>): Set<Int>? = nodeGlobalToLocal[globalId]?.let { local ->
        val selected = tagAdjacency?.incoming(local, groups) ?: storage.getIncomingEdges(local, groups)
        MappedEdgeSet(selected, edgeLocalToGlobal, edgeGlobalToLocal)
    }

    fun outgoingEdges(globalId: Int, groups: Set<String>): Set<Int>? = nodeGlobalToLocal[globalId]?.let { local ->
        val selected = tagAdjacency?.outgoing(local, groups) ?: storage.getOutgoingEdges(local, groups)
        MappedEdgeSet(selected, edgeLocalToGlobal, edgeGlobalToLocal)
    }

    // Frozen storages produced by the factory may hold external resources (file-backed
    // storages); discarding one without closing leaks its handle.
    fun close() {
        (storage as? AutoCloseable)?.close()
    }

    companion object {
        /**
         * Builds a new frozen layer by copying [previous] (when present) and overlaying
         * [active] into [merged]. A promoted edge contributes only its explicit edits;
         * all other properties still come from the previous frozen row. Edges tombstoned
         * in [active] are not transferred.
         */
        fun merge(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
            tagGroup: (String) -> String = IDENTITY_TAG_GROUP,
        ): FrozenLayer {
            val oldToNewNode = transferPreviousNodes(previous, active, merged)
            val (nodeG2L, nodeL2G) = mergeNodes(previous, active, merged, oldToNewNode)
            val (edgeG2L, edgeL2G) = mergeEdges(previous, active, merged, oldToNewNode, nodeG2L)
            transferMetadata(previous, active, merged)
            return FrozenLayer(merged, tagGroup, nodeG2L, nodeL2G, edgeG2L, edgeL2G)
        }

        private fun transferPreviousNodes(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
        ): Map<Int, Int> {
            val oldToNew = HashMap<Int, Int>()
            if (previous == null) return oldToNew
            for (localId in previous.storage.nodeIDs) {
                val globalId = previous.nodeLocalToGlobal.getValue(localId)
                val props =
                    if (active.containsNode(globalId)) {
                        active.nodeRows.captured(globalId)
                    } else {
                        previous.storage.nodeCapture(localId)
                    }
                oldToNew[localId] = merged.addNodeCapture(props)
            }
            return oldToNew
        }

        private fun mergeNodes(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
            oldToNew: Map<Int, Int>,
        ): Pair<HashMap<Int, Int>, HashMap<Int, Int>> {
            val (g2l, l2g) =
                if (previous != null) {
                    mergePreviousNodes(previous, oldToNew)
                } else {
                    LinkedHashMap<Int, Int>() to LinkedHashMap()
                }
            for (globalId in active.nodeIds) {
                if (globalId in g2l) continue
                val newLocalId = merged.addNodeCapture(active.nodeRows.captured(globalId))
                g2l[globalId] = newLocalId
                l2g[newLocalId] = globalId
            }
            return g2l to l2g
        }

        // Properties were already resolved in transferPreviousNodes; only the ID
        // mappings remain to be rebuilt for previously frozen nodes.
        private fun mergePreviousNodes(
            previous: FrozenLayer,
            oldToNew: Map<Int, Int>,
        ): Pair<HashMap<Int, Int>, HashMap<Int, Int>> {
            val g2l = LinkedHashMap<Int, Int>()
            val l2g = LinkedHashMap<Int, Int>()
            for ((globalId, oldLocalId) in previous.nodeGlobalToLocal) {
                val newLocalId = oldToNew.getValue(oldLocalId)
                g2l[globalId] = newLocalId
                l2g[newLocalId] = globalId
            }
            return g2l to l2g
        }

        private fun mergeEdges(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
            oldToNewNode: Map<Int, Int>,
            nodeG2L: Map<Int, Int>,
        ): Pair<HashMap<Int, Int>, HashMap<Int, Int>> {
            val (g2l, l2g) =
                if (previous != null) {
                    mergePreviousEdges(previous, active, merged, oldToNewNode)
                } else {
                    LinkedHashMap<Int, Int>() to LinkedHashMap()
                }
            for ((globalEdgeId, structure) in active.edgeEndpoints) {
                if (globalEdgeId in g2l) continue
                val newId =
                    merged.addEdgeCapture(
                        nodeG2L.getValue(structure.src),
                        nodeG2L.getValue(structure.dst),
                        structure.tag,
                        active.edgeRows.captured(globalEdgeId),
                    )
                g2l[globalEdgeId] = newId
                l2g[newId] = globalEdgeId
            }
            return g2l to l2g
        }

        private fun mergePreviousEdges(
            previous: FrozenLayer,
            active: ActiveLayer,
            merged: IStorage,
            oldToNewNode: Map<Int, Int>,
        ): Pair<HashMap<Int, Int>, HashMap<Int, Int>> {
            val g2l = LinkedHashMap<Int, Int>()
            val l2g = LinkedHashMap<Int, Int>()
            for ((globalEdgeId, localEdgeId) in previous.edgeGlobalToLocal) {
                // Edges tombstoned in the active layer stay deleted in the merged layer.
                if (globalEdgeId in active.deletedEdgeIds) continue
                val structure = previous.storage.getEdgeStructure(localEdgeId)
                val props = resolveEdgeProperties(previous, active, localEdgeId, globalEdgeId)
                val newId =
                    merged.addEdgeCapture(
                        oldToNewNode.getValue(structure.src),
                        oldToNewNode.getValue(structure.dst),
                        structure.tag,
                        props,
                    )
                g2l[globalEdgeId] = newId
                l2g[newId] = globalEdgeId
            }
            return g2l to l2g
        }

        private fun resolveEdgeProperties(
            previous: FrozenLayer,
            active: ActiveLayer,
            localEdgeId: Int,
            globalEdgeId: Int,
        ): CapturedRow {
            val base = previous.storage.edgeCapture(localEdgeId)
            if (!active.containsEdge(globalEdgeId)) return base
            if (globalEdgeId !in active.promotedEdgeEdits) return active.edgeRows.captured(globalEdgeId)
            return active.composePromotedCaptured(globalEdgeId, base)
        }

        private fun transferMetadata(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
        ) {
            if (previous != null) {
                // Names tombstoned in the active layer stay deleted in the merged layer.
                for (name in previous.storage.metaNames - active.deletedMetaNames) {
                    merged.setMeta(name, previous.storage.getMeta(name))
                }
            }
            for ((name, value) in active.metaProperties) {
                merged.setMeta(name, value)
            }
        }
    }
}
