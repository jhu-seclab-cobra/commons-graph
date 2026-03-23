package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.graph.InvalidPropNameException
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import org.neo4j.graphdb.Entity

private val RESERVED_PROPS = setOf("__meta_id__", "__sid__", "__tag__")

private fun Entity.setProp(
    name: String,
    value: IValue,
) = setProperty(name, DftByteArraySerializerImpl.serialize(value))

private inline fun <reified T : IValue> Entity.getProp(byName: String): T? =
    (getProperty(byName, null) as? ByteArray)?.let(DftByteArraySerializerImpl::deserialize) as? T

val Entity.keys: Collection<String>
    get() =
        this.propertyKeys.filter { it !in RESERVED_PROPS }.distinct()

operator fun Entity.set(
    byName: String,
    newVal: IValue,
) = if (byName in RESERVED_PROPS) throw InvalidPropNameException(byName, null) else this.setProp(byName, newVal)

operator fun Entity.get(byName: String): IValue? =
    if (byName in RESERVED_PROPS) throw InvalidPropNameException(byName, null) else getProp(byName)
