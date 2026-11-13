# Storage Operation Logging

## Design Overview

The Storage implementations and file importers submit completion records through kotlin-logging.
The core module's internal `logCompletion` function in `StorageOperationLog.kt` constructs every
completion record. Each operation passes its file-level logger, operation, affected Storage and
start time to that function. The JGraphT, MapDB and Neo4j main compilations have friend access to
core's main compilation output so they can call this internal function. This access is limited
to those three backend modules; external consumers receive no public logging declaration.
The owner changes no Storage state or return value. The existing `RevertListener` interface remains the
callback mechanism; it does not depend on a separate observer library.

## Function Specifications

The storage implementations use kotlin-logging with an SLF4J backend selected by the consuming
application. The library installs no backend, output destination, or log-level setting.

Each successful `freeze`, `clearActiveLayer`, `clear`, `transferTo`, and importer `import` emits
exactly one INFO completion record when INFO is enabled, including an operation on empty Storage. `clearActiveLayer`
is the rollback operation. A layered `clear` emits only its `clear` record, not a second rollback
record. `transferTo` is the in-memory bulk load; CSV, GML and MapDB `import` are file bulk loads.

The operation emits after all state updates, lock or transaction completion, resource closure,
and synchronous revert callbacks return successfully. A propagated exception emits no success completion
record; the caller that handles it owns failure detail and its failed-unit summary.

Each record has message `Storage operation completed` and structured fields `operation`,
`implementation`, `storageId`, `outcome` (`success`), and `durationNs`. `storageId` is the process-local
identity hash of the affected Storage; `implementation` is its runtime class name. For `transferTo`
and `import`, the affected Storage is the destination. Duration uses the monotonic clock from
entry through successful completion before logging. Records contain no entity properties or file
contents. Constructing the fields requires no Storage query or collection traversal.

Reads, individual node and edge mutations, adjacency selection, property access, and Value
operations perform no logging, level checks, timing, or event allocation. Completion logging adds
constant work per whole operation, independent of the number of nodes, edges, and properties.

## Exception / Error Types

Storage and importer exceptions propagate without interception by logging code.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.storage.LayeredStorageImpl

val storage = LayeredStorageImpl()
val node = storage.addNode()
storage.freeze()
storage.setNodeProperties(node, emptyMap())
storage.clearActiveLayer()
check(storage.nodeIDs == setOf(node))
storage.clear()
```

With INFO enabled, this sequence produces `freeze`, `clearActiveLayer`, and `clear` records.
`addNode`, `setNodeProperties`, and the read of `nodeIDs` produce no record.

## Open Gaps

- Correlation/resource reporting is chosen but absent from `logCompletion`: accept or generate correlation at the standalone boundary and bind once; carry operation identity, schema/phase, budget, old/new counts and processed bytes when available without a hot-path scan. [Typed Freeze](spec-storage.md#typed-selection-and-freeze) accounts for old storage, replacement and workspace together.
- Bounded/external-I/O operations need an INFO start with target/budget and completed outcomes on handled exits. `StorageOperationLog.kt` records successful completions only. Propagation attaches context without duplicate ERROR records; the handling boundary emits failure detail once and links its summary by error ID. Expected revert/discard is success.
- Normal reads/writes retain zero logging checks, timers, counters or event allocations. Profiling counters are separate and opt-in; repeatable optional diagnostics are sampled to at most one per second, while required summaries remain complete. [Logging acceptance](design-gaps-storage.md#pending-acceptance-measurements) remains pending.
- Can completion emission remain outside storage locks and after consistency/resource closure for every new bulk operation? No logging exception may alter committed state. The application owns backend, bounded sinks, destination, rotation and retention.
