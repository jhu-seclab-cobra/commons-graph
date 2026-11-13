# Storage Operation Logging

## Design Overview

The Storage implementations and file importers submit completion records through kotlin-logging.
Private file-level loggers belong to each implementation module. Logging introduces no public API
and changes no Storage state or return value. The existing `RevertListener` interface remains the
callback mechanism; it does not depend on a separate observer library.

## Function Specifications

The storage implementations use kotlin-logging with an SLF4J backend selected by the consuming
application. The library installs no backend, output destination, or log-level setting.

Each successful `freeze`, `clearActiveLayer`, `clear`, `transferTo`, and importer `import` emits
exactly one INFO completion record when INFO is enabled, including an operation on empty Storage. `clearActiveLayer`
is the rollback operation. A layered `clear` emits only its `clear` record, not a second rollback
record. `transferTo` is the in-memory bulk load; CSV, GML and MapDB `import` are file bulk loads.

The operation emits after all state updates, lock or transaction completion, resource closure,
and synchronous revert callbacks return successfully. A propagated exception emits no completion
record; the caller that handles the exception owns failure logging.

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
