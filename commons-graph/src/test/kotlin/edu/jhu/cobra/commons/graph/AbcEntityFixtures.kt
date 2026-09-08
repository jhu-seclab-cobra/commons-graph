package edu.jhu.cobra.commons.graph

import edu.jhu.cobra.commons.value.StrVal
import edu.jhu.cobra.commons.value.strVal

// Shared fixtures for AbcEntity tests: node types exercising the EntityProperty and EntityType delegates.

internal class PropNode : AbcNode() {
    override val type: AbcNode.Type =
        object : AbcNode.Type {
            override val name = "PropNode"
        }
    var label: StrVal by EntityProperty(default = "default".strVal)
    var custom: StrVal by EntityProperty("customKey", default = "d".strVal)
    var opt: StrVal? by EntityProperty()
}

internal enum class Kind : IEntity.Type {
    SOURCE,
    SINK,
}

internal class TypeNode : AbcNode() {
    override val type: AbcNode.Type =
        object : AbcNode.Type {
            override val name = "TypeNode"
        }
    var kind: Kind by EntityType(default = Kind.SOURCE)
    var namedKind: Kind by EntityType("myKind", default = Kind.SOURCE)
}
