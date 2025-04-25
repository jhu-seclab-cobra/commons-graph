plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "commons-graph"
include(
    "graph",
    "modules:extension-jgrapht",
    "modules:extension-mapdb",
    "modules:extension-neo4j",
)
