package edu.jhu.cobra.commons.graph.storage.utils

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import edu.jhu.cobra.commons.value.serializer.asByteArray
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.io.Serializable

/**
 * Serializer implementation for [IValue] objects using MapDB's serialization framework.
 * This class bridges between the custom [IValSerializer] and MapDB's [Serializer] interface,
 * enabling efficient serialization of graph data in MapDB storage.
 *
 * @property core The underlying serializer used to convert [IValue] objects to and from byte arrays.
 */
class MapDbValSerializer(private val core: IValSerializer<ByteArray>) : Serializer<IValue>, Serializable {

    /**
     * Indicates whether the serializer can be trusted for security-sensitive operations.
     *
     * @return `true` as this serializer is considered safe for all operations.
     */
    override fun isTrusted(): Boolean = true

    /**
     * Serializes an [IValue] object to a byte array using the underlying serializer.
     *
     * @param out The output stream to write the serialized data to.
     * @param value The [IValue] object to serialize.
     */
    override fun serialize(out: DataOutput2, value: IValue) =
        out.write(core.serialize(value = value))

    /**
     * Deserializes a byte array back into an [IValue] object.
     *
     * @param input The input stream containing the serialized data.
     * @param available The number of bytes available for reading.
     * @return The deserialized [IValue] object, or [NullVal] if no data is available.
     */
    override fun deserialize(input: DataInput2, available: Int): IValue =
        if (available == 0) NullVal else core.deserialize(input.asByteArray(available))
}