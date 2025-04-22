plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "commons-graph"
include(
    "graph",
    "modules:storage-jgrapht",
    "modules:storage-mapdb",
    "modules:storage-neo4j",
    "modules:exchange-mapdb",
)
