package edu.jhu.cobra.commons.graph.nio

// GML keys contain only letters and digits; fixed-width UTF-16 encoding preserves every Kotlin name.
private const val PROPERTY_ATTRIBUTE_PREFIX = "property"
private const val PROPERTY_NAME_RADIX = 16
private const val PROPERTY_NAME_UNIT_WIDTH = 4

internal fun encodePropertyName(name: String): String = buildString {
    append(PROPERTY_ATTRIBUTE_PREFIX)
    for (unit in name) {
        append(unit.code.toString(PROPERTY_NAME_RADIX).padStart(PROPERTY_NAME_UNIT_WIDTH, '0'))
    }
}

internal fun decodePropertyName(name: String): String {
    if (!name.startsWith(PROPERTY_ATTRIBUTE_PREFIX)) return name
    val encoded = name.removePrefix(PROPERTY_ATTRIBUTE_PREFIX)
    require(
        encoded.length % PROPERTY_NAME_UNIT_WIDTH == 0 &&
            encoded.all { it.digitToIntOrNull(PROPERTY_NAME_RADIX) != null },
    ) {
        "Invalid encoded GML property name: $name"
    }
    return encoded.chunked(PROPERTY_NAME_UNIT_WIDTH).joinToString("") {
        it.toInt(PROPERTY_NAME_RADIX).toChar().toString()
    }
}
