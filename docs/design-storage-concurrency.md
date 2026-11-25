# Storage concurrency

## Design Overview

- **Types:** `IStorage`, `Lock`; the extension `criticalSection` coordinates compound calls.
- **Relationships:** Every concurrent implementation exposes its own write lock; callers and graph implementations use that same lock.
- **Exceptions:** No synchronization-specific exception; exceptions from the action propagate after lock release.
- **Dependency roles:** `IStorage` owns the lock; callers supply actions.

## Class / Type Specifications

### Storage critical section

**Responsibility:** `IStorage.criticalSection(action)` runs `action` holding the storage's `criticalSectionLock`, or runs it unguarded when that is `null`. A thread-safe `IStorage` makes each single call atomic; a graph-layer step that makes several calls (allocate an ID then persist it, check-then-create, read-modify-write, a multi-edge traversal) runs inside `criticalSection` so the sequence is atomic as a unit. Every graph and poset sharing one storage serializes through that storage's lock; a nested call on the same thread does not block, and every storage call inside the section re-enters the same lock.

Compound atomicity is a property of the chosen implementation, like per-call atomicity: a graph over a storage that is not thread-safe is single-threaded. Each storage holds exactly one lock; no lock state lives outside the storage.

A caller holding a storage's read lock never enters its critical section (a read lock does not upgrade to the write lock). Storage implementations never invoke caller code while holding their read lock.

**Concurrent use per implementation:**

| Implementation | Thread-safe | Returned collections (property maps, adjacency sets, ID and meta-name sets) |
|----------------|-------------|-------------------------------------------|
| `NativeStorageImpl`, `JgraphtStorageImpl`, `MapDBStorageImpl`, `Neo4jStorageImpl` | No (`criticalSectionLock` is `null`) | -- |
| `NativeConcurStorageImpl`, `JgraphtConcurStorageImpl`, `MapDBConcurStorageImpl`, `Neo4jConcurStorageImpl`, `LayeredStorageImpl` | Yes | Copies, readable outside the lock |

A section that holds two storages' locks at once (a `criticalSection` nested inside another storage's, or `transferTo`, which holds the source's read lock while writing the target) deadlocks against a thread taking the same two locks in the opposite order. Callers take the locks of any two storages in one fixed order.

## Function Specifications

`IStorage.criticalSection(action: () -> T): T` invokes a synchronous action once and returns its result; a non-null criticalSectionLock is held for the full invocation and released on every exit, a `StackOverflowError` included. With a null lock, the action runs unguarded.

## Exception / Error Types

Action exceptions propagate. A `StackOverflowError` propagates too, and its exit releases the lock: before it takes the lock, a section checks that the stack at its own frame has room for the release and throws the overflow when it has not, so no hold is left; its exit then releases the lock down to the holds the thread had on entry, which also frees a hold that a storage call of the action left when the overflow cut that call's own release. The lock of a storage that does not count the holds of a thread (any `Lock` other than a reentrant lock) is released once. A caller never suspends or waits on another thread while holding the section, and never attempts to upgrade a held read lock to a write lock.

## Caller Examples

```kotlin
import edu.jhu.cobra.commons.graph.storage.NativeConcurStorageImpl
import edu.jhu.cobra.commons.graph.storage.criticalSection

val storage = NativeConcurStorageImpl()
val count = storage.criticalSection {
    storage.addNode()
    storage.nodeIDs.size
}
check(count == 1)
```

## Open Gaps

- **A single storage call outside a critical section can lose its own unlock.** Decided: a held lock is released on every exit ([Function Specifications](#function-specifications)). Code: a section checks its stack headroom and releases down to its entry holds, and every other lock use takes the storage's own lock with `withLock` or with `read` and `write` and releases it in a `finally`, in `NativeConcurStorageImpl`, `LayeredStorageImpl`, `StorageAccessGuard` (guarded reads and `close`), `JgraphtConcurStorageImpl`, `MapDBConcurStorageImpl` and `Neo4jConcurStorageImpl`. A `StackOverflowError` thrown inside the unlock of such a call outside any section leaves the lock held by that thread. A caller observes the error, and then every other thread that calls the storage, and `close`, waits without end.
- **A read hold left by a read-only call is not released by a section exit.** Decided: the same sentence. Code: the exit of `criticalSection` counts and releases only the holds on the write lock. A read-only storage call (for example `containsNode`) whose unlock was cut by a `StackOverflowError` leaves a hold on the read lock, and the section exit does not release it. A caller observes that a later write acquisition on that thread (a write call or a `criticalSection`) blocks without end, because a read lock does not upgrade to the write lock.
