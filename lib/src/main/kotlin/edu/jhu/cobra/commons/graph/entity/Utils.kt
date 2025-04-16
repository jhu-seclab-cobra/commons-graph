package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.value.IPrimitiveVal
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Retrieves a property by its name.
 * @param name The name of the property.
 * @return The property value, or null if the property doesn't exist.
 */
inline fun <reified T : IValue> IEntity.getTypeProp(name: String): T? = getProp(name) as? T

/**
 * Operator function to get a property value by its name. Please note that this method only loads the data from
 * cache, so the data may be dirty in multithreading programs, if you want to ensure the data consistency.
 * Please use the fresh method at first.
 * @param byName The name of the property.
 * @return The property value, or null if the property doesn't exist.
 */
operator fun IEntity.get(byName: String): IPrimitiveVal? = getProp(byName) as? IPrimitiveVal

/**
 * Operator function to set a property value by its name.
 * This method will only keep update in the cache,
 * which means that it does not save the changes to the permanent storage. If you want to
 * save the changes, please call the save() method or invoke the method under alsoSave context.
 * @param byName The name of the property.
 * @param newVal The new value to set.
 */
operator fun IEntity.set(byName: String, newVal: IPrimitiveVal?) = setProp(byName, newVal)

/**
 * Operator function to check if a property exists in the cache.
 * @param byName The name of the property.
 * @return True if the property exists, false otherwise.
 */
operator fun IEntity.contains(byName: String): Boolean = get(byName) != null

/**
 * Provides a delegate for a property that retrieves and sets its value in the cache.
 * @param optName The optional name of the property. If not provided, the property's actual name is used.
 * @param default The default value to return if the property does not exist in the cache.
 * @return A delegate for the property.
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
 * Provides a delegate for a nullable property that retrieves and sets its value in the cache.
 * @param optName The optional name of the property. If not provided, the property's actual name is used.
 * @return A delegate for the property.
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
 * This property retrieves the core value of the [IValue], converts it to a string,
 * and initializes a [NodeID] with that string representation.
 *
 * @return [NodeID] constructed from the string representation of the [IValue]'s core value.
 */
val IValue.toNid get() = NodeID(this.core.toString())

/**
 * Converts a [String] to a [NodeID].
 * This property simply wraps the string into a [NodeID], treating the string as an identifier.
 *
 * @return [NodeID] constructed from the string.
 */
val String.toNid get() = NodeID(this)

/**
 * Converts a [StrVal] to an [EdgeID] using its core value.
 * This property retrieves the core value of the [StrVal], which is a string, and initializes an [EdgeID] with it.
 *
 * @return [EdgeID] constructed from the core string value of the [StrVal].
 */
val StrVal.toEid get() = EdgeID(this.core)

/**
 * Converts a [String] to an [EdgeID].
 * This property simply wraps the string into an [EdgeID], treating the string as an identifier.
 *
 * @return [EdgeID] constructed from the string.
 */
val String.toEid get() = EdgeID(this)
