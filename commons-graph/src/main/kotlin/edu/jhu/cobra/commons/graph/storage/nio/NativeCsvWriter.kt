package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.CSV_DELIMITER
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.EDGE_DST_COL
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.EDGE_ID_COL
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.EDGE_SRC_COL
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.EDGE_TAG_COL
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.NODE_ID_COL
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.escape
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.notExists

/**
 * Streams a storage's nodes, edges, and metadata into a CSV directory in
 * [NativeCsvFormat]. Property headers grow as entities are written; changed
 * headers are rewritten into the files on [close].
 */
internal class NativeCsvWriter(
    path: Path,
) : Closeable {
    private var isClosed: Boolean = false

    private val nodeFile = path.resolve(NativeCsvFormat.NODE_FILE)
    private val nodeWriter: BufferedWriter
    private val edgeFile = path.resolve(NativeCsvFormat.EDGE_FILE)
    private val edgeWriter: BufferedWriter
    private val metaFile = path.resolve(NativeCsvFormat.META_FILE)
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
        nodeWriter = nodeFile.bufferedWriter()
        nodeWriter.appendLine(NODE_ID_COL)
        edgeWriter = edgeFile.bufferedWriter()
        edgeWriter.appendLine("$EDGE_ID_COL$CSV_DELIMITER$EDGE_SRC_COL$CSV_DELIMITER$EDGE_DST_COL$CSV_DELIMITER$EDGE_TAG_COL")
        metaWriter = metaFile.bufferedWriter()
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
