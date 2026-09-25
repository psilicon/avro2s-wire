# Native schema evolution

The optional `avro2s-wire-resolution` module reads data written with a different Avro
schema into the generated Scala reader model. It depends on the native runtime
and Jackson for JSON parsing. Apache Avro is a test dependency only.

```scala
import avro2s.wire.resolution.ResolvingReader

val reader = new ResolvingReader(writerSchemaJson, Account.codec)
val account: Account = reader.decode(bytes)
```

`Account.codec.schemaJson` supplies the reader schema. The supplied writer schema
must describe the actual bytes. Raw Avro data has no schema identifier; container
and registry integrations are separate work.

## Construction and reuse

Each `ResolvingReader` parses the schema graphs and compiles a plan once. Retain
the reader for messages with that schema pair. Recursive pairs use deferred plans
so recursive records do not require infinitely constructing readers. There is no
global cache retaining an unbounded set of schemas. Defaults and aliases are part
of the plan; a parsing-canonical fingerprint alone is not sufficient to identify
resolution behavior.

When the writer JSON exactly equals the generated reader JSON, the reader uses the
ordinary generated codec directly. Otherwise, the plan walks writer fields in wire
order, stores retained values in reader-ordered slots, and calls generated
construction methods. Nested records are constructed as Scala models immediately.
This path uses per-record slot arrays and boxed primitive values; it does not claim
the allocation profile of a direct matching-schema codec. It creates no Java
GenericRecords and does not serialize an intermediate datum.

The plan can be shared between callers after construction. Each read needs its own
input object, and each result gets its own record slots. `read(in)` consumes one
datum and uses that input's policy; `decode(bytes, limits)` uses native validation
and requires exactly one complete datum.

## Resolution rules

- Records are read in writer order and constructed in reader order. Reader aliases
  can match renamed types or fields. Ambiguous field aliases are rejected.
- Writer fields absent from the reader are skipped. Strings, bytes and fixed data
  can be skipped without allocating their values. Collection and record structure
  is still traversed, with native count, depth, bounds and UTF-8 checks.
- Missing reader fields use their Avro defaults. A missing required field fails.
  Complex defaults produce reader models, including named types and unions.
  Defaults do not allow a writer to omit a field from its own schema.
- Numeric promotions include int to long/float/double, long to float/double, and
  float to double. String/bytes promotions use UTF-8; malformed bytes promoted to
  strings fail under the native validation policy.
- Enums map writer symbols to reader ordinals. An unknown symbol uses the reader's
  enum default if present, otherwise that datum fails.
- Fixed types require a matching name (including reader aliases) and byte size.
  Decimal schemas additionally require matching precision and scale when both
  sides carry decimal annotations.
- Writer unions dispatch on their encoded index. Reader unions select the first
  exact matching branch before considering promotions, matching Java Avro 1.12.1.
  Among promotion candidates, reader schema order is used. An incompatible writer branch fails
  when selected, allowing other compatible branches to be read.
- Native Scala union representation is independent of wire order. Nullable unions
  become Option; distinct named fixed wrappers and ambiguous time wrappers retain
  branch identity. Logical conversions follow the reader schema.

Reader-union selection deserves an explicit compatibility note: the specification
uses first-matching-branch wording, while Java Avro 1.12.1 prefers an exact type
before a promotion. avro2s-wire follows the Java behavior: writer `int` with reader
`[long, int]` returns the Int alternative. This also keeps a selected branch stable
when the only schema change is documentation. Tests exercise both that behavior
and the ambiguous time-union wrappers against metadata-only schema changes.

The [Avro specification](https://avro.apache.org/docs/1.12.0/specification/#schema-resolution)
is the format and resolution reference; the union-selection choice above is
explicitly recorded. Tests compare Java and native results for representative
schema changes and separately test native validation and branch selection.

## Current boundaries

The compiler supports named record, enum and fixed roots. Generated models have
the `construct` and `namedCodec` hooks used by resolution; a hand-written codec
needs corresponding hooks when using the evolving-schema path.

Unknown logical types and invalid logical annotations are rejected explicitly,
including in writer schemas. Avro permits ignoring unknown logical annotations;
this project currently chooses the stricter supported-feature policy. Logical
writes also reject precision loss rather than truncating or rounding.

Schema parsing and construction errors use `SchemaResolutionException`.
Malformed data and native resource-limit violations use `AvroDecodingException`.
Some compatibility errors are reported when reading the offending branch or enum
symbol because they depend on the data.

DecodeLimits bounds data consumed from the input, including skipped fields. Native
string/bytes promotions enforce both source and destination byte limits. Defaults
come from the trusted reader schema and are not charged against the input's
collection/depth budgets; they may construct more output than those wire budgets
allow. Default-plan construction rejects cyclic expansion and nesting beyond 256
levels. Reader schemas should be controlled by the application.

Object containers, compression and streaming input are not implemented here.
[Schema registry lookup and framing](schema-registry.md) are provided by a separate
optional module. The original Trade benchmark predates these additions;
it does not measure evolved-schema reads or logical-type costs.
