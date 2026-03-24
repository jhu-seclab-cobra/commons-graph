package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.SetVal
import edu.jhu.cobra.commons.value.StrVal
import org.mapdb.DB
import org.mapdb.Serializer

/**
 * A MapDB-backed implementation of [MutableMap] that stores entity properties.
 * This provides a mapping from entity Int IDs to their associated property maps.
 *
 * @param dbManager The MapDB database manager
 * @param name The base name for the database stores
 */
internal class EntityPropertyMap(
    dbManager: DB,
    name: String,
) : AbstractMutableMap<Int, Map<String, IValue>>() {
    companion object {
        val SERIALIZER_IVALUE = MapDbValSerializer<IValue>()
        val SERIALIZER_SETVAL = MapDbValSerializer<SetVal>()
    }

    private val identities =
        dbManager
            .hashMap(
                "$name-id",
                MapDbIDSerializer(),
                SERIALIZER_SETVAL,
            ).counterEnable()
            .createOrOpen()

    private val propertiesMap = dbManager.hashMap("$name-pr", Serializer.STRING, SERIALIZER_IVALUE).createOrOpen()

    /**
     * Inner class that represents a property map for a specific entity.
     *
     * @param eid The entity Int ID
     */
    inner class PropertyMap(
        private val eid: Int,
    ) : AbstractMutableMap<String, IValue>() {
        private inner class Entry(
            override val key: String,
        ) : MutableMap.MutableEntry<String, IValue> {
            override val value get() = this@PropertyMap[key] ?: NullVal

            override fun setValue(newValue: IValue) = this@PropertyMap.set(key, newValue).let { newValue }
        }

        private val entityPrefix = "$eid:"

        override val entries =
            object : MutableSet<MutableMap.MutableEntry<String, IValue>> {
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

                override fun iterator() =
                    object : MutableIterator<MutableMap.MutableEntry<String, IValue>> {
                        private var currentItem: String? = null
                        private val propKeyIterators = identities[eid]!!.core.iterator()

                        override fun hasNext(): Boolean = propKeyIterators.hasNext()

                        override fun next(): MutableMap.MutableEntry<String, IValue> {
                            if (!hasNext()) throw NoSuchElementException("No more elements")
                            currentItem = propKeyIterators.next().core.toString()
                            return Entry(currentItem!!)
                        }

                        override fun remove() {
                            check(currentItem != null) { "next() has not been called yet" }
                            this@PropertyMap.remove(key = currentItem)
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
            get() =
                object : MutableSet<String> {
                    override val size: Int get() = entries.size

                    override fun clear() = entries.clear()

                    override fun isEmpty(): Boolean = entries.isEmpty()

                    override fun containsAll(elements: Collection<String>) = elements.all { propertiesMap.containsKey("$entityPrefix$it") }

                    override fun contains(element: String): Boolean = propertiesMap.containsKey("$entityPrefix$element")

                    override fun addAll(elements: Collection<String>): Boolean =
                        elements.map { this@PropertyMap.put(it, NullVal) != NullVal }.any { it }

                    override fun add(element: String): Boolean = put(element, NullVal) != NullVal

                    override fun iterator() =
                        object : MutableIterator<String> {
                            val entryIterator = entries.iterator()

                            override fun hasNext() = entryIterator.hasNext()

                            override fun next() = entryIterator.next().key

                            override fun remove() = entryIterator.remove()
                        }

                    override fun retainAll(elements: Collection<String>): Boolean {
                        val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.toSet()
                        val allRmvKeys = (allPropKeys - elements.toSet())
                        return allRmvKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                    }

                    override fun removeAll(elements: Collection<String>): Boolean =
                        elements.map { this@PropertyMap.remove(it) != null }.any { it }

                    override fun remove(element: String): Boolean = this@PropertyMap.remove(element) != null
                }

        override val size: Int
            get() {
                val propKeys = identities[eid] ?: return 0
                return propKeys.core.size
            }

        override fun containsKey(key: String) = propertiesMap.containsKey("$entityPrefix$key")

        override fun put(
            key: String,
            value: IValue,
        ): IValue? {
            val prev = propertiesMap.put("$entityPrefix$key", value)
            val propKeys = identities[eid] ?: SetVal()
            propKeys.core += StrVal(key)
            identities[eid] = propKeys
            return prev
        }

        override fun get(key: String): IValue? = propertiesMap["$entityPrefix$key"]

        override fun remove(key: String): IValue? {
            val prev = propertiesMap.remove("$entityPrefix$key") ?: return null
            val propKeys = identities[eid]!!
            propKeys.core -= StrVal(key)
            identities[eid] = propKeys
            return prev
        }

        override fun clear() {
            val propKeys = identities[eid] ?: return
            propKeys.core.map { it.core.toString() }.forEach { propertiesMap.remove("$entityPrefix$it") }
            identities[eid] = SetVal()
        }
    }

    override fun put(
        key: Int,
        value: Map<String, IValue>,
    ): Map<String, IValue>? {
        val prev = if (identities.containsKey(key)) PropertyMap(key).toMap() else null
        identities.putIfAbsent(key, SetVal())
        val propertyMap = PropertyMap(key)
        propertyMap.clear()
        propertyMap.putAll(value)
        return prev
    }

    override fun containsKey(key: Int): Boolean = identities.containsKey(key)

    fun contains(key: Int): Boolean = identities.containsKey(key)

    override fun get(key: Int): PropertyMap? {
        if (!identities.containsKey(key)) return null
        return PropertyMap(key)
    }

    override fun remove(key: Int): Map<String, IValue>? {
        val prev = if (identities.containsKey(key)) PropertyMap(key).toMap() else null
        PropertyMap(key).clear()
        identities.remove(key)
        return prev
    }

    override val entries: MutableSet<MutableMap.MutableEntry<Int, Map<String, IValue>>>
        get() =
            object : AbstractMutableSet<MutableMap.MutableEntry<Int, Map<String, IValue>>>() {
                override val size: Int get() = identities.size.toInt()

                override fun add(element: MutableMap.MutableEntry<Int, Map<String, IValue>>): Boolean {
                    put(element.key, element.value)
                    return true
                }

                override fun iterator() =
                    object : MutableIterator<MutableMap.MutableEntry<Int, Map<String, IValue>>> {
                        private val keysIterator = identities.keys.iterator()
                        private var currentKey: Int? = null

                        override fun hasNext(): Boolean = keysIterator.hasNext()

                        override fun next(): MutableMap.MutableEntry<Int, Map<String, IValue>> {
                            if (!hasNext()) throw NoSuchElementException("No more elements")
                            currentKey = keysIterator.next()
                            return object : MutableMap.MutableEntry<Int, Map<String, IValue>> {
                                override val key: Int = currentKey!!
                                override val value: Map<String, IValue> get() = PropertyMap(key)

                                override fun setValue(newValue: Map<String, IValue>): Map<String, IValue> {
                                    val prev = PropertyMap(key).toMap()
                                    put(key, newValue)
                                    return prev
                                }
                            }
                        }

                        override fun remove() {
                            check(currentKey != null) { "next() has not been called yet" }
                            this@EntityPropertyMap.remove(currentKey!!)
                        }
                    }
            }

    override fun clear() {
        identities.keys.forEach { key -> PropertyMap(key).clear() }
        identities.clear()
    }

    override val size: Int get() = identities.size.toInt()

    override fun isEmpty(): Boolean = identities.isEmpty()
}
