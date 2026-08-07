package edu.jhu.cobra.commons.graph.nio

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.MapDbValSerializer
import edu.jhu.cobra.commons.graph.storage.nio.EntityFilter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageExporter
import edu.jhu.cobra.commons.graph.storage.nio.IStorageImporter
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.MapVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.mapVal
import org.mapdb.DB
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
public object MapDbGraphIOImpl : IStorageExporter, IStorageImporter {
    private const val NODE_ID_KEY = "_nid"
    private const val EDGE_SRC_KEY = "_esrc"
    private const val EDGE_DST_KEY = "_edst"
    private const val EDGE_TAG_KEY = "_etag"

    // A user property stored under one of these keys would be overwritten by the
    // structural value on export and stripped on import; export rejects the collision.
    private val RESERVED_KEYS = setOf(NODE_ID_KEY, EDGE_SRC_KEY, EDGE_DST_KEY, EDGE_TAG_KEY)

    private fun rejectReservedProps(
        entityId: Int,
        props: Map<String, IValue>,
    ) {
        val clash = props.keys.firstOrNull { it in RESERVED_KEYS } ?: return
        throw InvalidPropNameException(clash, entityId.toString())
    }

    private val mapValSerializer = MapDbValSerializer<MapVal>()

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
        val exportNodeIds = from.nodeIDs.filter(predicate)
        val exportEdgeIds = from.edgeIDs.filter(predicate)
        exportNodeIds.forEach { rejectReservedProps(it, from.getNodeProperties(it)) }
        exportEdgeIds.forEach { rejectReservedProps(it, from.getEdgeProperties(it)) }
        if (dstFile.parent.notExists()) dstFile.createParentDirectories()
        val dbManager = DBMaker.fileDB(dstFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            exportNodes(dbManager, from, exportNodeIds)
            exportEdges(dbManager, from, exportEdgeIds)
            exportMeta(dbManager, from)
        }
        return dstFile
    }

    private fun exportNodes(
        dbManager: DB,
        from: IStorage,
        nodeIds: List<Int>,
    ) {
        val nodesList = dbManager.indexTreeList("nodes", mapValSerializer).create()
        nodeIds.forEach { nodeID ->
            val nodeProperties = from.getNodeProperties(id = nodeID).mapVal
            nodesList.add(nodeProperties.also { it.add(NODE_ID_KEY, IntVal(nodeID.toLong())) })
        }
    }

    private fun exportEdges(
        dbManager: DB,
        from: IStorage,
        edgeIds: List<Int>,
    ) {
        val edgesList = dbManager.indexTreeList("edges", mapValSerializer).create()
        edgeIds.forEach { edgeID ->
            val edgeProperties = from.getEdgeProperties(id = edgeID).mapVal
            val structure = from.getEdgeStructure(edgeID)
            edgesList.add(
                edgeProperties.also {
                    it.add(EDGE_SRC_KEY, IntVal(structure.src.toLong()))
                    it.add(EDGE_DST_KEY, IntVal(structure.dst.toLong()))
                    it.add(EDGE_TAG_KEY, StrVal(structure.tag))
                },
            )
        }
    }

    private fun exportMeta(
        dbManager: DB,
        from: IStorage,
    ) {
        val metaList = dbManager.indexTreeList("meta", mapValSerializer).create()
        val metaVal = MapVal()
        from.metaNames.forEach { name -> from.getMeta(name)?.let { value -> metaVal.add(name, value) } }
        metaList.add(metaVal)
    }

    override fun import(
        srcFile: Path,
        into: IStorage,
        predicate: EntityFilter,
    ): IStorage {
        require(srcFile.exists() && srcFile.fileSize() > 0) { "File $srcFile does not exist" }
        val dbManager = DBMaker.fileDB(srcFile.toFile()).fileMmapEnableIfSupported().make()
        dbManager.use {
            // The predicate filters on the original storage Int IDs persisted in _nid; edges
            // carry no persisted ID of their own, so they are filtered through their endpoints.
            val (nodeIdMapping, filteredNodeIds) = importNodes(dbManager, into, predicate)
            importEdges(dbManager, into, nodeIdMapping, filteredNodeIds)
            importMeta(dbManager, into)
        }
        return into
    }

    private fun importNodes(
        dbManager: DB,
        into: IStorage,
        predicate: EntityFilter,
    ): Pair<Map<Int, Int>, Set<Int>> {
        val nodeIdMapping = HashMap<Int, Int>()
        val filteredNodeIds = HashSet<Int>()
        val nodesList = dbManager.indexTreeList("nodes", mapValSerializer).open()
        nodesList.forEach { props ->
            val oldNid = (props!!.remove(NODE_ID_KEY) as? IntVal)?.core?.toInt() ?: return@forEach
            if (!predicate(oldNid)) {
                filteredNodeIds.add(oldNid)
                return@forEach
            }
            val propsMap: Map<String, IValue> = props.core.toMap()
            nodeIdMapping[oldNid] = into.addNode(propsMap)
        }
        return nodeIdMapping to filteredNodeIds
    }

    private fun importEdges(
        dbManager: DB,
        into: IStorage,
        nodeIdMapping: Map<Int, Int>,
        filteredNodeIds: Set<Int>,
    ) {
        val edgesList = dbManager.indexTreeList("edges", mapValSerializer).open()
        edgesList.forEach { props ->
            val oldSrc = (props!!.remove(EDGE_SRC_KEY) as? IntVal)?.core?.toInt() ?: return@forEach
            val oldDst = (props.remove(EDGE_DST_KEY) as? IntVal)?.core?.toInt() ?: return@forEach
            val tag = (props.remove(EDGE_TAG_KEY) as? StrVal)?.core ?: return@forEach
            if (oldSrc in filteredNodeIds || oldDst in filteredNodeIds) return@forEach
            val src = nodeIdMapping[oldSrc] ?: error("Unknown node ID: $oldSrc")
            val dst = nodeIdMapping[oldDst] ?: error("Unknown node ID: $oldDst")
            val propsMap: Map<String, IValue> = props.core.toMap()
            into.addEdge(src, dst, tag, propsMap)
        }
    }

    private fun importMeta(
        dbManager: DB,
        into: IStorage,
    ) {
        if (!dbManager.exists("meta")) return
        val metaList = dbManager.indexTreeList("meta", mapValSerializer).open()
        metaList.forEach { metaVal -> metaVal!!.core.forEach { (name, value) -> into.setMeta(name, value) } }
    }
}
