package edu.jhu.cobra.commons.graph.exchange

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.contains
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.mapVal
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.serializer.asByteArray
import edu.jhu.cobra.commons.value.strVal
import org.mapdb.DBMaker
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Implementation of the IGraphExchange interface that provides functionality to export and import graph data from/to a file.
 */
object FdbExchangeImpl : IGraphExchange {

    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isRegularFile() || file.fileSize() == 0L) return false
        try {
            val dbChecker = DBMaker.fileDB(file.toFile()).fileMmapEnableIfSupported().make()
            dbChecker.indexTreeList("nodes").open()
            dbChecker.indexTreeList("edges").open()
            dbChecker.close()
            return true
        } catch (e: Exception) {
            println(e.message)
            return false
        }
    }

    override fun export(dstFile: Path, from: IStorage, predicate: EntityFilter): Path {
        require(dstFile.notExists()) { "File $dstFile already exists" }
        if (dstFile.parent.notExists()) dstFile.createParentDirectories()
        val dbManager = DBMaker.fileDB(dstFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            // we use list to ensure order of nodes and edges by which the ast loading will ensure same
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).create()
            from.nodeIDsSequence.filter(predicate).forEach { nodeID ->
                val nodeProperties = from.getNodeProperties(id = nodeID).mapVal
                nodesList.add(nodeProperties.also { it.add("_nid", nodeID.name.strVal) })
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).create()
            from.edgeIDsSequence.filter(predicate).forEach { edgeID ->
                val edgeProperties = from.getEdgeProperties(id = edgeID).mapVal
                edgesList.add(edgeProperties.also { it.add("_eid", edgeID.name.strVal) })
            }
        }
        return dstFile
    }

    override fun import(srcFile: Path, into: IStorage, predicate: EntityFilter): IStorage {
        require(srcFile.exists() || srcFile.fileSize() > 0) { "File $srcFile does not exist" }
        val dbManager = DBMaker.fileDB(srcFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).open()
            nodesList.forEach { props ->
                val nid = NodeID(props!!.remove("_nid")!!.core.toString())
                if (!predicate(nid)) return@forEach // skip the new node
                if (nid in into) into.setNodeProperties(nid, *props.toTypeArray())
                else into.addNode(id = nid, newProperties = props.toTypeArray())
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).open()
            edgesList.forEach { props ->
                val eid = EdgeID(props!!.remove("_eid")!!.core.toString())
                if (!predicate(eid)) return@forEach // skip the new edge
                if (eid.srcNid !in into) into.addNode(id = eid.srcNid)
                if (eid.dstNid !in into) into.addNode(id = eid.dstNid)
                if (eid in into) into.setEdgeProperties(eid, *props.toTypeArray())
                else into.addEdge(id = eid, newProperties = props.toTypeArray())
            }
        }
        return into
    }

}

private object MapSerializer : Serializer<MapVal> {

    private val serializer = DftByteArraySerializerImpl

    override fun isTrusted(): Boolean = true

    override fun serialize(out: DataOutput2, value: MapVal) =
        out.write(serializer.serialize(value = value))

    override fun deserialize(input: DataInput2, available: Int): MapVal =
        if (available == 0) MapVal() else serializer.deserialize(input.asByteArray(available)) as MapVal

}
