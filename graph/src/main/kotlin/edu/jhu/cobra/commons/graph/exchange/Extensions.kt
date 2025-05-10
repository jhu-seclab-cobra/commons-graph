package edu.jhu.cobra.commons.graph.exchange

import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.serializer.DftCharBufferSerializerImpl
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import edu.jhu.cobra.commons.value.serializer.asCharBuffer

/**
 * Represents a filter function for selecting entities in a graph based on their ID.
 * The filter function takes an [IEntity.ID] as a parameter and returns a Boolean value indicating whether the entity should be selected or not.
 */
typealias EntityFilter = (IEntity.ID) -> Boolean

const val CSV_DELIMITER = "\t"

object CsvSerializer : IValSerializer<String> {

    private val ESCAPE_MAP = setOf("\n" to "\\n", "\r" to "\\r", "\t" to "\\t", "\r\n" to "\\r\\n")
    private val UNESCAPE_MAP = setOf("\\n" to "\n", "\\r" to "\r", "\\t" to "\t", "\\r\\n" to "\r\n")

    override fun serialize(value: IValue): String {
        val rawString = DftCharBufferSerializerImpl.serialize(value).toString()
        return ESCAPE_MAP.fold(rawString) { o, (c, r) -> o.replace(c, r) }
    }

    override fun deserialize(material: String): IValue {
        if (material.isEmpty()) return null // The mini length of a serialized value UNSURE is 3
        val unescaped = UNESCAPE_MAP.fold(material) { o, (c, r) -> o.replace(c, r) }
        return DftCharBufferSerializerImpl.deserialize(unescaped.asCharBuffer())
    }
}