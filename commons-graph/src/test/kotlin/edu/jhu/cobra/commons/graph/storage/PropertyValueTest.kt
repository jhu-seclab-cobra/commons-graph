/*
 * Immutable content transport for caller-owned buffers.
 * - should retain typed content and protect compatibility aliases: one captured payload across named reads/writes.
 * - should publish membership and a selected cell together: complete family content through Freeze and revert.
 */
package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

internal class PropertyValueTest {
    @Test
    fun `should retain typed content and protect compatibility aliases`() {
        val key = PropertyKey("text", String::class, TextCodec)
        val value = PropertyValue.capture(key, "fixed")
        assertEquals("fixed", value.get(key))
        val source = ListVal(StrVal("fixed"))
        val legacy = PropertyValue.legacy(source)
        source.clear()
        (legacy.snapshot() as ListVal).clear()
        assertEquals(ListVal(StrVal("fixed")), legacy.snapshot())
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(key)))).use { access ->
            val node = access.writer.write { it.addNode() }
            access.writer.write { it.setNodeContents(node, mapOf(key.name to value)) }
            assertSame(value, access.reader.read { it.getNodeContent(node, key.name) })
            assertEquals("fixed", access.reader.read { it.getNodeContents(node).getValue(key.name).get(key) })
            val wrong = PropertyKey("other", String::class, TextCodec)
            assertFailsWith<IllegalStateException> { value.get(wrong) }
        }
    }

    @Test
    fun `should distinguish required primitive zero from absence`() {
        val integer = PropertyKey("int", Int::class, object : PropertyCodec<Int> {
            override fun capture(value: Int): Int = value
            override fun decode(value: IValue): Int = (value as IntVal).core.toInt()
            override fun encode(value: Int): IValue = IntVal(value.toLong())
        })
        val long = PropertyKey("long", Long::class, object : PropertyCodec<Long> {
            override fun capture(value: Long): Long = value
            override fun decode(value: IValue): Long = (value as IntVal).core
            override fun encode(value: Long): IValue = IntVal(value)
        })
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(integer, long)))).use { access ->
            val node = access.writer.write { it.addNode() }
            val edge = access.writer.write { it.addEdge(node, node, "plain") }
            assertFailsWith<IllegalStateException> { access.reader.read { it.getNodeInt(node, integer) } }
            assertFailsWith<IllegalStateException> { access.reader.read { it.getEdgeLong(edge, long) } }
            access.writer.write {
                it.setNodeProperty(node, integer, 0)
                it.setNodeProperty(node, long, Long.MAX_VALUE)
                it.setEdgeProperty(edge, integer, Int.MIN_VALUE)
                it.setEdgeProperty(edge, long, 0L)
            }
            access.reader.read {
                assertEquals(0, it.getNodeInt(node, integer))
                assertEquals(Long.MAX_VALUE, it.getNodeLong(node, long))
                assertEquals(Int.MIN_VALUE, it.getEdgeInt(edge, integer))
                assertEquals(0L, it.getEdgeLong(edge, long))
            }
        }
    }

    @Test
    fun `should publish membership and a selected cell together`() {
        val key = IndexedPropertyKey("labels", "cell:", String::class, ContentCellCodec, "call")
        StorageAccess(LayeredStorageImpl(StorageSchema(listOf(key)))).use { access ->
            val node = access.writer.write { it.addNode() }
            val edge = access.writer.write { it.addEdge(node, node, "call") }
            fun write(label: String, payload: String) = access.writer.write {
                it.setEdgeContents(edge, mapOf("labels" to PropertyValue.membership(key, listOf(label)),
                    "cell:C:$label" to PropertyValue.capture(key, payload)))
            }
            write("L", "n7")
            access.writer.freeze()
            write("L2", "n9")
            val selected = access.reader.read { it.getEdgeContent(edge, "cell:C:L2") }
            assertEquals("n9", selected?.get(key))
            assertNull(access.reader.read { it.getEdgeContent(edge, "cell:C:L") })
            assertEquals(listOf("L2"), access.reader.read { it.getEdgeContent(edge, "labels")?.members(key) })
            val fixed = access.reader.read { checkNotNull(it.getEdgeContents(edge)) }
            write("L3", "n10")
            assertEquals("n9", fixed.getValue("cell:C:L2").get(key))
            access.writer.clearActiveLayer()
            assertEquals("n7", access.reader.read { it.getEdgeContent(edge, "cell:C:L")?.get(key) })
        }
    }
}

private object TextCodec : PropertyCodec<String> {
    override fun capture(value: String): String = value
    override fun decode(value: IValue): String { require(value is StrVal); return value.core }
    override fun encode(value: String): IValue = StrVal(value)
}

private object ContentCellCodec : IndexedPropertyCodec<String>, PropertyCodec<String> by TextCodec {
    override fun cellName(first: String, second: String): String = "cell:$first:$second"
    override fun cellAddress(name: String): Pair<String, String>? =
        name.takeIf { it.startsWith("cell:") }?.removePrefix("cell:")?.split(':')?.let { it[0] to it[1] }
    override fun decodeMembers(value: IValue): List<String> {
        require(value is ListVal); return value.map { require(it is StrVal); it.core }
    }
    override fun encodeMembers(members: List<String>): IValue = ListVal(members.map(::StrVal))
}
