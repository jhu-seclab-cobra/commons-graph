package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.listVal
import edu.jhu.cobra.commons.value.strVal

data class NodeID(val name: String) : IEntity.ID {
    override val serialize: StrVal get() = name.strVal
    override fun toString() = name

    constructor(strVal: StrVal) : this(strVal.core)
}

data class EdgeID(val srcNid: NodeID, val dstNid: NodeID, val eType: String) : IEntity.ID {
    val name: String by lazy { "$srcNid-$eType-$dstNid" }
    override val serialize: ListVal by lazy { ListVal(srcNid.serialize, dstNid.serialize, eType.strVal) }
    override fun toString() = name

    constructor(value: ListVal) : this(
        value[0].toNid,
        value[1].toNid,
        value[2].core.toString()
    )

    constructor(value: String) : this(value.split("-").let { listOf(it[0], it[2], it[1]) }.listVal)
}

