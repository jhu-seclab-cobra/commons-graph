package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.value.IPrimitiveVal
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


/**
 * Retrieves a property value of a specific type from the entity.
 *
 * @param name The name of the property to retrieve.
 * @return The property value cast to the specified type, or `null` if the property doesn't exist or is of a different type.
 */
inline fun <reified T : IValue> IEntity.getTypeProp(name: String): T? = getProp(name) as? T

/**
 * Retrieves a primitive property value from the entity using the indexing operator.
 * Note: This method only loads data from cache, which may be stale in multithreaded environments.
 * For data consistency, ensure to refresh the data first.
 *
 * @param byName The name of the property to retrieve.
 * @return The primitive property value, or `null` if the property doesn't exist.
 */
operator fun IEntity.get(byName: String): IPrimitiveVal? = getProp(byName) as? IPrimitiveVal

/**
 * Sets a primitive property value for the entity using the indexing operator.
 * Note: This method only updates the cache. To persist changes, call the save() method
 * or use the alsoSave context.
 *
 * @param byName The name of the property to set.
 * @param newVal The new value to associate with the property.
 */
operator fun IEntity.set(byName: String, newVal: IPrimitiveVal?) = setProp(byName, newVal)

/**
 * Checks if a property exists in the entity's cache.
 *
 * @param byName The name of the property to check.
 * @return `true` if the property exists in the cache, `false` otherwise.
 */
operator fun IEntity.contains(byName: String): Boolean = get(byName) != null

/**
 * Creates a property delegate that manages property access through the entity's cache.
 *
 * @param optName The optional name of the property. If not provided, the property's actual name is used.
 * @param default The default value to return if the property doesn't exist in the cache.
 * @return A delegate that handles property access and modification.
 */
inline fun <reified T : IValue> entityProperty(
    optName: String? = null, default: T
) = object : ReadWriteProperty<IEntity, T> {
    override fun getValue(thisRef: IEntity, property: KProperty<*>): T =
        thisRef.getProp(optName ?: property.name) as? T ?: default

    override fun setValue(thisRef: IEntity, property: KProperty<*>, value: T) =
        thisRef.setProp(optName ?: property.name, value)
}

/**
 * Creates a nullable property delegate that manages property access through the entity's cache.
 *
 * @param optName The optional name of the property. If not provided, the property's actual name is used.
 * @return A delegate that handles property access and modification.
 */
inline fun <reified T : IValue?> entityProperty(
    optName: String? = null
) = object : ReadWriteProperty<IEntity, T?> {
    override fun getValue(thisRef: IEntity, property: KProperty<*>): T? =
        thisRef.getProp(optName ?: property.name) as? T

    override fun setValue(thisRef: IEntity, property: KProperty<*>, value: T?) {
        value?.let { thisRef.setProp(optName ?: property.name, it) }
    }
}

/**
 * Creates a property delegate for managing entity type properties.
 * The property name is automatically prefixed with the lowercase class name.
 *
 * @param optName The optional name of the property. If not provided, a name is generated.
 * @param default The default value to return if the property doesn't exist.
 * @return A delegate that handles type property access and modification.
 */
inline fun <reified T : IEntity.Type> entityType(
    optName: String? = null, default: T
) = object : ReadWriteProperty<IEntity, T> {

    private val propPrefix by lazy { this::class.java.simpleName.lowercase() }
    private val enumTypeMap by lazy { T::class.java.enumConstants.associateBy { it.name } }

    override fun getValue(thisRef: IEntity, property: KProperty<*>): T {
        val propName = optName ?: "${propPrefix}_${property.name}"
        val typeStrVal = thisRef.getProp(propName) as? StrVal
        return typeStrVal?.core?.let { enumTypeMap[it] } ?: default
    }

    override fun setValue(thisRef: IEntity, property: KProperty<*>, value: T) {
        val propName = optName ?: "${propPrefix}_${property.name}"
        val prevValue = thisRef.getProp(propName) as? StrVal
        if (prevValue?.core == value.name) return
        thisRef.setProp(name = propName, value = value.name.strVal)
    }
}

/**
 * Converts an [IValue] to a [NodeID] using its core value.
 *
 * @return A [NodeID] constructed from the string representation of the [IValue]'s core value.
 */
val IValue.toNid get() = NodeID(this.core.toString())

/**
 * Converts a [String] to a [NodeID].
 *
 * @return A [NodeID] constructed from the string.
 */
val String.toNid get() = NodeID(this)

/**
 * Converts a [StrVal] to an [EdgeID] using its core value.
 *
 * @return An [EdgeID] constructed from the core string value of the [StrVal].
 */
val StrVal.toEid get() = EdgeID(this.core)

/**
 * Converts a [String] to an [EdgeID].
 *
 * @return An [EdgeID] constructed from the string.
 */
val String.toEid get() = EdgeID(this)
