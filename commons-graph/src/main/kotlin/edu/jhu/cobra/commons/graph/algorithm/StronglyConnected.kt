package edu.jhu.cobra.commons.graph.algorithm

/**
 * The strongly connected components of the directed graph [graph] (Tarjan 1972).
 *
 * @param graph The out-neighbours of each node; a neighbour without an entry has none.
 * @return The components in reverse topological order: a component precedes every distinct component that reaches it.
 */
public fun <K> tarjanScc(graph: Map<K, Collection<K>>): List<Set<K>> = TarjanSearch(graph).run()

// One search. A frame is a node whose neighbours are being walked.
private class TarjanSearch<K>(private val graph: Map<K, Collection<K>>) {

    private inner class Frame(val node: K, val neighbours: Iterator<K>)

    private val index = mutableMapOf<K, Int>()
    private val lowlink = mutableMapOf<K, Int>()
    private val onStack = mutableSetOf<K>()
    private val stack = ArrayDeque<K>()
    private val components = mutableListOf<Set<K>>()

    fun run(): List<Set<K>> {
        for (node in graph.keys) if (node !in index) visitFrom(node)
        return components
    }

    private fun visitFrom(start: K) {
        val frames = ArrayDeque<Frame>()
        frames.addLast(open(start))
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (frame.neighbours.hasNext()) walk(frame, frame.neighbours.next(), frames) else close(frame, frames)
        }
    }

    private fun walk(frame: Frame, next: K, frames: ArrayDeque<Frame>) {
        when {
            next !in index -> frames.addLast(open(next))
            next in onStack -> lowlink[frame.node] = minOf(lowlink.getValue(frame.node), index.getValue(next))
        }
    }

    private fun open(node: K): Frame {
        index[node] = index.size
        lowlink[node] = index.getValue(node)
        stack.addLast(node)
        onStack += node
        return Frame(node, graph[node].orEmpty().iterator())
    }

    // The node's neighbours are done: it roots a component when no neighbour reaches below it, and either way it
    // passes its lowlink to the node that opened it.
    private fun close(frame: Frame, frames: ArrayDeque<Frame>) {
        frames.removeLast()
        val node = frame.node
        if (lowlink.getValue(node) == index.getValue(node)) components += popComponent(node)
        val parent = frames.lastOrNull() ?: return
        lowlink[parent.node] = minOf(lowlink.getValue(parent.node), lowlink.getValue(node))
    }

    private fun popComponent(root: K): Set<K> {
        val component = mutableSetOf<K>()
        do {
            val member = stack.removeLast()
            onStack -= member
            component += member
        } while (member != root)
        return component
    }
}
