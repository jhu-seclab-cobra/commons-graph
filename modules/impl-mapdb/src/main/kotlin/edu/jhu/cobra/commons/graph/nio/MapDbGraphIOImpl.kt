package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.nio.EntityFilter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageExporter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageImporter
import edu.jhu.cobra.commons.graph.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.mapVal
import org.mapdb.DBException
import org.mapdb.DBMaker
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Implementation of [IStorageExporter] and [IStorageImporter] using MapDB for graph data persistence.
 * Provides functionality to export and import graph data between [IStorage] and MapDB files.
 */
object MapDbGraphIOImpl : IStorageExporter, IStorageImporter {
    private const val NODE_ID_KEY = "_nid"
    private const val EDGE_ID_KEY = "_eid"

    private val MapSerializer = MapDbValSerializer<MapVal>()

    @Suppress("SwallowedException")
    override fun isValidFile(file: Path): Boolean {
        if (file.notExists() || !file.isRegularFile() || file.fileSize() == 0L) return false
        try {
            val dbChecker = DBMaker.fileDB(file.toFile()).fileMmapEnableIfSupported().make()
            dbChecker.indexTreeList("nodes").open()
            dbChecker.indexTreeList("edges").open()
            dbChecker.close()
            return true
        } catch (e: DBException) {
            return false
        }
    }

    override fun export(
        dstFile: Path,
        from: IStorage,
        predicate: EntityFilter,
    ): Path {
        require(dstFile.notExists()) { "File $dstFile already exists" }
        if (dstFile.parent.notExists()) dstFile.createParentDirectories()
        val dbManager = DBMaker.fileDB(dstFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).create()
            from.nodeIDs.filter(predicate).forEach { nodeID ->
                val nodeProperties = from.getNodeProperties(id = nodeID).mapVal
                nodesList.add(nodeProperties.also { it.add(NODE_ID_KEY, nodeID.serialize) })
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).create()
            from.edgeIDs.filter(predicate).forEach { edgeID ->
                val edgeProperties = from.getEdgeProperties(id = edgeID).mapVal
                edgesList.add(edgeProperties.also { it.add(EDGE_ID_KEY, edgeID.serialize) })
            }
        }
        return dstFile
    }

    @Suppress("LongMethod")
    override fun import(
        srcFile: Path,
        into: IStorage,
        predicate: EntityFilter,
    ): IStorage {
        require(srcFile.exists() && srcFile.fileSize() > 0) { "File $srcFile does not exist" }
        val dbManager = DBMaker.fileDB(srcFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).open()
            nodesList.forEach { props ->
                val nid = NodeID(props!!.remove(NODE_ID_KEY)!!.core.toString())
                if (!predicate(nid)) return@forEach
                val propsMap: Map<String, IValue> = props.core.toMap()
                if (into.containsNode(nid)) {
                    into.setNodeProperties(nid, propsMap)
                } else {
                    into.addNode(id = nid, properties = propsMap)
                }
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).open()
            edgesList.forEach { props ->
                val eid = EdgeID(props!!.remove(EDGE_ID_KEY)!! as ListVal)
                if (!predicate(eid)) return@forEach
                if (!into.containsNode(eid.srcNid)) into.addNode(id = eid.srcNid)
                if (!into.containsNode(eid.dstNid)) into.addNode(id = eid.dstNid)
                val propsMap: Map<String, IValue> = props.core.toMap()
                if (into.containsEdge(eid)) {
                    into.setEdgeProperties(eid, propsMap)
                } else {
                    into.addEdge(id = eid, properties = propsMap)
                }
            }
        }
        return into
    }
}
