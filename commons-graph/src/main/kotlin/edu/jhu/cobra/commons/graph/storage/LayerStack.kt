package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue

/**
 * The two layers of a [LayeredStorageImpl] and the resolution of its reads over them.
 *
 * The active layer is authoritative for the entities it holds; an entity only the frozen layer holds reads from
 * the frozen layer; an edge the active layer tombstoned reads as absent. Not synchronized: the owning storage
 * calls every member under its lock.
 */
internal class LayerStack(
    tagGroup: (String) -> String = IDENTITY_TAG_GROUP,
    private val families: () -> EdgePropertyFamilies?,
) {
    val active = ActiveLayer(families, tagGroup)
    var frozen: FrozenLayer? = null

    fun nodeIds(): Set<Int> {
        val frozenIds = frozen?.nodeIds ?: emptySet()
        if (active.nodeIds.isEmpty()) return frozenIds
        if (frozenIds.isEmpty()) return active.nodeIds
        return UnionSet(frozenIds, active.nodeIds)
    }

    fun containsNode(id: Int): Boolean = active.containsNode(id) || frozen?.containsNode(id) == true

    fun nodeProperties(id: Int): Map<String, IValue> {
        if (active.containsNode(id)) return active.nodeRows.view(id)
        return frozen?.nodeProperties(id) ?: throw EntityNotExistException(id.toString())
    }

    fun nodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        when {
            active.containsNode(id) -> active.nodeRows[id, name]
            frozen?.containsNode(id) == true -> frozen?.nodeProperty(id, name)
            else -> throw EntityNotExistException(id.toString())
        }

    // Writes always land in the active layer; a frozen node is promoted by copying
    // its frozen properties into its active row first.
    fun promoteNode(id: Int) {
        if (active.containsNode(id)) return
        val props = frozen?.capturedNodeRow(id) ?: throw EntityNotExistException(id.toString())
        active.addNode(id, emptyMap())
        active.nodeRows.setCaptured(id, props)
    }

    fun capturedNodeProperty(id: Int, name: String): CapturedProperty? = when {
        active.containsNode(id) -> active.nodeRows.capturedProperty(id, name)
        frozen?.containsNode(id) == true -> frozen?.capturedNodeProperty(id, name)
        else -> throw EntityNotExistException(id.toString())
    }

    fun nodeContents(id: Int): Map<String, PropertyValue> = when {
        active.containsNode(id) -> active.nodeRows.captured(id).contents()
        frozen?.containsNode(id) == true -> checkNotNull(frozen?.capturedNodeRow(id)).contents()
        else -> throw EntityNotExistException(id.toString())
    }

    fun nodeContent(id: Int, name: String): PropertyValue? = when {
        active.containsNode(id) -> active.nodeRows.content(id, name)
        frozen?.containsNode(id) == true -> frozen?.nodeContent(id, name)
        else -> throw EntityNotExistException(id.toString())
    }

    fun edgeContents(id: Int, layer: StorageLayer): Map<String, PropertyValue>? {
        if (layer == StorageLayer.FROZEN) return frozen?.capturedEdgeRow(id)?.contents()
        if (layer == StorageLayer.VISIBLE && !containsEdge(id)) return null
        if (active.containsEdge(id)) {
            val row = if (id in active.promotedEdgeEdits) {
                active.composePromotedCaptured(id, checkNotNull(frozen?.capturedEdgeRow(id)))
            } else active.edgeRows.captured(id)
            return row.contents()
        }
        return if (layer == StorageLayer.ACTIVE) null else frozen?.capturedEdgeRow(id)?.contents()
    }

    fun edgeContent(id: Int, name: String, layer: StorageLayer): PropertyValue? {
        if (layer == StorageLayer.FROZEN) return frozen?.edgeContent(id, name)
        if (layer == StorageLayer.VISIBLE && !containsEdge(id)) return null
        if (layer == StorageLayer.ACTIVE) return if (active.containsEdge(id)) active.edgeContent(id, name) else null
        if (active.containsEdge(id)) {
            if (id !in active.promotedEdgeEdits || active.hasEdgeProperty(id, name)) return active.edgeContent(id, name)
            val anchor = families().anchor(active.edgeEndpoints.getValue(id).tag, name)
            if (anchor != null && active.hasEdgeProperty(id, anchor) || active.masksFrozenProperty(id, name)) return null
        }
        return frozen?.edgeContent(id, name)
    }

    fun edgeIds(): Set<Int> {
        val frozenIds = frozen?.edgeIds?.let(::withoutTombstones) ?: emptySet()
        if (active.edgeEndpoints.isEmpty()) return frozenIds
        if (frozenIds.isEmpty()) return active.edgeEndpoints.keys
        return UnionSet(frozenIds, active.edgeEndpoints.keys)
    }

    fun containsEdge(id: Int): Boolean = active.containsEdge(id) || containsFrozenEdge(id)

    // A frozen edge is visible unless the active layer tombstoned it.
    fun containsFrozenEdge(id: Int): Boolean = frozen?.containsEdge(id) == true && id !in active.deletedEdgeIds

    fun withoutTombstones(frozenEdges: Set<Int>): Set<Int> =
        if (active.deletedEdgeIds.isEmpty()) frozenEdges else ExcludingSet(frozenEdges, active.deletedEdgeIds)

    fun edgeStructure(id: Int): IStorage.EdgeStructure {
        active.edgeEndpoints[id]?.let { return it }
        if (!containsFrozenEdge(id)) throw EntityNotExistException(id.toString())
        return checkNotNull(frozen?.edgeStructure(id))
    }

    fun edgeProperties(id: Int): Map<String, IValue> {
        if (active.containsEdge(id)) return activeEdgeProperties(id)
        if (!containsFrozenEdge(id)) throw EntityNotExistException(id.toString())
        return checkNotNull(frozen?.edgeProperties(id))
    }

    private fun activeEdgeProperties(id: Int): Map<String, IValue> {
        if (id !in active.promotedEdgeEdits) return active.edgeRows.view(id)
        return active.composePromotedCaptured(id, checkNotNull(frozen?.capturedEdgeRow(id))).compatibility()
    }

    // The row one layer itself holds, or null; a tombstone hides an edge from VISIBLE only.
    fun edgeProperties(
        id: Int,
        layer: StorageLayer,
    ): Map<String, IValue>? =
        when (layer) {
            StorageLayer.VISIBLE -> if (containsEdge(id)) edgeProperties(id) else null
            StorageLayer.ACTIVE -> if (active.containsEdge(id)) activeEdgeProperties(id) else null
            StorageLayer.FROZEN -> frozen?.edgeProperties(id)
        }

    fun edgeProperty(
        id: Int,
        name: String,
    ): IValue? =
        when {
            active.containsEdge(id) -> visibleActiveEdgeProperty(id, name)
            containsFrozenEdge(id) -> frozen?.edgeProperty(id, name)
            else -> throw EntityNotExistException(id.toString())
        }

    private fun visibleActiveEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        val edits = active.promotedEdgeEdits[id] ?: return active.edgeRows[id, name]
        if (active.hasEdgeProperty(id, name)) return active.explicitEdgeProperty(id, name)
        val anchor = families().anchor(active.edgeEndpoints.getValue(id).tag, name)
        return if (anchor != null && active.hasEdgeProperty(id, anchor) || active.masksFrozenProperty(id, name))
            null else frozen?.edgeProperty(id, name)
    }

    private fun localActiveEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        return active.explicitEdgeProperty(id, name)
    }

    fun capturedEdgeProperty(id: Int, name: String, layer: StorageLayer): CapturedProperty? = when (layer) {
        StorageLayer.FROZEN -> frozen?.capturedEdgeProperty(id, name)
        StorageLayer.ACTIVE -> if (active.containsEdge(id)) active.capturedEdgeProperty(id, name) else null
        StorageLayer.VISIBLE -> when {
            !containsEdge(id) -> null
            active.containsEdge(id) && active.hasEdgeProperty(id, name) -> active.capturedEdgeProperty(id, name)
            else -> frozen?.capturedEdgeProperty(id, name)
        }
    }

    fun edgeProperty(
        id: Int,
        name: String,
        layer: StorageLayer,
    ): IValue? =
        when (layer) {
            StorageLayer.VISIBLE -> if (containsEdge(id)) edgeProperty(id, name) else null
            StorageLayer.ACTIVE -> if (active.containsEdge(id)) localActiveEdgeProperty(id, name) else null
            StorageLayer.FROZEN -> frozen?.edgeProperty(id, name)
        }

    fun metaNames(): Set<String> {
        val frozenNames = (frozen?.metaNames ?: emptySet()) - active.deletedMetaNames
        if (active.metaProperties.isEmpty()) return frozenNames
        if (frozenNames.isEmpty()) return active.metaProperties.keys
        return UnionSet(frozenNames, active.metaProperties.keys)
    }

    fun meta(name: String): IValue? {
        active.metaProperties[name]?.let { return it }
        if (name in active.deletedMetaNames) return null
        return frozen?.meta(name)
    }
}
