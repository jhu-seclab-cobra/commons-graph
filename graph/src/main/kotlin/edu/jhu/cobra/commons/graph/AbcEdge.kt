package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.strVal

/**
 * Unique identifier for an edge in the graph.
 *
 * Represents an immutable identifier composed of source node, destination node, and edge type.
 *
 * @property srcNid The source node identifier.
 * @property dstNid The destination node identifier.
 * @property eType The edge type name.
 * @constructor Creates an [EdgeID] from source, destination, and type.
 * @param srcNid The source node identifier.
 * @param dstNid The destination node identifier.
 * @param eType The edge type name.
 * @see NodeID
 * @see IEntity.ID
 */
data class EdgeID(val srcNid: NodeID, val dstNid: NodeID, val eType: String) : IEntity.ID {
    /**
     * Returns the string representation of this identifier.
     *
     * @return The identifier as string in format "sourceNodeId-edgeType-destinationNodeId".
     */
    override val asString: String by lazy { "$srcNid-$eType-$dstNid" }

    /**
     * Returns the serialized edge identifier as a [ListVal].
     *
     * @return List containing source ID, destination ID, and edge type.
     */
    override val serialize: ListVal by lazy { ListVal(srcNid.serialize, dstNid.serialize, eType.strVal) }

    override fun toString() = asString

    /**
     * Creates an [EdgeID] from a [ListVal].
     *
     * @param value List containing source ID, destination ID, and edge type.
     */
    constructor(value: ListVal) : this(
        value[0].toEntityID<NodeID>(),
        value[1].toEntityID<NodeID>(),
        value[2].core.toString()
    )
}

/**
 * Abstract base class for graph edges with storage-backed property management.
 *
 * Provides property access, identity management, and storage integration for edges.
 *
 * @property storage The storage system for edge properties.
 * @constructor Creates an edge with the given [IStorage].
 * @param storage The storage system for edge properties.
 * @see AbcEntity
 * @see IEntity
 * @see EdgeID
 */
abstract class AbcEdge(protected val storage: IStorage) : AbcEntity() {

    /**
     * Represents the type information for an edge.
     */
    interface Type : IEntity.Type

    /**
     * Returns the unique edge identifier.
     *
     * @return The edge's identifier.
     */
    abstract override val id: EdgeID

    /**
     * Returns the source node identifier.
     *
     * @return The source node ID.
     */
    val srcNid: NodeID get() = id.srcNid

    /**
     * Returns the destination node identifier.
     *
     * @return The destination node ID.
     */
    val dstNid: NodeID get() = id.dstNid

    /**
     * Returns the edge type name.
     *
     * @return The edge type.
     */
    val eType: String get() = id.eType

    /**
     * Sets a property value for the edge.
     *
     * @param name The property name.
     * @param value The property value.
     */
    override fun setProp(name: String, value: IValue?) =
        storage.setEdgeProperties(id, mapOf(name to value))

    /**
     * Sets multiple properties for the edge.
     *
     * @param props Map of property names to values.
     */
    override fun setProps(props: Map<String, IValue?>) =
        storage.setEdgeProperties(id, props)

    /**
     * Returns a property value from the edge.
     *
     * @param name The property name.
     * @return The property value, or null if absent.
     */
    override fun getProp(name: String): IValue? = storage.getEdgeProperties(id)[name]

    /**
     * Returns all properties of the edge.
     *
     * @return Map of property names to values.
     */
    override fun getAllProps(): Map<String, IValue> = storage.getEdgeProperties(id)

    /**
     * Returns true if the edge contains the specified property.
     *
     * @param name The property name.
     * @return True if the property exists, false otherwise.
     */
    override fun containProp(name: String): Boolean = storage.getEdgeProperties(id)[name] != null

    /**
     * Returns a string representation of the edge.
     *
     * @return String containing edge ID and type.
     */
    override fun toString(): String = "{${id}, ${this.type}}"

    /**
     * Returns the hash code based on string representation.
     *
     * @return The hash code value.
     */
    override fun hashCode(): Int = toString().hashCode()

    /**
     * Compares this edge with another object for equality.
     *
     * @param other The object to compare with.
     * @return True if other is an edge with the same ID, false otherwise.
     */
    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.id == other.id else super.equals(other)
}

