plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "commons-graph"
include(
    "graph",
    "modules:impl-jgrapht",
    "modules:impl-mapdb",
    "modules:impl-neo4j",
    "modules:trait-lattice",
    "modules:trait-group",
    "modules:trait-delta",
)
