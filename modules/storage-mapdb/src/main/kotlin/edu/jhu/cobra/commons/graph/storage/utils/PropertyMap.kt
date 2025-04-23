package edu.jhu.cobra.commons.graph.storage.utils

import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.value.IValue
import org.mapdb.DB


class PropertyMap<K : IEntity.ID>(dbManager: DB, name: String) : Map<K, Map<String, IValue>> {

    private val idMap = dbManager.hashMap("$name-id")
    private val propertyMap = dbManager.hashMap("$name-property")

    inner class PropertyMap : Map<String, IValue> {
        override val entries: Set<Map.Entry<String, IValue>>
            get() = TODO("Not yet implemented")

        override val keys: Set<String>
            get() = TODO("Not yet implemented")
        override val size: Int
            get() = TODO("Not yet implemented")
        override val values: Collection<IValue>
            get() = TODO("Not yet implemented")

        override fun isEmpty(): Boolean {
            TODO("Not yet implemented")
        }

        override fun get(key: String): IValue? {
            TODO("Not yet implemented")
        }

        override fun containsValue(value: IValue): Boolean {
            TODO("Not yet implemented")
        }

        override fun containsKey(key: String): Boolean {
            TODO("Not yet implemented")
        }
    }

    override val entries: Set<Map.Entry<K, Map<String, IValue>>>
        get() = TODO("Not yet implemented")

    override val keys: Set<K>
        get() = TODO("Not yet implemented")
    override val size: Int
        get() = TODO("Not yet implemented")
    override val values: Collection<Map<String, IValue>>
        get() = TODO("Not yet implemented")

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun get(key: K): Map<String, IValue>? {
        TODO("Not yet implemented")
    }

    override fun containsValue(value: Map<String, IValue>): Boolean {
        TODO("Not yet implemented")
    }

    override fun containsKey(key: K): Boolean {
        TODO("Not yet implemented")
    }

}