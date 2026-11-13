package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/** A pair-addressed reader confined to its entering thread and guarded read scope. */
public class CellReader<T : Any> internal constructor(
    private val family: CapturedFamily<T>?,
    private val lifetime: StorageScope,
    private val fallback: CapturedFamily<T>? = null,
) {
    /** Returns the selected immutable payload; absent and deleted values return null. */
    public fun lookup(first: String, second: String): T? {
        lifetime.checkActive()
        if (family?.hasEdit(first, second) == true) return family.lookup(first, second)
        return if (family?.anchorWritten == true) null else fallback?.lookup(first, second)
    }

    /** Whether this physical family contains an explicit cell edit, a deletion included. */
    public fun hasEdit(first: String, second: String): Boolean {
        lifetime.checkActive()
        return family?.hasEdit(first, second) == true
    }

    /** Fixed membership identifiers in the stored primitive-ID order. */
    public fun members(): List<String> {
        lifetime.checkActive()
        return if (family?.anchorWritten == true) family.members() else fallback?.members().orEmpty()
    }
}

// The owner alone mutates an active directory. Frozen directories and selected payloads are never mutated.
internal class CapturedFamily<T : Any>(
    val key: IndexedPropertyKey<T>,
    private val symbols: PropertySymbols,
    private val cells: MutableMap<Long, T?> = LinkedHashMap(),
) : CapturedProperty {
    private val counts = HashMap<Int, Int>()
    private var membership = IntArray(0)
    var anchorWritten = false
        private set
    private var anchorDeleted = false

    fun lookup(first: String, second: String): T? {
        val pair = address(first, second) ?: return null
        return cells[pair]
    }

    fun hasEdit(first: String, second: String): Boolean = address(first, second)?.let(cells::containsKey) == true

    fun members(): List<String> = membership.map(symbols::text)

    fun setMembers(members: List<String>?) {
        anchorWritten = true
        anchorDeleted = members == null
        membership = members.orEmpty().map(symbols::intern).distinct().sorted().toIntArray()
    }

    fun set(first: String, second: String, value: T?, updateMembership: Boolean) {
        val firstId = symbols.intern(first)
        val secondId = symbols.intern(second)
        val pair = propertyPair(firstId, secondId)
        val before = cells[pair]
        cells[pair] = value
        if (before == null && value != null) counts[secondId] = (counts[secondId] ?: 0) + 1
        if (before != null && value == null) counts[secondId] = counts.getValue(secondId) - 1
        if (!updateMembership) return
        anchorWritten = true
        anchorDeleted = false
        val at = membership.binarySearch(secondId)
        if (value != null && at < 0) membership = (membership + secondId).sortedArray()
        if (value == null && counts[secondId] == 0 && at >= 0) {
            membership = membership.filter { it != secondId }.toIntArray()
        }
    }

    override fun compatibility(name: String): IValue? {
        if (name == key.name) {
            return if (anchorWritten && !anchorDeleted) key.indexedCodec.encodeMembers(members()) else null
        }
        val address = key.indexedCodec.cellAddress(name) ?: return null
        return lookup(address.first, address.second)?.let(key.codec::encode)
    }

    fun holds(name: String): Boolean =
        if (name == key.name) anchorWritten else key.indexedCodec.cellAddress(name)?.let {
            hasEdit(it.first, it.second)
        } == true

    override fun isPresent(name: String): Boolean =
        if (name == key.name) anchorWritten && !anchorDeleted else key.indexedCodec.cellAddress(name)?.let {
            lookup(it.first, it.second) != null
        } == true

    override fun copyInto(name: String, target: MutableMap<String, IValue>) {
        compatibility(key.name)?.let { target[key.name] = it }
        for ((pair, value) in cells) {
            if (value == null) continue
            val first = symbols.text((pair ushr Int.SIZE_BITS).toInt())
            val second = symbols.text(pair.toInt())
            target[key.indexedCodec.cellName(first, second)] = key.codec.encode(value)
        }
    }

    private fun address(first: String, second: String): Long? {
        val firstId = symbols.find(first) ?: return null
        val secondId = symbols.find(second) ?: return null
        return propertyPair(firstId, secondId)
    }

    // Whole-row snapshots and Freeze may copy a directory; selected reads and updates never call this fold.
    fun composedWith(frozen: CapturedFamily<T>?): CapturedFamily<T> {
        if (frozen == null || anchorWritten) return this
        val result = CapturedFamily(key, symbols)
        result.membership = frozen.membership
        result.anchorWritten = frozen.anchorWritten
        result.anchorDeleted = frozen.anchorDeleted
        result.cells.putAll(frozen.cells)
        result.counts.putAll(frozen.counts)
        for ((pair, value) in cells) {
            result.set(symbols.text((pair ushr Int.SIZE_BITS).toInt()), symbols.text(pair.toInt()), value, false)
        }
        return result
    }
}

@Suppress("UNCHECKED_CAST") // Schema identity and payload class establish the erased cell type.
internal fun <T : Any> IndexedPropertyKey<T>.family(property: CapturedProperty?): CapturedFamily<T>? {
    if (property == null) return null
    check(property is CapturedFamily<*> && agrees(property.key)) { "Wrong stored family for $name" }
    return property as CapturedFamily<T>
}

private fun <T : Any> CapturedFamily<T>.combine(frozen: CapturedProperty?): CapturedProperty =
    composedWith(key.family(frozen))

internal fun CapturedProperty.composeFamily(frozen: CapturedProperty?): CapturedProperty =
    if (this is CapturedFamily<*>) combine(frozen) else this
