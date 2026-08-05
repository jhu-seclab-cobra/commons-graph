package edu.jhu.cobra.commons.graph.storage.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists

/**
 * Exports and imports nodes and edges from storage to a CSV directory.
 *
 * Node CSV format: __nid__ structural column + property columns.
 * Edge CSV format: __eid__, __src__, __dst__, __tag__ structural columns + property columns.
 * Format details live in [NativeCsvFormat]; streaming lives in [NativeCsvWriter] and
 * [NativeCsvReader].
 */
public object NativeCsvIOImpl : IStorageExporter, IStorageImporter {
    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isDirectory()) return false
        val nodeFile = file.resolve(NativeCsvFormat.NODE_FILE)
        val edgeFile = file.resolve(NativeCsvFormat.EDGE_FILE)
        return nodeFile.exists() && nodeFile.fileSize() > 0 && edgeFile.exists() && edgeFile.fileSize() > 0
    }

    override fun export(
        dstFile: Path,
        from: IStorage,
        predicate: EntityFilter,
    ): Path {
        NativeCsvWriter(path = dstFile).use { writer ->
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
        NativeCsvReader(path = srcFile).use { reader ->
            val nodeStringToInt = HashMap<String, Int>()
            val filteredNodeIds = HashSet<String>()
            reader.readNodes().forEach { (nodeId, props) ->
                if (isFilteredOut(nodeId, predicate)) {
                    filteredNodeIds.add(nodeId)
                    return@forEach
                }
                val storageId = into.addNode(props)
                nodeStringToInt[nodeId] = storageId
            }
            reader.readEdges().forEach { record ->
                if (isFilteredOut(record.edgeId, predicate)) return@forEach
                if (record.src in filteredNodeIds || record.dst in filteredNodeIds) return@forEach
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

    /**
     * Applies the [EntityFilter] to a CSV entity ID column. The filter operates on the
     * original storage Int IDs; an ID that does not parse as Int (hand-edited CSV) has no
     * original Int identity and is never filtered.
     */
    private fun isFilteredOut(
        entityId: String,
        predicate: EntityFilter,
    ): Boolean {
        val originalId = entityId.toIntOrNull() ?: return false
        return !predicate(originalId)
    }
}
