package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/** One immutable physical-layer family declaration. */
public data class EdgePropertyFamilies(
    public val edgeTag: String,
    public val memberPrefix: String,
    public val anchorName: String,
) {
    public companion object {
        /** An unbound storage declares no property family. */
        public val NONE: EdgePropertyFamilies? = null
    }
}

/** The anchor for a declared member (including the anchor itself), or null for an ordinary property. */
public fun EdgePropertyFamilies?.anchor(tag: String, name: String): String? {
    if (this == null || tag != edgeTag) return null
    return if (name == anchorName || name.startsWith(memberPrefix)) anchorName else null
}

/** Layer-selected edge points and explicit ACTIVE presence, including null edits. */
public interface LayeredEdgeReads {
    public val propertyFamilies: EdgePropertyFamilies?

    /** Whether the writable layer explicitly holds [name]; an explicit null still returns true. */
    public fun hasActiveEdgeProperty(id: Int, name: String): Boolean

    /** One property in the requested physical layer, without collecting its row. */
    public fun getEdgeProperty(id: Int, name: String, layer: StorageLayer): IValue?
}
