package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.serializer.DftByteArraySerializerImpl
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.io.Serializable
import kotlin.reflect.KClass

/**
 * Serializer implementation for [T] objects using MapDB's serialization framework.
 * This class bridges between the custom [IValSerializer] and MapDB's [Serializer] interface,
 * enabling efficient serialization of graph data in MapDB storage.
 *
 * @property core The underlying serializer used to convert [T] objects to and from byte arrays.
 */
class MapDbValSerializer<T : IValue>(
    private val core: IValSerializer<ByteArray> = DftByteArraySerializerImpl,
    private var valueType: KClass<out T>? = null
) : Serializer<T>, Serializable {

    private val delegator = Serializer.BYTE_ARRAY


    /**
     * Indicates whether the serializer can be trusted for security-sensitive operations.
     *
     * @return `true` as this serializer is considered safe for all operations.
     */
    override fun isTrusted(): Boolean = true

    /**
     * Serializes an [T] object to a byte array using the underlying serializer.
     *
     * @param out The output stream to write the serialized data to.
     * @param value The [T] object to serialize.
     */
    override fun serialize(out: DataOutput2, value: T) =
        delegator.serialize(out, core.serialize(value = value))


    /**
     * Deserializes a byte array back into an [T] object.
     *
     * @param input The input stream containing the serialized data.
     * @param available The number of bytes available for reading.
     * @return The deserialized [T] object, or [NullVal] if no data is available.
     */
    @Suppress("UNCHECKED_CAST")
    override fun deserialize(input: DataInput2, available: Int): T {
        val deserialized = core.deserialize(delegator.deserialize(input, available))
        if (valueType?.isInstance(deserialized) != false) return deserialized as T
        else throw IllegalArgumentException("Deserialized value type does not match expected type")
    }
}