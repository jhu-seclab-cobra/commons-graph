package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.MapVal

/**
 * Determines if a node or edge specified by [id] exists in the storage.
 *
 * @param id The identifier of the node or edge, which can be either a [NodeID] or an [EdgeID].
 * @return True if the node or edge exists in the storage, false otherwise.
 */
operator fun IStorage.contains(id: IEntity.ID): Boolean = when (id) {
    is NodeID -> containsNode(id)
    is EdgeID -> containsEdge(id)
}

/**
 * Checks if the storage contains metadata with the specified property name.
 *
 * @param prop The name of the property to check for in the storage metadata.
 * @return `true` if the storage contains metadata with the specified property name, `false` otherwise.
 */
operator fun IStorage.contains(prop: String): Boolean = getMeta(prop) != null


/**
 * Updates all properties of a node or edge with new values provided in [newProperties].
 *
 * @param id The identifier of the node or edge to update.
 * @param newProperties A [MapVal] containing the properties to update.
 */
operator fun IStorage.set(id: IEntity.ID, newProperties: Map<String, IValue?>) {
    val validProperties by lazy { newProperties.mapNotNull { (k, v) -> v?.let { k to v } } }
    return when (id) {
        is NodeID ->
            if (id !in this) addNode(id, *validProperties.toTypedArray())
            else setNodeProperties(id, *newProperties.toTypeArray())

        is EdgeID ->
            if (id !in this) addEdge(id, *validProperties.toTypedArray())
            else setEdgeProperties(id, *newProperties.toTypeArray())
    }
}

/**
 * Sets metadata properties for the storage.
 *
 * @param props Vararg parameter representing key-value pairs of metadata properties to be set.
 */
fun IStorage.setMeta(vararg props: Pair<String, IValue>) {
    val metaID = NodeID("__meta__")
    if (!containsNode(metaID)) addNode(metaID, newProperties = props)
    else setNodeProperties(metaID, newProperties = props)
}


/**
 * Sets a metadata property on the IStorage instance.
 *
 * @param name The name of the metadata property.
 * @param value The value of the metadata property.
 */
operator fun IStorage.set(name: String, value: IValue) = setMeta(name to value)

/**
 * Retrieves all properties of a node or edge specified by [id].
 *
 * @param id The identifier of the node or edge.
 * @return A [MapVal] containing all properties of the specified node or edge.
 */
operator fun IStorage.get(id: IEntity.ID) = when (id) {
    is NodeID -> getNodeProperties(id = id)
    is EdgeID -> getEdgeProperties(id = id)
}

/**
 * Retrieves a specific metadata property from the storage.
 *
 * @param name The name of the metadata property to retrieve.
 * @return The value of the specified metadata property, or null if not found.
 */
fun IStorage.getMeta(name: String): IValue? {
    val metaID = NodeID("__meta__")
    if (!containsNode(metaID)) return null
    return getNodeProperty(metaID, name)
}

/**
 * Retrieves an IValue associated with the given name from the storage.
 *
 * @param name The name of the value to be retrieved.
 * @return The IValue associated with the given name if it exists, or null otherwise.
 */
operator fun IStorage.get(name: String): IValue? = getMeta(name)

/**
 * Retrieves a specific property of a node or edge.
 *
 * @param pair A pair consisting of an identifier and the property name to retrieve.
 * @return The value of the specified property or null if the property is not found.
 */
operator fun IStorage.get(pair: Pair<IEntity.ID, String>): IValue? =
    when (val id = pair.first) {
        is NodeID -> getNodeProperty(id, byName = pair.second)
        is EdgeID -> getEdgeProperty(id, byName = pair.second)
    }

/**
 * Sets a specific property of a node or edge.
 *
 * @param pair A pair consisting of an identifier and the property name.
 * @param value The new value to set for the specified property.
 */
operator fun IStorage.set(pair: Pair<IEntity.ID, String>, value: IValue?) =
    when (val id = pair.first) {
        is NodeID -> setNodeProperties(id, pair.second to value)
        is EdgeID -> setEdgeProperties(id, pair.second to value)
    }


/**
 * Converts the map into an array of key-value pairs.
 *
 * @return An array containing pairs of keys and values from the map.
 */
fun <K, V> Map<K, V>.toTypeArray(): Array<Pair<K, V>> {
    val pairIterator = this.iterator() // get the iterator
    return Array(this.size) { pairIterator.next().toPair() }
}
