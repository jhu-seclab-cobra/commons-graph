package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Shared columnar-storage helpers used by [NativeStorageImpl],
 * [NativeConcurStorageImpl], and [LayeredStorageImpl]. All functions are
 * pure operations on the column maps passed as arguments.
 */
internal object ColumnarProperties {
    /**
     * Removes all property entries for [id] across every column.
     * Drops empty columns to avoid unbounded key accumulation.
     */
    fun removeEntityFromColumns(
        id: Int,
        columns: HashMap<String, HashMap<Int, IValue>>,
    ) {
        val colIter = columns.values.iterator()
        while (colIter.hasNext()) {
            val col = colIter.next()
            col.remove(id)
            if (col.isEmpty()) colIter.remove()
        }
    }

    /** Snapshots all properties of entity [id] across every column into a plain map. */
    fun collectProperties(
        id: Int,
        columns: HashMap<String, HashMap<Int, IValue>>,
    ): Map<String, IValue> {
        val result = HashMap<String, IValue>()
        for ((colName, col) in columns) {
            val value = col[id] ?: continue
            result[colName] = value
        }
        return result
    }

    /**
     * Applies property updates for [id]. Non-null values are set; null values
     * delete the property. [internKey] transforms column-name strings before
     * insertion (identity by default; callers may pass a deduplication pool).
     */
    fun setColumnarProperties(
        id: Int,
        properties: Map<String, IValue?>,
        columns: HashMap<String, HashMap<Int, IValue>>,
        internKey: (String) -> String = { it },
    ) {
        for ((key, value) in properties) {
            if (value != null) {
                columns.getOrPut(internKey(key)) { HashMap() }[id] = value
            } else {
                val col = columns[key] ?: continue
                col.remove(id)
                if (col.isEmpty()) columns.remove(key)
            }
        }
    }
}

/**
 * Lazy read-only map view over one entity's properties in a columnar store.
 * Entries are materialized once on first access and cached; size and
 * emptiness reuse the cache when present.
 */
internal class ColumnViewMap(
    private val entityId: Int,
    private val columns: HashMap<String, HashMap<Int, IValue>>,
) : AbstractMap<String, IValue>() {
    private var cachedEntries: Set<Map.Entry<String, IValue>>? = null

    override val entries: Set<Map.Entry<String, IValue>>
        get() {
            cachedEntries?.let { return it }
            val result = LinkedHashMap<String, IValue>()
            for ((colName, col) in columns) {
                val value = col[entityId] ?: continue
                result[colName] = value
            }
            return result.entries.also { cachedEntries = it }
        }

    override fun get(key: String): IValue? = columns[key]?.get(entityId)

    override fun containsKey(key: String): Boolean = columns[key]?.containsKey(entityId) == true

    override val size: Int get() = entries.size

    override fun isEmpty(): Boolean {
        cachedEntries?.let { return it.isEmpty() }
        for (col in columns.values) {
            if (col.containsKey(entityId)) return false
        }
        return true
    }
}
