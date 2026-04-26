package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/**
 * Shared columnar-storage helpers used by [NativeStorageImpl] and
 * [NativeConcurStorageImpl]. All functions are pure operations on the
 * column maps passed as arguments.
 */
internal object ColumnarUtils {
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
