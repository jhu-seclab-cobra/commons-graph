package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.nio.EntityFilter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageExporter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageImporter
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.Type
import edu.jhu.cobra.commons.value.serializer.asCharBuffer
import org.jgrapht.graph.DirectedPseudograph
import org.jgrapht.nio.Attribute
import org.jgrapht.nio.AttributeType
import org.jgrapht.nio.DefaultAttribute
import org.jgrapht.nio.ImportException
import org.jgrapht.nio.gml.GmlExporter
import org.jgrapht.nio.gml.GmlImporter
import org.jgrapht.util.SupplierUtil
import java.nio.BufferUnderflowException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

public object JgraphtGmlIOImpl : IStorageExporter, IStorageImporter {
    private const val NODE_ID_ATTR = "nid"
    private const val EDGE_SRC_ATTR = "esrc"
    private const val EDGE_DST_ATTR = "edst"
    private const val EDGE_TAG_ATTR = "etype"

    /**
     * Type tags [DftCharBufferSerializerImpl] emits as the frame prefix before the first `:`.
     * The shortest frame is a payload-less tag plus its terminator (`Null:`, `True:`).
     * An attribute value whose prefix is not one of these tags was never produced by the
     * serializer (e.g. GML `label` or `ID` values) and is legitimately not deserialized;
     * a value with a matching tag whose payload fails to decode is corrupt data.
     */
    private val SERIALIZED_TYPE_TAGS = Type.entries.map(Type::str).toSet()

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isRegularFile()) return false
        // probeContentType returns null when the platform cannot identify the type
        // (macOS has no mapping for .gml); only a positively non-text type vetoes.
        val contentType: String? = Files.probeContentType(file)
        return file.fileSize() > 0 && (contentType == null || "text" in contentType)
    }

    @Suppress("LongMethod")
    override fun export(
        dstFile: Path,
        from: IStorage,
        predicate: EntityFilter,
    ): Path {
        require(dstFile.notExists() || dstFile.fileSize() == 0L) { "File $dstFile already exists" }
        val exporter = GmlExporter<Int, Int>()
        val nodeList = from.nodeIDs.filter(predicate).toList()
        val idOfVx = nodeList.withIndex().associate { (index, node) -> node to index }
        // An edge whose endpoint node was filtered out cannot be represented; skip it.
        val edgeList =
            from.edgeIDs.filter(predicate).filter { edgeID ->
                val structure = from.getEdgeStructure(edgeID)
                structure.src in idOfVx && structure.dst in idOfVx
            }
        exporter.setVertexAttributeProvider { index: Int ->
            val nodeID = nodeList[index]
            val metaProp = mapOf(NODE_ID_ATTR to StrVal(nodeID.toString()))
            val props = metaProp + from.getNodeProperties(nodeID)
            props.mapValues { (_, value) -> value.toAttribute }
        }
        exporter.setEdgeAttributeProvider { index: Int ->
            val edgeID = edgeList[index]
            val structure = from.getEdgeStructure(edgeID)
            val metaProp =
                mapOf(
                    EDGE_SRC_ATTR to StrVal(structure.src.toString()),
                    EDGE_DST_ATTR to StrVal(structure.dst.toString()),
                    EDGE_TAG_ATTR to StrVal(structure.tag),
                )
            val props = metaProp + from.getEdgeProperties(edgeID)
            props.mapValues { (_, value) -> value.toAttribute }
        }
        val graph = DirectedPseudograph<Int, Int>(Int::class.java)
        nodeList.forEachIndexed { index, _ -> graph.addVertex(index) }
        edgeList.forEachIndexed { index, edge ->
            val edgeStructure = from.getEdgeStructure(edge)
            graph.addEdge(idOfVx.getValue(edgeStructure.src), idOfVx.getValue(edgeStructure.dst), index)
        }
        exporter.setParameter(GmlExporter.Parameter.EXPORT_VERTEX_LABELS, true)
        exporter.setParameter(GmlExporter.Parameter.EXPORT_EDGE_LABELS, true)
        exporter.exportGraph(graph, dstFile.toFile())
        return dstFile
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
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
        try {
            importer.importGraph(vGraph, srcFile.toFile())
        } catch (e: ImportException) {
            // jgrapht wraps exceptions thrown by attribute consumers; surface the
            // corrupt-attribute cause directly instead of the transport wrapper.
            throw (e.cause as? IllegalArgumentException) ?: e
        }

        // Track old node ID → new storage ID mapping for edge resolution. The predicate
        // filters on the original storage Int IDs persisted in the nid attribute; edges have
        // no persisted ID of their own, so they are filtered through their endpoints.
        val nodeIdMapping = HashMap<String, Int>()
        val filteredNodeIds = HashSet<String>()
        nodesCache.values.forEach { props ->
            val oldNid = (props.remove(NODE_ID_ATTR) as? StrVal)?.core ?: return@forEach
            val originalId = oldNid.toIntOrNull()
            if (originalId != null && !predicate(originalId)) {
                filteredNodeIds.add(oldNid)
                return@forEach
            }
            val storageId = into.addNode(props)
            nodeIdMapping[oldNid] = storageId
        }
        edgeCache.values.forEach { props ->
            val oldSrc = (props.remove(EDGE_SRC_ATTR) as? StrVal)?.core ?: return@forEach
            val oldDst = (props.remove(EDGE_DST_ATTR) as? StrVal)?.core ?: return@forEach
            val type = (props.remove(EDGE_TAG_ATTR) as? StrVal)?.core ?: return@forEach
            if (oldSrc in filteredNodeIds || oldDst in filteredNodeIds) return@forEach
            val src = nodeIdMapping[oldSrc] ?: error("Unknown node ID: $oldSrc")
            val dst = nodeIdMapping[oldDst] ?: error("Unknown node ID: $oldDst")
            into.addEdge(src, dst, type, props)
        }
        return into
    }

    private val Attribute.toValue: IValue?
        get() {
            if (type != AttributeType.STRING) return null
            val unescaped = value.replace("\\\"", "\"")
            val tag = unescaped.substringBefore(':', missingDelimiterValue = "")
            if (tag !in SERIALIZED_TYPE_TAGS) return null
            return try {
                DftCharBufferSerializerImpl.deserialize(unescaped.asCharBuffer())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Corrupt serialized attribute value '$value'", e)
            } catch (e: BufferUnderflowException) {
                throw IllegalArgumentException("Corrupt serialized attribute value '$value'", e)
            }
        }

    private val IValue.toAttribute: Attribute
        get() {
            val value = DftCharBufferSerializerImpl.serialize(this).toString()
            return DefaultAttribute.createAttribute(value.replace("\"", "\\\""))
        }
}
