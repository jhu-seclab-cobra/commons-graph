/*
 * Registered field and cell contracts through the public capability API.
 * - should admit once and expose independent compatibility content: immutable payload sharing and alias isolation.
 * - should reject schema and codec errors before mutation: wrong keys, malformed legacy values and atomic validation.
 * - should select one physical family and expire its reader: first-active reset, later cells and frozen restoration.
 * - should preserve membership encounter order independently of other rows: dictionary allocation cannot reorder
 *   labels.
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class RegisteredPropertiesTest {
    @Test
    fun `should admit once and expose independent compatibility content`() {
        val codec = PayloadCodec()
        val key = PropertyKey("payload", Payload::class, codec)
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(key)))).use { access ->
            val source = mutableListOf("n7")
            val id = access.writer.write { write ->
                write.addNode().also { write.setNodeProperty(it, key, Payload(source)) }
            }
            source += "caller mutation"
            val captured = access.reader.read { it.getNodeProperty(id, key) }
            assertEquals(listOf("n7"), captured?.items)
            assertSame(captured, access.reader.read { it.getNodeProperty(id, key) })
            assertEquals(1, codec.captures)
            val legacy = access.compatibility.getNodeProperty(id, "payload") as ListVal
            legacy.add(StrVal("compatibility mutation"))
            assertEquals(listOf("n7"), access.reader.read { it.getNodeProperty(id, key) }?.items)
            access.writer.freeze()
            assertSame(captured, access.reader.read { it.getNodeProperty(id, key) })
            assertEquals(1, codec.captures)
        }
    }

    @Test
    fun `should reject schema and codec errors before mutation`() {
        val codec = PayloadCodec()
        val key = PropertyKey("payload", Payload::class, codec)
        assertFailsWith<IllegalArgumentException> {
            StorageSchema(listOf(key, PropertyKey("payload", Payload::class, PayloadCodec())))
        }
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(key)))).use { access ->
            val id = access.writer.write { it.addNode(mapOf("payload" to ListVal(StrVal("n7")))) }
            val wrong = PropertyKey("unregistered", Payload::class, codec)
            assertFailsWith<IllegalStateException> { access.reader.read { it.getNodeProperty(id, wrong) } }
            assertFailsWith<IllegalArgumentException> {
                access.compatibility.setNodeProperties(id, mapOf("before" to StrVal("bad"), "payload" to IntVal(1)))
            }
            assertNull(access.compatibility.getNodeProperty(id, "before"))
            assertEquals(listOf("n7"), access.reader.read { it.getNodeProperty(id, key) }?.items)
        }
    }

    @Test
    fun `should preserve membership encounter order independently of other rows`() {
        val key = IndexedPropertyKey("labels", "cell:", Payload::class, CellCodec())
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(key)))).use { access ->
            val edge = access.writer.write { write ->
                val node = write.addNode()
                val seed = write.addEdge(node, node, "seed")
                write.setEdgeCell(seed, key, "C", "L1", Payload(listOf("seed")))
                write.addEdge(node, node, "selected").also {
                    write.setEdgeCell(it, key, "C", "L2", Payload(listOf("n9")))
                    write.setEdgeCell(it, key, "C", "L1", Payload(listOf("n7")))
                }
            }
            assertEquals(listOf("L2", "L1"), access.reader.read { it.edgeCells(edge, key).members() })
            assertEquals(ListVal(StrVal("L2"), StrVal("L1")),
                access.compatibility.getEdgeProperty(edge, "labels"))
            access.writer.write { write ->
                write.setEdgeCell(edge, key, "C", "L2", null)
                write.setEdgeCell(edge, key, "C", "L2", Payload(listOf("n9")))
            }
            assertEquals(listOf("L1", "L2"), access.reader.read { it.edgeCells(edge, key).members() })
            access.compatibility.setEdgeProperties(edge, mapOf("labels" to ListVal(StrVal("L2"), StrVal("L1"))))
            access.writer.freeze()
            assertEquals(listOf("L2", "L1"), access.reader.read { it.edgeCells(edge, key).members() })
        }
    }

    @Test
    fun `should select one physical family and expire its reader`() {
        val key = IndexedPropertyKey("labels", "cell:", Payload::class, CellCodec())
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(key)))).use { access ->
            val node = access.writer.write { it.addNode() }
            val edge = access.writer.write { it.addEdge(node, node, "call") }
            access.writer.write { it.setEdgeCell(edge, key, "C", "L", Payload(listOf("n7"))) }
            access.writer.freeze()
            access.writer.write { it.setEdgeCell(edge, key, "C", "L2", Payload(listOf("n9"))) }
            lateinit var saved: CellReader<Payload>
            access.reader.read { read ->
                saved = read.edgeCells(edge, key)
                assertEquals(listOf("L2"), saved.members())
                assertNull(saved.lookup("C", "L"))
                assertEquals(listOf("n9"), saved.lookup("C", "L2")?.items)
                assertEquals(listOf("n7"), read.edgeCells(edge, key, StorageLayer.FROZEN).lookup("C", "L")?.items)
            }
            assertFailsWith<IllegalStateException> { saved.lookup("C", "L2") }
            access.writer.write { it.setEdgeCell(edge, key, "C", "L3", Payload(listOf("n10"))) }
            access.reader.read { read ->
                assertEquals(listOf("n9"), read.edgeCells(edge, key).lookup("C", "L2")?.items)
                assertEquals(listOf("n10"), read.edgeCells(edge, key).lookup("C", "L3")?.items)
            }
            access.writer.write { it.setEdgeCell(edge, key, "C", "L2", null) }
            access.reader.read { read ->
                val active = read.edgeCells(edge, key, StorageLayer.ACTIVE)
                assertTrue(active.hasEdit("C", "L2"))
                assertNull(active.lookup("C", "L2"))
                assertFalse(active.hasEdit("C", "absent"))
            }
            access.writer.clearActiveLayer()
            access.reader.read { read ->
                assertEquals(listOf("L"), read.edgeCells(edge, key).members())
                assertEquals(listOf("n7"), read.edgeCells(edge, key).lookup("C", "L")?.items)
                assertNull(read.edgeCells(edge, key).lookup("C", "L2"))
            }
        }
    }
}

private class Payload(val items: List<String>)

private open class PayloadCodec : PropertyCodec<Payload> {
    var captures = 0
    override fun capture(value: Payload): Payload {
        captures++
        return Payload(Collections.unmodifiableList(ArrayList(value.items)))
    }

    override fun decode(value: IValue): Payload {
        require(value is ListVal) { "Expected payload list" }
        return capture(Payload(value.map { require(it is StrVal); it.core }))
    }

    override fun encode(value: Payload): IValue = ListVal(value.items.map(::StrVal))
}

private class CellCodec : PayloadCodec(), IndexedPropertyCodec<Payload> {
    override fun cellName(first: String, second: String): String = "cell:${first.length}:$first${second.length}:$second"

    override fun cellAddress(name: String): Pair<String, String>? {
        if (!name.startsWith("cell:")) return null
        val tail = name.removePrefix("cell:")
        val firstSize = tail.substringBefore(':').toInt()
        val rest = tail.substringAfter(':')
        val first = rest.take(firstSize)
        val last = rest.drop(firstSize)
        val secondSize = last.substringBefore(':').toInt()
        val second = last.substringAfter(':')
        require(second.length == secondSize)
        return first to second
    }

    override fun decodeMembers(value: IValue): List<String> {
        require(value is ListVal)
        return value.map { require(it is StrVal); it.core }
    }

    override fun encodeMembers(members: List<String>): IValue = ListVal(members.map(::StrVal))
}
