package edu.jhu.cobra.commons.graph.storage

// Core in-memory Freeze transfers retain admitted payloads; external targets use the interchange boundary.
internal fun IStorage.nodeCapture(id: Int): CapturedRow =
    if (this is NativeStorageImpl) checkNotNull(capturedNodeRow(id))
    else getNodeProperties(id).mapValues { LegacyProperty(it.value) }

internal fun IStorage.edgeCapture(id: Int): CapturedRow =
    if (this is NativeStorageImpl) checkNotNull(capturedEdgeRow(id))
    else getEdgeProperties(id).mapValues { LegacyProperty(it.value) }

internal fun CapturedRow.compatibility(): Map<String, edu.jhu.cobra.commons.value.IValue> {
    val result = LinkedHashMap<String, edu.jhu.cobra.commons.value.IValue>()
    for ((name, value) in this) value.copyInto(name, result)
    return result
}

internal fun IStorage.addNodeCapture(properties: CapturedRow): Int =
    if (this is NativeStorageImpl) addCapturedNode(properties) else addNode(properties.compatibility())

internal fun IStorage.addEdgeCapture(src: Int, dst: Int, tag: String, properties: CapturedRow): Int =
    if (this is NativeStorageImpl) addCapturedEdge(src, dst, tag, properties)
    else addEdge(src, dst, tag, properties.compatibility())
