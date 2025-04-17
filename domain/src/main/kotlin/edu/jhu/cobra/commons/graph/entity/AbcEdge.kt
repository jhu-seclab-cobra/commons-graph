package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.toTypeArray
import edu.jhu.cobra.commons.value.IValue

abstract class AbcEdge(private val storage: IStorage) : IEntity {

    interface Type : IEntity.Type

    abstract override val id: EdgeID
    val srcNid: NodeID get() = id.srcNid
    val dstNid: NodeID get() = id.dstNid
    val eType: String get() = id.eType

    override fun setProp(name: String, value: IValue?) =
        storage.setEdgeProperties(id, name to value!!)

    override fun setProps(props: Map<String, IValue?>) =
        storage.setEdgeProperties(id, *props.toTypeArray())

    override fun getProp(name: String): IValue? = storage.getEdgeProperty(id, name)

    override fun getAllProps(): Map<String, IValue> = storage.getEdgeProperties(id)

    override fun toString(): String = "{${id}, ${this.type}}"

    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcEdge) this.id == other.id else super.equals(other)

}