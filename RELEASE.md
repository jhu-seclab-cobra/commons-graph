## Version 0.1.0 (Initial Release)
[![codecov](https://codecov.io/gh/jhu-seclab-cobra/commons-graph/branch/main/graph/badge.svg)](https://codecov.io/gh/jhu-seclab-cobra/commons-graph)
[![Kotlin JVM](https://img.shields.io/badge/Kotlin%20JVM-1.8%2B-blue?logo=kotlin)](https://kotlinlang.org/)
[![Release](https://img.shields.io/github/v/release/jhu-seclab-cobra/commons-graph?include_prereleases)](https://github.com/jhu-seclab-cobra/commons-graph/releases)
[![last commit](https://img.shields.io/github/last-commit/jhu-seclab-cobra/commons-graph)](https://github.com/jhu-seclab-cobra/commons-graph/commits/main)
[![JitPack](https://jitpack.io/v/jhu-seclab-cobra/commons-graph.svg)](https://jitpack.io/#jhu-seclab-cobra/commons-graph)
[![License](https://img.shields.io/github/license/jhu-seclab-cobra/commons-graph)](./LICENSE)

### Features

- Unified, extensible graph API
- Multiple storage backends: In-memory, MapDB, JGraphT, Neo4j
- Type-safe, persistent entity property management
- Thread-safe and concurrent graph operations
- Custom serialization for MapDB
- CSV, MapDB, and GML import/export utilities
- Comprehensive test suite for correctness and concurrency

### System Requirements

- Java 8 or higher
- (Optional) MapDB 3.x for persistent storage
- (Optional) Neo4j server for Neo4j backend

### Installation

Add to your `build.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.jhu-seclab-cobra.commons-graph:graph:0.1.0")
    // For MapDB backend:
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-extension-mapdb:0.1.0")
    // For JGraphT backend:
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-extension-jgrapht:0.1.0")
    // For Neo4j backend:
    implementation("com.github.jhu-seclab-cobra.commons-graph:modules-extension-neo4j:0.1.0")
}
```

### Configuration Options

- Choose the appropriate storage backend by selecting the corresponding dependency and instantiating the correct
  `IStorage` implementation in your code.
- For Neo4j, configure the connection string, username, and password as required by your environment.

### Known Issues

- Neo4j backend requires a running Neo4j server and correct credentials.
- MapDB backend is not thread-safe by default; use the concurrent variant for multi-threaded scenarios.
- JGraphT backend is in-memory only and not suitable for persistent storage.
- API and module structure may change before 1.0.0 stable release.

### License

[GNU General Public License v2.0](./LICENSE)
