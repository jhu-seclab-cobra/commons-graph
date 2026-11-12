package edu.jhu.cobra.commons.graph.algorithm

import edu.jhu.cobra.commons.graph.NodeID

/**
 * The immediate dominator of every node reachable from [root] in [successors] (Lengauer and Tarjan 1979).
 *
 * @param root The node every path starts at.
 * @param successors The out-neighbours of each node; a node without an entry has none.
 * @return The parent of each reachable node but [root] in the dominator tree; map iteration order is unspecified.
 */
public fun immediateDominators(root: NodeID, successors: Map<NodeID, Collection<NodeID>>): Map<NodeID, NodeID> =
    LengauerTarjan(root, successors).run()

// One computation. Nodes are numbered in depth-first order, and every array is indexed by that number.
private class LengauerTarjan(root: NodeID, private val successors: Map<NodeID, Collection<NodeID>>) {

    private val order = depthFirstOrder(root)
    private val size = order.nodes.size
    private val predecessors = List(size) { mutableListOf<Int>() }
    private val semi = IntArray(size) { it }
    private val ancestor = IntArray(size) { NONE }
    private val label = IntArray(size) { it }
    private val idom = IntArray(size) { NONE }
    private val bucket = List(size) { mutableListOf<Int>() }

    fun run(): Map<NodeID, NodeID> {
        collectPredecessors()
        for (w in size - 1 downTo 1) {
            semi[w] = predecessors[w].minOf { semi[evaluate(it)] }.coerceAtMost(semi[w])
            bucket[semi[w]] += w
            val parent = order.parent[w]
            ancestor[w] = parent
            resolveBucket(parent)
        }
        for (w in 1 until size) {
            if (idom[w] != semi[w]) idom[w] = idom[idom[w]]
        }
        return (1 until size).associate { order.nodes[it] to order.nodes[idom[it]] }
    }

    private fun collectPredecessors() {
        for (v in 0 until size) {
            for (next in successors[order.nodes[v]].orEmpty()) predecessors[order.index.getValue(next)] += v
        }
    }

    // The nodes whose semidominator is [parent] get their immediate dominator or a pointer to it.
    private fun resolveBucket(parent: Int) {
        for (v in bucket[parent]) {
            val u = evaluate(v)
            idom[v] = if (semi[u] < semi[v]) u else parent
        }
        bucket[parent].clear()
    }

    // The node with the smallest semidominator on the compressed path from [v] to the root of its tree.
    private fun evaluate(v: Int): Int {
        if (ancestor[v] == NONE) return v
        compress(v)
        return label[v]
    }

    private fun compress(v: Int) {
        val path = ArrayDeque<Int>()
        var node = v
        while (ancestor[ancestor[node]] != NONE) {
            path.addLast(node)
            node = ancestor[node]
        }
        while (path.isNotEmpty()) {
            val current = path.removeLast()
            val above = ancestor[current]
            if (semi[label[above]] < semi[label[current]]) label[current] = label[above]
            ancestor[current] = ancestor[above]
        }
    }

    // The nodes reachable from [root] in preorder, the preorder number of each, and the depth-first parent of each.
    private fun depthFirstOrder(root: NodeID): DepthFirstOrder {
        val nodes = mutableListOf<NodeID>()
        val index = HashMap<NodeID, Int>()
        val parent = mutableListOf<Int>()
        val stack = ArrayDeque<Pair<NodeID, Int>>()
        stack.addLast(root to NONE)
        while (stack.isNotEmpty()) {
            val (node, from) = stack.removeLast()
            if (node in index) continue
            index[node] = nodes.size
            nodes += node
            parent += from
            val number = index.getValue(node)
            for (next in successors[node].orEmpty().reversed()) stack.addLast(next to number)
        }
        return DepthFirstOrder(nodes, index, parent)
    }

    private class DepthFirstOrder(val nodes: List<NodeID>, val index: Map<NodeID, Int>, val parent: List<Int>)

    private companion object {
        // Marks a missing ancestor or dominator; fixed by the use of array indices as node numbers.
        const val NONE = -1
    }
}
