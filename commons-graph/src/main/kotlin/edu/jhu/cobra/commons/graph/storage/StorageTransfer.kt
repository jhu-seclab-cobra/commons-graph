package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

// The existing transfer owns traversal and ID mapping; its destination must not retain source-owned values.
internal class StorageTransfer(private val target: IStorage) : IStorage by target {
    override fun addNode(properties: Map<String, IValue>): Int = target.addNode(capture(properties))

    override fun addEdge(src: Int, dst: Int, tag: String, properties: Map<String, IValue>): Int =
        target.addEdge(src, dst, tag, capture(properties))

    override fun setMeta(name: String, value: IValue?): Unit = target.setMeta(name, value?.deepCopy())
}
