package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.graph.entity.IEntity
import edu.jhu.cobra.commons.graph.entity.toEntityID
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.io.Serializable

class MapDbIDSerializer<T : IEntity.ID> : Serializer<T>, Serializable {

    private val delegator = Serializer.BYTE_ARRAY
    private val core: IValSerializer<ByteArray> = DftByteArraySerializerImpl

    /**
     * Indicates whether the serializer can be trusted for security-sensitive operations.
     *
     * @return `true` as this serializer is considered safe for all operations.
     */
    override fun isTrusted(): Boolean = delegator.isTrusted

    /**
     * Serializes an [T] object to a byte array using the underlying serializer.
     *
     * @param out The output stream to write the serialized data to.
     * @param value The [T] object to serialize.
     */
    override fun serialize(out: DataOutput2, value: T) {
        delegator.serialize(out, core.serialize(value = value.serialize))
    }

    /**
     * Deserializes a byte array back into an [T] object.
     *
     * @param input The input stream containing the serialized data.
     * @param available The number of bytes available for reading.
     * @return The deserialized [T] object, or [NullVal] if no data is available.
     */
    override fun deserialize(input: DataInput2, available: Int): T {
        return core.deserialize(delegator.deserialize(input, available)).toEntityID()
    }
}
