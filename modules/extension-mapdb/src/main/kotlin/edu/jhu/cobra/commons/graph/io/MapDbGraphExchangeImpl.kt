package edu.jhu.cobra.commons.graph.exchange

import edu.jhu.cobra.commons.graph.entity.EdgeID
import edu.jhu.cobra.commons.graph.entity.NodeID
import edu.jhu.cobra.commons.graph.entity.toEntityID
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.contains
import edu.jhu.cobra.commons.graph.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.mapVal
import org.mapdb.DBMaker
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Implementation of [IGraphExchange] using MapDB for graph data persistence.
 * Provides functionality to export and import graph data between [IStorage] and MapDB files.
 */
object MapDbGraphExchangeImpl : IGraphExchange {

    private const val NODE_ID_KEY = "_nid"
    private const val EDGE_ID_KEY = "_eid"

    /** Serializer for MapDB value storage. */
    private val MapSerializer = MapDbValSerializer<MapVal>()

    /**
     * @return true if the file is a valid MapDB file containing required collections.
     */
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

    /**
     * Exports graph data from [from] to [dstFile] using MapDB.
     * @param dstFile Target file path (must not exist)
     * @param from Source storage
     * @param predicate Filter for entities to export
     * @return Path to the created file
     * @throws IllegalArgumentException if dstFile already exists
     */
    override fun export(dstFile: Path, from: IStorage, predicate: EntityFilter): Path {
        require(dstFile.notExists()) { "File $dstFile already exists" }
        if (dstFile.parent.notExists()) dstFile.createParentDirectories()
        val dbManager = DBMaker.fileDB(dstFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            // we use a list to ensure the order of nodes and edges by which the ast loading will ensure same
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).create()
            from.nodeIDsSequence.filter(predicate).forEach { nodeID ->
                val nodeProperties = from.getNodeProperties(id = nodeID).mapVal
                nodesList.add(nodeProperties.also { it.add(NODE_ID_KEY, nodeID.serialize) })
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).create()
            from.edgeIDsSequence.filter(predicate).forEach { edgeID ->
                val edgeProperties = from.getEdgeProperties(id = edgeID).mapVal
                edgesList.add(edgeProperties.also { it.add(EDGE_ID_KEY, edgeID.serialize) })
            }
        }
        return dstFile
    }

    /**
     * Imports graph data from [srcFile] into [into] using MapDB.
     * @param srcFile Source file path (must exist and be non-empty)
     * @param into Target storage
     * @param predicate Filter for entities to import
     * @return The storage with imported data
     * @throws IllegalArgumentException if srcFile does not exist or is empty
     */
    override fun import(srcFile: Path, into: IStorage, predicate: EntityFilter): IStorage {
        require(srcFile.exists() && srcFile.fileSize() > 0) { "File $srcFile does not exist" }
        val dbManager = DBMaker.fileDB(srcFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).open()
            nodesList.forEach { props ->
                val nid = NodeID(props!!.remove(NODE_ID_KEY)!!.core.toString())
                if (!predicate(nid)) return@forEach // skip the new node
                if (nid in into) into.setNodeProperties(nid, *props.toTypeArray())
                else into.addNode(id = nid, newProperties = props.toTypeArray())
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).open()
            edgesList.forEach { props ->
                val eid = props!!.remove(EDGE_ID_KEY)!!.toEntityID<EdgeID>()
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
