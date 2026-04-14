# MapDB 3.0.5 — Implementation Notes

Paired design: `storage.design.md`

---

## APIs

**`org.mapdb.DBMaker`** `DBMaker.memoryDB()` — in-memory DB; `DBMaker.fileDB(file)` — file-based DB. Chained config: `concurrencyDisable()` disables internal locks (single-thread); `fileMmapEnableIfSupported()` enables mmap IO (significant file-backed performance gain); `closeOnJvmShutdown()` auto-close on JVM exit; `make()` creates `DB` instance.
**`org.mapdb.DB`** `db.hashMap(name, keySerializer, valueSerializer)` — create HashMap builder; `db.indexTreeList(name)` — create ordered list builder. Builder uses `.createOrOpen()` to open or create collection (`.create()` throws `DBException.WrongConfiguration` if collection already exists). `.counterEnable()` makes `size` query O(1). `db.close()` — close and persist (MapDB 3.x non-transactional mode does not require `commit()`).
**`org.mapdb.Serializer`** `Serializer.STRING` — string serializer; `Serializer.BYTE_ARRAY` — byte array serializer. Custom serializers implement `serialize(DataOutput2, T)` / `deserialize(DataInput2, Int): T`. `isTrusted()` returns `true` to skip extra validation.
**`org.mapdb.DataOutput2`** / **`org.mapdb.DataInput2`** — MapDB custom binary stream interfaces for `Serializer` implementations.

---

## Libraries

- `org.mapdb:mapdb:3.0.5` — embedded Java database engine (off-heap storage, supports HashMap / TreeMap / IndexTreeList)
- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` serialization; `MapDbValSerializer` delegates to `DftByteArraySerializerImpl`

---

## Developer instructions

- File-based DB collections must use `.createOrOpen()`, not `.create()` (reopening after restart would fail).
- Use `.create()` only when a fresh state is explicitly needed (e.g., single-write IO temp file).
- MapDB 3.x non-transactional mode: `db.close()` persists data; no `commit()` needed.
- `closeOnJvmShutdown()` is a safety net; call `close()` explicitly as primary path.

---

## Design-specific

### Collection type selection

| Collection | Usage | Notes |
|-----------|-------|-------|
| `hashMap` | node/edge properties, graph structure | O(1) read/write, primary storage |
| `indexTreeList` | IO import/export sequences | ordered list, suited for full-scan iteration |

### Serialization

`MapDbValSerializer<T>` bridges `DftByteArraySerializerImpl` and MapDB `Serializer` interface, delegating to `Serializer.BYTE_ARRAY` for underlying bytes. `isTrusted()` returns `true`, consistent with `Serializer.BYTE_ARRAY`.

`MapDbIDSerializer` serializes `Int` IDs through `DftByteArraySerializerImpl` as `NumVal`, delegating to `Serializer.BYTE_ARRAY`.

| Serializer | Use case |
|------------|----------|
| `Serializer.STRING` | HashMap keys when key is a plain string (e.g., composite property keys) |
| `Serializer.BYTE_ARRAY` | Underlying byte handling delegated to built-in implementation |
| `MapDbValSerializer<T>` | `IValue` subtypes, bridges `DftByteArraySerializerImpl` |
| `MapDbIDSerializer` | Entity Int IDs, serializes via `NumVal` through same path |

### fileMmapEnableIfSupported() effect

| Mode | Read/write method | Advantage |
|------|------------------|-----------|
| RandomAccessFile (default) | System call per read/write | Better compatibility |
| **Memory-mapped (mmap)** | Direct memory access, OS page cache | Significant large-file improvement; avoids user/kernel switch |

File-based DB should always enable `fileMmapEnableIfSupported()`.
