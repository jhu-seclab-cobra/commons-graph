package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/** Write operations valid on the entering thread during one [GraphWriter.write] callback. */
public class StorageWriteScope internal constructor(
    private val storage: LayeredStorage,
    private val lifetime: StorageScope,
    private val typed: LayeredStorageImpl? = null,
) {
    /** Captures a complete node field once; null deletes the field. */
    public fun <T : Any> setNodeProperty(id: Int, key: PropertyKey<T>, value: T?): Unit = access {
        registered().setNodeField(id, key, value)
    }

    /** Captures a complete edge field once; null deletes the field. */
    public fun <T : Any> setEdgeProperty(id: Int, key: PropertyKey<T>, value: T?): Unit = access {
        registered().setEdgeField(id, key, value)
    }

    /** Publishes one captured cell and its family membership under the current write guard. */
    public fun <T : Any> setEdgeCell(
        id: Int,
        key: IndexedPropertyKey<T>,
        first: String,
        second: String,
        value: T?,
    ): Unit = access { registered().setEdgeCell(id, key, first to second, value) }

    private fun registered(): LayeredStorageImpl = checkNotNull(typed) { "Storage has no registered field capability" }

    /** Creates a node with captured property content and returns its stable storage ID. */
    public fun addNode(properties: Map<String, IValue> = emptyMap()): Int = access { storage.addNode(properties) }

    /** Creates an edge between existing endpoints, preserving its complete opaque tag. */
    public fun addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue> = emptyMap()): Int =
        access { storage.addEdge(src, dst, tag, properties) }

    /** Applies captured updates; null deletes a property, unlike stored NullVal. */
    public fun setNodeProperties(id: Int, properties: Map<String, IValue?>): Unit =
        access { storage.setNodeProperties(id, properties) }

    /** Applies captured edge updates under the current property-family rules. */
    public fun setEdgeProperties(id: Int, properties: Map<String, IValue?>): Unit =
        access { storage.setEdgeProperties(id, properties) }

    /** Deletes an existing active node and its incident edges; frozen nodes retain their error. */
    public fun deleteNode(id: Int): Unit = access { storage.deleteNode(id) }

    /** Deletes an existing edge, including supported frozen-edge deletion. */
    public fun deleteEdge(id: Int): Unit = access { storage.deleteEdge(id) }

    /** Captures metadata content; null deletes the name. */
    public fun setMeta(name: String, value: IValue?): Unit = access { storage.setMeta(name, value) }

    /** Binds one storage-owned family declaration, rejecting a conflicting declaration. */
    public fun bindPropertyFamilies(families: EdgePropertyFamilies): Unit = access { storage.bindPropertyFamilies(families) }

    private fun <R> access(action: () -> R): R {
        lifetime.checkActive()
        return action()
    }
}
