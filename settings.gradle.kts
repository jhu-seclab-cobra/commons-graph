plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "commons-graph"

// Only include commons-value when building standalone (not as a composite build).
// When included by a parent project, the parent is responsible for providing commons-value.
if (gradle.parent == null) {
    includeBuild("extern/commons-value") {
        dependencySubstitution {
            substitute(module("com.github.jhu-seclab-cobra:commons-value")).using(project(":jhu-seclab-cobra-commons-value"))
        }
    }
}

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
