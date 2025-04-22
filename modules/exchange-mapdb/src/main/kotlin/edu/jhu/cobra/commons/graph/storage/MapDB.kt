package edu.jhu.cobra.commons.graph.utils

import edu.jhu.cobra.commons.value.IValue
import edu.jhu.cobra.commons.value.NullVal
import edu.jhu.cobra.commons.value.serializer.IValSerializer
import edu.jhu.cobra.commons.value.serializer.asByteArray
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.io.Serializable


class MapDbValSerializer(private val core: IValSerializer<ByteArray>) : Serializer<IValue>, Serializable {

    override fun isTrusted(): Boolean = true

    override fun serialize(out: DataOutput2, value: IValue) =
        out.write(core.serialize(value = value))

    override fun deserialize(input: DataInput2, available: Int): IValue =
        if (available == 0) NullVal else core.deserialize(input.asByteArray(available))
}