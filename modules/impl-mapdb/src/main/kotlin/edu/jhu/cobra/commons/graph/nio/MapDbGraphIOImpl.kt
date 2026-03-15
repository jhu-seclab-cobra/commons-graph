package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.nio.EntityFilter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageExporter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageImporter
import edu.jhu.cobra.commons.graph.utils.MapDbValSerializer
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.NumVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.mapVal
import org.mapdb.DBException
import org.mapdb.DBMaker
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

/**
 * Implementation of [IStorageExporter] and [IStorageImporter] using MapDB for graph data persistence.
 * Provides functionality to export and import graph data between [IStorage] and MapDB files.
 */
object MapDbGraphIOImpl : IStorageExporter, IStorageImporter {
    private const val NODE_ID_KEY = "_nid"
    private const val EDGE_SRC_KEY = "_esrc"
    private const val EDGE_DST_KEY = "_edst"
    private const val EDGE_TYPE_KEY = "_etype"

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
                nodesList.add(nodeProperties.also { it.add(NODE_ID_KEY, NumVal(nodeID)) })
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).create()
            from.edgeIDs.filter(predicate).forEach { edgeID ->
                val edgeProperties = from.getEdgeProperties(id = edgeID).mapVal
                edgesList.add(
                    edgeProperties.also {
                        it.add(EDGE_SRC_KEY, NumVal(from.getEdgeSrc(edgeID)))
                        it.add(EDGE_DST_KEY, NumVal(from.getEdgeDst(edgeID)))
                        it.add(EDGE_TYPE_KEY, StrVal(from.getEdgeType(edgeID)))
                    },
                )
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
            val nodeIdMapping = HashMap<Int, Int>()
            val nodesList = dbManager.indexTreeList("nodes", MapSerializer).open()
            nodesList.forEach { props ->
                val oldNid = (props!!.remove(NODE_ID_KEY) as? NumVal)?.core?.toInt() ?: return@forEach
                if (!predicate(oldNid)) return@forEach
                val propsMap: Map<String, IValue> = props.core.toMap()
                val storageId = into.addNode(propsMap)
                nodeIdMapping[oldNid] = storageId
            }
            val edgesList = dbManager.indexTreeList("edges", MapSerializer).open()
            edgesList.forEach { props ->
                val oldSrc = (props!!.remove(EDGE_SRC_KEY) as? NumVal)?.core?.toInt() ?: return@forEach
                val oldDst = (props.remove(EDGE_DST_KEY) as? NumVal)?.core?.toInt() ?: return@forEach
                val type = (props.remove(EDGE_TYPE_KEY) as? StrVal)?.core ?: return@forEach
                val src = nodeIdMapping[oldSrc] ?: oldSrc
                val dst = nodeIdMapping[oldDst] ?: oldDst
                if (!predicate(src)) return@forEach
                val propsMap: Map<String, IValue> = props.core.toMap()
                into.addEdge(src, dst, type, propsMap)
            }
        }
        return into
    }
}
