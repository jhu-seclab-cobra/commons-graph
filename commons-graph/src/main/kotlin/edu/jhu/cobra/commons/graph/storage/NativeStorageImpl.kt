package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import java.util.Collections

/**
 * In-memory graph storage using Int-keyed HashMaps with auto-generated IDs.
 *
 * O(1) average time for structure operations; a property operation costs the entity's own property count.
 * Not thread-safe.
 * Uses split incoming/outgoing adjacency sets for directional edge queries.
 * Node and edge properties are kept per entity: one row of names and values per node or edge.
 * Adjacency queries return unmodifiable views without allocation.
 *
 * @constructor Creates a new empty storage instance.
 * @see NativeConcurStorageImpl
 */
@Suppress("TooManyFunctions")
public class NativeStorageImpl(projection: AdjacencyProjection? = null) : ExactAdjacencyStorage {
    private val exact = projection?.let(::ExactAdjacencyIndex)

    override val adjacencyProjection: AdjacencyProjection? get() = exact?.projection

    internal val indexedEdgeCount: Int get() = exact?.size ?: 0
    // Auto-increment counters
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // Node property rows: each node holds its own names and values
    private val nodeRows = RowProperties()

    // Edge property rows
    private val edgeRows = RowProperties()

    // Edge endpoint index (edge ID -> src, dst, tag)
    private val edgeEndpoints = HashMap<Int, IStorage.EdgeStructure>()

    // Adjacency lists (also serve as node existence index via outEdges.keys)
    private val outEdges = HashMap<Int, MutableSet<Int>>()
    private val inEdges = HashMap<Int, MutableSet<Int>>()

    // Metadata
    private val metaProperties = HashMap<String, IValue>()

    // ============================================================================
    // NODE OPERATIONS
    // ============================================================================

    override val nodeIDs: Set<Int>
        get() = Collections.unmodifiableSet(outEdges.keys)

    override fun containsNode(id: Int): Boolean = id in outEdges

    override fun addNode(properties: Map<String, IValue>): Int {
        val id = nodeCounter++
        outEdges[id] = HashSet()
        inEdges[id] = HashSet()
        nodeRows.set(id, properties)
        return id
    }

    override fun getNodeProperties(id: Int): Map<String, IValue> {
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        return nodeRows.view(id)
    }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? {
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        return nodeRows[id, name]
    }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (id !in outEdges) throw EntityNotExistException(id.toString())
        exact?.validateChange(id, properties, ::getNodeProperty)
        nodeRows.set(id, properties)
    }

    override fun deleteNode(id: Int) {
        val outSet = outEdges[id] ?: throw EntityNotExistException(id.toString())
        val outEdgeIds = outSet.toList()
        val inEdgeIds = inEdges[id]?.toList() ?: emptyList()
        for (eid in outEdgeIds) deleteIncidentEdge(eid)
        for (eid in inEdgeIds) deleteIncidentEdge(eid)
        outEdges.remove(id)
        inEdges.remove(id)
        nodeRows.remove(id)
    }

    // ============================================================================
    // EDGE OPERATIONS
    // ============================================================================

    override val edgeIDs: Set<Int>
        get() = Collections.unmodifiableSet(edgeEndpoints.keys)

    override fun containsEdge(id: Int): Boolean = id in edgeEndpoints

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int {
        val srcOut = outEdges[src] ?: throw EntityNotExistException(src.toString())
        val dstIn = inEdges[dst] ?: throw EntityNotExistException(dst.toString())
        val projected = exact?.prepare(IStorage.EdgeStructure(src, dst, tag), ::getNodeProperty)
        val id = edgeCounter++
        edgeEndpoints[id] = IStorage.EdgeStructure(src, dst, tag)
        srcOut.add(id)
        dstIn.add(id)
        edgeRows.set(id, properties)
        exact?.record(id, projected)
        return id
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure = edgeEndpoints[id] ?: throw EntityNotExistException(id.toString())

    override fun getEdgeProperties(id: Int): Map<String, IValue> {
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        return edgeRows.view(id)
    }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? {
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        return edgeRows[id, name]
    }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        if (id !in edgeEndpoints) throw EntityNotExistException(id.toString())
        edgeRows.set(id, properties)
    }

    override fun deleteEdge(id: Int) {
        val edge = edgeEndpoints.remove(id) ?: throw EntityNotExistException(id.toString())
        exact?.remove(id)
        outEdges[edge.src]?.remove(id)
        inEdges[edge.dst]?.remove(id)
        edgeRows.remove(id)
    }

    private fun deleteIncidentEdge(eid: Int) {
        val edge = edgeEndpoints[eid] ?: return
        exact?.remove(eid)
        inEdges[edge.dst]?.remove(eid)
        outEdges[edge.src]?.remove(eid)
        edgeEndpoints.remove(eid)
        edgeRows.remove(eid)
    }

    // ============================================================================
    // ADJACENCY QUERIES
    // ============================================================================

    override fun getIncomingEdges(id: Int): Set<Int> {
        val edges = inEdges[id] ?: throw EntityNotExistException(id.toString())
        return Collections.unmodifiableSet(edges)
    }

    override fun getOutgoingEdges(id: Int): Set<Int> {
        val edges = outEdges[id] ?: throw EntityNotExistException(id.toString())
        return Collections.unmodifiableSet(edges)
    }

    override fun getExactOutgoingEdges(source: Int, kind: IValue, key: IValue): Set<Int> {
        if (!containsNode(source)) throw EntityNotExistException(source.toString())
        return checkNotNull(exact) { "No exact adjacency projection was configured" }.select(source, kind, key)
    }

    override fun getExactOutgoingEdges(source: Int, kinds: Set<IValue>, key: IValue): Set<Int> {
        if (!containsNode(source)) throw EntityNotExistException(source.toString())
        val index = checkNotNull(exact) { "No exact adjacency projection was configured" }
        return kinds.flatMapTo(LinkedHashSet()) { index.select(source, it, key) }
    }

    override fun attachExact(source: Int, target: Int, properties: Map<String, IValue>): Int {
        if (!containsNode(source)) throw EntityNotExistException(source.toString())
        if (!containsNode(target)) throw EntityNotExistException(target.toString())
        val index = checkNotNull(exact) { "No exact adjacency projection was configured" }
        val structure = IStorage.EdgeStructure(source, target, index.projection.edgeTag)
        val member = checkNotNull(index.prepare(structure, ::getNodeProperty))
        return index.existing(member) ?: addEdge(source, target, index.projection.edgeTag, properties)
    }

    // ============================================================================
    // METADATA OPERATIONS
    // ============================================================================

    override val metaNames: Set<String>
        get() = metaProperties.keys

    override fun getMeta(name: String): IValue? = metaProperties[name]

    override fun setMeta(
        name: String,
        value: IValue?,
    ) {
        if (value == null) metaProperties.remove(name) else metaProperties[name] = value
    }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun flush() {
        // No buffered writes; mutations are applied immediately.
    }

    override fun clear() {
        exact?.clear()
        outEdges.clear()
        inEdges.clear()
        edgeEndpoints.clear()
        edgeRows.clear()
        nodeRows.clear()
        metaProperties.clear()
        nodeCounter = 0
        edgeCounter = 0
    }

    override fun transferTo(target: IStorage): Map<Int, Int> {
        // Copying into the iterated storage mutates it mid-iteration; reject up front.
        require(target !== this) { "Cannot transfer a storage into itself" }
        val nodeIdMap = HashMap<Int, Int>()
        for (nodeId in outEdges.keys) {
            val newId = target.addNode(getNodeProperties(nodeId))
            nodeIdMap[nodeId] = newId
        }
        for ((edgeId, ep) in edgeEndpoints) {
            val newSrc = nodeIdMap.getValue(ep.src)
            val newDst = nodeIdMap.getValue(ep.dst)
            target.addEdge(newSrc, newDst, ep.tag, getEdgeProperties(edgeId))
        }
        for (name in metaProperties.keys) {
            target.setMeta(name, metaProperties[name])
        }
        return nodeIdMap
    }
}
