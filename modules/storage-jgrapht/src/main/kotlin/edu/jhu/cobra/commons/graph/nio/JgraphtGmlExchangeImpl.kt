package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.nio.EntityFilter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageExchange
import edu.jhu.cobra.commons.graph.storage.toTypeArray
import edu.jhu.cobra.commons.value.IValue
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
import kotlin.collections.MutableMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.contains
import kotlin.collections.forEach
import kotlin.collections.forEachIndexed
import kotlin.collections.mapOf
import kotlin.collections.mapValues
import kotlin.collections.mutableMapOf
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

object JgraphtGmlExchangeImpl : IStorageExchange {

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isRegularFile()) return false
        return file.fileSize() > 0 && "text" in Files.probeContentType(file)
    }

    override fun export(dstFile: Path, from: IStorage, predicate: EntityFilter): Path {
        require(dstFile.notExists() || dstFile.fileSize() != 0L) { "File $dstFile already exists" }
        val exporter = GmlExporter<Int, Int>()
        val nodeList = from.nodeIDsSequence.filter(predicate).toList()
        exporter.setVertexAttributeProvider { index: Int ->
            val nodeID = nodeList[index] // NodeId
            val metaProp = mapOf("nid" to nodeID.serialize)
            val props = metaProp + from.getNodeProperties(nodeID)
            props.mapValues { (_, value) -> value.toAttribute }
        }
        val edgeList = from.edgeIDsSequence.filter(predicate).toList()
        exporter.setEdgeAttributeProvider { index: Int ->
            val edgeID = edgeList[index] // EdgeId
            val metaProp = mapOf("eid" to edgeID.serialize)
            val props = metaProp + from.getEdgeProperties(edgeID)
            props.mapValues { (_, value) -> value.toAttribute }
        }
        val idOfVx = mutableMapOf<NodeID, Int>()
        val graph = DirectedPseudograph<Int, Int>(Int::class.java)
        nodeList.forEachIndexed { index, node -> graph.addVertex(index); idOfVx[node] = index; }
        edgeList.forEachIndexed { index, edge -> graph.addEdge(idOfVx[edge.srcNid], idOfVx[edge.dstNid], index) }
        exporter.setParameter(GmlExporter.Parameter.EXPORT_VERTEX_LABELS, true)
        exporter.setParameter(GmlExporter.Parameter.EXPORT_EDGE_LABELS, true)
        exporter.exportGraph(graph, dstFile.toFile())
        return dstFile
    }

    override fun import(srcFile: Path, into: IStorage, predicate: EntityFilter): IStorage {
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
        nodesCache.values.forEach { props ->
            val nid = props.remove("nid")!!.toEntityID<NodeID>()
            if (nid !in into) into.addNode(nid, *props.toTypeArray())
            else into.setNodeProperties(nid, *props.toTypeArray())
        }
        edgeCache.values.forEach { props ->
            val eid = props.remove("eid")!!.toEntityID<EdgeID>()
            if (eid !in into) into.addEdge(eid, *props.toTypeArray())
            else into.setEdgeProperties(eid, *props.toTypeArray())
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
