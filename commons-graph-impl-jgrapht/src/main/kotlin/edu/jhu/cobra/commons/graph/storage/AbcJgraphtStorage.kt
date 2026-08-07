package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.graph.EntityNotExistException
import edu.jhu.cobra.commons.value.IValue
import org.jgrapht.Graph
import org.jgrapht.graph.DirectedPseudograph

/**
 * Shared [IStorage] engine backed by the JGraphT library.
 *
 * Internally uses JGraphT String-based vertices/edges, with a bidirectional mapping layer
 * between external Int IDs and internal JGraphT String IDs. Property maps are returned as
 * snapshot copies, never live views.
 *
 * Subclasses supply the concurrency guards: [JgraphtStorageImpl] passes actions through
 * unguarded; [JgraphtConcurStorageImpl] wraps them in a read-write lock.
 */
@Suppress("TooManyFunctions")
public abstract class AbcJgraphtStorage protected constructor() : IStorage {
    private var nodeCounter: Int = 0
    private var edgeCounter: Int = 0

    // Bidirectional mapping: external Int ID <-> JGraphT internal String vertex/edge
    private val intToVertex = HashMap<Int, String>()
    private val vertexToInt = HashMap<String, Int>()
    private val intToEdge = HashMap<Int, String>()
    private val edgeToInt = HashMap<String, Int>()

    private val nodeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeProperties: MutableMap<Int, MutableMap<String, IValue>> = linkedMapOf()
    private val edgeTagMap: MutableMap<Int, String> = hashMapOf()
    private val metaProperties: MutableMap<String, IValue> = mutableMapOf()
    private val jgtGraph: Graph<String, String> = DirectedPseudograph(String::class.java)

    /** Runs [action] under this implementation's read guard. */
    protected abstract fun <R> readGuarded(action: () -> R): R

    /** Runs [action] under this implementation's write guard. */
    protected abstract fun <R> writeGuarded(action: () -> R): R

    override val nodeIDs: Set<Int>
        get() = readGuarded { nodeProperties.keys.toSet() }

    override val edgeIDs: Set<Int>
        get() = readGuarded { edgeProperties.keys.toSet() }

    override fun containsNode(id: Int): Boolean = readGuarded { id in nodeProperties }

    override fun containsEdge(id: Int): Boolean = readGuarded { id in edgeProperties }

    override fun addNode(properties: Map<String, IValue>): Int =
        writeGuarded {
            val id = nodeCounter++
            val vertex = "v$id"
            jgtGraph.addVertex(vertex)
            intToVertex[id] = vertex
            vertexToInt[vertex] = id
            nodeProperties[id] = properties.toMutableMap()
            id
        }

    override fun addEdge(
        src: Int,
        dst: Int,
        tag: String,
        properties: Map<String, IValue>,
    ): Int =
        writeGuarded {
            if (src !in nodeProperties) throw EntityNotExistException(src)
            if (dst !in nodeProperties) throw EntityNotExistException(dst)
            val id = edgeCounter++
            val edgeStr = "e$id"
            val srcVertex = intToVertex.getValue(src)
            val dstVertex = intToVertex.getValue(dst)
            jgtGraph.addEdge(srcVertex, dstVertex, edgeStr)
            intToEdge[id] = edgeStr
            edgeToInt[edgeStr] = id
            edgeTagMap[id] = tag
            edgeProperties[id] = properties.toMutableMap()
            id
        }

    override fun getNodeProperties(id: Int): Map<String, IValue> =
        readGuarded {
            val props = nodeProperties[id] ?: throw EntityNotExistException(id)
            HashMap(props)
        }

    override fun getEdgeProperties(id: Int): Map<String, IValue> =
        readGuarded {
            val props = edgeProperties[id] ?: throw EntityNotExistException(id)
            HashMap(props)
        }

    override fun getNodeProperty(
        id: Int,
        name: String,
    ): IValue? =
        readGuarded {
            val props = nodeProperties[id] ?: throw EntityNotExistException(id)
            props[name]
        }

    override fun getEdgeProperty(
        id: Int,
        name: String,
    ): IValue? =
        readGuarded {
            val props = edgeProperties[id] ?: throw EntityNotExistException(id)
            props[name]
        }

    override fun setNodeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        writeGuarded {
            val container = nodeProperties[id] ?: throw EntityNotExistException(id)
            properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
        }

    override fun setEdgeProperties(
        id: Int,
        properties: Map<String, IValue?>,
    ): Unit =
        writeGuarded {
            val container = edgeProperties[id] ?: throw EntityNotExistException(id)
            properties.forEach { (k, v) -> if (v != null) container[k] = v else container.remove(k) }
        }

    override fun deleteNode(id: Int): Unit =
        writeGuarded {
            if (id !in nodeProperties) throw EntityNotExistException(id)
            val vertex = intToVertex.getValue(id)
            val incoming = jgtGraph.incomingEdgesOf(vertex).toSet()
            val outgoing = jgtGraph.outgoingEdgesOf(vertex).toSet()
            for (edgeStr in incoming + outgoing) {
                removeEdgeUnguarded(edgeToInt.getValue(edgeStr), edgeStr)
            }
            jgtGraph.removeVertex(vertex)
            intToVertex.remove(id)
            vertexToInt.remove(vertex)
            nodeProperties.remove(id)
        }

    override fun deleteEdge(id: Int): Unit =
        writeGuarded {
            if (id !in edgeProperties) throw EntityNotExistException(id)
            removeEdgeUnguarded(id, intToEdge.getValue(id))
        }

    private fun removeEdgeUnguarded(
        edgeId: Int,
        edgeStr: String,
    ) {
        jgtGraph.removeEdge(edgeStr)
        intToEdge.remove(edgeId)
        edgeToInt.remove(edgeStr)
        edgeProperties.remove(edgeId)
        edgeTagMap.remove(edgeId)
    }

    override fun getEdgeStructure(id: Int): IStorage.EdgeStructure = readGuarded { edgeStructureUnguarded(id) }

    private fun edgeStructureUnguarded(id: Int): IStorage.EdgeStructure {
        if (id !in edgeProperties) throw EntityNotExistException(id)
        val edgeStr = intToEdge.getValue(id)
        val src = vertexToInt.getValue(jgtGraph.getEdgeSource(edgeStr))
        val dst = vertexToInt.getValue(jgtGraph.getEdgeTarget(edgeStr))
        val tag = edgeTagMap[id] ?: throw EntityNotExistException(id)
        return IStorage.EdgeStructure(src, dst, tag)
    }

    override fun getIncomingEdges(id: Int): Set<Int> =
        readGuarded {
            if (id !in nodeProperties) throw EntityNotExistException(id)
            jgtGraph.incomingEdgesOf(intToVertex.getValue(id)).mapTo(HashSet()) { edgeToInt.getValue(it) }
        }

    override fun getOutgoingEdges(id: Int): Set<Int> =
        readGuarded {
            if (id !in nodeProperties) throw EntityNotExistException(id)
            jgtGraph.outgoingEdgesOf(intToVertex.getValue(id)).mapTo(HashSet()) { edgeToInt.getValue(it) }
        }

    override val metaNames: Set<String>
        get() = readGuarded { metaProperties.keys.toSet() }

    override fun getMeta(name: String): IValue? = readGuarded { metaProperties[name] }

    override fun setMeta(
        name: String,
        value: IValue?,
    ): Unit =
        writeGuarded {
            if (value == null) metaProperties.remove(name) else metaProperties[name] = value
        }

    override fun flush() {
        // No buffered writes; mutations are applied immediately.
    }

    override fun clear(): Unit =
        writeGuarded {
            jgtGraph.removeAllEdges(jgtGraph.edgeSet().toSet())
            jgtGraph.removeAllVertices(jgtGraph.vertexSet().toSet())
            intToVertex.clear()
            vertexToInt.clear()
            intToEdge.clear()
            edgeToInt.clear()
            edgeProperties.clear()
            edgeTagMap.clear()
            nodeProperties.clear()
            metaProperties.clear()
            nodeCounter = 0
            edgeCounter = 0
        }

    override fun transferTo(target: IStorage): Map<Int, Int> =
        readGuarded {
            val idMap = HashMap<Int, Int>()
            for (nodeId in nodeProperties.keys) {
                idMap[nodeId] = target.addNode(nodeProperties.getValue(nodeId))
            }
            for (edgeId in edgeProperties.keys) {
                val structure = edgeStructureUnguarded(edgeId)
                val newSrc = idMap.getValue(structure.src)
                val newDst = idMap.getValue(structure.dst)
                target.addEdge(newSrc, newDst, structure.tag, edgeProperties.getValue(edgeId))
            }
            for (name in metaProperties.keys) {
                target.setMeta(name, metaProperties[name])
            }
            idMap
        }
}
