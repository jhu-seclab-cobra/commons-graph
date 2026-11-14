package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Mutable active layer of [LayeredStorageImpl].
 *
 * Holds node/edge property rows, edge endpoints, adjacency sets,
 * metadata, and edge/metadata tombstones, all keyed by global IDs. Property rows are kept in
 * one [RowProperties] store per entity kind.
 */
internal class ActiveLayer(
    private val families: () -> EdgePropertyFamilies? = { null },
    tagGroup: (String) -> String = IDENTITY_TAG_GROUP,
) {
    val tagAdjacency = TagAdjacencyIndex(tagGroup)
    val nodeRows = RowProperties()
    val edgeRows = RowProperties()

    // Explicit writes to edges inherited from the frozen layer. Null masks a frozen property.
    val promotedEdgeEdits = HashMap<Int, MutableMap<String, CapturedProperty?>>()
    val edgeEndpoints = LinkedHashMap<Int, IStorage.EdgeStructure>()
    val outEdges = HashMap<Int, MutableSet<Int>>()
    val inEdges = HashMap<Int, MutableSet<Int>>()
    val metaProperties = HashMap<String, IValue>()

    // Tombstones masking frozen-layer metadata deleted through the active layer.
    val deletedMetaNames = HashSet<String>()

    // Tombstones masking frozen-layer edges deleted through the active layer.
    val deletedEdgeIds = HashSet<Int>()

    val nodeIds: Set<Int> get() = outEdges.keys

    fun containsNode(id: Int): Boolean = id in outEdges

    fun containsEdge(id: Int): Boolean = id in edgeEndpoints

    fun addNode(
        id: Int,
        properties: Map<String, IValue>,
    ) {
        outEdges[id] = LinkedHashSet()
        inEdges[id] = LinkedHashSet()
        nodeRows.set(id, properties)
    }

    fun addEdge(
        id: Int,
        structure: IStorage.EdgeStructure,
        properties: Map<String, IValue>,
    ) {
        edgeEndpoints[id] = structure
        tagAdjacency.add(id, structure)
        outEdges.getValue(structure.src).add(id)
        inEdges.getValue(structure.dst).add(id)
        edgeRows.set(id, properties)
    }

    fun collectNodeProperties(id: Int): Map<String, IValue> = nodeRows.collect(id)

    fun collectEdgeProperties(id: Int): Map<String, IValue> = edgeRows.collect(id)

    fun composePromotedEdgeProperties(
        id: Int,
        frozen: Map<String, IValue>,
    ): Map<String, IValue> = composePromotedCaptured(id, frozen.mapValues { LegacyProperty(it.value) }).compatibility()

    fun composePromotedCaptured(id: Int, frozen: CapturedRow): CapturedRow {
        val edits = promotedEdgeEdits.getValue(id)
        val tag = edgeEndpoints.getValue(id).tag
        val family = families()
        val result = HashMap<String, CapturedProperty>()
        for ((name, value) in frozen) {
            val anchor = family.anchor(tag, name)
            if (anchor == null || !hasEdgeProperty(id, anchor)) result[name] = value
        }
        for ((name, value) in edits) {
            if (value == null) result.remove(name) else result[name] = value.composeFamily(frozen[name])
        }
        return result
    }

    fun hasEdgeProperty(id: Int, name: String): Boolean {
        val promoted = promotedEdgeEdits[id] ?: return edgeRows.contains(id, name)
        if (name in promoted) return (promoted[name] as? CapturedFamily<*>)?.holds(name) ?: true
        return promoted.values.filterIsInstance<CapturedFamily<*>>().any { it.holds(name) }
    }

    fun capturedEdgeProperty(id: Int, name: String): CapturedProperty? =
        promotedEdgeEdits[id]?.get(name) ?: edgeRows.capturedProperty(id, name)

    fun explicitEdgeProperty(id: Int, name: String): IValue? {
        val promoted = promotedEdgeEdits[id] ?: return edgeRows[id, name]
        if (name in promoted) return promoted[name]?.compatibility(name)
        return promoted.values.filterIsInstance<CapturedFamily<*>>()
            .firstOrNull { name.startsWith(it.key.memberPrefix) }?.compatibility(name)
    }

    fun edgeContent(id: Int, name: String): PropertyValue? {
        val promoted = promotedEdgeEdits[id] ?: return edgeRows.content(id, name)
        if (name in promoted) return promoted[name]?.content(name)
        for (value in promoted.values) {
            val family = value as? CapturedFamily<*> ?: continue
            if (name.startsWith(family.key.memberPrefix)) return family.content(name)
        }
        return null
    }

    fun masksFrozenProperty(id: Int, name: String): Boolean =
        promotedEdgeEdits[id]?.values.orEmpty().filterIsInstance<CapturedFamily<*>>().any {
            it.anchorWritten && name.startsWith(it.key.memberPrefix)
        }

    fun setCapturedEdgeProperties(id: Int, properties: Map<String, CapturedProperty?>) {
        val promoted = promotedEdgeEdits[id]
        if (promoted == null) edgeRows.setCaptured(id, properties) else promoted.putAll(properties)
    }

    fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        nodeRows.set(id, properties)
    }

    fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        val promoted = promotedEdgeEdits[id]
        if (promoted == null) {
            edgeRows.set(id, properties)
        } else {
            promoted.putAll(properties.mapValues { it.value?.let(::LegacyProperty) })
        }
    }

    fun removeNode(id: Int) {
        for (eid in (outEdges[id] ?: emptySet<Int>()).toList()) removeEdge(eid)
        for (eid in (inEdges[id] ?: emptySet<Int>()).toList()) removeEdge(eid)
        outEdges.remove(id)
        inEdges.remove(id)
        nodeRows.remove(id)
    }

    fun removeEdge(id: Int) {
        val edge = edgeEndpoints.remove(id) ?: return
        tagAdjacency.remove(id, edge)
        outEdges[edge.src]?.remove(id)
        inEdges[edge.dst]?.remove(id)
        edgeRows.remove(id)
        promotedEdgeEdits.remove(id)
    }

    fun clear() {
        tagAdjacency.clear()
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeRows.clear()
        promotedEdgeEdits.clear()
        nodeRows.clear()
        metaProperties.clear()
        deletedMetaNames.clear()
        deletedEdgeIds.clear()
    }
}
