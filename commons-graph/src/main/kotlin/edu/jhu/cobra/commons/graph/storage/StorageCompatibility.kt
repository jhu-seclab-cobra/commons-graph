package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import java.util.concurrent.locks.Lock

// One migration boundary; no independent identity, row, adjacency or family state.
@Suppress("TooManyFunctions") // The combined compatibility interface requires these overrides.
internal class StorageCompatibility(
    private val storage: LayeredStorage,
    private val guard: StorageAccessGuard,
    private val closeOwner: () -> Unit,
) : LayeredStorage {
    override val criticalSectionLock: Lock get() = guard.mutationLock
    override val nodeIDs: Set<Int> get() = guard.read { storage.nodeIDs.toSet() }
    override val edgeIDs: Set<Int> get() = guard.read { storage.edgeIDs.toSet() }
    override val metaNames: Set<String> get() = guard.read { storage.metaNames.toSet() }
    override val tagGroup: (String) -> String get() = guard.read { storage.tagGroup }
    override val propertyFamilies: EdgePropertyFamilies? get() = guard.read { storage.propertyFamilies }
    override val adjacencyProjection: AdjacencyProjection? get() = guard.read { storage.adjacencyProjection }
    override val attachmentProjection: AdjacencyProjection? get() = guard.read { storage.attachmentProjection }
    override val layerCount: Int get() = guard.read { storage.layerCount }

    override fun containsNode(id: Int): Boolean =
        guard.read { storage.containsNode(id) }

    override fun containsEdge(id: Int): Boolean =
        guard.read { storage.containsEdge(id) }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        guard.read { capture(storage.getNodeProperties(id)) }

    override fun getNodeProperty(id: Int, name: String): IValue? =
        guard.read { storage.getNodeProperty(id, name)?.deepCopy() }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure =
        guard.read { storage.getEdgeStructure(id) }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        guard.read { capture(storage.getEdgeProperties(id)) }

    override fun getEdgeProperties(id: Int, layer: StorageLayer): Map<String, IValue>? =
        guard.read { storage.getEdgeProperties(id, layer)?.let(::capture) }

    override fun getEdgeProperty(id: Int, name: String): IValue? =
        guard.read { storage.getEdgeProperty(id, name)?.deepCopy() }

    override fun getEdgeProperty(id: Int, name: String, layer: StorageLayer): IValue? =
        guard.read { storage.getEdgeProperty(id, name, layer)?.deepCopy() }

    override fun hasActiveEdgeProperty(id: Int, name: String): Boolean =
        guard.read { storage.hasActiveEdgeProperty(id, name) }

    override fun getMeta(name: String): IValue? =
        guard.read { storage.getMeta(name)?.deepCopy() }

    override fun getIncomingEdges(id: Int): Set<Int> =
        guard.read { storage.getIncomingEdges(id) }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        guard.read { storage.getOutgoingEdges(id) }

    override fun getIncomingEdges(id: Int, group: String): Set<Int> =
        guard.read { storage.getIncomingEdges(id, group) }

    override fun getOutgoingEdges(id: Int, group: String): Set<Int> =
        guard.read { storage.getOutgoingEdges(id, group) }

    override fun getIncomingEdges(id: Int, groups: Set<String>): Set<Int> =
        guard.read { storage.getIncomingEdges(id, groups) }

    override fun getOutgoingEdges(id: Int, groups: Set<String>): Set<Int> =
        guard.read { storage.getOutgoingEdges(id, groups) }

    override fun getEdges(src: Int, dst: Int, tag: String?): Set<Int> =
        guard.read { storage.getEdges(src, dst, tag) }

    override fun findEdge(src: Int, dst: Int): Int? =
        guard.read { storage.findEdge(src, dst) }

    override fun findEdge(src: Int, dst: Int, tag: String): Int? =
        guard.read { storage.findEdge(src, dst, tag) }

    override fun getExactOutgoingEdges(source: Int, kind: IValue, key: IValue): Set<Int> =
        guard.read { storage.getExactOutgoingEdges(source, kind, key) }

    override fun getExactOutgoingEdges(source: Int, kinds: Set<IValue>, key: IValue): Set<Int> =
        guard.read { storage.getExactOutgoingEdges(source, kinds, key) }

    override fun addNode(properties: Map<String, IValue>): Int =
        guard.write { storage.addNode(capture(properties)) }

    override fun addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue>): Int =
        guard.write { storage.addEdge(src, dst, tag, capture(properties)) }

    override fun setNodeProperties(id: Int, properties: Map<String, IValue?>): Unit =
        guard.write { storage.setNodeProperties(id, captureUpdates(properties)) }

    override fun setEdgeProperties(id: Int, properties: Map<String, IValue?>): Unit =
        guard.write { storage.setEdgeProperties(id, captureUpdates(properties)) }

    override fun deleteNode(id: Int): Unit =
        guard.write { storage.deleteNode(id) }

    override fun deleteEdge(id: Int): Unit =
        guard.write { storage.deleteEdge(id) }

    override fun setMeta(name: String, value: IValue?): Unit =
        guard.write { storage.setMeta(name, value?.deepCopy()) }

    override fun bindPropertyFamilies(families: EdgePropertyFamilies): Unit =
        guard.write { storage.bindPropertyFamilies(families) }

    override fun addRevertListener(listener: RevertListener): Unit =
        guard.write { storage.addRevertListener(listener) }

    override fun attachExact(source: Int, target: Int, properties: Map<String, IValue>): Int =
        guard.write { storage.attachExact(source, target, capture(properties)) }

    override fun freeze(): Unit =
        guard.write { storage.freeze() }

    override fun clearActiveLayer(): Unit =
        guard.write { storage.clearActiveLayer() }

    override fun clear(): Unit =
        guard.write { storage.clear() }

    override fun flush(): Unit =
        guard.write { storage.flush() }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        require(target !== this && target !== storage) { "Cannot transfer a storage into itself" }
        return guard.read { storage.transferTo(StorageTransfer(target)) }
    }

    override fun close(): Unit = closeOwner()
}

// Complete capture precedes publication of a multi-property update.
private fun captureUpdates(properties: Map<String, IValue?>): Map<String, IValue?> =
    properties.mapValues { (_, value) -> value?.deepCopy() }
