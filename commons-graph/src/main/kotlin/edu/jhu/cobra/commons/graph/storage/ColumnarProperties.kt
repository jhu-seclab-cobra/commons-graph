package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Columnar property store of one entity kind, shared by [NativeStorageImpl],
 * [NativeConcurStorageImpl], and the active layer of [LayeredStorageImpl].
 *
 * Holds one column (`HashMap<Int, IValue>`) per property name and, for each entity that holds a
 * property, the names of the columns that hold it, in the order they were first set. Every
 * operation keeps the two equal: an entity id appears in a column exactly when that column's name
 * is listed once for the entity, and an entity without properties has no list. A row read and a row
 * removal therefore visit only the entity's own columns, never every column of the store.
 *
 * Not thread-safe; the owning storage guards every call.
 *
 * @param internKey maps a new column name to the instance the store keeps (identity by default;
 *   a storage may pass a deduplication pool).
 */
internal class ColumnarProperties(
    private val internKey: (String) -> String = { it },
) {
    // A column keeps the name instance every per-entity list refers to.
    private class Column(val name: String) : HashMap<Int, IValue>()

    private val columns = HashMap<String, Column>()
    private val rowNames = HashMap<Int, ArrayList<String>>()

    /** Names of the columns that hold at least one entity. */
    val names: Set<String> get() = columns.keys

    /** The value of property [name] of entity [id], or null. */
    operator fun get(
        id: Int,
        name: String,
    ): IValue? = columns[name]?.get(id)

    /** True when entity [id] holds property [name]. */
    fun contains(
        id: Int,
        name: String,
    ): Boolean = columns[name]?.containsKey(id) == true

    /** Names of the columns that hold entity [id], in first-set order; empty when it holds none. */
    fun rowNames(id: Int): List<String> = rowNames[id] ?: emptyList()

    /**
     * Applies property updates for [id]. Non-null values are set; null values delete the property.
     * Takes O(1) per updated name, plus O(properties of the row) for each deleted name.
     */
    fun set(
        id: Int,
        properties: Map<String, IValue?>,
    ) {
        for ((key, value) in properties) {
            if (value != null) put(id, key, value, properties.size) else delete(id, key)
        }
    }

    /** Snapshots all properties of entity [id] into a plain map. */
    fun collect(id: Int): Map<String, IValue> {
        val names = rowNames[id] ?: return HashMap()
        val result = HashMap<String, IValue>(names.size * 2)
        for (name in names) result[name] = columns.getValue(name).getValue(id)
        return result
    }

    /** Lazy read-only view of the properties of entity [id]. */
    fun view(id: Int): Map<String, IValue> = ColumnViewMap(id, this)

    /** Removes every property of entity [id] and drops the columns it leaves empty. */
    fun remove(id: Int) {
        val names = rowNames.remove(id) ?: return
        for (name in names) {
            val column = columns.getValue(name)
            column.remove(id)
            if (column.isEmpty()) columns.remove(name)
        }
    }

    fun clear() {
        columns.clear()
        rowNames.clear()
    }

    /**
     * Throws [IllegalStateException] when a column and the per-entity names disagree: an entity in a
     * column whose name it does not list, a listed name whose column lacks the entity, a name listed
     * twice, an empty list, or an empty column.
     */
    fun checkRows() {
        for ((id, names) in rowNames) {
            check(names.isNotEmpty()) { "Entity $id has an empty column name list" }
            check(names.size == names.toSet().size) { "Entity $id lists a column name twice: $names" }
            for (name in names) check(contains(id, name)) { "Column $name lacks listed entity $id" }
        }
        for ((name, column) in columns) {
            check(column.isNotEmpty()) { "Column $name is empty" }
            for (id in column.keys) check(name in rowNames(id)) { "Entity $id does not list column $name" }
        }
    }

    private fun put(
        id: Int,
        key: String,
        value: IValue,
        expected: Int,
    ) {
        val column = columns[key] ?: internKey(key).let { name -> Column(name).also { columns[name] = it } }
        if (column.put(id, value) == null) rowNames.getOrPut(id) { ArrayList(expected) }.add(column.name)
    }

    private fun delete(
        id: Int,
        key: String,
    ) {
        val column = columns[key] ?: return
        if (column.remove(id) == null) return
        if (column.isEmpty()) columns.remove(key)
        val names = rowNames.getValue(id)
        names.remove(key)
        if (names.isEmpty()) rowNames.remove(id)
    }
}

/**
 * Lazy read-only map view over one entity's properties in a [ColumnarProperties] store.
 * Entries are materialized once on first access from the entity's own column names and cached;
 * size and emptiness reuse the cache when present.
 */
internal class ColumnViewMap(
    private val entityId: Int,
    private val columns: ColumnarProperties,
) : AbstractMap<String, IValue>() {
    private var cachedEntries: Set<Map.Entry<String, IValue>>? = null

    override val entries: Set<Map.Entry<String, IValue>>
        get() {
            cachedEntries?.let { return it }
            val names = columns.rowNames(entityId)
            val result = LinkedHashMap<String, IValue>(names.size * 2)
            for (name in names) result[name] = checkNotNull(columns[entityId, name])
            return result.entries.also { cachedEntries = it }
        }

    override fun get(key: String): IValue? = columns[entityId, key]

    override fun containsKey(key: String): Boolean = columns.contains(entityId, key)

    override val size: Int get() = entries.size

    override fun isEmpty(): Boolean {
        cachedEntries?.let { return it.isEmpty() }
        return columns.rowNames(entityId).isEmpty()
    }
}
