package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists

/**
 * Exports and imports nodes and edges from storage to a CSV directory.
 *
 * Node CSV format: __nid__ structural column + property columns.
 * Edge CSV format: __eid__, __src__, __dst__, __type__ structural columns + property columns.
 */
object NativeCsvIOImpl : IStorageExporter, IStorageImporter {
    private const val CSV_DELIMITER = ","
    private val CSV_DELIMITER_CHAR: Char = CSV_DELIMITER.single()
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

    private const val META_FILE = "meta.csv"
    private const val NODE_ID_COL = "__nid__"
    private const val EDGE_ID_COL = "__eid__"
    private const val EDGE_SRC_COL = "__src__"
    private const val EDGE_DST_COL = "__dst__"
    private const val EDGE_TAG_COL = "__tag__"

    private fun escape(value: String): String = value.replace(escapeRegex) { r -> escapeMap[r.value] ?: r.value }

    private fun unescape(value: String): String = value.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value }

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
    private fun splitCsvLine(
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

    private class CsvWriter(
        path: Path,
    ) : Closeable {
        private var isClosed: Boolean = false

        private val nodeFile = path.resolve("nodes.csv")
        private val nodeWriter: BufferedWriter
        private val edgeFile = path.resolve("edges.csv")
        private val edgeWriter: BufferedWriter
        private val metaFile = path.resolve(META_FILE)
        private val metaWriter: BufferedWriter

        private var isNodeHeaderChanged: Boolean = false
        private val nodeHeaders = LinkedHashSet<String>()

        private var isEdgeHeaderChanged: Boolean = false
        private val edgeHeaders = LinkedHashSet<String>()

        init {
            require(!nodeFile.exists() || nodeFile.fileSize() <= 0) { "File $nodeFile already exists" }
            require(!edgeFile.exists() || edgeFile.fileSize() <= 0) { "File $edgeFile already exists" }
            require(!metaFile.exists() || metaFile.fileSize() <= 0) { "File $metaFile already exists" }
            if (path.notExists()) path.createDirectories()
            nodeWriter = nodeFile.createFile().bufferedWriter()
            nodeWriter.appendLine(NODE_ID_COL)
            edgeWriter = edgeFile.createFile().bufferedWriter()
            edgeWriter.appendLine("$EDGE_ID_COL$CSV_DELIMITER$EDGE_SRC_COL$CSV_DELIMITER$EDGE_DST_COL$CSV_DELIMITER$EDGE_TAG_COL")
            metaWriter = metaFile.createFile().bufferedWriter()
            metaWriter.appendLine("name${CSV_DELIMITER}value")
        }

        fun writeNode(
            nodeId: String,
            props: Map<String, IValue>,
        ) {
            require(!isClosed) { "The file is closed" }
            isNodeHeaderChanged = nodeHeaders.addAll(props.keys) || isNodeHeaderChanged
            val structural = sequenceOf(escape(nodeId))
            val values = nodeHeaders.asSequence().map(props::get)
            val serialized = values.map { it?.let { v -> DftCharBufferSerializerImpl.serialize(v).toString() } ?: "" }
            val escaped = serialized.map { escape(it) }
            nodeWriter.appendLine((structural + escaped).joinToString(CSV_DELIMITER))
        }

        fun writeEdge(
            edgeId: String,
            src: String,
            dst: String,
            tag: String,
            props: Map<String, IValue>,
        ) {
            require(!isClosed) { "The file is closed" }
            isEdgeHeaderChanged = edgeHeaders.addAll(props.keys) || isEdgeHeaderChanged
            val structural = sequenceOf(escape(edgeId), escape(src), escape(dst), escape(tag))
            val values = edgeHeaders.asSequence().map(props::get)
            val serialized = values.map { it?.let { v -> DftCharBufferSerializerImpl.serialize(v).toString() } ?: "" }
            val escaped = serialized.map { escape(it) }
            val all = structural + escaped
            edgeWriter.appendLine(all.joinToString(CSV_DELIMITER))
        }

        fun writeMeta(
            name: String,
            value: IValue,
        ) {
            require(!isClosed) { "The file is closed" }
            val serName = escape(name)
            val rawSerValue = DftCharBufferSerializerImpl.serialize(value).toString()
            val serValue = escape(rawSerValue)
            metaWriter.appendLine("$serName$CSV_DELIMITER$serValue")
        }

        private fun updateHeader(
            file: File,
            header: LinkedHashSet<String>,
            fixedPrefix: String,
        ) {
            require(!isClosed) { "The file is closed" }
            val headerSequence =
                if (fixedPrefix.isEmpty()) {
                    header.asSequence()
                } else {
                    sequenceOf(fixedPrefix) + header.asSequence()
                }
            val newFirstLine = headerSequence.map { escape(it) }.joinToString(CSV_DELIMITER)
            val tempFile = File.createTempFile("tmp", ".txt")
            try {
                file.bufferedReader().use { reader ->
                    tempFile.bufferedWriter().use { writer ->
                        writer.appendLine(newFirstLine)
                        reader.readLine()
                        reader.forEachLine(writer::appendLine)
                    }
                }
                tempFile.copyTo(file, overwrite = true)
            } finally {
                tempFile.delete()
            }
        }

        override fun close() {
            if (isClosed) return
            nodeWriter.close()
            edgeWriter.close()
            metaWriter.close()
            if (isNodeHeaderChanged) {
                updateHeader(nodeFile.toFile(), nodeHeaders, NODE_ID_COL)
            }
            if (isEdgeHeaderChanged) {
                val edgeFixedPrefix =
                    "$EDGE_ID_COL$CSV_DELIMITER$EDGE_SRC_COL$CSV_DELIMITER$EDGE_DST_COL$CSV_DELIMITER$EDGE_TAG_COL"
                updateHeader(edgeFile.toFile(), edgeHeaders, edgeFixedPrefix)
            }
            isClosed = true
        }
    }

    private class CsvReader(
        path: Path,
    ) : Closeable {
        private var isClosed: Boolean = false

        private val nodeFile = path.resolve("nodes.csv")
        private val edgeFile = path.resolve("edges.csv")
        private val metaFile = path.resolve(META_FILE)

        init {
            require(nodeFile.exists() && nodeFile.fileSize() > 0) { "File $nodeFile is empty" }
            require(edgeFile.exists() && edgeFile.fileSize() > 0) { "File $edgeFile is empty" }
        }

        private fun deserialize(strValue: String): IValue? {
            if (strValue == "") return null
            val charBuffer = strValue.asCharBuffer()
            return DftCharBufferSerializerImpl.deserialize(charBuffer)
        }

        private fun decodeProps(
            cells: List<String>,
            headers: List<String>,
            offset: Int,
        ): HashMap<String, IValue> {
            val props = HashMap<String, IValue>()
            for (i in headers.indices) {
                val value = cells.getOrNull(i + offset)?.let { deserialize(it) } ?: continue
                props[headers[i]] = value
            }
            return props
        }

        fun readNodes(): Iterator<NodeRecord> =
            iterator {
                val nodeReader = nodeFile.bufferedReader()
                try {
                    val rawHeaderString = nodeReader.readLine() ?: ""
                    val rawHeader = splitCsvLine(rawHeaderString)
                    val fullHeader = rawHeader.map { unescape(it) }
                    // First column is __nid__ (structural)
                    val propHeaders = fullHeader.drop(1)
                    for (line in nodeReader.lineSequence()) {
                        val parts = splitCsvLine(line)
                        if (parts.isEmpty()) continue
                        val unescaped = parts.map { unescape(it) }
                        val nodeId = unescaped[0]
                        yield(NodeRecord(nodeId, decodeProps(unescaped, propHeaders, offset = 1)))
                    }
                } finally {
                    nodeReader.close()
                }
            }

        fun readEdges(): Iterator<EdgeRecord> =
            iterator {
                val edgeReader = edgeFile.bufferedReader()
                try {
                    val rawHeader = splitCsvLine(edgeReader.readLine())
                    val edgeHeader = rawHeader.map { unescape(it) }
                    // First 4 columns are eid, src, dst, tag
                    val propHeaders = edgeHeader.drop(4)
                    for (line in edgeReader.lineSequence()) {
                        require(!isClosed) { "The edge file is closed" }
                        val parts = splitCsvLine(line)
                        if (parts.size < 4) continue
                        val unescaped = parts.map { unescape(it) }
                        val edgeId = unescaped[0]
                        val src = unescaped[1]
                        val dst = unescaped[2]
                        val tag = unescaped[3]
                        yield(EdgeRecord(edgeId, src, dst, tag, decodeProps(unescaped, propHeaders, offset = 4)))
                    }
                } finally {
                    edgeReader.close()
                }
            }

        fun readMeta(): Iterator<Pair<String, IValue>> =
            iterator {
                if (!metaFile.exists()) return@iterator
                val reader = metaFile.bufferedReader()
                try {
                    reader.readLine()
                    for (line in reader.lineSequence()) {
                        val parts = splitCsvLine(line, limit = 2)
                        val value = parts.getOrNull(1)?.let { deserialize(unescape(it)) } ?: continue
                        yield(unescape(parts[0]) to value)
                    }
                } finally {
                    reader.close()
                }
            }

        override fun close() {
            isClosed = true
        }
    }

    internal data class NodeRecord(
        val nodeId: String,
        val properties: Map<String, IValue>,
    )

    internal data class EdgeRecord(
        val edgeId: String,
        val src: String,
        val dst: String,
        val tag: String,
        val properties: Map<String, IValue>,
    )

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isDirectory()) return false
        val (nodeFile, edgeFile) = file.let { it.resolve("nodes.csv") to it.resolve("edges.csv") }
        return nodeFile.exists() && nodeFile.fileSize() > 0 && edgeFile.exists() && edgeFile.fileSize() > 0
    }

    override fun export(
        dstFile: Path,
        from: IStorage,
        predicate: EntityFilter,
    ): Path {
        CsvWriter(path = dstFile).use { writer ->
            from.nodeIDs.filter(predicate).forEach { nodeId ->
                writer.writeNode(nodeId.toString(), from.getNodeProperties(nodeId))
            }
            from.edgeIDs.filter(predicate).forEach { edgeId ->
                val (src, dst, tag) = from.getEdgeStructure(edgeId)
                writer.writeEdge(edgeId.toString(), src.toString(), dst.toString(), tag, from.getEdgeProperties(edgeId))
            }
            for (name in from.metaNames) {
                val value = from.getMeta(name) ?: continue
                writer.writeMeta(name, value)
            }
        }
        return dstFile
    }

    override fun import(
        srcFile: Path,
        into: IStorage,
        predicate: EntityFilter,
    ): IStorage {
        CsvReader(path = srcFile).use { reader ->
            val nodeStringToInt = HashMap<String, Int>()
            reader.readNodes().forEach { (nodeId, props) ->
                val storageId = into.addNode(props)
                nodeStringToInt[nodeId] = storageId
            }
            reader.readEdges().forEach { record ->
                val srcInt =
                    requireNotNull(nodeStringToInt[record.src]) {
                        "Edge references unknown source node '${record.src}'"
                    }
                val dstInt =
                    requireNotNull(nodeStringToInt[record.dst]) {
                        "Edge references unknown destination node '${record.dst}'"
                    }
                into.addEdge(srcInt, dstInt, record.tag, record.properties)
            }
            reader.readMeta().forEach { (name, value) -> into.setMeta(name, value) }
        }
        return into
    }
}
