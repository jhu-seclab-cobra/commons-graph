package edu.jhu.cobra.commons.graph.storage.nio

/**
 * Shared CSV format definition for [NativeCsvIOImpl]: file and column names,
 * delimiter, escaping rules, and line splitting.
 */
internal object NativeCsvFormat {
    const val CSV_DELIMITER = ","
    private val CSV_DELIMITER_CHAR: Char = CSV_DELIMITER.single()

    const val NODE_FILE = "nodes.csv"
    const val EDGE_FILE = "edges.csv"
    const val META_FILE = "meta.csv"
    const val NODE_ID_COL = "__nid__"
    const val EDGE_ID_COL = "__eid__"
    const val EDGE_SRC_COL = "__src__"
    const val EDGE_DST_COL = "__dst__"
    const val EDGE_TAG_COL = "__tag__"

    private val escapeMap =
        mapOf(
            "\\" to "\\\\",
            "," to "\\,",
            "\r\n" to "\\r\\n",
            "\n" to "\\n",
            "\r" to "\\r",
            "\t" to "\\t",
        )
    private val unescapeMap = escapeMap.entries.associate { (key, value) -> value to key }
    private val escapeRegex = escapeMap.keys.joinToString("|") { Regex.escape(it) }.toRegex()
    private val unescapeRegex = unescapeMap.keys.joinToString("|") { Regex.escape(it) }.toRegex()

    fun escape(value: String): String = value.replace(escapeRegex) { r -> escapeMap[r.value] ?: r.value }

    fun unescape(value: String): String = value.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value }

    /**
     * Splits an escaped CSV line on unescaped delimiters.
     *
     * A delimiter is unescaped when preceded by an even number of backslashes;
     * an odd count means the delimiter itself is escaped (`\,`). A lookbehind
     * regex cannot express this: a cell ending in an escaped backslash (`\\`)
     * would hide the following delimiter.
     *
     * @param limit maximum number of cells; 0 means unlimited. Once reached,
     * the remainder of the line becomes the last cell verbatim.
     */
    fun splitCsvLine(
        line: String,
        limit: Int = 0,
    ): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var backslashCount = 0
        for (char in line) {
            val isDelimiter = char == CSV_DELIMITER_CHAR && backslashCount % 2 == 0
            if (isDelimiter && (limit <= 0 || cells.size < limit - 1)) {
                cells.add(current.toString())
                current.setLength(0)
            } else {
                current.append(char)
            }
            backslashCount = if (char == '\\') backslashCount + 1 else 0
        }
        cells.add(current.toString())
        return cells
    }
}
