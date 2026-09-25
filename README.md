# avro2s-wire

A Scala 3 schema compiler and native Avro binary runtime. Generated models are
ordinary immutable Scala values; generated codecs read and write their fields
directly. Matching-schema applications need `avro2s-wire-runtime`, the Scala
standard library and the JDK. Apache Avro is used by the schema compiler and the
optional `avro2s-wire-java-backend` module. Native output is standard Avro binary
and can be read by Java Avro without that backend.

This is an early implementation with direct codecs, native Scala 3 unions, logical
types, an optional native schema-evolution reader, and an optional Confluent Schema
Registry adapter. Matching-schema codecs need only the runtime; schema resolution
and registry access are separate optional dependencies.

The former `avrogen` prototype is now `avro2s-wire`, with packages under
`avro2s.wire` and artifact names such as `avro2s-wire-runtime`. Regenerate existing
models to update their runtime references. Historical benchmark reports retain
their original project names, source paths and hashes.

## Build and test

The native runtime requires JDK 11 or newer. The build pins Scala 3.3.8 and
sbt 1.13.0; JDK 21 is the CI and benchmark reference environment.

Scala 3.3 is an LTS line and provides the Scala 3 features used here, including unions and enums.
Building on an older Scala 3 line allows newer Scala 3 consumers to use the
library without requiring every consumer to move to a newer compiler. See the
[Scala compatibility guarantees](https://www.scala-lang.org/development/).
Exact versions are pinned for reproducible builds. Historical benchmark reports
retain the compiler and build-tool versions used for their measurements.

sbt 1.11 introduced the built-in Central Portal tasks used by the release
workflow (`localStaging` and `sonaUpload`). These are the same publishing tasks
used by avro2s. sbt's version controls the build tool; `scalaVersion` independently
controls the compiler for the library and generated models. See the
[sbt 1.11 release notes](https://github.com/sbt/sbt/releases/tag/v1.11.0).

The pinned Scala compiler supports JDK 25 and 26 as well as the older JDKs
supported by the 3.3 line. CI uses JDK 21. Consult the
[JDK compatibility matrix](https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html)
when choosing a build JDK.

```sh
sbt test
sbt 'fixtures/runMain avro2s.wire.fixtures.Demo'
```

The build generates fixture sources from `.avsc` files and compiles them against
the native runtime alone. Tests cross-read and cross-write with Java Avro's
independent generic reader/writer, in addition to binary-format and generator tests.
Property tests also generate schemas and values, compile the resulting Scala,
check Java interoperability in both directions, and shrink failing cases for
replay. The default campaign covers 203 schemas and 2,246 values, with required
coverage assertions. Additional campaigns generate writer/reader schema pairs,
alternative legal collection blocks, malformed inputs, limits and ownership
operations. See the [testing guide](docs/testing.md) for the test suites,
reproducible campaign commands and remaining gaps.

GitHub Actions follows avro2s's PR, pre-release and release flow, using JDK 21.
See [releasing](docs/releasing.md) for the required repository secrets and Central
Portal upload process. Snapshot versions are not automatically published.

## Generate Scala

From this project's root:

```sh
sbt 'compiler/run fixtures/src/main/resources/avro/Trade.avsc /tmp/avro2s-wire-generated'
```

The two positional arguments are an `.avsc` file (or a directory recursively containing
`.avsc` files) and an output directory. Directory generation resolves references
between files. Generated sources need `avro2s-wire-runtime` on their compile/runtime
classpath; they do not need Apache Avro. To use the current checkout in another
local sbt project, publish the five library modules locally:

```sh
sbt 'runtime/publishLocal' 'compiler/publishLocal' 'javaBackend/publishLocal' 'resolution/publishLocal' 'schemaRegistry/publishLocal'
```

For native matching-schema use, add this dependency to the consuming build:

```scala
libraryDependencies += "io.psilicon" %% "avro2s-wire-runtime" % "0.1.0-SNAPSHOT"
```

Use the version from this project's `build.sbt`. The compiler is needed only
during generation; the Java backend, schema resolver and Schema Registry adapter
are optional application dependencies. See [Schema Registry](docs/schema-registry.md)
for setup and limits. Public release setup is described in [releasing](docs/releasing.md).

The sample generates this model and a companion codec:

```scala
final case class Trade(
  id: Long,
  symbol: String,
  price: Double,
  quantities: Vector[Int]
)
```

Using the fixture within this build:

```scala
import avro2s.wire.fixtures.Trade
import avro2s.wire.runtime.*

val trade = Trade(123L, "ABC", 42.5, Vector(10, 20))
val bytes: Array[Byte] = Trade.codec.encode(trade)
val restored: Trade = Trade.codec.decode(bytes)
assert(restored == trade)

// Reuse a caller-owned buffer when writing multiple messages.
val output = new BinaryOutput(1024)
Trade.codec.write(trade, output)
val message = output.toByteArray
output.reset()

// Each call to read consumes one datum, allowing concatenated records.
val input = new BinaryInput(bytes)
val next = Trade.codec.read(input)
input.requireEnd()
```

`encode` allocates an output buffer and returns a copy of the encoded bytes.
`decode` creates an input reader over the supplied array without copying that
array; decoded byte fields get independent storage. `decode` requires exactly
one complete datum and rejects trailing bytes. `read` and `write`
provide the lower-level APIs. Codecs are shareable; mutable input/output instances
must be confined to their caller. Raw datum bytes contain no schema identifier:
the caller must already know the exact writer schema.

### Generator configuration

Options are passed at generation time. Defaults preserve Avro namespaces as Scala
packages and convert logical values to the types listed below, using
`scala.BigDecimal` for decimals.

```scala
import avro2s.wire.compiler.*
import java.nio.file.Path

val config = GeneratorConfig(
  decimalType = DecimalType.Java,
  namespaceMappings = Map(
    "com.acme" -> "myapp.model",
    "com.acme.events" -> "myapp.events"
  ),
  logicalTypes = Map(
    LogicalType.Date -> LogicalTypeMode.Raw,
    LogicalType.TimestampMicros -> LogicalTypeMode.Converted
  )
)

SchemaCompiler.generate(Path.of("schemas"), Path.of("generated"), config)
```

`CodeGenerator.generate(schema, config)` accepts the same options. Namespace
mappings match whole namespace components; the longest matching prefix wins.
For example, `com.acme.orders.Order` becomes `myapp.model.orders.Order`, while
`com.acme.events.Created` becomes `myapp.events.Created`. Avro schema names,
aliases and schema JSON keep their original identities.

`Raw` selects the physical value: for example, `date` becomes `Int` and
`timestamp-micros` becomes `Long`. Unspecified logical types use `Converted`.
Logical fixed types retain their named wrappers, containing `Bytes` in raw mode.
The decimal option selects Java values for converted `decimal` and `big-decimal`.

The equivalent command-line options are repeatable for namespaces and logical types:

```sh
sbt 'compiler/run --decimal-type java --namespace-map com.acme=myapp.model --namespace-map com.acme.events=myapp.events --logical-type date=raw --logical-type timestamp-micros=converted schemas generated'
```

Settings apply throughout generated records, collections, unions and named fixed
wrappers. Generated codecs carry representation choices into schema resolution,
including reader defaults. Regenerate and recompile consumers after changing
options; the Avro wire format stays the same. These are generation choices,
with no runtime option checks in matching-schema read/write methods.

Scala decimal equality ignores trailing-zero scale; Java decimal `equals` includes
scale. Avro `decimal` always decodes at its schema's scale, while `big-decimal`
preserves each value's scale. See [logical types and generator options](docs/logical-types.md)
for raw mappings, namespace edge cases, decimal semantics and extension design.

### Generated-code compatibility

Generated `.scala` files are source code. The current generator emits Scala
3.3-compatible syntax, which can also be compiled by newer Scala 3 compilers.
The generator's own compiler version does not automatically become the minimum
version for its source output; that depends on the emitted syntax and runtime API.
The automated suite currently compiles the generated sources with the pinned
compiler, not a matrix of all consumer Scala versions.

Compiled library JARs have a separate compatibility boundary: newer Scala 3
compilers can consume libraries compiled with older Scala 3 versions, but the
reverse is not generally supported. For example, a Scala 3.9 application can use
a library built with Scala 3.3; a Scala 3.3 application cannot use a Wire runtime
built with Scala 3.9. Updating sbt alone does not change this boundary.

Use matching Wire compiler and runtime release versions. Compatibility across
Wire releases is a separate concern from Scala compatibility and is not yet a
published guarantee for this early implementation.

## Supported subset

| Avro | Generated Scala |
| --- | --- |
| boolean, int, long, float, double | Boolean, Int, Long, Float, Double |
| string | String |
| null | Null |
| bytes | immutable `Bytes` with value equality |
| record | immutable final case class |
| enum with at least one symbol | Scala 3 enum |
| fixed | named case class with a checked `Bytes` value |
| array | Vector |
| map | immutable Map[String, A] |
| non-null union | A \| B, preserving schema branch order in the codec |
| nullable union | Option[A] or Option[A \| B], preserving wire branch indices |
| date; time-millis/time-micros | java.time.LocalDate; java.time.LocalTime |
| timestamps and local timestamps, millis/micros/nanos | java.time.Instant and java.time.LocalDateTime |
| UUID string; UUID fixed | java.util.UUID; named wrapper around UUID |
| decimal bytes; decimal fixed | Scala BigDecimal (or configured Java BigDecimal); named wrapper for fixed |
| big-decimal bytes | Scala BigDecimal (or configured Java BigDecimal), preserving per-value scale |
| duration fixed | named wrapper around AvroDuration |
| recursive named records | direct references to named codecs |

Top-level schemas must be named records, enums, or fixed types. Unsupported
schemas fail generation explicitly. Unknown/unsupported or invalid logical types,
empty enums, and the identifiers `_` and `_root_` are currently rejected. Keywords are escaped;
model and enum member collisions are renamed deterministically. Wire names and
schema metadata remain intact in `schemaJson`.

Default-package types are supported, but a type in a named package cannot refer
to a default-package type in Scala. Those schemas, and default-package type names
that collide with generated members, are rejected with guidance to add a namespace.

Defaults and aliases drive the optional schema resolver. They do not add Scala
constructor defaults, and a field is always written even when it equals its Avro
default. Avro IDL, JSON datum encoding, object container files, compression, schema
registries, Kafka serializers, and Scala `derives` support are not yet implemented.

Generated records, enums, and fixed types retain distinct runtime classes. For
example, UUID string/fixed unions become `UUID | NamedFixedUuid`. When a union
contains both `time-millis` and `time-micros`, their otherwise identical LocalTime
mappings use `TimeMillis | TimeMicros` wrappers. Unambiguous time fields stay
LocalTime. Scala union order does not determine Avro branch indices.

Logical writes reject overflow and precision loss. Timestamps and times must be
exactly representable at their schema's precision; decimal rescaling may add or
remove trailing zeros but never rounds. Duration keeps separate unsigned months,
days, and milliseconds because those calendar components cannot be reduced to a
single elapsed-time value. Unknown logical annotations are rejected explicitly;
this implementation does not silently fall back to the underlying primitive.

`Bytes` stores an immutable byte sequence with content-based `equals` and
`hashCode`. A plain `Array[Byte]` remains mutable inside a `val` field and normally
uses reference equality. `Bytes.fromArray` and `Bytes.toArray` copy so callers
cannot change the stored value through an array they retain or receive. Decoded
byte/fixed values own their storage, independently of the input buffer.

This is an ownership and API choice, not an Avro format requirement. avro2s
already obtains content-aware record equality from Java Avro's `SpecificRecordBase`;
Wire's ordinary case classes use `Bytes` equality instead. Custom generated equality
could also support raw arrays, but would not make them immutable. The cost of
`Bytes` is a wrapper allocation and copying at public array boundaries. Its internal
`unsafeWrap` transfers an array without copying and requires the caller never to
mutate it afterward; it is not a public API.

`BinaryInput` borrows its input array for the duration of decoding; callers must
not mutate that array concurrently. Primitive values inside Vector, Map, and
Option can still box; this implementation does not claim zero allocation.

Native decoding validates bounds, varint widths, UTF-8, collection block sizes,
and generated union/enum indices. `DecodeLimits` controls input size, string/byte
lengths, cumulative collection item counts, and nesting depth. Limits apply to the
input instance, so a fresh input resets the budget. Invalid input raises
`AvroDecodingException`. Native string encoding rejects unpaired UTF-16 surrogates
to prevent silently replacing malformed text. The Java backend follows Java Avro's
replacement behavior. Valid Unicode text has the same wire representation.

## Schema evolution

Add `avro2s-wire-resolution` when writer and reader schemas can differ:

```scala
import avro2s.wire.resolution.ResolvingReader

// Account is the generated reader model. Retain this reader for repeated messages.
val reader = new ResolvingReader(writerSchemaJson, Account.codec)
val account: Account = reader.decode(bytes)
```

The reader parses each schema pair and builds its resolution plan at construction.
It supports field reordering, reader aliases and defaults, discarded writer fields,
promotions, enum remapping/defaults, fixed types, unions, and recursive records. It
constructs generated Scala models directly; it does not create Java GenericRecords
or re-encode the datum. Identical schema JSON uses the generated direct codec.

The optional module uses Jackson to parse JSON and has no Apache Avro runtime
dependency. Core codecs keep their original dependency footprint. Native decode
limits apply during resolution, including skipped fields. See
[the evolution design and compatibility notes](docs/schema-evolution.md).

## Optional Java Avro backend

Add `avro2s-wire-java-backend` to use Apache Avro's binary implementation with
Wire's generated models and codecs. Its `avro2s.wire.javabackend` package supplies
`JavaAvroInput` and `JavaAvroOutput`, which delegate primitive calls to Java Avro's
`Decoder` and `Encoder`. This lets the same generated
codec run on either binary engine. It is optional and is not used by native
`encode`/`decode`. Java adapter inputs use Java's validation/resource-limit behavior,
not `DecodeLimits`; callers own decoder configuration and encoder flushing.

This adapter does not perform schema evolution or turn generated models into
`SpecificRecord` instances.

## Benchmarks

Use the [benchmark guide](benchmarks/README.md) for profiles, methodology and
[the selected dated reference results](benchmarks/reference/2026-09-17/README.md).

```sh
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile comparison
```

The runner checks benchmark correctness, then records timing and allocation for
native Wire, Wire's Java backend, avro2s and supported official Java variants.
`--profile full` adds evolution, explicit String readers, big-decimal and allocating
APIs. Reports, raw results and provenance go to ignored `benchmarks/results/`.

The generated Java and avro2s comparison models remain checked in, with
[pinned provenance and regeneration instructions](benchmarks/generator/README.md).
Normal tests and benchmarks need no avro2s checkout. Historical experiments remain
recoverable through [Git history](docs/benchmarks/HISTORY.md).

## Modules and next steps

- `runtime`: owned bytes, binary I/O, codec API, decode limits; no Apache Avro dependency.
- `compiler`: validated schema graph and deterministic Scala source generation.
- `java-backend`: optional Apache Avro Java encoding and decoding backend.
- `resolution`: optional native schema parsing/resolution and cached reader plans.
- `schema-registry`: optional Confluent classic-frame Kafka serializer/deserializer adapters.
- `fixtures`: generated-model compilation and interoperability checks.
- `benchmarks`: JMH comparisons.
- `property-tests`: generated schemas, schema evolution, wire layouts, limits and ownership properties.

The broader [comparison corpus](benchmarks/COMPARISON.md) covers 13 input profiles
and includes a separate schema-evolution benchmark. The [testing guide](docs/testing.md)
describes the bounded, reproducible property campaigns and their remaining gaps.

Release automation is configured; repository credentials and the final Central
Portal publication step are described in [releasing](docs/releasing.md). See the
[Schema Registry guide](docs/schema-registry.md) for supported framing, usage and
the optional Docker-backed interoperability check.
Optional primitive-backed collections, input reuse and bulk block skipping remain
separate experiments. A dedicated build-tool plugin and streaming/container APIs
are future work; see [the architecture notes](docs/architecture.md).
