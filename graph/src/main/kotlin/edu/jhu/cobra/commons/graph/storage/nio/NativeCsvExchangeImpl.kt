package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.IEntity
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Exports the nodes and edges from the storage to a CSV directory.
 * This exchanger will create CSV files for nodes and edges and allows filtering of entities that are exported.
 */
object NativeCsvExchangeImpl : IStorageExchange {

    private const val CSV_DELIMITER = ","
    private val CSV_DELIMITER_REGEX = Regex("(?<!\\\\)$CSV_DELIMITER")
    private val escapeMap = mapOf(
        "\\" to "\\\\", "," to "\\,",
        "\r\n" to "\\r\\n", "\n" to "\\n", "\r" to "\\r", "\t" to "\\t",
    )
    private val unescapeMap = escapeMap.entries.associate { (key, value) -> value to key }
    private val escapeRegex = escapeMap.keys.joinToString("|") { Regex.escape(it) }.toRegex()
    private val unescapeRegex = unescapeMap.keys.joinToString("|") { Regex.escape(it) }.toRegex()

    private class CsvWriter(path: Path) : Closeable {

        private var isClosed: Boolean = false

        private val nodeFile = path.resolve("nodes.csv")
        private val nodeWriter: BufferedWriter
        private val edgeFile = path.resolve("edges.csv")
        private val edgeWriter: BufferedWriter

        private var isNodeHeaderChanged: Boolean = false
        private val nodeHeaders = LinkedHashSet<String>()

        private var isEdgeHeaderChanged: Boolean = false
        private val edgeHeaders = LinkedHashSet<String>()

        init {
            require(!nodeFile.exists() || nodeFile.fileSize() <= 0) { "File $nodeFile already exists" }
            require(!edgeFile.exists() || edgeFile.fileSize() <= 0) { "File $edgeFile already exists" }
            if (path.notExists()) path.createDirectories()
            nodeWriter = nodeFile.createFile().bufferedWriter()
            nodeWriter.appendLine("ID")
            edgeWriter = edgeFile.createFile().bufferedWriter()
            edgeWriter.appendLine("ID")
        }

        private fun getWriter(id: IEntity.ID): BufferedWriter {
            require(!isClosed) { "The file is closed" }
            return when (id) {
                is NodeID -> nodeWriter
                is EdgeID -> edgeWriter
            }
        }

        fun write(id: IEntity.ID, props: Map<String, IValue>) {
            val writer = getWriter(id)
            props.keys.forEach {
                if (id is NodeID) isNodeHeaderChanged = nodeHeaders.add(it)
                else isEdgeHeaderChanged = edgeHeaders.add(it)
            }
            val headerSequence = if (id is NodeID) nodeHeaders.asSequence() else edgeHeaders.asSequence()
            val orderProps = sequenceOf(id.serialize) + headerSequence.map(props::get)
            val serPropSeq = orderProps.map { it?.let(DftCharBufferSerializerImpl::serialize) ?: "" }
            val escapePropSeq = serPropSeq.map { it.replace(escapeRegex) { r -> escapeMap[r.value] ?: r.value } }
            val escapePropIterator = escapePropSeq.iterator()
            if (escapePropIterator.hasNext()) {
                writer.append(escapePropIterator.next())
            }
            while (escapePropIterator.hasNext()) {
                writer.append(CSV_DELIMITER)
                writer.append(escapePropIterator.next())
            }
            writer.appendLine()
        }

        private fun updateHeader(file: File, header: LinkedHashSet<String>) {
            require(!isClosed) { "The file is closed" }
            val tempFile = File.createTempFile("tmp", ".txt")
            val reader = file.bufferedReader()
            val writer = tempFile.bufferedWriter()
            try {
                val headerSequence = sequenceOf("ID") + header
                val fmtHeader = headerSequence.map { it.replace(escapeRegex) { r -> escapeMap[r.value] ?: r.value } }
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
            if (isNodeHeaderChanged) updateHeader(nodeFile.toFile(), nodeHeaders)
            if (isEdgeHeaderChanged) updateHeader(edgeFile.toFile(), edgeHeaders)
            isClosed = true
        }
    }

    private class CsvReader(path: Path) : Closeable {

        private var isClosed: Boolean = false

        private val nodeFile = path.resolve("nodes.csv")
        private val edgeFile = path.resolve("edges.csv")

        init {
            require(nodeFile.exists() && nodeFile.fileSize() > 0) { "File $nodeFile is empty" }
            require(edgeFile.exists() && edgeFile.fileSize() > 0) { "File $edgeFile is empty" }
        }

        private fun deserialize(strValue: String): IValue? {
            if (strValue == "") return null
            val charBuffer = strValue.asCharBuffer()
            return runCatching { DftCharBufferSerializerImpl.deserialize(charBuffer) }.getOrNull()
        }

        fun readNodes(): Iterator<Pair<NodeID, Map<String, IValue>>> = iterator {
            val nodeReader = nodeFile.bufferedReader()
            try {
                val rawHeader = nodeReader.readLine()!!.split(CSV_DELIMITER_REGEX).drop(1)
                val nodeHeader = rawHeader.map { it.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value } }
                val nodeSequence = nodeReader.lineSequence()
                for (line in nodeSequence) {
                    val props = line.split(CSV_DELIMITER_REGEX).asSequence()
                    val unescaped = props.map { it.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value } }
                    val validValues = unescaped.map { strValue -> if (strValue == "") null else strValue }
                    val values = validValues.map { if (it == null) null else deserialize(it) }.toList()
                    val nodeID = (values.firstOrNull() as? StrVal)?.let(block = ::NodeID) ?: continue
                    val validIndexes = (1..nodeHeader.size).filter { values.getOrNull(it) != null }
                    val nodeProps = validIndexes.associate { nodeHeader[it - 1] to values[it]!! }
                    yield(value = nodeID to nodeProps)
                }
            } finally {
                nodeReader.close()
            }
        }

        fun readEdges(): Iterator<Pair<EdgeID, Map<String, IValue>>> = iterator {
            val edgeReader = edgeFile.bufferedReader()
            try {
                val rawHeader = edgeReader.readLine().split(CSV_DELIMITER_REGEX).drop(1)
                val edgeHeader = rawHeader.map { it.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value } }
                for (line in edgeReader.lineSequence()) {
                    val props = line.split(CSV_DELIMITER_REGEX)
                    require(!isClosed) { "The edge file is closed" }
                    val unescaped = props.map { it.replace(unescapeRegex) { r -> unescapeMap[r.value] ?: r.value } }
                    val validValues = unescaped.map { string -> if (string == "") null else string }
                    val values = validValues.map { if (it == null) null else deserialize(strValue = it) }.toList()
                    val edgeID = (values.firstOrNull() as? ListVal)?.let(block = ::EdgeID) ?: continue
                    val validIndexes = (1..edgeHeader.size).filter { values.getOrNull(it) != null }
                    val edgeProps = validIndexes.associate { edgeHeader[it - 1] to values[it]!! }
                    yield(value = edgeID to edgeProps)
                }
            } finally {
                edgeReader.close()
            }
        }

        override fun close() {
            isClosed = true
        }

    }

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isDirectory()) return false
        val (nodeFile, edgeFile) = file.let { it.resolve("nodes.csv") to it.resolve("edges.csv") }
        return nodeFile.exists() && nodeFile.fileSize() > 0 && edgeFile.exists() && edgeFile.fileSize() > 0
    }

    override fun export(dstFile: Path, from: IStorage, predicate: EntityFilter): Path {
        CsvWriter(path = dstFile).use { writer ->
            from.nodeIDs.filter(predicate).forEach { writer.write(it, from.getNodeProperties(it)) }
            from.edgeIDs.filter(predicate).forEach { writer.write(it, from.getEdgeProperties(it)) }
        }
        return dstFile
    }

    override fun import(srcFile: Path, into: IStorage, predicate: EntityFilter): IStorage {
        CsvReader(path = srcFile).use { reader ->
            val validNodes = reader.readNodes().asSequence().filter { (nid) -> predicate(nid) }
            validNodes.forEach { (nid, properties) -> into.addNode(nid, properties) }
            val validEdges = reader.readEdges().asSequence().filter { (eid) -> predicate(eid) }
            validEdges.forEach { (eid, properties) -> into.addEdge(eid, properties) }
        }
        return into
    }

}