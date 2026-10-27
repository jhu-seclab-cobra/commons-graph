package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.IValue

/**
 * One writer's prospective change to a value: the value the writer read and the value it wants.
 * The null value of [IValue] stands for an absent key, so an absent key and a present value differ.
 *
 * @property before The writer's own before-image.
 * @property after The writer's after-image.
 */
public data class WriterChange<out T>(
    val before: T,
    val after: T,
) {
    /** Whether the writer changes the value at all. */
    val changed: Boolean get() = before != after
}

/** A writer's prospective change to one entity property; null is an absent key. */
public typealias PropertyChange = WriterChange<IValue?>

/**
 * Thrown when the prospective changes of several writers cannot merge: the whole set fails.
 *
 * @param detail What the writers changed in opposing ways.
 */
public class MergeConflictException(
    detail: String,
) : GraphException("Merge conflict: $detail")

/**
 * A pure merge law of one entity property. It reads no storage: it maps the current [base] value and the
 * writers' changes to the merged value, or throws [MergeConflictException]. The result is a function of the
 * collection of changes, never of their order.
 */
public fun interface PropertyMergeLaw {
    /**
     * Merges the [changes] of several writers on top of [base].
     *
     * @param base The current value; null when the key is absent.
     * @param changes Each writer's before-image and after-image.
     * @return The merged value; null when the merged key is absent.
     * @throws MergeConflictException when the changes oppose one another.
     */
    public fun merge(
        base: IValue?,
        changes: Collection<PropertyChange>,
    ): IValue?

    public companion object {
        /**
         * The default law: equal writes are idempotent and differing writes fail. A write also fails when
         * the base moved away from a writer's before-image to another value than the one written.
         */
        public val EQUAL_OR_FAIL: PropertyMergeLaw = PropertyMergeLaw(::mergeEqualOrFail)

        /** The law of a property that is never overwritten: an unset property takes one value, a set one keeps it. */
        public val IMMUTABLE: PropertyMergeLaw = PropertyMergeLaw(::mergeImmutable)
    }
}

private fun mergeEqualOrFail(
    base: IValue?,
    changes: Collection<PropertyChange>,
): IValue? {
    val changed = changes.filter { it.changed }
    if (changed.isEmpty()) return base
    val written = changed.mapTo(HashSet()) { it.after }
    if (written.size > 1) throw MergeConflictException("differing writes $written to one property")
    val value = written.single()
    if (value == base || changed.all { it.before == base }) return value
    throw MergeConflictException("write $value against base $base that moved from the writer's before-image")
}

private fun mergeImmutable(
    base: IValue?,
    changes: Collection<PropertyChange>,
): IValue? {
    if (base == null) return mergeEqualOrFail(base, changes)
    val overwrite = changes.firstOrNull { it.changed && it.after != base }
    if (overwrite != null) throw MergeConflictException("immutable property $base overwritten by ${overwrite.after}")
    return base
}
