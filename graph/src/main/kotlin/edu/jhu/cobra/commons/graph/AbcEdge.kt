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
 * The edge's [id] is the deterministic string "$src-$tag-$dst". Storage operations
 * use the internal [storageId] (auto-generated Int). Structural info (source,
 * destination, tag) is injected at bind time by the graph layer — no lazy
 * storage lookup needed.
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

    /** The storage-internal Int ID, injected by the graph layer via [bind]. */
    var storageId: Int = -1
        internal set

    /** Deterministic edge ID string "$src-$tag-$dst", set at bind time. */
    lateinit var edgeId: String
        internal set

    /** Source node ID, injected at bind time. */
    lateinit var srcNid: NodeID
        internal set

    /** Destination node ID, injected at bind time. */
    lateinit var dstNid: NodeID
        internal set

    /** Edge tag name, injected at bind time. */
    lateinit var eTag: String
        internal set

    /**
     * Initializes this edge with the given storage and structural info.
     * Called by the graph layer after construction — must not be called by user code.
     */
    internal fun bind(
        storage: IStorage,
        storageId: Int,
        srcNid: NodeID,
        dstNid: NodeID,
        tag: String,
    ) {
        this.storage = storage
        this.storageId = storageId
        this.srcNid = srcNid
        this.dstNid = dstNid
        this.eTag = tag
        this.edgeId = "$srcNid-$tag-$dstNid"
    }

    /**
     * The deterministic edge identifier string.
     */
    override val id: String get() = edgeId

    /**
     * Visibility labels assigned to this edge.
     *
     * @return Set of labels currently assigned.
     */
    var labels: Set<Label>
        get() {
            val raw = storage.getEdgeProperty(storageId, "labels") as? ListVal ?: return emptySet()
            return raw.core.mapTo(HashSet(raw.core.size)) { Label((it as StrVal).core) }
        }
        set(values) {
            storage.setEdgeProperties(storageId, mapOf("labels" to values.map { it.core }.listVal))
        }

    override fun get(name: String): IValue? = storage.getEdgeProperty(storageId, name)

    override fun set(
        name: String,
        value: IValue?,
    ) {
        storage.setEdgeProperties(storageId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean = storage.getEdgeProperty(storageId, name) != null

    override fun asMap(): Map<String, IValue> = storage.getEdgeProperties(storageId)

    override fun update(props: Map<String, IValue?>) {
        storage.setEdgeProperties(storageId, props)
    }

    override fun toString(): String = "{$srcNid-$eTag-$dstNid, ${this.type}}"

    override fun hashCode(): Int = storageId

    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.edgeId == other.edgeId else super.equals(other)
}
