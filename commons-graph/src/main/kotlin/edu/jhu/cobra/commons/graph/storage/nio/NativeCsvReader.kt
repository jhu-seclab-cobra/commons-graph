package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.splitCsvLine
import edu.jhu.cobra.commons.graph.storage.nio.NativeCsvFormat.unescape
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/** One decoded node row of a [NativeCsvFormat] directory. */
internal data class NodeRecord(
    val nodeId: String,
    val properties: Map<String, IValue>,
)

/** One decoded edge row of a [NativeCsvFormat] directory. */
internal data class EdgeRecord(
    val edgeId: String,
    val src: String,
    val dst: String,
    val tag: String,
    val properties: Map<String, IValue>,
)

/**
 * Streams nodes, edges, and metadata out of a CSV directory in [NativeCsvFormat].
 * The meta file is optional; node and edge files must exist and be non-empty.
 */
internal class NativeCsvReader(
    path: Path,
) : Closeable {
    private var isClosed: Boolean = false

    private val nodeFile = path.resolve(NativeCsvFormat.NODE_FILE)
    private val edgeFile = path.resolve(NativeCsvFormat.EDGE_FILE)
    private val metaFile = path.resolve(NativeCsvFormat.META_FILE)

    private val openReaders = mutableListOf<BufferedReader>()
    private val nodeReader: BufferedReader
    private val edgeReader: BufferedReader
    private val metaReader: BufferedReader?

    init {
        require(nodeFile.exists() && nodeFile.fileSize() > 0) { "File $nodeFile is empty" }
        require(edgeFile.exists() && edgeFile.fileSize() > 0) { "File $edgeFile is empty" }
        try {
            nodeReader = openReader(nodeFile)
            edgeReader = openReader(edgeFile)
            metaReader = if (metaFile.exists()) openReader(metaFile) else null
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun openReader(file: Path): BufferedReader = file.bufferedReader().also(openReaders::add)

    private fun readLineOrNull(reader: BufferedReader): String? {
        check(!isClosed) { "NativeCsvReader is closed" }
        return reader.readLine()
    }

    private fun remainingLines(reader: BufferedReader): Sequence<String> = generateSequence { readLineOrNull(reader) }

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
            val rawHeaderString = readLineOrNull(nodeReader) ?: ""
            val fullHeader = splitCsvLine(rawHeaderString).map { unescape(it) }
            // First column is __nid__ (structural)
            val propHeaders = fullHeader.drop(1)
            for (line in remainingLines(nodeReader)) {
                val parts = splitCsvLine(line)
                if (parts.isEmpty()) continue
                val unescaped = parts.map { unescape(it) }
                val nodeId = unescaped[0]
                yield(NodeRecord(nodeId, decodeProps(unescaped, propHeaders, offset = 1)))
            }
        }

    fun readEdges(): Iterator<EdgeRecord> =
        iterator {
            val rawHeaderString = readLineOrNull(edgeReader) ?: ""
            val edgeHeader = splitCsvLine(rawHeaderString).map { unescape(it) }
            // First 4 columns are eid, src, dst, tag
            val propHeaders = edgeHeader.drop(4)
            for (line in remainingLines(edgeReader)) {
                val parts = splitCsvLine(line)
                if (parts.size < 4) continue
                val unescaped = parts.map { unescape(it) }
                val edgeId = unescaped[0]
                val src = unescaped[1]
                val dst = unescaped[2]
                val tag = unescaped[3]
                yield(EdgeRecord(edgeId, src, dst, tag, decodeProps(unescaped, propHeaders, offset = 4)))
            }
        }

    fun readMeta(): Iterator<Pair<String, IValue>> =
        iterator {
            val reader = metaReader ?: return@iterator
            readLineOrNull(reader)
            for (line in remainingLines(reader)) {
                val parts = splitCsvLine(line, limit = 2)
                val value = parts.getOrNull(1)?.let { deserialize(unescape(it)) } ?: continue
                yield(unescape(parts[0]) to value)
            }
        }

    override fun close() {
        if (isClosed) return
        isClosed = true
        openReaders.forEach { reader -> reader.close() }
    }
}
