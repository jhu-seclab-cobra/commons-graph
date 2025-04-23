package edu.jhu.cobra.commons.graph.storage.utils

import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.toEntityID
import edu.jhu.cobra.commons.value.*
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import org.mapdb.DB
import org.mapdb.Serializer
import kotlin.reflect.KClass

internal class EntityPropertyMap<K : IEntity.ID>(
    dbManager: DB, // MapDB instance for database operations
    name: String // Name of the property map to be created
) : MutableMap<K, Map<String, IValue>> {

    companion object {
        val SET_SERIALIZER = MapDbValSerializer<SetVal>(DftByteArraySerializerImpl)
        val PRIMITIVE_SERIALIZER = MapDbValSerializer<IValue>(DftByteArraySerializerImpl)
    }

    private var entityType: KClass<out K>? = null

    private val identities = dbManager.hashMap(
        "$name-id", Serializer.STRING, SET_SERIALIZER
    ).counterEnable().createOrOpen()

    private val propertiesMap = dbManager.hashMap(
        "$name-pr", Serializer.STRING, PRIMITIVE_SERIALIZER
    ).createOrOpen()

    inner class PropertyMap(private val eid: String) : MutableMap<String, IValue> {

        private inner class Entry(override val key: String) : MutableMap.MutableEntry<String, IValue> {
            override val value get() = this@PropertyMap[key] ?: NullVal
            override fun setValue(newValue: IValue) = this@PropertyMap.set(key, newValue).let { newValue }
        }

        private val entityPrefix = "$eid:"

        override val entries = object : MutableSet<MutableMap.MutableEntry<String, IValue>> {

            override val size: Int get() = this@PropertyMap.size

            override fun clear() = this@PropertyMap.clear()

            override fun isEmpty(): Boolean = this@PropertyMap.isEmpty()

            override fun containsAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>) =
                elements.all { (eleKey, eleValue) -> this@PropertyMap[eleKey] == eleValue }

            override fun contains(element: MutableMap.MutableEntry<String, IValue>): Boolean =
                element.let { (eleKey, eleValue) -> this@PropertyMap[eleKey] == eleValue }

            override fun addAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>): Boolean =
                elements.map { this@PropertyMap.put(it.key, it.value) != it.value }.any { it }

            override fun add(element: MutableMap.MutableEntry<String, IValue>): Boolean =
                element.let { this@PropertyMap.put(it.key, it.value) != it.value }

            override fun iterator() = object : MutableIterator<MutableMap.MutableEntry<String, IValue>> {
                private var currentItem: String? = null
                private val propKeyIterators = identities.remove(eid)!!.core.iterator()
                override fun hasNext(): Boolean = propKeyIterators.hasNext()
                override fun next(): MutableMap.MutableEntry<String, IValue> {
                    if (!hasNext()) throw NoSuchElementException("No more elements")
                    currentItem = propKeyIterators.next().core.toString()
                    return Entry(currentItem!!)
                }

                override fun remove() {
                    if (currentItem == null) throw IllegalStateException("next() has not been called yet")
                    else this@PropertyMap.remove(key = currentItem)
                }
            }

            override fun retainAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>): Boolean {
                val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.toSet()
                val allRmvKeys = (allPropKeys - elements.map { ele -> ele.key }.toSet())
                return allRmvKeys.map { this@PropertyMap.remove(it) != null }.any { it }
            }

            override fun removeAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>): Boolean =
                elements.map { this@PropertyMap.remove(it.key) != null }.any { it }

            override fun remove(element: MutableMap.MutableEntry<String, IValue>): Boolean =
                element.let { this@PropertyMap.remove(it.key) != null }
        }

        override val keys
            get() = object : MutableSet<String> {

                override val size: Int get() = entries.size

                override fun clear() = entries.clear()

                override fun isEmpty(): Boolean = entries.isEmpty()

                override fun containsAll(elements: Collection<String>): Boolean =
                    elements.all { eleKey -> this@PropertyMap[eleKey] != null }

                override fun contains(element: String): Boolean =
                    element.let { eleKey -> this@PropertyMap[eleKey] != null }

                override fun addAll(elements: Collection<String>): Boolean =
                    throw UnsupportedOperationException("Cannot addAll to only keys")

                override fun add(element: String): Boolean =
                    throw UnsupportedOperationException("Cannot add to only key")

                override fun retainAll(elements: Collection<String>): Boolean {
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.toSet()
                    val removeKeys = (allPropKeys - elements.toSet())
                    return removeKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                override fun removeAll(elements: Collection<String>): Boolean =
                    elements.distinct().map { this@PropertyMap.remove(it) != null }.any { it }

                override fun remove(element: String): Boolean =
                    element.let { this@PropertyMap.remove(it) != null }

                override fun iterator() = object : MutableIterator<String> {
                    private val entriesIterator = entries.iterator()
                    override fun hasNext(): Boolean = entriesIterator.hasNext()
                    override fun next(): String = entriesIterator.next().key
                    override fun remove() = entriesIterator.remove()
                }
            }

        override val values
            get() = object : MutableCollection<IValue> {
                override val size: Int get() = entries.size

                override fun clear() = entries.clear()

                override fun isEmpty(): Boolean = entries.isEmpty()

                override fun retainAll(elements: Collection<IValue>): Boolean {
                    val elementSet = elements.toSet()
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val rmvPropKeys = allPropKeys.filter { this@PropertyMap[it] !in elementSet }
                    return rmvPropKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                override fun removeAll(elements: Collection<IValue>): Boolean {
                    val elementSet = elements.toSet()
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val rmvPropKeys = allPropKeys.filter { this@PropertyMap[it] in elementSet }
                    return rmvPropKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                override fun remove(element: IValue): Boolean {
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val rmvPropKeys = allPropKeys.filter { this@PropertyMap[it] == element }
                    return rmvPropKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                override fun containsAll(elements: Collection<IValue>): Boolean {
                    val elementSet = elements.toSet()
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val allPropValues = allPropKeys.map { this@PropertyMap[it] }.toSet()
                    return allPropValues.all { it in elementSet }
                }

                override fun contains(element: IValue): Boolean {
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    return allPropKeys.any { propKey -> this@PropertyMap[propKey] == element }
                }

                override fun addAll(elements: Collection<IValue>): Boolean =
                    throw UnsupportedOperationException("Cannot addAll to only values")

                override fun add(element: IValue): Boolean =
                    throw UnsupportedOperationException("Cannot add to only values")

                override fun iterator() = object : MutableIterator<IValue> {
                    val entriesIterator = entries.iterator()
                    override fun hasNext(): Boolean = entriesIterator.hasNext()
                    override fun next(): IValue = entriesIterator.next().value
                    override fun remove() = entriesIterator.remove()
                }

            }

        override val size: Int get() = identities[eid]!!.size

        override fun isEmpty(): Boolean = eid in identities

        override fun clear() {
            if (eid !in identities) return
            val keys = identities.remove(eid)!!.core
            keys.forEach { this@PropertyMap.remove(it.toString()) }
        }

        override fun remove(key: String): IValue? {
            require(eid !in identities) { "Entity $eid does not exist" }
            identities[eid] = (keys - key).setVal
            return propertiesMap.remove("$entityPrefix$key")
        }

        override fun putAll(from: Map<out String, IValue>) {
            require(eid !in identities) { "Entity $eid does not exist" }
            identities[eid] = SetVal(identities[eid]!!.core + from.keys.map { it.strVal })
            from.forEach { (k, v) -> this@PropertyMap[k] = v }
        }

        override fun put(key: String, value: IValue): IValue? {
            require(eid !in identities) { "Entity $eid does not exist" }
            identities[eid] = SetVal(identities[eid]!!.core + key.strVal)
            return propertiesMap.put("$entityPrefix$key", value)
        }

        override fun get(key: String): IValue? = propertiesMap["$entityPrefix$key"]
        override fun containsValue(value: IValue): Boolean = keys.any { get(it) == value }
        override fun containsKey(key: String): Boolean = key in keys
    }

    private inner class Entry(override val key: K) : MutableMap.MutableEntry<K, Map<String, IValue>> {
        override val value: Map<String, IValue> get() = PropertyMap(key.uname)
        override fun setValue(newValue: Map<String, IValue>) = this@EntityPropertyMap.put(key, newValue)!!
    }

    override val size: Int get() = identities.size

    override val entries
        get() = object : MutableSet<MutableMap.MutableEntry<K, Map<String, IValue>>> {

            override val size: Int get() = this@EntityPropertyMap.size

            override fun clear() = this@EntityPropertyMap.clear()

            override fun isEmpty(): Boolean = this@EntityPropertyMap.isEmpty()

            override fun containsAll(
                elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>
            ) = elements.all { (eid, props) ->
                if (eid.uname !in identities) return@all false
                val curPropKeys = identities[eid.uname]?.core?.map { it.core.toString() }.orEmpty().toSet()
                if (curPropKeys !== props.keys.toSet()) return@all false
                curPropKeys.all { pKey -> propertiesMap["${eid.uname}:$pKey"] == props[pKey] }
            }

            override fun contains(element: MutableMap.MutableEntry<K, Map<String, IValue>>): Boolean {
                if (element.key.uname !in identities) return false
                val curPropKeys = identities[element.key.uname]?.core?.map { it.core.toString() }.orEmpty().toSet()
                if (curPropKeys !== element.value.keys.toSet()) return false
                return curPropKeys.all { pKey -> propertiesMap["${element.key.uname}:$pKey"] == element.value[pKey] }
            }

            override fun addAll(elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>) =
                elements.map { this@EntityPropertyMap.put(it.key, it.value) != it.value }.any { it }

            override fun add(element: MutableMap.MutableEntry<K, Map<String, IValue>>): Boolean =
                element.let { this@EntityPropertyMap.put(it.key, it.value) != it.value }

            override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>): Boolean {
                val rmvEntityIDs = identities.keys - elements.map { it.key.uname }.toSet()
                rmvEntityIDs.filter { this@EntityPropertyMap.get(it.toEntityID(entityType!!)) }
                return rmvEntityIDs.isNotEmpty()
            }

            override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>): Boolean {
                val rmvEntityIDs = elements.map { it.key }.toSet().filter { it.uname in identities }
                rmvEntityIDs.map { entityID -> this@EntityPropertyMap.remove(key = entityID) }
                return rmvEntityIDs.isNotEmpty()
            }

            override fun remove(element: MutableMap.MutableEntry<K, Map<String, IValue>>): Boolean {
                if (element.key.uname !in identities) return false
                return this@EntityPropertyMap.remove(element.key) != element.value
            }

            override fun iterator() = object : MutableIterator<MutableMap.MutableEntry<K, Map<String, IValue>>> {
                private var currentItem: K? = null
                val entityIdIterator = identities.keys.iterator()

                override fun hasNext(): Boolean = entityIdIterator.hasNext()

                override fun next(): MutableMap.MutableEntry<K, Map<String, IValue>> {
                    if (!hasNext()) throw NoSuchElementException("No more elements")
                    currentItem = entityIdIterator.next().toEntityID(entityType!!)
                    return Entry(key = currentItem!!)
                }

                override fun remove() {
                    if (currentItem == null) throw IllegalStateException("next() has not been called yet")
                    else remove(Entry(key = currentItem!!))
                }

            }
        }


    override val keys
        get() = object : MutableSet<K> {
            override val size: Int get() = this@EntityPropertyMap.entries.size

            override fun clear() = this@EntityPropertyMap.clear()

            override fun isEmpty(): Boolean = this@EntityPropertyMap.isEmpty()

            override fun containsAll(elements: Collection<K>): Boolean =
                elements.all { this@EntityPropertyMap.containsKey(it) }

            override fun contains(element: K): Boolean =
                element.let { this@EntityPropertyMap.containsKey(it) }

            override fun addAll(elements: Collection<K>): Boolean =
                throw UnsupportedOperationException("Cannot addAll to only keys")

            override fun add(element: K): Boolean =
                throw UnsupportedOperationException("Cannot add to only key")

            override fun retainAll(elements: Collection<K>): Boolean {
                val all
            }

            override fun removeAll(elements: Collection<K>): Boolean {
                TODO("Not yet implemented")
            }

            override fun remove(element: K): Boolean {
                TODO("Not yet implemented")
            }

            override fun iterator() = object : MutableIterator<K> {
                private val entriesIterator = this@EntityPropertyMap.entries.iterator()
                override fun hasNext(): Boolean = entriesIterator.hasNext()
                override fun next(): K = entriesIterator.next().key
                override fun remove() = entriesIterator.remove()
            }

        }

    override val values: MutableCollection<Map<String, IValue>>
        get() = TODO("Not yet implemented")

    override fun clear() {
        identities.clear()
        propertiesMap.clear()
    }

    override fun isEmpty(): Boolean = identities.isEmpty()

    override fun remove(key: K): Map<String, IValue>? {
        if (key.uname !in identities) return null
        val propKeys = identities.remove(key.uname)!!.map { it.core.toString() }
        return propKeys.associateWith { propertiesMap.remove("$key:$it")!! }
    }

    override fun putAll(from: Map<out K, Map<String, IValue>>) = from.forEach { (key, value) ->
        if (entityType == null) entityType = key::class
        val properties = identities[key.uname]?.core.orEmpty()
        identities[key.uname] = SetVal(properties + value.keys.map { it.strVal })
        propertiesMap.putAll(value.mapKeys { "${key.uname}:${it.key}" })
    }

    override fun put(key: K, value: Map<String, IValue>): Map<String, IValue>? {
        if (entityType == null) entityType = key::class
        val prevPropKeys = identities[key.uname]?.core?.map { it.core.toString() }
        identities[key.uname] = (prevPropKeys.orEmpty() + value.keys).map { it.strVal }.setVal
        val prevPropValues = prevPropKeys?.associateWith { propertiesMap.remove("${key.uname}:$it")!! }
        propertiesMap.putAll(value.mapKeys { "${key.uname}:${it.key}" })
        return prevPropValues
    }

    override fun get(key: K): Map<String, IValue>? =
        if (key.uname !in identities) null else PropertyMap(key.uname)

    override fun containsValue(value: Map<String, IValue>) = identities.any { (eid, pKeys) ->
        if (value.keys.toSet() !== pKeys.core.map { it.core.toString() }.toSet()) return@any false
        pKeys.core.all { propertiesMap["$eid:${it.core.toString()}"] == value[it.core.toString()] }
    }

    override fun containsKey(key: K): Boolean = key.uname in identities

}