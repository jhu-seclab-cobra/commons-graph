package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue

/**
 * User-provided node identifier.
 */
typealias NodeID = String

/**
 * Abstract base class for graph nodes with storage-backed property management.
 *
 * The node's [id] is the user-provided [NodeID]. All properties stored in storage
 * are user properties — no internal metadata is kept in the property namespace.
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

    /** The user-provided node ID, injected by the graph layer via [bind]. */
    lateinit var nodeId: NodeID
        internal set

    /**
     * Initializes this node with the given storage and node ID.
     * Called by the graph layer after construction — must not be called by user code.
     */
    internal fun bind(
        storage: IStorage,
        nodeId: NodeID,
    ) {
        this.storage = storage
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

    override fun get(name: String): IValue? = storage.getNodeProperty(nodeId, name)

    override fun set(
        name: String,
        value: IValue?,
    ) {
        storage.setNodeProperties(nodeId, mapOf(name to value))
    }

    override fun contains(name: String): Boolean = storage.getNodeProperty(nodeId, name) != null

    override fun asMap(): Map<String, IValue> = storage.getNodeProperties(nodeId)

    override fun update(props: Map<String, IValue?>) {
        storage.setNodeProperties(nodeId, props)
    }

    override fun toString(): String = "{id=$id, type=${this.type}}"

    override fun hashCode(): Int = nodeId.hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)
}
