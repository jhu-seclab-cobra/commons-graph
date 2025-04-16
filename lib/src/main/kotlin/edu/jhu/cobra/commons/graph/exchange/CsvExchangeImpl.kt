package edu.jhu.cobra.commons.graph.exchange

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.contains
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import org.mapdb.DBMaker
import org.mapdb.Serializer
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Exports the nodes and edges from the storage to a CSV directory.
 * This exchanger will create CSV files for nodes and edges and allows filtering of entities that are exported.
 */
object CsvExchangeImpl : IGraphExchange {

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isDirectory()) return false
        val (nodeFile, edgeFile) = file.let { it.resolve("nodes.csv") to it.resolve("edges.csv") }
        return nodeFile.exists() && nodeFile.fileSize() > 0 && edgeFile.exists() && edgeFile.fileSize() > 0
    }

    override fun export(dstFile: Path, from: IStorage, predicate: EntityFilter): Path {
        if (dstFile.notExists()) dstFile.createDirectories() // create the directory if it does not exist
        val (nodeFile, edgeFile) = dstFile.let { it.resolve("nodes.csv") to it.resolve("edges.csv") }
        require(!nodeFile.exists() || nodeFile.fileSize() <= 0) { "File $nodeFile already exists" }
        require(!edgeFile.exists() || edgeFile.fileSize() <= 0) { "File $edgeFile already exists" }
        // 1. Create a file DB manager for temporary storage of nodes and edges
        val fileDBManager = DBMaker.tempFileDB().fileMmapEnableIfSupported().make() // create a temp file db
        // 2. First write the nodes to the TSV file
        val nodeHeaders = linkedSetOf<String>() // create an ordered set to store the node header
        val nodeList = fileDBManager.indexTreeList("nodes", Serializer.STRING).create()
        from.nodeIDsSequence.filter(predicate).forEach { nid ->
            val propsMap = from.getNodeProperties(nid) // get all properties of the node
            nodeHeaders.addAll(elements = propsMap.keys.filter { it !in nodeHeaders })
            val props = nodeHeaders.map { propsMap[it]?.let(CsvSerializer::serialize) ?: "" }
            nodeList.add(element = "$nid$CSV_DELIMITER${props.joinToString(CSV_DELIMITER)}")
        }
        if (nodeFile.notExists()) nodeFile.toFile().createNewFile()
        nodeFile.writeText("ID$CSV_DELIMITER${nodeHeaders.joinToString(CSV_DELIMITER)}\n")
        nodeFile.appendLines(nodeList.asSequence().map { it as CharSequence }, Charsets.UTF_8)
        // 3. Next write the edges to the TSV file
        val edgeHeaders = linkedSetOf<String>() // create an ordered set to store the edge header
        val edgeList = fileDBManager.indexTreeList("edges", Serializer.STRING).create()
        from.edgeIDsSequence.filter(predicate).forEach { eid ->
            val propsMap = from.getEdgeProperties(eid) // get all properties of the edge
            edgeHeaders.addAll(elements = propsMap.keys.filter { it !in edgeHeaders })
            val props = edgeHeaders.map { propsMap[it]?.let(CsvSerializer::serialize) ?: "" }
            edgeList.add("$eid$CSV_DELIMITER${props.joinToString(CSV_DELIMITER)}")
        }
        if (edgeFile.notExists()) edgeFile.toFile().createNewFile()
        edgeFile.writeText("ID$CSV_DELIMITER${edgeHeaders.joinToString(CSV_DELIMITER)}\n")
        edgeFile.appendLines(edgeList.asSequence().map { it as CharSequence }, Charsets.UTF_8)
        // 4. Close the file DB manager and return the paths
        fileDBManager.close() // close the file db manager to release the file resources
        return dstFile
    }

    override fun import(srcFile: Path, into: IStorage, predicate: EntityFilter): IStorage {
        val (nodeFile, edgeFile) = srcFile.let { it.resolve("nodes.csv") to it.resolve("edges.csv") }
        require(nodeFile.exists() && nodeFile.fileSize() > 0) { "File $nodeFile does not exists or it is empty" }
        require(edgeFile.exists() && edgeFile.fileSize() > 0) { "File $edgeFile does not exists or it is empty" }
        if (nodeFile.notExists()) throw IllegalArgumentException("File $nodeFile does not exist")
        if (edgeFile.notExists()) throw IllegalArgumentException("File $edgeFile does not exist")
        val nodeReader = nodeFile.bufferedReader()
        val nodeHeader = nodeReader.readLine().split(CSV_DELIMITER).toMutableList().also { it.removeAt(0) }
        nodeReader.forEachLine { line ->
            val propStrings = line.split(CSV_DELIMITER).toMutableList()
            val nodeID = runCatching { NodeID(propStrings.removeFirst()) }.getOrNull() ?: return@forEachLine
            val nullableProps = nodeHeader.zip(propStrings.map(CsvSerializer::deserialize))
            val props = nullableProps.mapNotNull { (f, s) -> s?.let { f to it } }
            if (nodeID !in into) into.addNode(nodeID, newProperties = props.toTypedArray())
            else into.setNodeProperties(nodeID, newProperties = props.toTypedArray())
        }
        nodeReader.close()
        val edgeReader = edgeFile.bufferedReader()
        val edgeHeader = edgeReader.readLine().split(CSV_DELIMITER).toMutableList().also { it.removeAt(0) }
        edgeReader.forEachLine { line ->
            val propStrings = line.split(CSV_DELIMITER).toMutableList()
            val edgeID = runCatching { EdgeID(propStrings.removeFirst()) }.getOrNull() ?: return@forEachLine
            val nullableProps = edgeHeader.zip(propStrings.map(CsvSerializer::deserialize))
            val props = nullableProps.mapNotNull { (f, s) -> s?.let { f to it } }
            if (edgeID !in into) into.addEdge(edgeID, newProperties = props.toTypedArray())
            else into.setEdgeProperties(edgeID, newProperties = props.toTypedArray())
        }
        edgeReader.close()
        return into
    }

}

private const val CSV_DELIMITER = "\t"

private object CsvSerializer {
    private val ESCAPE_MAP = setOf("\n" to "\\n", "\r" to "\\r", "\t" to "\\t", "\r\n" to "\\r\\n")
    private val UNESCAPE_MAP = setOf("\\n" to "\n", "\\r" to "\r", "\\t" to "\t", "\\r\\n" to "\r\n")

    fun serialize(value: IValue): String {
        val rawString = DftCharBufferSerializerImpl.serialize(value).toString()
        return ESCAPE_MAP.fold(rawString) { o, (c, r) -> o.replace(c, r) }
    }

    fun deserialize(value: String): IValue? {
        if (value.isEmpty()) return null // The mini length of a serialized value UNSURE is 3
        val unescaped = UNESCAPE_MAP.fold(value) { o, (c, r) -> o.replace(c, r) }
        return DftCharBufferSerializerImpl.deserialize(unescaped.asCharBuffer())
    }
}