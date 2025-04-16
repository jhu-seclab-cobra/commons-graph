plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "commons-graph"
include(
    "core",
    "storages",
    "exchanges",
)
