package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.nio.EntityFilter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageExporter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageImporter
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import org.jgrapht.graph.DirectedPseudograph
import org.jgrapht.nio.Attribute
import org.jgrapht.nio.AttributeType
import org.jgrapht.nio.DefaultAttribute
import org.jgrapht.nio.gml.GmlExporter
import org.jgrapht.nio.gml.GmlImporter
import org.jgrapht.util.SupplierUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

object JgraphtGmlIOImpl : IStorageExporter, IStorageImporter {
    private const val NODE_ID_ATTR = "nid"
    private const val EDGE_SRC_ATTR = "esrc"
    private const val EDGE_DST_ATTR = "edst"
    private const val EDGE_TYPE_ATTR = "etype"

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isRegularFile()) return false
        return file.fileSize() > 0 && "text" in Files.probeContentType(file)
    }

    override fun export(
        dstFile: Path,
        from: IStorage,
        predicate: EntityFilter,
    ): Path {
        require(dstFile.notExists() || dstFile.fileSize() != 0L) { "File $dstFile already exists" }
        val exporter = GmlExporter<Int, Int>()
        val nodeList = from.nodeIDs.filter(predicate).toList()
        exporter.setVertexAttributeProvider { index: Int ->
            val nodeID = nodeList[index]
            val metaProp = mapOf(NODE_ID_ATTR to StrVal(nodeID))
            val props = metaProp + from.getNodeProperties(nodeID)
            props.mapValues { (_, value) -> value.toAttribute }
        }
        val edgeList = from.edgeIDs.filter(predicate).toList()
        exporter.setEdgeAttributeProvider { index: Int ->
            val edgeID = edgeList[index]
            val metaProp =
                mapOf(
                    EDGE_SRC_ATTR to StrVal(from.getEdgeSrc(edgeID)),
                    EDGE_DST_ATTR to StrVal(from.getEdgeDst(edgeID)),
                    EDGE_TYPE_ATTR to StrVal(from.getEdgeType(edgeID)),
                )
            val props = metaProp + from.getEdgeProperties(edgeID)
            props.mapValues { (_, value) -> value.toAttribute }
        }
        val idOfVx = mutableMapOf<String, Int>()
        val graph = DirectedPseudograph<Int, Int>(Int::class.java)
        nodeList.forEachIndexed { index, node ->
            graph.addVertex(index)
            idOfVx[node] = index
        }
        edgeList.forEachIndexed { index, edge ->
            val src = from.getEdgeSrc(edge)
            val dst = from.getEdgeDst(edge)
            graph.addEdge(idOfVx[src], idOfVx[dst], index)
        }
        exporter.setParameter(GmlExporter.Parameter.EXPORT_VERTEX_LABELS, true)
        exporter.setParameter(GmlExporter.Parameter.EXPORT_EDGE_LABELS, true)
        exporter.exportGraph(graph, dstFile.toFile())
        return dstFile
    }

    @Suppress("LongMethod")
    override fun import(
        srcFile: Path,
        into: IStorage,
        predicate: EntityFilter,
    ): IStorage {
        require(srcFile.exists() && srcFile.fileSize() > 0) { "File $srcFile does not exists or it is empty" }
        val importer = GmlImporter<Int, Int>()
        val nodesCache = mutableMapOf<Int, MutableMap<String, IValue>>()
        importer.addVertexAttributeConsumer { nidAndName, prop ->
            val (id, propName) = nidAndName.first to nidAndName.second
            if (propName == "ID") return@addVertexAttributeConsumer
            if (id !in nodesCache) nodesCache[id] = mutableMapOf()
            nodesCache[id]!![propName] = prop.toValue ?: return@addVertexAttributeConsumer
        }
        val edgeCache = mutableMapOf<Int, MutableMap<String, IValue>>()
        importer.addEdgeAttributeConsumer { eidAndName, prop ->
            val (id, propName) = eidAndName.first to eidAndName.second
            if (id !in edgeCache) edgeCache[id] = mutableMapOf()
            edgeCache[id]!![propName] = prop.toValue ?: return@addEdgeAttributeConsumer
        }
        val vGraph = DirectedPseudograph<Int, Int>(Int::class.java)
        vGraph.vertexSupplier = SupplierUtil.createIntegerSupplier()
        vGraph.edgeSupplier = SupplierUtil.createIntegerSupplier()
        importer.importGraph(vGraph, srcFile.toFile())

        // Track old node ID → new storage ID mapping for edge resolution
        val nodeIdMapping = HashMap<String, String>()
        nodesCache.values.forEach { props ->
            val oldNid = (props.remove(NODE_ID_ATTR) as? StrVal)?.core ?: return@forEach
            val storageId = into.addNode(props)
            nodeIdMapping[oldNid] = storageId
        }
        edgeCache.values.forEach { props ->
            val oldSrc = (props.remove(EDGE_SRC_ATTR) as? StrVal)?.core ?: return@forEach
            val oldDst = (props.remove(EDGE_DST_ATTR) as? StrVal)?.core ?: return@forEach
            val type = (props.remove(EDGE_TYPE_ATTR) as? StrVal)?.core ?: return@forEach
            val src = nodeIdMapping[oldSrc] ?: oldSrc
            val dst = nodeIdMapping[oldDst] ?: oldDst
            into.addEdge(src, dst, type, props)
        }
        return into
    }

    private val Attribute.toValue: IValue?
        get() {
            if (type != AttributeType.STRING || value.length < 6) return null
            val escaped = value.replace("\\\"", "\"").asCharBuffer()
            return runCatching { DftCharBufferSerializerImpl.deserialize(escaped) }.getOrNull()
        }

    private val IValue.toAttribute: Attribute
        get() {
            val value = DftCharBufferSerializerImpl.serialize(this).toString()
            return DefaultAttribute.createAttribute(value.replace("\"", "\\\""))
        }
}
