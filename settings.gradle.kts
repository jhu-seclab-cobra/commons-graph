plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "commons-graph"

includeBuild("extern/commons-value") {
    dependencySubstitution {
        substitute(module("com.github.jhu-seclab-cobra:commons-value")).using(project(":lib"))
    }
}

include(
    "graph",
    "modules:impl-jgrapht",
    "modules:impl-mapdb",
    "modules:impl-neo4j",
)
