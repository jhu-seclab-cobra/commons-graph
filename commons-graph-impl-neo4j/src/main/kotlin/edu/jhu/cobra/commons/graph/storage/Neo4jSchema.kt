package edu.jhu.cobra.commons.graph.storage

import org.neo4j.graphdb.Label
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.Transaction

// Graph schema fixed by the storage format: every entity carries its storage ID in SID;
// every edge carries its tag in TAG. Nodes use one label, edges one relationship type.
internal const val SID = "__sid__"
internal const val TAG = "__tag__"
internal val NODE_LABEL: Label = Label.label("_N")
internal val EDGE_TYPE: RelationshipType = RelationshipType.withName("_E")

// Storage meta lives on a single dedicated node outside NODE_LABEL, so it
// persists across reopen without appearing in nodeIDs. The node is identified
// by the reserved marker property META_ID.
internal const val META_ID = "__meta_id__"
internal val META_LABEL: Label = Label.label("_M")

internal fun Transaction.findMetaNode(): Node? = findNode(META_LABEL, META_ID, 0L)

internal fun Transaction.findOrCreateMetaNode(): Node =
    findMetaNode() ?: createNode(META_LABEL).also { node -> node.setProperty(META_ID, 0L) }
