package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.poset.Label
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal

/**
 * Abstract base class for graph edges with storage-backed property management.
 *
 * The edge's [id] is the storage edge identifier. Structural info (source, destination, tag)
 * is resolved via [IStorage.getEdgeSrc], [IStorage.getEdgeDst], [IStorage.getEdgeTag].
 * All properties stored in storage are user properties — structural metadata lives in the
 * storage layer's own data structures, not in the property namespace.
 *
 * Subclasses use a no-arg constructor. The graph layer calls [bind] after
 * creation to inject storage and edge identity — these are not constructor
 * parameters, keeping subclass constructors free of infrastructure concerns.
 *
 * @see AbcEntity
 * @see IEntity
 */
abstract class AbcEdge : AbcEntity() {
    /**
     * Represents the type information for an edge.
     */
    interface Type : IEntity.Type

    /** Backing storage, injected by the graph layer via [bind]. */
    protected lateinit var storage: IStorage
        private set

    /** The edge identifier, injected by the graph layer via [bind]. */
    lateinit var edgeId: String
        internal set

    /**
     * Initializes this edge with the given storage and edge ID.
     * Called by the graph layer after construction — must not be called by user code.
     */
    internal fun bind(
        storage: IStorage,
        edgeId: String,
    ) {
        this.storage = storage
        this.edgeId = edgeId
    }

    private val structure by lazy(LazyThreadSafetyMode.NONE) { storage.getEdgeStructure(edgeId) }

    /** Source node ID, resolved from storage edge structure. */
    val srcNid: NodeID get() = structure.src

    /** Destination node ID, resolved from storage edge structure. */
    val dstNid: NodeID get() = structure.dst

    /** Edge tag name, resolved from storage edge structure. */
    val eTag: String get() = structure.tag

    /**
     * The edge's storage identifier.
     */
    override val id: String get() = edgeId

    /**
     * Visibility labels assigned to this edge.
     *
     * @return Set of labels currently assigned.
     */
    var labels: Set<Label>
        get() {
            val raw = storage.getEdgeProperty(edgeId, "labels") as? ListVal ?: return emptySet()
            return raw.core.mapTo(HashSet(raw.core.size)) { Label((it as StrVal).core) }
        }
        set(values) {
            storage.setEdgeProperties(edgeId, mapOf("labels" to values.map { it.core }.listVal))
        }

    override fun get(name: String): IValue? = storage.getEdgeProperty(edgeId, name)

    override fun set(
        name: String,
        value: IValue?,
    ) {
        storage.setEdgeProperties(edgeId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean = storage.getEdgeProperty(edgeId, name) != null

    override fun asMap(): Map<String, IValue> = storage.getEdgeProperties(edgeId)

    override fun update(props: Map<String, IValue?>) {
        storage.setEdgeProperties(edgeId, props)
    }

    override fun toString(): String = "{$srcNid-$eTag-$dstNid, ${this.type}}"

    override fun hashCode(): Int = edgeId.hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.edgeId == other.edgeId else super.equals(other)
}
