package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.graph.EdgeID
import edu.jhu.cobra.commons.graph.NodeID
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.ListVal
import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import org.neo4j.graphdb.PropertyContainer
import org.neo4j.graphdb.Node as Neo4jNode
import org.neo4j.graphdb.Relationship as Neo4jEdge


private const val META_ID: String = "__meta_id__"

private fun PropertyContainer.setProp(name: String, value: IValue) =
    setProperty(name, DftByteArraySerializerImpl.serialize(value))

private inline fun <reified T : IValue> PropertyContainer.getProp(byName: String): T? =
    (getProperty(byName, null) as? ByteArray)?.let(DftByteArraySerializerImpl::deserialize) as? T

val PropertyContainer.keys: Collection<String>
    get() =
        this.propertyKeys.filter { it != META_ID }.distinct()

var Neo4jNode.storageID: NodeID
    get() = NodeID(getProp<StrVal>(META_ID)!!)
    set(value) = setProp(META_ID, value.serialize)

var Neo4jEdge.storageID: EdgeID
    get() = EdgeID(getProp<ListVal>(META_ID)!!)
    set(value) = setProp(META_ID, value.serialize)

operator fun PropertyContainer.set(byName: String, newVal: IValue) =
    if (byName == META_ID) throw InvalidPropNameException(byName, null) else this.setProp(byName, newVal)

operator fun PropertyContainer.get(byName: String): IValue? =
    if (byName == META_ID) throw InvalidPropNameException(byName, null) else getProp(byName)
