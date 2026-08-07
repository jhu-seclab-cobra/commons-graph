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
    private val nodeGlobalToLocal: Map<Int, Int>,
    private val nodeLocalToGlobal: Map<Int, Int>,
    private val edgeGlobalToLocal: Map<Int, Int>,
    private val edgeLocalToGlobal: Map<Int, Int>,
) {
    private val edgeStructureCache = HashMap<Int, IStorage.EdgeStructure>()

    val nodeIds: Set<Int> get() = nodeGlobalToLocal.keys

    val edgeIds: Set<Int> get() = edgeGlobalToLocal.keys

    val metaNames: Set<String> get() = storage.metaNames

    fun containsNode(globalId: Int): Boolean = globalId in nodeGlobalToLocal

    fun containsEdge(globalId: Int): Boolean = globalId in edgeGlobalToLocal

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

    // Frozen storages produced by the factory may hold external resources (file-backed
    // storages); discarding one without closing leaks its handle.
    fun close() {
        (storage as? AutoCloseable)?.close()
    }

    companion object {
        /**
         * Builds a new frozen layer by copying [previous] (when present) and overlaying
         * [active] into [merged]. For an entity present in the active layer, its active
         * property set replaces the frozen one wholesale — promotion copied all frozen
         * properties, so a property absent from the active copy was deleted and stays
         * deleted.
         */
        fun merge(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
        ): FrozenLayer {
            val oldToNewNode = transferPreviousNodes(previous, active, merged)
            val (nodeG2L, nodeL2G) = mergeNodes(previous, active, merged, oldToNewNode)
            val (edgeG2L, edgeL2G) = mergeEdges(previous, active, merged, oldToNewNode, nodeG2L)
            transferMetadata(previous, active, merged)
            return FrozenLayer(merged, nodeG2L, nodeL2G, edgeG2L, edgeL2G)
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
                        active.collectNodeProperties(globalId)
                    } else {
                        previous.storage.getNodeProperties(localId)
                    }
                oldToNew[localId] = merged.addNode(props)
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
                    HashMap<Int, Int>() to HashMap()
                }
            for (globalId in active.nodeIds) {
                if (globalId in g2l) continue
                val newLocalId = merged.addNode(active.collectNodeProperties(globalId))
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
            val g2l = HashMap<Int, Int>()
            val l2g = HashMap<Int, Int>()
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
                    HashMap<Int, Int>() to HashMap()
                }
            for ((globalEdgeId, structure) in active.edgeEndpoints) {
                if (globalEdgeId in g2l) continue
                val newId =
                    merged.addEdge(
                        nodeG2L.getValue(structure.src),
                        nodeG2L.getValue(structure.dst),
                        structure.tag,
                        active.collectEdgeProperties(globalEdgeId),
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
            val g2l = HashMap<Int, Int>()
            val l2g = HashMap<Int, Int>()
            for (localEdgeId in previous.storage.edgeIDs) {
                val structure = previous.storage.getEdgeStructure(localEdgeId)
                val globalEdgeId = previous.edgeLocalToGlobal.getValue(localEdgeId)
                val props = resolveEdgeProperties(previous, active, localEdgeId, globalEdgeId)
                val newId =
                    merged.addEdge(
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
        ): Map<String, IValue> {
            if (active.containsEdge(globalEdgeId)) return active.collectEdgeProperties(globalEdgeId)
            return previous.storage.getEdgeProperties(localEdgeId)
        }

        private fun transferMetadata(
            previous: FrozenLayer?,
            active: ActiveLayer,
            merged: IStorage,
        ) {
            if (previous != null) {
                for (name in previous.storage.metaNames) {
                    merged.setMeta(name, previous.storage.getMeta(name))
                }
            }
            for ((name, value) in active.metaProperties) {
                merged.setMeta(name, value)
            }
        }
    }
}
