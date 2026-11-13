/*
 * Measured row and cell work at their authoritative maps, with bounded fixtures.
 * - should keep selected row probes independent of unrelated names: fixed two-property row, 1/100/10000 other names.
 * - should retain selected row cost without copying typed payloads: selected row growth and explicit full snapshots.
 * - should update a selected cell without copying its directory: directory growth and explicit full snapshots.
 */
package edu.jhu.cobra.commons.graph.storage

import com.sun.management.ThreadMXBean
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import java.lang.management.ManagementFactory
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class RegisteredPropertiesGrowthTest {
    @Test
    fun `should keep selected row probes independent of unrelated names`() {
        for (size in listOf(1, 100, 10000)) {
            val table = RowMap()
            val rows = RowProperties(table)
            val codec = CountedCodec()
            val key = PropertyKey("selected", Strings::class, codec)
            val payload = key.capture(Strings(listOf("n7", "n7")))
            rows.setCaptured(7, mapOf("other" to LegacyProperty(StrVal("other")), key.name to payload))
            repeat(size) { rows.set(it + 100, mapOf("name_$it" to StrVal("unrelated"))) }
            table.probes = 0
            val result = measured("unrelatedNames", size) {
                repeat(100) { assertSame(payload.value, key.value(rows.capturedProperty(7, key.name))) }
            }
            assertEquals(100, table.probes)
            assertEquals(0, codec.encodes)
            assertEquals(0, codec.decodes)
            println("$result selectedRowProbes=${table.probes} encodedOccurrences=0 decodedOccurrences=0")
        }
    }

    @Test
    fun `should retain selected row cost without copying typed payloads`() {
        for (size in listOf(1, 100, 10000)) {
            val table = RowMap()
            val rows = RowProperties(table)
            val codec = CountedCodec()
            val key = PropertyKey("selected", Strings::class, codec)
            val payload = key.capture(Strings(listOf("n7", "n7")))
            val fields = LinkedHashMap<String, CapturedProperty>()
            repeat(size - 1) { fields["unrelated_$it"] = LegacyProperty(StrVal("other")) }
            fields[key.name] = payload
            rows.setCaptured(7, fields)
            table.probes = 0
            val result = measured("selectedRow", size) {
                repeat(100) { assertSame(payload.value, key.value(rows.capturedProperty(7, key.name))) }
            }
            assertEquals(100, table.probes)
            assertEquals(0, codec.encodes)
            assertEquals(0, codec.decodes)
            val snapshot = rows.collect(7)
            assertEquals(size, snapshot.size)
            assertEquals(1, codec.encodes)
            println("$result selectedRowProbes=100 typedReadEncodedOccurrences=0 typedReadDecodedOccurrences=0 " +
                "snapshotProperties=${snapshot.size} snapshotEncodedOccurrences=${codec.encodedItems}")
        }
    }

    @Test
    fun `should update a selected cell without copying its directory`() {
        for (size in listOf(1, 100, 10000)) {
            val codec = CountedCodec()
            val key = IndexedPropertyKey("labels", "cell:", Strings::class, codec)
            val directory = CellMap<Strings>()
            val family = CapturedFamily(key, PropertySymbols(), directory)
            repeat(size) { family.set("C$it", "L$it", key.capture(Strings(listOf("n$it"))).value, true) }
            directory.reset()
            val capturesBefore = codec.captures
            val result = measured("cellDirectory", size) {
                repeat(100) {
                    assertEquals(listOf("n0"), family.lookup("C0", "L0")?.items)
                    family.set("C0", "L0", key.capture(Strings(listOf("n0"))).value, true)
                }
            }
            assertEquals(200, directory.gets)
            assertEquals(100, directory.puts)
            assertEquals(0, directory.visits)
            assertEquals(100, codec.captures - capturesBefore)
            assertEquals(0, codec.encodes)
            val snapshot = LinkedHashMap<String, IValue>()
            family.copyInto(key.name, snapshot)
            assertEquals(size, directory.visits)
            assertEquals(size + 1, snapshot.size)
            println("$result cellDirectoryGets=200 cellDirectoryPuts=100 selectedDirectoryEntryVisits=0 " +
                "newPayloadCapturedOccurrences=100 oldPayloadRecaptures=0 " +
                "snapshotDirectoryEntryVisits=${directory.visits} snapshotEncodedOccurrences=${codec.encodedItems}")
        }
    }

    private fun measured(dimension: String, size: Int, action: () -> Unit): String {
        assertTrue(Runtime.getRuntime().maxMemory() <= 1024L * 1024 * 1024)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val allocatedBefore = bean.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        action()
        val elapsed = System.nanoTime() - started
        assertTrue(elapsed < 60_000_000_000L)
        val allocated = bean.getThreadAllocatedBytes(thread) - allocatedBefore
        val live = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        return "dimension=$dimension size=$size operations=100 allocatedBytes=$allocated elapsedNanos=$elapsed " +
            "jvmUsedHeapBytes=$live maxHeapBytes=${Runtime.getRuntime().maxMemory()}"
    }
}

private class RowMap : HashMap<Int, Array<Any?>>() {
    var probes = 0
    override fun get(key: Int): Array<Any?>? { probes++; return super.get(key) }
}

private class CellMap<T> : LinkedHashMap<Long, T?>() {
    var gets = 0
    var puts = 0
    var visits = 0
    override fun get(key: Long): T? { gets++; return super.get(key) }
    override fun put(key: Long, value: T?): T? { puts++; return super.put(key, value) }
    override val entries: MutableSet<MutableMap.MutableEntry<Long, T?>>
        get() {
            val source = super.entries
            return object : AbstractMutableSet<MutableMap.MutableEntry<Long, T?>>() {
                override val size: Int get() = source.size
                override fun add(element: MutableMap.MutableEntry<Long, T?>): Boolean = error("Not used")
                override fun iterator(): MutableIterator<MutableMap.MutableEntry<Long, T?>> {
                    val iterator = source.iterator()
                    return object : MutableIterator<MutableMap.MutableEntry<Long, T?>> {
                        override fun hasNext(): Boolean = iterator.hasNext()
                        override fun next(): MutableMap.MutableEntry<Long, T?> { visits++; return iterator.next() }
                        override fun remove() = iterator.remove()
                    }
                }
            }
        }
    fun reset() { gets = 0; puts = 0; visits = 0 }
}

private class Strings(val items: List<String>)

private class CountedCodec : IndexedPropertyCodec<Strings> {
    var captures = 0
    var encodes = 0
    var decodes = 0
    var encodedItems = 0
    override fun capture(value: Strings): Strings {
        captures++
        return Strings(Collections.unmodifiableList(ArrayList(value.items)))
    }
    override fun decode(value: IValue): Strings {
        decodes++
        require(value is ListVal)
        return capture(Strings(value.map { require(it is StrVal); it.core }))
    }
    override fun encode(value: Strings): IValue {
        encodes++
        encodedItems += value.items.size
        return ListVal(value.items.map(::StrVal))
    }
    override fun cellName(first: String, second: String): String = "cell:$first:$second"
    override fun cellAddress(name: String): Pair<String, String>? =
        name.takeIf { it.startsWith("cell:") }?.removePrefix("cell:")?.split(':')?.let {
            require(it.size == 2); it[0] to it[1]
        }
    override fun decodeMembers(value: IValue): List<String> {
        require(value is ListVal)
        return value.map { require(it is StrVal); it.core }
    }
    override fun encodeMembers(members: List<String>): IValue = ListVal(members.map(::StrVal))
}
