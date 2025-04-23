package edu.jhu.cobra.commons.graph.storage.utils

import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.toEntityID
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.strVal
import org.mapdb.DB
import org.mapdb.Serializer
import kotlin.reflect.KClass

internal class EntityPropertyMap<K : IEntity.ID>(dbManager: DB, name: String) {

    companion object {
        val VALUE_SERIALIZER = MapDbValSerializer(DftByteArraySerializerImpl)
    }

    private var entityType: KClass<out K>? = null
    private val identity = dbManager.hashSet("$name-id", VALUE_SERIALIZER).counterEnable().createOrOpen()
    private val propertyMap = dbManager.hashMap("$name-pr", Serializer.STRING, VALUE_SERIALIZER).createOrOpen()

    inner class PropertyMap(eid: String) : Map<String, IValue> {

        private val entityPrefix = "$eid:"

        private val mapDelegate
            get() = propertyMap.filter { it.key.startsWith(entityPrefix) }
                .mapKeys { (k) -> k.removePrefix(entityPrefix) }

        override val entries: Set<Map.Entry<String, IValue>> get() = mapDelegate.entries

        override val keys: Set<String> get() = mapDelegate.keys

        override val size: Int get() = mapDelegate.size

        override val values: Collection<IValue> get() = mapDelegate.values

        override fun isEmpty(): Boolean = propertyMap.any { (k) -> k.startsWith(entityPrefix) }

        override fun get(key: String): IValue? = propertyMap["$entityPrefix$key"]

        override fun containsValue(value: IValue): Boolean = mapDelegate.any { (_, v) -> v == value }

        override fun containsKey(key: String): Boolean = propertyMap.containsKey("$entityPrefix$key")

        operator fun set(key: String, value: IValue) {
            propertyMap["$entityPrefix$key"] = value
        }

        operator fun plusAssign(entry: Pair<String, IValue>) {
            set(entry.first, entry.second)
        }

        operator fun minusAssign(key: String) {
            propertyMap.remove("$entityPrefix$key")
        }
    }

    val size: Int get() = propertyMap.size

    @Suppress("UNCHECKED_CAST")
    val keysSequence: Sequence<K>
        get() = if (entityType == null) emptySequence()
        else identity.asSequence().map { it.core.toString().toEntityID(entityType!!) as K }

    operator fun contains(id: K): Boolean = id.uname.strVal in identity

    operator fun get(id: K): PropertyMap? {
        return if (id.uname.strVal !in identity) null
        else PropertyMap(id.uname)
    }

    operator fun set(id: K, properties: Map<String, IValue>) {
        if (entityType == null) entityType = id::class
        if (id.uname.strVal !in identity) identity.add(id.uname.strVal)
        val propMap = PropertyMap(eid = id.uname)
        properties.forEach { (k, v) -> propMap[k] = v }
    }

}