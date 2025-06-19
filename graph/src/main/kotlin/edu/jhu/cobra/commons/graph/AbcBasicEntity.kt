package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

sealed class AbcBasicEntity : IEntity {

    /**
     * Retrieves a property value of a specific type from the entity.
     *
     * @param name The name of the property to retrieve.
     * @return The property value cast to the specified type, or `null` if the property doesn't exist or is of a different type.
     */
    inline fun <reified T : IValue> getTypeProp(name: String): T? = getProp(name) as? T

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
}