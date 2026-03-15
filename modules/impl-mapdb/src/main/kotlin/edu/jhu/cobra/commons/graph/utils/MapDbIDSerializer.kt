package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.io.Serializable

class MapDbIDSerializer :
    Serializer<String>,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    private val delegator = Serializer.BYTE_ARRAY
    private val core: IValSerializer<ByteArray> = DftByteArraySerializerImpl

    override fun isTrusted(): Boolean = delegator.isTrusted

    override fun serialize(
        out: DataOutput2,
        value: String,
    ) {
        delegator.serialize(out, core.serialize(value = StrVal(value)))
    }

    override fun deserialize(
        input: DataInput2,
        available: Int,
    ): String {
        val deserialized = core.deserialize(delegator.deserialize(input, available))
        return when (deserialized) {
            is StrVal -> deserialized.core
            else -> throw IllegalArgumentException("Cannot deserialize ${deserialized::class} to String")
        }
    }
}
