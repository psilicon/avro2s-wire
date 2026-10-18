# Top-level unions with Schema Registry

A topic can carry several generated record types through one codec. The
[Event.avsc schema](examples/schema-registry/schemas/Event.avsc) defines a union
of `example.trading.OrderPlaced` and `example.trading.OrderCancelled`. Generation
produces the two case classes and this Scala facade:

```scala
type Event = OrderPlaced | OrderCancelled
```

The `Event` companion provides `schemaJson` and the standard given `codec`.
`Event` names the Scala API; it does not introduce an additional Avro record or
an allocation around each event. The binary datum is the union branch index
followed by that branch's fields.

## Generate and run the example

The [complete UnionExample.scala program](examples/schema-registry/UnionExample.scala)
constructs immutable reader and writer settings, uses `Event.codec` explicitly,
and round-trips both branches. It uses schema GUID headers and requires Schema
Registry at `http://localhost:18081`, or the URL supplied through
`AVRO2S_WIRE_REGISTRY_URL` or its first command-line argument. It does not require
a Kafka broker or publish Kafka records.

Before running the program, register the **complete JSON union** in `Event.avsc`
under the subject `events-value`, using your normal schema deployment process.
Registering `OrderPlaced` and `OrderCancelled` individually is insufficient.
The example uses `autoRegisterSchemas = false`, which is also Wire's default.
The registry must support GUID lookups for the selected header mode.

From the repository root:

```sh
sbt \
  'compiler/run docs/examples/schema-registry/schemas target/schema-registry-example-generated' \
  'set schemaRegistry / Compile / unmanagedSourceDirectories ++= Seq(file("docs/examples/schema-registry"), file("target/schema-registry-example-generated"))' \
  'schemaRegistry/runMain example.registry.UnionExample'
```

This generates all schemas in the example directory and compiles both example
programs, but runs only `UnionExample`. The source-directory settings last for
that sbt session. In an application, add the
[registry module dependency](schema-registry.md) and generate into the
application's sources.

The example sets every registry adapter setting. `WriterSettings.default`
rejects malformed strings, and `DecodeLimits.default` leaves all resource
ceilings at `None`. Closing each adapter closes the client it created from
`RegistryConnection`; the shared connection value is immutable configuration,
not a shared client.

## Name the generated facade

Avro unions have no fullname. `SchemaCompiler` and the CLI derive the Scala
facade's name from the `.avsc` filename and the common namespace of its directly
named branches. For this schema, `Event.avsc` plus `example.trading` produces
`example.trading.Event`. Namespace mappings apply to that inferred name and to
the generated branch models.

Use an explicit Scala name when a different API name is preferable:

```sh
sbt 'compiler/run --root-type-name example.trading.OrderEvent docs/examples/schema-registry/schemas/Event.avsc target/custom-union-generated'
```

That produces `OrderEvent.codec`; update imports and codec references accordingly.
The explicit name is already a Scala name, so namespace mappings do not rewrite
it. It leaves the Avro schema and registry identity unchanged. The equivalent
programmatic setting is `GeneratorConfig(rootTypeName = Some("example.trading.OrderEvent"))`.
Direct `CodeGenerator.generate` calls require this setting for an unnamed root
union because they have no input filename from which to infer it.

Directory generation infers a separate name for each union file. An explicit
`--root-type-name` requires exactly one union root in that input; generate files
separately when assigning different explicit names to several unions. A
primitive-only union has no named-branch namespace and therefore defaults to
the default package unless a namespace mapping or explicit name supplies one.
Name collisions and inaccessible default-package references fail before any
output is written.

Multi-branch Scala type aliases do not add their companion's givens to implicit
scope. Pass `Event.codec` as the runnable example does, or import the given
explicitly:

```scala
import avro2s.wire.runtime.AvroCodec
import example.trading.Event
import example.trading.Event.given

val eventCodec: AvroCodec[Event] = AvroCodec[Event]
```

A union with exactly one branch has a different Scala representation. For a
schema containing only `OrderPlaced`, generation produces an opaque type:

```scala
opaque type Event = OrderPlaced

object Event:
  def apply(value: OrderPlaced): Event = value
  extension (event: Event) def value: OrderPlaced = event
  // schemaJson, given codec and optional stackSafeCodec are also generated.
```

Construct it with `Event(OrderPlaced(...))` and access the underlying record
with `event.value`. There is no wrapper allocation. Its distinct type ensures
`AvroCodec[Event]` automatically selects the union codec, without a given
import. A transparent singleton alias would instead silently select
`OrderPlaced.codec`, which omits the union branch index. Single-branch primitive
and collection roots use the same opaque representation. This does not change
the Avro schema or bytes, and does not affect multi-branch or nullable unions.

## Keep schema identity and branch encoding together

Both events use the same union schema identity. The datum's branch index tells
the reader whether the fields belong to `OrderPlaced` or `OrderCancelled`.
Using a selected record's ID or GUID alongside union-encoded bytes would give
the reader the wrong schema for those bytes.

Use `SubjectNameStrategy.TopicName` for root unions. `RecordName` and
`TopicRecordName` require a record root and are rejected when constructing a
union serializer. `forValue` selects `events-value`; `forKey` would instead
select `events-key` and require the union registered under that subject.

No latest-schema setting is necessary. Wire looks up its codec's exact union
schema, or registers that whole union if `autoRegisterSchemas` is explicitly
enabled. The ordinary Confluent serializer normally discovers the schema of an
individual record; when comparing or interoperating with a fixed root union,
its `SerializerWithSchema` overload can supply the complete union explicitly.

The example chooses `SchemaIdPlacement.HeaderGuid`. Its payload contains only
the Avro datum, and the `__value_schema_id` header identifies the union. Preserve
these headers together with the bytes. Change the setting to
`SchemaIdPlacement.PayloadPrefix` to use the default numeric-ID prefix; the
reader accepts either format. See the
[GUID framing and migration guidance](schema-registry.md#opting-into-guid-headers).

## Null branches, evolution and stack safety

Adding a null branch to a union with non-null branches produces an `Option`:
for example, `Option[OrderPlaced | OrderCancelled]`. `None` writes a real Avro
null branch, including its branch index and registry framing. A JVM `null`
passed to the Kafka adapter remains a Kafka tombstone, with a null payload.
The reader returns `None` for the encoded Avro null and JVM `null` for a
tombstone. Confluent's public Kafka serializer likewise reserves Java `null`
for tombstones, even when given an explicit union schema.

The degenerate union `["null"]` also has an opaque facade. Construct its only
value with `NullEvent(null)` (using the generated facade name). This is still
JVM `null` at runtime: its core codec can encode an Avro null branch, but its
registry serializer treats that value as a tombstone. Use a nullable union
with a non-null alternative when those two meanings must be distinct.

Root unions reuse Wire's existing binary union encoding and schema-resolution
machinery. The feature extends generation and registry root handling; it does
not replace either runtime or resolver. Readers can resolve reordered branches,
promoted fields, defaults and compatible named/union combinations using the
writer schema identified by each message.

Stack-safe generation remains optional and defaults to false. Add
`--generate-stack-safe-codecs` when generating the models, then supply
`Event.stackSafeCodec` to both adapters. Root union steps share the same
stack-safe execution as their named branches, including recursive records.
