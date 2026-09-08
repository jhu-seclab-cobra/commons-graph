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

// Structural columns, fixed by NativeCsvFormat: they precede every property column.
private val NODE_FIXED_COLUMNS = listOf(NODE_ID_COL)
private val EDGE_FIXED_COLUMNS = listOf(EDGE_ID_COL, EDGE_SRC_COL, EDGE_DST_COL, EDGE_TAG_COL)

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
        nodeWriter.appendLine(headerLine(NODE_FIXED_COLUMNS, emptySet()))
        edgeWriter = edgeFile.bufferedWriter()
        edgeWriter.appendLine(headerLine(EDGE_FIXED_COLUMNS, emptySet()))
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
        nodeWriter.appendLine((structural + serializedColumns(nodeHeaders, props)).joinToString(CSV_DELIMITER))
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
        edgeWriter.appendLine((structural + serializedColumns(edgeHeaders, props)).joinToString(CSV_DELIMITER))
    }

    // One escaped cell per header, in header order; an absent property is an empty cell.
    private fun serializedColumns(
        headers: LinkedHashSet<String>,
        props: Map<String, IValue>,
    ): Sequence<String> =
        headers.asSequence().map { name ->
            val value = props[name] ?: return@map escape("")
            escape(DftCharBufferSerializerImpl.serialize(value).toString())
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

    // Each column name is escaped as its own cell; the fixed names never contain the delimiter,
    // and a property name that does must not split the header.
    private fun headerLine(
        fixedColumns: List<String>,
        header: Set<String>,
    ): String = (fixedColumns.asSequence() + header.asSequence()).map { escape(it) }.joinToString(CSV_DELIMITER)

    private fun updateHeader(
        file: File,
        header: LinkedHashSet<String>,
        fixedColumns: List<String>,
    ) {
        require(!isClosed) { "The file is closed" }
        val newFirstLine = headerLine(fixedColumns, header)
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
            updateHeader(nodeFile.toFile(), nodeHeaders, NODE_FIXED_COLUMNS)
        }
        if (isEdgeHeaderChanged) {
            updateHeader(edgeFile.toFile(), edgeHeaders, EDGE_FIXED_COLUMNS)
        }
        isClosed = true
    }
}
