package edu.jhu.cobra.commons.graph.entity

import edu.jhu.cobra.commons.graph.storage.IStorage
import edu.jhu.cobra.commons.graph.storage.toTypeArray
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal


data class NodeID(val name: String) : IEntity.ID {
    override val serialize: StrVal get() = name.strVal
    override fun toString() = name

    constructor(strVal: StrVal) : this(strVal.core)
}

abstract class AbcNode(private val storage: IStorage) : IEntity {

    interface Type : IEntity.Type

    abstract override val id: NodeID

    abstract override val type: Type

    /**
     * Checks if the provided target storage is the same as the storage associated with this entity.
     *
     * @param target The target storage to compare.
     */
    fun doUseStorage(target: IStorage) = target == storage

    override fun setProp(name: String, value: IValue?) =
        storage.setNodeProperties(id, name to value)

    override fun setProps(props: Map<String, IValue?>) =
        storage.setNodeProperties(id, *props.toTypeArray())

    override fun getProp(name: String): IValue? = storage.getNodeProperty(id, name)

    override fun getAllProps() = storage.getNodeProperties(id)

    override fun toString(): String = "{id=${id}, type=${this.type}}"

    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = if (other is AbcNode) this.id == other.id else super.equals(other)

}
