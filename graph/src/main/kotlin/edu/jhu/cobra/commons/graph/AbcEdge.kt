package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.toTypeArray
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Represents a unique identifier for a node in the graph. This data class encapsulates the name
 * information necessary to uniquely identify a node.
 *
 * @property name The string representation of the node identifier.
 */
data class NodeID(override val name: String) : IEntity.ID {
    /**
     * The serialized representation of the node identifier as a string value.
     */
    override val serialize: StrVal get() = name.strVal

    override fun toString() = name

    /**
     * Creates a node identifier from a string value.
     *
     * @param strVal The string value representing the node identifier.
     */
    constructor(strVal: StrVal) : this(strVal.core)
}

/**
 * Abstract base class for nodes in the graph. This class implements the [IEntity] interface and provides
 * common functionality for node operations, including property management and identity comparison.
 *
 * @property storage The storage system used to persist node properties and relationships.
 */
abstract class AbcNode(private val storage: IStorage) : AbcBasicEntity() {

    /**
     * Interface representing the type information for a node.
     */
    interface Type : IEntity.Type

    /**
     * The unique identifier of the node.
     */
    abstract override val id: NodeID

    /**
     * The type information of the node.
     */
    abstract override val type: Type

    /**
     * Checks if the provided target storage is the same as the storage associated with this node.
     *
     * @param target The target storage to compare.
     * @return `true` if the target storage matches the node's storage, `false` otherwise.
     */
    fun doUseStorage(target: IStorage) = target == storage

    /**
     * Sets a property value for the node.
     *
     * @param name The name of the property to set.
     * @param value The value to associate with the property.
     */
    override fun setProp(name: String, value: IValue?) =
        storage.setNodeProperties(id, name to value)

    /**
     * Sets multiple properties for the node at once.
     *
     * @param props A map of property names to their corresponding values.
     */
    override fun setProps(props: Map<String, IValue?>) =
        storage.setNodeProperties(id, *props.toTypeArray())

    /**
     * Retrieves the value of a property from the node.
     *
     * @param name The name of the property to retrieve.
     * @return The value of the property if it exists, `null` otherwise.
     */
    override fun getProp(name: String): IValue? = storage.getNodeProperty(id, name)

    /**
     * Retrieves all properties of the node.
     *
     * @return A map containing all properties of the node, where the key is the property name
     *         and the value is the property value.
     */
    override fun getAllProps() = storage.getNodeProperties(id)

    /**
     * Returns a string representation of the node.
     *
     * @return A string containing the node identifier and type information.
     */
    override fun toString(): String = "{id=${id}, type=${this.type}}"

    /**
     * Returns a hash code value for the node.
     *
     * @return The hash code value based on the string representation of the node.
     */
    override fun hashCode(): Int = toString().hashCode()

    /**
     * Compares this node with another object for equality.
     *
     * @param other The object to compare with.
     * @return `true` if the other object is a node with the same identifier, `false` otherwise.
     */
    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)
}

/**
 * Represents a unique identifier for an edge in the graph. This data class encapsulates the source node,
 * destination node, and edge type information necessary to uniquely identify an edge.
 *
 * @property srcNid The identifier of the source node.
 * @property dstNid The identifier of the destination node.
 * @property eType The type of the edge.
 */
data class EdgeID(val srcNid: NodeID, val dstNid: NodeID, val eType: String) : IEntity.ID {
    /**
     * The string representation of the edge identifier, formatted as "sourceNodeId-edgeType-destinationNodeId".
     */
    override val name: String by lazy { "$srcNid-$eType-$dstNid" }

    /**
     * The serialized representation of the edge identifier as a list of values.
     */
    override val serialize: ListVal by lazy { ListVal(srcNid.serialize, dstNid.serialize, eType.strVal) }

    override fun toString() = name

    /**
     * Creates an edge identifier from a list of values.
     *
     * @param value The list containing source node ID, destination node ID, and edge type.
     */
    constructor(value: ListVal) : this(
        value[0].toEntityID<NodeID>(),
        value[1].toEntityID<NodeID>(),
        value[2].core.toString()
    )
}

/**
 * Abstract base class for edges in the graph. This class implements the [IEntity] interface and provides
 * common functionality for edge operations, including property management and identity comparison.
 *
 * @property storage The storage system used to persist edge properties and relationships.
 */
abstract class AbcEdge(private val storage: IStorage) : AbcBasicEntity() {

    /**
     * Interface representing the type information for an edge.
     */
    interface Type : IEntity.Type

    /**
     * The unique identifier of the edge.
     */
    abstract override val id: EdgeID

    /**
     * The identifier of the source node.
     */
    val srcNid: NodeID get() = id.srcNid

    /**
     * The identifier of the destination node.
     */
    val dstNid: NodeID get() = id.dstNid

    /**
     * The type of the edge.
     */
    val eType: String get() = id.eType

    /**
     * Sets a property value for the edge.
     *
     * @param name The name of the property to set.
     * @param value The value to associate with the property.
     */
    override fun setProp(name: String, value: IValue?) =
        storage.setEdgeProperties(id, name to value!!)

    /**
     * Sets multiple properties for the edge at once.
     *
     * @param props A map of property names to their corresponding values.
     */
    override fun setProps(props: Map<String, IValue?>) =
        storage.setEdgeProperties(id, *props.toTypeArray())

    /**
     * Retrieves the value of a property from the edge.
     *
     * @param name The name of the property to retrieve.
     * @return The value of the property if it exists, `null` otherwise.
     */
    override fun getProp(name: String): IValue? = storage.getEdgeProperty(id, name)

    /**
     * Retrieves all properties of the edge.
     *
     * @return A map containing all properties of the edge, where the key is the property name
     *         and the value is the property value.
     */
    override fun getAllProps(): Map<String, IValue> = storage.getEdgeProperties(id)

    /**
     * Returns a string representation of the edge.
     *
     * @return A string containing the edge identifier and type information.
     */
    override fun toString(): String = "{${id}, ${this.type}}"

    /**
     * Returns a hash code value for the edge.
     *
     * @return The hash code value based on the string representation of the edge.
     */
    override fun hashCode(): Int = toString().hashCode()

    /**
     * Compares this edge with another object for equality.
     *
     * @param other The object to compare with.
     * @return `true` if the other object is an edge with the same identifier, `false` otherwise.
     */
    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.id == other.id else super.equals(other)
}

