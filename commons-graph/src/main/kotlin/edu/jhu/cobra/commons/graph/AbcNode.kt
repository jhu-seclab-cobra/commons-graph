package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.AbcMultipleGraph.Companion.PROP_NODE_ID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue

/**
 * User-provided node identifier.
 */
typealias NodeID = String

/**
 * Abstract base class for graph nodes with storage-backed property management.
 *
 * The node's [id] is the user-provided [NodeID]. Storage operations use the
 * internal [storageId] (auto-generated Int). The graph layer stores the NodeID
 * as an internal property ([AbcMultipleGraph.PROP_NODE_ID]) which is filtered
 * from all user-facing property APIs ([get], [set], [contains], [asMap], [update]).
 *
 * Subclasses use a no-arg constructor. The graph layer calls [bind] after
 * creation to inject storage and node identity — these are not constructor
 * parameters, keeping subclass constructors free of infrastructure concerns.
 *
 * @see AbcEntity
 * @see IEntity
 */
abstract class AbcNode : AbcEntity() {
    /**
     * Represents the type information for a node.
     */
    interface Type : IEntity.Type

    /** Backing storage, injected by the graph layer via [bind]. */
    protected lateinit var storage: IStorage
        private set

    /** The storage-internal Int ID, injected by the graph layer via [bind]. */
    var storageId: Int = -1
        internal set

    /** The user-provided node ID, injected by the graph layer via [bind]. */
    lateinit var nodeId: NodeID
        internal set

    /**
     * Initializes this node with the given storage, storage ID, and node ID.
     * Called by the graph layer after construction — must not be called by user code.
     */
    internal fun bind(
        storage: IStorage,
        storageId: Int,
        nodeId: NodeID,
    ) {
        this.storage = storage
        this.storageId = storageId
        this.nodeId = nodeId
    }

    /**
     * The user-provided node ID.
     */
    override val id: NodeID
        get() = nodeId

    /**
     * Returns the node type information.
     *
     * @return The node's type.
     */
    abstract override val type: Type

    /**
     * Returns true if the target storage matches this node's storage.
     *
     * @param target The storage to compare.
     * @return True if storage matches, false otherwise.
     */
    fun doUseStorage(target: IStorage): Boolean = target == storage

    override fun get(name: String): IValue? {
        if (name == PROP_NODE_ID) return null
        return storage.getNodeProperty(storageId, name)
    }

    override fun set(
        name: String,
        value: IValue?,
    ) {
        require(name != PROP_NODE_ID) { "Cannot set reserved property: $PROP_NODE_ID" }
        storage.setNodeProperties(storageId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean {
        if (name == PROP_NODE_ID) return false
        return storage.getNodeProperty(storageId, name) != null
    }

    override fun asMap(): Map<String, IValue> = storage.getNodeProperties(storageId) - PROP_NODE_ID

    override fun update(props: Map<String, IValue?>) {
        require(PROP_NODE_ID !in props) { "Cannot set reserved property: $PROP_NODE_ID" }
        storage.setNodeProperties(storageId, props)
    }

    override fun toString(): String = "{id=$id, type=${this.type}}"

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)
}
