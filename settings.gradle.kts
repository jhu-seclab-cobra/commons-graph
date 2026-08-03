plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "commons-graph"

// No vendored checkouts. Standalone builds resolve commons-value from
// JitPack at the version in gradle/libs.versions.toml. Under a composite,
// the including root substitutes it with its own vendored build.

include(
    "jhu-seclab-cobra-commons-graph",
    "jhu-seclab-cobra-commons-graph-impl-jgrapht",
    "jhu-seclab-cobra-commons-graph-impl-mapdb",
    "jhu-seclab-cobra-commons-graph-impl-neo4j",
)
project(":jhu-seclab-cobra-commons-graph").projectDir = file("commons-graph")
project(":jhu-seclab-cobra-commons-graph-impl-jgrapht").projectDir = file("commons-graph-impl-jgrapht")
project(":jhu-seclab-cobra-commons-graph-impl-mapdb").projectDir = file("commons-graph-impl-mapdb")
project(":jhu-seclab-cobra-commons-graph-impl-neo4j").projectDir = file("commons-graph-impl-neo4j")
