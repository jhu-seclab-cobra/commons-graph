package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import edu.jhu.cobra.commons.value.strVal
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
 * Node CSV format: all properties (plus __sid__ for storage ID mapping) as columns.
 * Edge CSV format: src, dst, type columns + property columns.
 */
object NativeCsvIOImpl : IStorageExporter, IStorageImporter {
    private const val CSV_DELIMITER = ","
    private val CSV_DELIMITER_REGEX = Regex("(?<!\\\\)$CSV_DELIMITER")
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
    private const val EXPORT_SID_KEY = "__sid__"
    private const val EDGE_SRC_COL = "__src__"
    private const val EDGE_DST_COL = "__dst__"
    private const val EDGE_TYPE_COL = "__type__"

    private fun escape(value: String): String = value.replace(escapeRegex) { r -> escapeMap[r.value] ?: r.value }

    private fun unescape(value: String): String = value.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value }

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
            nodeWriter.appendLine("PROPS")
            edgeWriter = edgeFile.createFile().bufferedWriter()
            edgeWriter.appendLine("$EDGE_SRC_COL$CSV_DELIMITER$EDGE_DST_COL$CSV_DELIMITER$EDGE_TYPE_COL")
            metaWriter = metaFile.createFile().bufferedWriter()
            metaWriter.appendLine("name${CSV_DELIMITER}value")
        }

        fun writeNode(props: Map<String, IValue>) {
            require(!isClosed) { "The file is closed" }
            isNodeHeaderChanged = nodeHeaders.addAll(props.keys) || isNodeHeaderChanged
            val values = nodeHeaders.asSequence().map(props::get)
            val serialized = values.map { it?.let { v -> DftCharBufferSerializerImpl.serialize(v).toString() } ?: "" }
            val escaped = serialized.map { escape(it) }
            nodeWriter.appendLine(escaped.joinToString(CSV_DELIMITER))
        }

        fun writeEdge(
            src: String,
            dst: String,
            type: String,
            props: Map<String, IValue>,
        ) {
            require(!isClosed) { "The file is closed" }
            isEdgeHeaderChanged = edgeHeaders.addAll(props.keys) || isEdgeHeaderChanged
            val structural = sequenceOf(escape(src), escape(dst), escape(type))
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
            val tempFile = File.createTempFile("tmp", ".txt")
            val reader = file.bufferedReader()
            val writer = tempFile.bufferedWriter()
            try {
                val headerSequence =
                    if (fixedPrefix.isEmpty()) {
                        header.asSequence()
                    } else {
                        sequenceOf(fixedPrefix) + header.asSequence()
                    }
                val fmtHeader = headerSequence.map { escape(it) }
                val newFirstLine = fmtHeader.joinToString(CSV_DELIMITER)
                writer.appendLine(newFirstLine)
                reader.readLine()
                reader.forEachLine(writer::appendLine)
            } finally {
                reader.close()
                writer.close()
            }
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }

        override fun close() {
            if (isClosed) return
            nodeWriter.close()
            edgeWriter.close()
            metaWriter.close()
            if (isNodeHeaderChanged) updateHeader(nodeFile.toFile(), nodeHeaders, "")
            if (isEdgeHeaderChanged) {
                val edgeFixedPrefix = "$EDGE_SRC_COL$CSV_DELIMITER$EDGE_DST_COL$CSV_DELIMITER$EDGE_TYPE_COL"
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
            return runCatching { DftCharBufferSerializerImpl.deserialize(charBuffer) }.getOrNull()
        }

        fun readNodes(): Iterator<Map<String, IValue>> =
            iterator {
                val nodeReader = nodeFile.bufferedReader()
                try {
                    val rawHeaderString = nodeReader.readLine() ?: ""
                    val rawHeader = rawHeaderString.split(CSV_DELIMITER_REGEX)
                    val nodeHeader = rawHeader.map { unescape(it) }
                    for (line in nodeReader.lineSequence()) {
                        val parts = line.split(CSV_DELIMITER_REGEX)
                        val unescaped = parts.map { unescape(it) }
                        val props = HashMap<String, IValue>()
                        for (i in nodeHeader.indices) {
                            val raw = unescaped.getOrNull(i) ?: continue
                            val value = deserialize(raw) ?: continue
                            props[nodeHeader[i]] = value
                        }
                        yield(props)
                    }
                } finally {
                    nodeReader.close()
                }
            }

        fun readEdges(): Iterator<EdgeRecord> =
            iterator {
                val edgeReader = edgeFile.bufferedReader()
                try {
                    val rawHeader = edgeReader.readLine().split(CSV_DELIMITER_REGEX)
                    val edgeHeader = rawHeader.map { unescape(it) }
                    // First 3 columns are src, dst, type
                    val propHeaders = edgeHeader.drop(3)
                    for (line in edgeReader.lineSequence()) {
                        require(!isClosed) { "The edge file is closed" }
                        val parts = line.split(CSV_DELIMITER_REGEX)
                        if (parts.size < 3) continue
                        val unescaped = parts.map { unescape(it) }
                        val src = unescaped[0]
                        val dst = unescaped[1]
                        val type = unescaped[2]
                        val props = HashMap<String, IValue>()
                        for (i in propHeaders.indices) {
                            val raw = unescaped.getOrNull(i + 3) ?: continue
                            val value = deserialize(raw) ?: continue
                            props[propHeaders[i]] = value
                        }
                        yield(EdgeRecord(src, dst, type, props))
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
                        val parts = line.split(CSV_DELIMITER_REGEX, limit = 2)
                        if (parts.size < 2) continue
                        val name = unescape(parts[0])
                        val rawValue = unescape(parts[1])
                        val value = deserialize(rawValue) ?: continue
                        yield(name to value)
                    }
                } finally {
                    reader.close()
                }
            }

        override fun close() {
            isClosed = true
        }
    }

    internal data class EdgeRecord(
        val src: String,
        val dst: String,
        val type: String,
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
                // Include __sid__ so import can reconstruct edge src/dst mappings
                val props = from.getNodeProperties(nodeId) + (EXPORT_SID_KEY to nodeId.strVal)
                writer.writeNode(props)
            }
            from.edgeIDs.filter(predicate).forEach { edgeId ->
                writer.writeEdge(
                    from.getEdgeSrc(edgeId),
                    from.getEdgeDst(edgeId),
                    from.getEdgeType(edgeId),
                    from.getEdgeProperties(edgeId),
                )
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
        // Track exported storage node ID → new storage node ID mapping
        val oldToNewId = HashMap<String, String>()
        CsvReader(path = srcFile).use { reader ->
            reader.readNodes().forEach { props ->
                val oldNid = (props[EXPORT_SID_KEY] as? StrVal)?.core
                val cleanProps = props - EXPORT_SID_KEY
                val newNid = into.addNode(oldNid ?: "", cleanProps)
                if (oldNid != null) {
                    oldToNewId[oldNid] = newNid
                }
            }
            reader.readEdges().forEach { record ->
                val srcId = oldToNewId[record.src] ?: error("Unknown node ID: ${record.src}")
                val dstId = oldToNewId[record.dst] ?: error("Unknown node ID: ${record.dst}")
                val edgeId = "${record.src}-${record.type}-${record.dst}"
                into.addEdge(srcId, dstId, edgeId, record.type, record.properties)
            }
            reader.readMeta().forEach { (name, value) -> into.setMeta(name, value) }
        }
        return into
    }
}
