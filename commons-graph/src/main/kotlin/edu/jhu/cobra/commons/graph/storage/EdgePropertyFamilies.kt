package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IValue

/** Immutable schema mapping: a member and its anchor share one physical storage layer. */
public fun interface EdgePropertyFamilies {
    /** The anchor for [name] on [tag], including the anchor itself; null for a nonfamily property. */
    public fun anchor(tag: String, name: String): String?

    public companion object {
        /** The default schema declares no property families. */
        public val NONE: EdgePropertyFamilies = EdgePropertyFamilies { _, _ -> null }
    }
}

/** Layer-selected edge points and explicit ACTIVE presence, including null edits. */
public interface LayeredEdgeReads {
    public val propertyFamilies: EdgePropertyFamilies

    /** Whether the writable layer explicitly holds [name]; an explicit null still returns true. */
    public fun hasActiveEdgeProperty(id: Int, name: String): Boolean

    /** One property in the requested physical layer, without collecting its row. */
    public fun getEdgeProperty(id: Int, name: String, layer: StorageLayer): IValue?
}
