# cobra-commons-value 0.1.0 — Implementation Notes

Paired designs: all modules (global dependency)

---

## APIs

**`edu.jhu.cobra.commons.value.IValue`** Top-level value type interface; subtypes: `NullVal`, `StrVal`, `NumVal`, `ListVal`, `SetVal`, `MapVal`.
**`edu.jhu.cobra.commons.value.StrVal`** `StrVal(core: String)` / `"text".strVal` — string value; extension property and direct construction are equivalent.
**`edu.jhu.cobra.commons.value.NumVal`** `NumVal(core: Number)` / `42.numVal` — numeric value (Int/Long/Float/Double interchangeable).
**`edu.jhu.cobra.commons.value.ListVal`** `ListVal(vararg vals)` / `listOf(v1, v2).listVal` — ordered list value.
**`edu.jhu.cobra.commons.value.SetVal`** Duplicate-free set value.
**`edu.jhu.cobra.commons.value.MapVal`** Key-value mapping value; `add(key, value)` is a mutable operation.
**`edu.jhu.cobra.commons.value.NullVal`** Null value (distinct from Kotlin `null`: `NullVal` is a valid `IValue` instance meaning "value present but empty"; `null` means key absent).
**`DftByteArraySerializerImpl`** `serialize(value): ByteArray` / `deserialize(bytes): IValue` — singleton binary serializer; used by MapDB and Neo4j property storage.
**`DftCharBufferSerializerImpl`** `serialize(value): CharBuffer` / `deserialize(buf): IValue` — singleton text serializer; used by GML/CSV IO.
**`ListVal?.orEmpty()`** / **`SetVal?.orEmpty()`** / **`MapVal?.orEmpty()`** Returns empty collection value for `null`, avoids null checks.

---

## Libraries

- `edu.jhu.cobra:commons-value:0.1.0` — `IValue` type system, serializers

---

## Developer instructions

- Prefer extension property construction (`"text".strVal`, `42.numVal`); equivalent to direct construction, no extra allocation.
- `NullVal` vs Kotlin `null`: `NullVal` is a valid instance; `null` means key absent.
- `MapVal.add(key, value)` is mutable; in MapDB off-heap storage, re-`put` after modification to persist changes.
- `ListVal` internal `core` is `MutableList<IValue>`; concurrent modification risk during iteration.
- Both serializers are stateless singleton `object`s, safe for concurrent reuse.

---

## Design-specific

### Serializer selection

| Serializer | Output type | Use case |
|------------|------------|----------|
| `DftByteArraySerializerImpl` | `ByteArray` | MapDB, Neo4j property storage (binary, compact) |
| `DftCharBufferSerializerImpl` | `CharBuffer` | GML/CSV text IO (readable, JSON-like encoding) |
