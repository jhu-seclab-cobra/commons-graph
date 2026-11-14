# Registered storage fields

## Design Overview

- **Classes:** public `StorageSchema`, `PropertyKey<T>`, `PropertyCodec<T>`, `IndexedPropertyKey<T>` and `CellReader<T>`; internal captured fields and cell directories.
- **Relationships:** schema registration precedes storage construction; the existing guarded read and write scopes select registered fields in entity-local rows.
- **Exceptions:** `IllegalArgumentException` rejects schema/type/capture errors before mutation; `IllegalStateException` rejects expired borrowing or a key from another schema.
- **Dependency roles:** the schema owns key identities and codecs; storage owns rows, directories and physical layers; the caller owns payload meaning and reference enumeration.

The [capability contract](design-storage-capabilities.md) owns scope and owner lifetime. The [family contract](design-storage-families.md#typed-indexed-families) owns physical family selection. This design does not add an adjacency representation or evaluation buffer.

## Class / Type Specifications

### StorageSchema and PropertyKey

`StorageSchema(keys)` validates and retains a fixed registration before constructing storage. A key has one stable compatibility name, one payload type and one capture/codec contract. The same registration may be repeated; incompatible registrations for one name fail. A schema never adds a key after construction. Process-local key ordinals and directory identifiers are not serialized identities.

`PropertyKey<T>` selects one primitive or immutable payload. `PropertyCodec<T>` captures caller-owned mutable descendants, validates legacy input and converts a captured value to independent `IValue` content. A successful capture returns content whose equality and hash cannot be changed through a caller alias. A read-only Kotlin collection type is insufficient for that guarantee. A codec rejects a malformed shape, unsupported version/type tag or excessive depth before its value becomes visible.

Registered fields and unregistered extension values occupy the existing entity-local property row. The row contains either one captured registered value or one legacy value for a name, never an authoritative encoded value beside an authoritative decoded value. A typed read selects the addressed row and field and returns the admitted immutable payload; it does not encode, decode or capture that payload again. A compatibility read encodes only the requested value or requested full row. Unregistered mutable content retains the captured compatibility boundary.

Small property rows retain linear name selection. A point access may visit the addressed row's names but never names in another row. Insertion and removal retain their row-copy cost. This interface does not claim that registered names eliminate that cost or that generic primitive payloads are unboxed internally.

### IndexedPropertyKey and CellReader

`IndexedPropertyKey<T>` registers one family anchor, its legacy cell-name prefix and its cell codec. A cell address consists of two opaque strings supplied by the caller; string length framing is an interchange concern, not a parser for graph identities. Storage interns the addressed strings into store-owned integer dictionaries and selects a primitive pair key. Dictionary, boxing, capacity and resize costs belong to the measured layout. Dictionary entries remain owned by the storage until `clear()` or `close()` releases every row and frozen layer; those operations then clear both directions of the dictionary. Escaped immutable content retains strings rather than dictionary offsets.

One private directory belongs to the family in its entity row. `CellReader<T>.lookup(first, second)` selects one entry without enumerating or copying the directory. The reader checks the entering thread, owner lifetime and read-scope lifetime on every operation. Only the selected immutable payload may escape; neither the mutable directory nor its backing arrays escape. Fixed membership results preserve the caller's encounter order independently of dictionary identifiers allocated for other rows.

`setEdgeCell(id, key, first, second, value)` captures the new payload before mutation and publishes its directory entry and family membership under the existing storage guard. A write to an existing active family retains its other active entries. The first active family write starts with empty membership and cells; it does not copy frozen entries. The caller's schema translates the membership identifiers and cell addresses to legacy names. The library assigns no PHP label or evaluation-context meaning to either coordinate.

Absence, an explicit deletion and stored `NullVal` remain distinct. A codec may reject `NullVal` for a required record type. Private deletion entries are absent from the compatibility map: its emptiness, size and entries describe the same visible properties. A deleted active family member masks the corresponding frozen content; clearing active state restores the complete frozen family. A full compatibility snapshot encodes the requested family members and cells under one guard, so membership and cells come from the same physical image.

## Function Specifications

`PropertyValue` is an admitted immutable scalar, cell payload, family membership or privately captured legacy extension value. `get(key)` returns its typed payload; `members(key)` returns its immutable family identifiers. `snapshot()` creates independent compatibility content. Wrong key or content kind fails. A caller view may retain these values in its own buffered records without a legacy codec cycle; it retains no storage row, mutable cell directory or read scope.

`getNodeContents` and `getEdgeContents` acquire fixed maps of these values. Named content reads select only one property or one cell. `setNodeContents` and `setEdgeContents` validate all named values against the construction schema before mutation and admit already captured payloads without recapture. Compatibility writes use the same validation after decoding. Family membership and addressed cell content are published together under the existing guard. Codecs validate the payload against its cell coordinates before publication.

The public read scope provides typed node/edge property selection and `edgeCells(id, key, layer)`. A key must belong to the storage's construction-time schema. Missing optional content returns absence. `getNodeInt`, `getNodeLong`, `getEdgeInt` and `getEdgeLong` return a required primitive value and reject absence; a stored zero remains a present value. A physical-layer read of an unknown edge returns absence, preserving the existing snapshot contract.

The public write scope provides typed node/edge field updates and selected edge-cell updates. Validation and new-payload capture precede publication. The existing callback exclusion contract applies; this API adds no rollback of earlier successful callback calls.

Freeze and node promotion transfer captured registered values without a legacy encode/decode cycle. An immutable payload may be shared between captured rows. A frozen cell directory is never mutated: the active family receives its own empty directory on its first write. Freeze may enumerate a complete family once; a selected cell read or update may not enumerate the complete family.

## Caller Examples

The caller registers its field codecs before construction and obtains capabilities through `StorageAccess`:

```kotlin
val schema = StorageSchema(GraphFields.keys)
val access = StorageAccess(LayeredStorageImpl(schema, projection, tagGroup))
val payload = access.reader.read { read ->
    read.edgeCells(edgeId, GraphFields.callInputs, StorageLayer.VISIBLE)
        .lookup(contextId, exactLabelId)
}
access.writer.write { write ->
    write.setEdgeCell(edgeId, GraphFields.callInputs, contextId, exactLabelId, nextPayload)
}
```

`GraphFields` and the meaning of `nextPayload` belong to the consuming graph schema. A caller with evaluation buffers forwards these operations through its own observed reads and writes; it never reads the underlying storage directly to avoid those observations.

## Open Gaps

The [implementation status](design-gaps-storage.md#typed-storage-implementation) identifies unimplemented adapters and acceptance measurements. Neither the API nor source bounds establish a layout or throughput result.
