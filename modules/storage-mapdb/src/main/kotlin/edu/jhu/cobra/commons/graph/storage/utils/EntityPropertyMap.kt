package edu.jhu.cobra.commons.graph.storage.utils

import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.toEntityID
import edu.jhu.cobra.commons.value.*
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import org.mapdb.DB
import org.mapdb.Serializer
import kotlin.reflect.KClass

/**
 * A MapDB-backed implementation of [MutableMap] that stores entity properties.
 * This provides a mapping from entity IDs to their associated property maps.
 *
 * @param K The type of entity ID
 * @param dbManager The MapDB database manager
 * @param name The base name for the database stores
 */
class EntityPropertyMap<K : IEntity.ID>(
    dbManager: DB, // MapDB instance for database operations
    name: String // Name of the property map to be created
) : AbstractMutableMap<K, Map<String, IValue>>() {

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

    /**
     * Inner class that represents a property map for a specific entity.
     * Implements [MutableMap] interface for property name to value mapping.
     *
     * @param eid The entity ID string
     */
    inner class PropertyMap(private val eid: String) : AbstractMutableMap<String, IValue>() {

        /**
         * Represents an entry in the property map.
         *
         * @param key The property name
         */
        private inner class Entry(override val key: String) : MutableMap.MutableEntry<String, IValue> {
            /**
             * The value associated with the key, or [NullVal] if not present
             */
            override val value get() = this@PropertyMap[key] ?: NullVal

            /**
             * Sets the value associated with the key
             *
             * @param newValue The new value to associate with the key
             * @return The new value that was set
             */
            override fun setValue(newValue: IValue) = this@PropertyMap.set(key, newValue).let { newValue }
        }

        private val entityPrefix = "$eid:"

        /**
         * The set of all entries in this property map
         */
        override val entries = object : MutableSet<MutableMap.MutableEntry<String, IValue>> {

            /**
             * @return The number of entries in this property map
             */
            override val size: Int get() = this@PropertyMap.size

            /**
             * Removes all entries from this property map
             */
            override fun clear() = this@PropertyMap.clear()

            /**
             * @return True if this property map contains no entries
             */
            override fun isEmpty(): Boolean = this@PropertyMap.isEmpty()

            /**
             * @param elements The collection of entries to check
             * @return True if this property map contains all the entries in the collection
             */
            override fun containsAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>) =
                elements.all { (eleKey, eleValue) -> this@PropertyMap[eleKey] == eleValue }

            /**
             * @param element The entry to check
             * @return True if this property map contains the entry
             */
            override fun contains(element: MutableMap.MutableEntry<String, IValue>): Boolean =
                element.let { (eleKey, eleValue) -> this@PropertyMap[eleKey] == eleValue }

            /**
             * Adds all entries in the collection to this property map
             *
             * @param elements The collection of entries to add
             * @return True if this property map was modified
             */
            override fun addAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>): Boolean =
                elements.map { this@PropertyMap.put(it.key, it.value) != it.value }.any { it }

            /**
             * Adds an entry to this property map
             *
             * @param element The entry to add
             * @return True if this property map was modified
             */
            override fun add(element: MutableMap.MutableEntry<String, IValue>): Boolean =
                element.let { this@PropertyMap.put(it.key, it.value) != it.value }

            /**
             * @return An iterator over the entries in this property map
             */
            override fun iterator() = object : MutableIterator<MutableMap.MutableEntry<String, IValue>> {
                private var currentItem: String? = null
                private val propKeyIterators = identities[eid]!!.core.iterator()

                /**
                 * Checks if there are more entries to iterate.
                 *
                 * @return True if there are more entries
                 */
                override fun hasNext(): Boolean = propKeyIterators.hasNext()

                /**
                 * Returns the next entry in the iteration.
                 *
                 * @return The next entry
                 * @throws NoSuchElementException If there are no more entries
                 */
                override fun next(): MutableMap.MutableEntry<String, IValue> {
                    if (!hasNext()) throw NoSuchElementException("No more elements")
                    currentItem = propKeyIterators.next().core.toString()
                    return Entry(currentItem!!)
                }

                /**
                 * Removes the current entry
                 * @throws IllegalStateException If next() has not been called yet
                 */
                override fun remove() {
                    if (currentItem == null) throw IllegalStateException("next() has not been called yet")
                    else this@PropertyMap.remove(key = currentItem)
                }
            }

            /**
             * Retains only the entries in this property map that are contained in the specified collection
             *
             * @param elements The collection of entries to retain
             * @return True if this property map was modified
             */
            override fun retainAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>): Boolean {
                val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.toSet()
                val allRmvKeys = (allPropKeys - elements.map { ele -> ele.key }.toSet())
                return allRmvKeys.map { this@PropertyMap.remove(it) != null }.any { it }
            }

            /**
             * Removes all entries in the specified collection from this property map
             *
             * @param elements The collection of entries to remove
             * @return True if this property map was modified
             */
            override fun removeAll(elements: Collection<MutableMap.MutableEntry<String, IValue>>): Boolean =
                elements.map { this@PropertyMap.remove(it.key) != null }.any { it }

            /**
             * Removes an entry from this property map
             *
             * @param element The entry to remove
             * @return True if this property map was modified
             */
            override fun remove(element: MutableMap.MutableEntry<String, IValue>): Boolean =
                element.let { this@PropertyMap.remove(it.key) != null }
        }

        /**
         * The set of property keys in this property map
         */
        override val keys
            get() = object : MutableSet<String> {

                /**
                 * @return The number of keys in this property map
                 */
                override val size: Int get() = entries.size

                /**
                 * Removes all entries from this property map
                 */
                override fun clear() = entries.clear()

                /**
                 * @return True if this property map contains no entries
                 */
                override fun isEmpty(): Boolean = entries.isEmpty()

                /**
                 * @param elements The collection of keys to check
                 * @return True if this property map contains all the keys in the collection
                 */
                override fun containsAll(elements: Collection<String>): Boolean =
                    elements.all { eleKey -> this@PropertyMap[eleKey] != null }

                /**
                 * @param element The key to check
                 * @return True if this property map contains the key
                 */
                override fun contains(element: String): Boolean =
                    element.let { eleKey -> this@PropertyMap[eleKey] != null }

                /**
                 * Not supported for key views
                 * @throws UnsupportedOperationException Always
                 */
                override fun addAll(elements: Collection<String>): Boolean =
                    throw UnsupportedOperationException("Cannot addAll to only keys")

                /**
                 * Not supported for key views
                 * @throws UnsupportedOperationException Always
                 */
                override fun add(element: String): Boolean =
                    throw UnsupportedOperationException("Cannot add to only key")

                /**
                 * Retains only the keys in this property map that are contained in the specified collection
                 *
                 * @param elements The collection of keys to retain
                 * @return True if this property map was modified
                 */
                override fun retainAll(elements: Collection<String>): Boolean {
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.toSet()
                    val removeKeys = (allPropKeys - elements.toSet())
                    return removeKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                /**
                 * Removes all keys in the specified collection from this property map
                 *
                 * @param elements The collection of keys to remove
                 * @return True if this property map was modified
                 */
                override fun removeAll(elements: Collection<String>): Boolean =
                    elements.distinct().map { this@PropertyMap.remove(it) != null }.any { it }

                /**
                 * Removes a key from this property map
                 *
                 * @param element The key to remove
                 * @return True if this property map was modified
                 */
                override fun remove(element: String): Boolean =
                    element.let { this@PropertyMap.remove(it) != null }

                /**
                 * @return An iterator over the keys in this property map
                 */
                override fun iterator() = object : MutableIterator<String> {
                    private val entriesIterator = entries.iterator()
                    override fun hasNext(): Boolean = entriesIterator.hasNext()
                    override fun next(): String = entriesIterator.next().key
                    override fun remove() = entriesIterator.remove()
                }
            }

        /**
         * The collection of property values in this property map
         */
        override val values
            get() = object : MutableCollection<IValue> {
                /**
                 * @return The number of values in this property map
                 */
                override val size: Int get() = entries.size

                /**
                 * Removes all entries from this property map
                 */
                override fun clear() = entries.clear()

                /**
                 * @return True if this property map contains no entries
                 */
                override fun isEmpty(): Boolean = entries.isEmpty()

                /**
                 * Retains only the values in this property map that are contained in the specified collection
                 *
                 * @param elements The collection of values to retain
                 * @return True if this property map was modified
                 */
                override fun retainAll(elements: Collection<IValue>): Boolean {
                    val elementSet = elements.toSet()
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val rmvPropKeys = allPropKeys.filter { this@PropertyMap[it] !in elementSet }
                    return rmvPropKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                /**
                 * Removes all entries with values in the specified collection from this property map
                 *
                 * @param elements The collection of values to remove
                 * @return True if this property map was modified
                 */
                override fun removeAll(elements: Collection<IValue>): Boolean {
                    val elementSet = elements.toSet()
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val rmvPropKeys = allPropKeys.filter { this@PropertyMap[it] in elementSet }
                    return rmvPropKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                /**
                 * Removes all entries with the specified value from this property map
                 *
                 * @param element The value to remove
                 * @return True if this property map was modified
                 */
                override fun remove(element: IValue): Boolean {
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val rmvPropKeys = allPropKeys.filter { this@PropertyMap[it] == element }
                    return rmvPropKeys.map { this@PropertyMap.remove(it) != null }.any { it }
                }

                /**
                 * @param elements The collection of values to check
                 * @return True if this property map contains all the values in the collection
                 */
                override fun containsAll(elements: Collection<IValue>): Boolean {
                    val elementSet = elements.toSet()
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    val allPropValues = allPropKeys.map { this@PropertyMap[it] }.toSet()
                    return allPropValues.all { it in elementSet }
                }

                /**
                 * @param element The value to check
                 * @return True if this property map contains the value
                 */
                override fun contains(element: IValue): Boolean {
                    val allPropKeys = identities[eid]!!.core.map { it.core.toString() }.asSequence()
                    return allPropKeys.any { propKey -> this@PropertyMap[propKey] == element }
                }

                /**
                 * Not supported for value collections
                 * @throws UnsupportedOperationException Always
                 */
                override fun addAll(elements: Collection<IValue>): Boolean =
                    throw UnsupportedOperationException("Cannot addAll to only values")

                /**
                 * Not supported for value collections
                 * @throws UnsupportedOperationException Always
                 */
                override fun add(element: IValue): Boolean =
                    throw UnsupportedOperationException("Cannot add to only values")

                /**
                 * @return An iterator over the values in this property map
                 */
                override fun iterator() = object : MutableIterator<IValue> {
                    val entriesIterator = entries.iterator()
                    override fun hasNext(): Boolean = entriesIterator.hasNext()
                    override fun next(): IValue = entriesIterator.next().value
                    override fun remove() = entriesIterator.remove()
                }

            }

        /**
         * @return The number of properties in this property map
         * @throws NoSuchElementException If the entity does not exist
         */
        override val size: Int
            get() = identities[eid]?.size
                ?: throw NoSuchElementException("Entity $eid does not exist")

        /**
         * @return True if this property map contains no properties
         * @throws NoSuchElementException If the entity does not exist
         */
        override fun isEmpty(): Boolean =
            identities[eid]?.isEmpty() ?: throw NoSuchElementException("Entity $eid does not exist")

        /**
         * Removes all properties from this property map
         */
        override fun clear() {
            require(eid in identities) { "Entity $eid does not exist" }
            val keys = identities[eid]!!.core
            keys.forEach { this@PropertyMap.remove(it.toString()) }
            identities[eid] = SetVal(emptyList())
        }

        /**
         * Removes a property from this property map
         *
         * @param key The property name to remove
         * @return The previous value associated with the key, or null if the key was not present
         * @throws IllegalArgumentException If the entity does not exist
         */
        override fun remove(key: String): IValue? {
            require(eid in identities) { "Entity $eid does not exist" }
            val previousKeys = identities[eid].orEmpty().asSequence().map { it.core.toString() }
            identities[eid] = SetVal((previousKeys - key).map { it.strVal })
            return propertiesMap.remove("$entityPrefix$key")
        }

        /**
         * Adds all key-value pairs from the specified map to this property map
         *
         * @param from The map containing the key-value pairs to add
         * @throws IllegalArgumentException If the entity does not exist
         */
        override fun putAll(from: Map<out String, IValue>) {
            require(eid in identities) { "Entity $eid does not exist" }
            identities[eid] = SetVal(identities[eid]!!.core + from.keys.map { it.strVal })
            from.forEach { (k, v) -> this@PropertyMap[k] = v }
        }

        /**
         * Associates a value with a property key
         *
         * @param key The property name
         * @param value The value to associate with the key
         * @return The previous value associated with the key, or null if the key was not present
         * @throws IllegalArgumentException If the entity does not exist
         */
        override fun put(key: String, value: IValue): IValue? {
            require(eid in identities) { "Entity $eid does not exist" }
            identities[eid] = SetVal(identities[eid]!!.core + key.strVal)
            return propertiesMap.put("$entityPrefix$key", value)
        }

        /**
         * Retrieves the value associated with the specified property key.
         *
         * @param key The property name
         * @return The value associated with the key, or null if the key is not present
         */
        override fun get(key: String): IValue? = propertiesMap["$entityPrefix$key"]

        /**
         * Checks if this property map maps one or more keys to the specified value.
         *
         * @param value The value to check
         * @return True if this property map contains at least one mapping to the specified value
         */
        override fun containsValue(value: IValue): Boolean = keys.any { get(it) == value }

        /**
         * Checks if this property map contains a mapping for the specified key.
         *
         * @param key The property name to check
         * @return True if this property map contains a mapping for the specified key
         */
        override fun containsKey(key: String): Boolean = key in keys
    }

    /**
     * Represents an entry in the entity-property map
     *
     * @param key The entity ID
     */
    private inner class Entry(override val key: K) : MutableMap.MutableEntry<K, Map<String, IValue>> {
        /**
         * Gets the property map associated with this entity ID.
         *
         * @return The property map for this entity
         */
        override val value: Map<String, IValue> get() = PropertyMap(key.name)

        /**
         * Replaces the property map for this entity with the specified new value.
         *
         * @param newValue The new property map to associate with the entity ID
         * @return The previous property map
         */
        override fun setValue(newValue: Map<String, IValue>) = this@EntityPropertyMap.put(key, newValue)!!
    }

    /**
     * Gets the number of entities in this entity-property map.
     *
     * @return The number of entities in this map
     */
    override val size: Int get() = identities.size

    /**
     * Gets the set of all entity-property map entries, allowing mutation operations.
     * Each entry represents an entity ID mapped to its property map.
     */
    override val entries
        get() = object : MutableSet<MutableMap.MutableEntry<K, Map<String, IValue>>> {

            /**
             * @return The number of entries in this map
             */
            override val size: Int get() = this@EntityPropertyMap.size

            /**
             * Removes all entries from this map
             */
            override fun clear() = this@EntityPropertyMap.clear()

            /**
             * Checks if this map contains no entities.
             *
             * @return True if this map contains no entries
             */
            override fun isEmpty(): Boolean = this@EntityPropertyMap.isEmpty()

            /**
             * Checks if this map contains all the mappings in the specified collection.
             *
             * @param elements The collection of mappings to check
             * @return True if this map contains all the mappings in the collection
             */
            override fun containsAll(
                elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>
            ) = elements.all { (eid, props) ->
                if (eid.name !in identities) return@all false
                val curPropKeys = identities[eid.name]?.core?.map { it.core.toString() }.orEmpty().toSet()
                if (curPropKeys != props.keys.toSet()) return@all false
                curPropKeys.all { pKey -> propertiesMap["${eid.name}:$pKey"] == props[pKey] }
            }

            /**
             * Checks if this map contains the specified mapping.
             *
             * @param element The mapping to check
             * @return True if this map contains the mapping
             */
            override fun contains(element: MutableMap.MutableEntry<K, Map<String, IValue>>): Boolean {
                if (element.key.name !in identities) return false
                val curPropKeys = identities[element.key.name]?.core?.map { it.core.toString() }.orEmpty().toSet()
                if (curPropKeys != element.value.keys.toSet()) return false
                return curPropKeys.all { pKey -> propertiesMap["${element.key.name}:$pKey"] == element.value[pKey] }
            }

            /**
             * Adds all mappings in the specified collection to this map.
             *
             * @param elements The collection of mappings to add
             * @return True if this map was modified
             */
            override fun addAll(elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>) =
                elements.map { this@EntityPropertyMap.put(it.key, it.value) != it.value }.any { it }

            /**
             * Adds the specified mapping to this map.
             *
             * @param element The mapping to add
             * @return True if this map was modified
             */
            override fun add(element: MutableMap.MutableEntry<K, Map<String, IValue>>): Boolean =
                element.let { this@EntityPropertyMap.put(it.key, it.value) != it.value }

            /**
             * Retains only the mappings that are contained in the specified collection.
             *
             * @param elements The collection of mappings to retain
             * @return True if this map was modified
             */
            override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>): Boolean {
                val keyMatchItems = elements.asSequence().filter { this@EntityPropertyMap.containsKey(it.key) }
                val valueMatchItems = keyMatchItems.filter { this@EntityPropertyMap[it.key] == it.value }
                val rmvEntityIDs = identities.keys - valueMatchItems.map { it.key.name }.toSet()
                rmvEntityIDs.forEach { this@EntityPropertyMap.remove(it.toEntityID(entityType!!)) }
                return rmvEntityIDs.isNotEmpty()
            }

            /**
             * Removes all mappings that are contained in the specified collection.
             *
             * @param elements The collection of mappings to remove
             * @return True if this map was modified
             */
            override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, Map<String, IValue>>>): Boolean {
                val keyMatchItems = elements.asSequence().filter { this@EntityPropertyMap.containsKey(it.key) }
                val valueMatchItems = keyMatchItems.filter { this@EntityPropertyMap[it.key] == it.value }
                val rmvEntityIDs = valueMatchItems.map { it.key }.toSet()
                rmvEntityIDs.map { entityID -> this@EntityPropertyMap.remove(key = entityID) }
                return rmvEntityIDs.isNotEmpty()
            }

            /**
             * Removes the specified mapping from this map.
             *
             * @param element The mapping to remove
             * @return True if this map was modified
             */
            override fun remove(element: MutableMap.MutableEntry<K, Map<String, IValue>>): Boolean {
                if (element.key.name !in identities) return false
                if (this@EntityPropertyMap[element.key] != element.value) return false
                this@EntityPropertyMap.remove(element.key)
                return true
            }

            /**
             * @return An iterator over the entries in this map
             */
            override fun iterator() = object : MutableIterator<MutableMap.MutableEntry<K, Map<String, IValue>>> {
                private var currentItem: K? = null
                val entityIdIterator = identities.keys.iterator()

                /**
                 * Checks if there are more entries to iterate.
                 *
                 * @return True if there are more entries
                 */
                override fun hasNext(): Boolean = entityIdIterator.hasNext()

                /**
                 * Returns the next entry in the iteration.
                 *
                 * @return The next entry
                 * @throws NoSuchElementException If there are no more entries
                 */
                override fun next(): MutableMap.MutableEntry<K, Map<String, IValue>> {
                    if (!hasNext()) throw NoSuchElementException("No more elements")
                    currentItem = entityIdIterator.next().toEntityID(entityType!!)
                    return Entry(key = currentItem!!)
                }

                /**
                 * Removes the current entry from the map.
                 *
                 * @throws IllegalStateException If next() has not been called yet
                 */
                override fun remove() {
                    if (currentItem == null) throw IllegalStateException("next() has not been called yet")
                    else remove(Entry(key = currentItem!!))
                }
            }
        }

    /**
     * Gets the set of all entity IDs in this map, allowing mutation operations.
     */
    override val keys
        get() = object : MutableSet<K> {
            /**
             * @return The number of entities in this map
             */
            override val size: Int get() = this@EntityPropertyMap.entries.size

            /**
             * Removes all entries from this map
             */
            override fun clear() = this@EntityPropertyMap.clear()

            /**
             * @return True if this map contains no entities
             */
            override fun isEmpty(): Boolean = this@EntityPropertyMap.isEmpty()

            /**
             * @param elements The collection of entity IDs to check
             * @return True if this map contains all the entity IDs in the collection
             */
            override fun containsAll(elements: Collection<K>) = elements.all { this@EntityPropertyMap.containsKey(it) }

            /**
             * @param element The entity ID to check
             * @return True if this map contains the entity ID
             */
            override fun contains(element: K) = element.let { this@EntityPropertyMap.containsKey(it) }

            /**
             * Not supported for key views
             * @throws UnsupportedOperationException Always
             */
            override fun addAll(elements: Collection<K>) = throw UnsupportedOperationException("Cannot add only key")

            /**
             * Not supported for key views
             * @throws UnsupportedOperationException Always
             */
            override fun add(element: K): Boolean = throw UnsupportedOperationException("Cannot add to only key")

            /**
             * Retains only the entity IDs in this map that are contained in the specified collection
             *
             * @param elements The collection of entity IDs to retain
             * @return True if this map was modified
             */
            override fun retainAll(elements: Collection<K>): Boolean {
                val rmvEntityIDs = identities.keys - elements.map { it.name }.toSet()
                rmvEntityIDs.forEach { this@EntityPropertyMap.remove(it.toEntityID(entityType!!)) }
                return rmvEntityIDs.isNotEmpty()
            }

            /**
             * Removes all entity IDs in the specified collection from this map
             *
             * @param elements The collection of entity IDs to remove
             * @return True if this map was modified
             */
            override fun removeAll(elements: Collection<K>): Boolean {
                val rmvEntityIDs = elements.filter { it.name in identities }.toSet()
                rmvEntityIDs.forEach { this@EntityPropertyMap.remove(key = it) }
                return rmvEntityIDs.isNotEmpty()
            }

            /**
             * Removes an entity ID from this map
             *
             * @param element The entity ID to remove
             * @return True if this map was modified
             */
            override fun remove(element: K): Boolean = this@EntityPropertyMap.remove(element) != null

            /**
             * @return An iterator over the entity IDs in this map
             */
            override fun iterator() = object : MutableIterator<K> {
                private val entriesIterator = this@EntityPropertyMap.entries.iterator()
                override fun hasNext(): Boolean = entriesIterator.hasNext()
                override fun next(): K = entriesIterator.next().key
                override fun remove() = entriesIterator.remove()
            }
        }

    /**
     * Gets the collection of all property maps in this entity-property map, allowing mutation operations.
     */
    override val values = object : MutableCollection<Map<String, IValue>> {
        /**
         * @return The number of property maps in this entity-property map
         */
        override val size: Int get() = entries.size

        /**
         * Removes all entries from this map
         */
        override fun clear() = entries.clear()

        /**
         * @return True if this map contains no entities
         */
        override fun isEmpty(): Boolean = entries.isEmpty()

        /**
         * Retains only the property maps in this entity-property map that are contained in the specified collection
         *
         * @param elements The collection of property maps to retain
         * @return True if this map was modified
         */
        override fun retainAll(elements: Collection<Map<String, IValue>>): Boolean {
            val allValues = identities.asSequence().map { it.key to PropertyMap(it.key) }
            val matchedKeys = allValues.filter { (_, propMap) -> elements.any { it == propMap } }
            val rmvEntityIDs = identities.keys - matchedKeys.map { it.first }.toSet()
            rmvEntityIDs.forEach { this@EntityPropertyMap.remove(it.toEntityID(entityType!!)) }
            return rmvEntityIDs.isNotEmpty()
        }

        /**
         * Removes all entries with property maps in the specified collection from this entity-property map
         *
         * @param elements The collection of property maps to remove
         * @return True if this map was modified
         */
        override fun removeAll(elements: Collection<Map<String, IValue>>): Boolean {
            val allValues = identities.asSequence().map { it.key to PropertyMap(it.key) }
            val matchedKeys = allValues.filter { (_, propMap) -> elements.any { it == propMap } }.toSet()
            matchedKeys.forEach { this@EntityPropertyMap.remove(it.first.toEntityID(entityType!!)) }
            return matchedKeys.isNotEmpty()
        }

        /**
         * Removes all entries with the specified property map from this entity-property map
         *
         * @param element The property map to remove
         * @return True if this map was modified
         */
        override fun remove(element: Map<String, IValue>): Boolean {
            val allValues = identities.asSequence().map { it.key to PropertyMap(it.key) }
            val matchedKeys = allValues.filter { (_, propMap) -> element == propMap }.toSet()
            matchedKeys.forEach { this@EntityPropertyMap.remove(it.first.toEntityID(entityType!!)) }
            return matchedKeys.isNotEmpty()
        }

        /**
         * @param elements The collection of property maps to check
         * @return True if this entity-property map contains all the property maps in the collection
         */
        override fun containsAll(elements: Collection<Map<String, IValue>>): Boolean {
            val allValues = identities.map { it.key to PropertyMap(it.key) }
            return elements.all { ele -> allValues.any { value -> ele == value } }
        }

        /**
         * @param element The property map to check
         * @return True if this entity-property map contains the property map
         */
        override fun contains(element: Map<String, IValue>): Boolean {
            val allValues = identities.map { it.key to PropertyMap(it.key) }
            return allValues.any { value -> element == value }
        }

        /**
         * Not supported for value collections
         * @throws UnsupportedOperationException Always
         */
        override fun addAll(elements: Collection<Map<String, IValue>>): Boolean =
            throw UnsupportedOperationException("Cannot addAll to only values")

        /**
         * Not supported for value collections
         * @throws UnsupportedOperationException Always
         */
        override fun add(element: Map<String, IValue>): Boolean =
            throw UnsupportedOperationException("Cannot add to only values")

        /**
         * @return An iterator over the property maps in this entity-property map
         */
        override fun iterator() = object : MutableIterator<Map<String, IValue>> {
            private val entriesIterator = entries.iterator()
            override fun hasNext(): Boolean = entriesIterator.hasNext()
            override fun next(): Map<String, IValue> = entriesIterator.next().value
            override fun remove() = entriesIterator.remove()
        }
    }

    /**
     * Checks if this entity-property map is empty (contains no entities).
     *
     * @return True if this map contains no entities
     */
    override fun isEmpty(): Boolean = identities.isEmpty()

    /**
     * Removes all entities and their properties from this map, leaving it empty.
     */
    override fun clear() {
        identities.clear()
        propertiesMap.clear()
    }

    /**
     * Returns the property map associated with the specified entity ID,
     * or null if this map contains no mapping for the entity ID.
     *
     * @param key The entity ID
     * @return The property map associated with the entity ID, or null if not present
     */
    override fun get(key: K): Map<String, IValue>? =
        if (key.name !in identities) null else PropertyMap(key.name)

    /**
     * Checks if this map maps one or more entity IDs to a property map that is equal to
     * the specified property map (value).
     *
     * @param value The property map to check
     * @return True if this map contains at least one mapping to the specified property map
     */
    override fun containsValue(value: Map<String, IValue>) = identities.any { (eid, pKeys) ->
        if (value.keys.toSet() != pKeys.core.map { it.core.toString() }.toSet()) return@any false
        pKeys.core.all { propertiesMap["${eid}:${it.core.toString()}"] == value[it.core.toString()] }
    }

    /**
     * Checks if this map contains a mapping for the specified entity ID.
     *
     * @param key The entity ID to check
     * @return True if this map contains a mapping for the specified entity ID
     */
    override fun containsKey(key: K): Boolean = key.name in identities

    /**
     * Copies all mappings from the specified map to this map.
     * For each entity ID, its property map will be added or updated.
     *
     * @param from The map containing entity ID to property map mappings to be added
     */
    override fun putAll(from: Map<out K, Map<String, IValue>>) = from.forEach { (key, value) ->
        if (entityType == null) entityType = key::class
        val properties = identities[key.name]?.core.orEmpty()
        identities[key.name] = SetVal(properties + value.keys.map { it.strVal })
        propertiesMap.putAll(value.mapKeys { "${key.name}:${it.key}" })
    }

    /**
     * Associates the specified property map with the specified entity ID in this map.
     * If the map previously contained a mapping for the entity ID, the old property map is replaced.
     *
     * @param key The entity ID
     * @param value The property map to associate with the entity ID
     * @return The previous property map associated with the entity ID, or null if not present
     */
    override fun put(key: K, value: Map<String, IValue>): Map<String, IValue>? {
        if (entityType == null) entityType = key::class
        val prevPropKeys = identities[key.name]?.core?.map { it.core.toString() }
        identities[key.name] = (prevPropKeys.orEmpty() + value.keys).map { it.strVal }.setVal
        val prevPropValues = prevPropKeys?.associateWith { propertiesMap.remove("${key.name}:$it")!! }
        propertiesMap.putAll(value.mapKeys { "${key.name}:${it.key}" })
        return prevPropValues
    }

    /**
     * Removes the entity with the specified ID and all its properties from this map.
     *
     * @param key The entity ID to remove
     * @return The previous property map associated with the entity ID, or null if not present
     */
    override fun remove(key: K): Map<String, IValue>? {
        if (key.name !in identities) return null
        val propKeys = identities.remove(key.name)!!.map { it.core.toString() }
        return propKeys.associateWith { propertiesMap.remove("${key.name}:$it")!! }
    }
}