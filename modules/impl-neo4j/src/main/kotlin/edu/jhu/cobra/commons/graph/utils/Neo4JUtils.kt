package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import org.neo4j.graphdb.Entity
import org.neo4j.graphdb.Node as Neo4jNode
import org.neo4j.graphdb.Relationship as Neo4jEdge

private const val META_ID: String = "__meta_id__"

private fun Entity.setProp(
    name: String,
    value: IValue,
) = setProperty(name, DftByteArraySerializerImpl.serialize(value))

private inline fun <reified T : IValue> Entity.getProp(byName: String): T? =
    (getProperty(byName, null) as? ByteArray)?.let(DftByteArraySerializerImpl::deserialize) as? T

val Entity.keys: Collection<String>
    get() =
        this.propertyKeys.filter { it != META_ID }.distinct()

var Neo4jNode.storageID: String
    get() = getProp<StrVal>(META_ID)!!.core
    set(value) = setProp(META_ID, StrVal(value))

var Neo4jEdge.storageID: String
    get() = getProp<StrVal>(META_ID)!!.core
    set(value) = setProp(META_ID, StrVal(value))

operator fun Entity.set(
    byName: String,
    newVal: IValue,
) = if (byName == META_ID) throw InvalidPropNameException(byName, null) else this.setProp(byName, newVal)

operator fun Entity.get(byName: String): IValue? =
    if (byName == META_ID) throw InvalidPropNameException(byName, null) else getProp(byName)
