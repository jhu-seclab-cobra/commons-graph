/*
 * Operation logging contract:
 * - `entity operations stay silent and whole operations emit once`: TRACE capture verifies
 *   silent node, edge and property operations, and one structured record per transfer or clear.
 */
package edu.jhu.cobra.commons.graph.storage

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import edu.jhu.cobra.commons.value.intVal
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class OperationLoggingTest {
    @Test
    fun `entity operations stay silent and whole operations emit once`() {
        val directory = java.nio.file.Files.createTempDirectory("operation-logging")
        val storages: List<IStorage> =
            listOf(
                Neo4jStorageImpl(directory.resolve("plain")),
                Neo4jConcurStorageImpl(directory.resolve("concurrent")),
            )
        try {
            for (storage in storages) {
                capture { events ->
                    val node = storage.addNode(mapOf("value" to 1.intVal))
                    val edge = storage.addEdge(node, node, "tag")
                    repeat(100) {
                        storage.getNodeProperty(node, "value")
                        storage.setNodeProperties(node, mapOf("value" to it.intVal))
                        storage.getEdgeStructure(edge)
                        storage.getOutgoingEdges(node)
                        storage.setEdgeProperties(edge, mapOf("value" to it.intVal))
                        storage.getEdgeProperty(edge, "value")
                    }
                    storage.getNodeProperties(node)
                    storage.getEdgeProperties(edge)
                    storage.nodeIDs
                    storage.edgeIDs
                    storage.setMeta("value", 1.intVal)
                    storage.getMeta("value")
                    assertEquals(0, events.size)
                    assertFailsWith<IllegalArgumentException> { storage.transferTo(storage) }
                    assertEquals(0, events.size)
                    val target = NativeStorageImpl()
                    storage.transferTo(target)
                    assertRecord(events.single(), "transferTo", target)
                    assertEquals(1, target.nodeIDs.size)
                    assertEquals(1, target.edgeIDs.size)
                    events.clear()
                    storage.deleteEdge(edge)
                    storage.deleteNode(node)
                    assertEquals(0, events.size)
                    storage.addNode()
                    storage.clear()
                    assertRecord(events.single(), "clear", storage)
                    events.clear()
                    storage.clear()
                    assertRecord(events.single(), "clear", storage)
                }
            }
        } finally {
            storages.filterIsInstance<AutoCloseable>().forEach { it.close() }
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertRecord(event: ILoggingEvent, operation: String, storage: IStorage) {
        val fields = fields(event)
        assertEquals(Level.INFO, event.level)
        assertEquals("Storage operation completed", event.message)
        assertEquals(operation, fields["operation"])
        assertEquals(storage.javaClass.name, fields["implementation"])
        assertEquals(System.identityHashCode(storage), fields["storageId"])
        assertEquals("success", fields["outcome"])
        assertTrue((fields.getValue("durationNs") as Long) >= 0)
    }

    private fun fields(event: ILoggingEvent): Map<String, Any?> =
        event.keyValuePairs.associate { it.key to it.value }

    private fun capture(action: (MutableList<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("edu.jhu.cobra.commons.graph") as Logger
        val oldLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            action(appender.list)
        } finally {
            logger.detachAppender(appender)
            logger.level = oldLevel
            appender.stop()
        }
    }
}
