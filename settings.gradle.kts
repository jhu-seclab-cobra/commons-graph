plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "commons-graph"

// Only include commons-value when building standalone (not as a composite build).
// When included by a parent project, the parent is responsible for providing commons-value.
if (gradle.parent == null) {
    includeBuild("extern/commons-value") {
        dependencySubstitution {
            substitute(module("com.github.jhu-seclab-cobra:commons-value")).using(project(":lib"))
        }
    }
}

include(
    "graph",
    "modules:impl-jgrapht",
    "modules:impl-mapdb",
    "modules:impl-neo4j",
)
