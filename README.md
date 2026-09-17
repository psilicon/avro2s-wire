# avro2s-wire

A Scala 3 schema compiler and native Avro binary runtime. Generated models are
ordinary immutable Scala values; generated codecs read and write their fields
directly. Matching-schema applications need `avro2s-wire-runtime`, the Scala
standard library and the JDK. Apache Avro is used by the schema compiler and the
optional `avro2s-wire-java-backend` module. Native output is standard Avro binary
and can be read by Java Avro without that backend.

This is an early implementation with direct codecs, native Scala 3 unions, logical
types, and an optional native schema-evolution reader. Matching-schema codecs need
only the runtime; schema resolution adds a separate JSON-parsing dependency.

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
replay. The default campaign covers 197 schemas and 2,174 values, with required
coverage assertions. Additional campaigns generate writer/reader schema pairs,
alternative legal collection blocks, malformed inputs, limits and ownership
operations. See the [testing guide](docs/testing.md) for the 171 tests,
reproducible campaign commands and remaining gaps.

GitHub Actions follows avro2s's PR, pre-release and release flow, using JDK 21.
See [releasing](docs/releasing.md) for the required repository secrets and Central
Portal upload process. Snapshot versions are not automatically published.

## Generate Scala

From this project's root:

```sh
sbt 'compiler/run fixtures/src/main/resources/avro/Trade.avsc /tmp/avro2s-wire-generated'
```

The two arguments are an `.avsc` file (or a directory recursively containing
`.avsc` files) and an output directory. Directory generation resolves references
between files. Generated sources need `avro2s-wire-runtime` on their compile/runtime
classpath; they do not need Apache Avro. To use the current checkout in another
local sbt project, publish the four library modules locally:

```sh
sbt 'runtime/publishLocal' 'compiler/publishLocal' 'javaBackend/publishLocal' 'resolution/publishLocal'
```

For native matching-schema use, add this dependency to the consuming build:

```scala
libraryDependencies += "io.psilicon" %% "avro2s-wire-runtime" % "0.1.0-SNAPSHOT"
```

Use the version from this project's `build.sbt`. The compiler is needed only
during generation; the Java backend and schema resolver are optional application
dependencies. Public release setup is described in [releasing](docs/releasing.md).

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
| decimal bytes; decimal fixed | Scala BigDecimal; named wrapper around BigDecimal |
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

```sh
sbt 'benchmarks/Jmh/run -prof gc .*TradeBenchmark.*'
```

The harness compares avro2s, default generated Java specific records, Java
generated custom coders, native avro2s-wire codecs, those same codecs using Java
binary primitives, and Java generic records. It measures reads and writes
separately at several collection sizes, with integer values outside the JVM cache.
Write buffers are reused, data construction is outside timing, and read paths use
fresh inputs and result records. Test counters verify that the generated Java
custom decoder is actually called.

For a harness smoke check, not a performance conclusion:

```sh
sbt 'benchmarks/Jmh/run -wi 1 -i 1 -w 300ms -r 300ms -f 1 -p collectionSize=32 .*TradeBenchmark.*'
```

The comparison models are genuine, checked-in generator output. Their versions,
options, schemas, source hashes, and regeneration instructions are in
[baseline provenance](benchmarks/generator/README.md). Normal tests and benchmarks
do not need an avro2s checkout or any manually inspected JAR files.

The [first measured baseline](docs/benchmarks/results-2026-09-17.md), recorded
before the union/logical/evolution additions, compares
avro2s, Java Avro, and avro2s-wire with raw results and source hashes.

The [expanded workload harness](docs/benchmarks/expanded-workloads.md) adds integer
distributions, ASCII and Unicode strings, bytes, maps, nested records and unions,
with both reused-buffer operations and allocating convenience APIs. The
[performance follow-up](docs/benchmarks/performance-2026-09-17.md) records paired
measurements for native integer output, empty collection readers and strict ASCII
fast paths, along with an updated six-way comparison.

The [broader comparison report](docs/benchmarks/comparison-2026-09-17.md) records
13 workload profiles plus schema evolution. It shows both gains and losses,
including slower string paths, with longer confirmations and all raw results.

The [native speed follow-up](docs/benchmarks/speed-2026-09-17.md) measures the
numeric, string and collection improvements against that baseline, including
Java readers explicitly configured to return Strings. Its
[complete tables](docs/benchmarks/speed-2026-09-17/tables.md) retain timings,
allocation and uncertainty, including the emoji-writing tradeoff.

See the [benchmark protocol](docs/benchmarks/README.md) for allocation profiling,
reproduction commands, and interpretation limits. Default Java models retain Utf8
and Java collections; the Scala models return String and Scala collections. Native
readers also perform validation that the Java paths may not. Those representation
and policy differences are part of the measurements.

## Modules and next steps

- `runtime`: owned bytes, binary I/O, codec API, decode limits; no Apache Avro dependency.
- `compiler`: validated schema graph and deterministic Scala source generation.
- `java-backend`: optional Apache Avro Java encoding and decoding backend.
- `resolution`: optional native schema parsing/resolution and cached reader plans.
- `fixtures`: generated-model compilation and interoperability checks.
- `benchmarks`: JMH comparisons.
- `property-tests`: generated schemas, schema evolution, wire layouts, limits and ownership properties.

The broader [comparison corpus](benchmarks/COMPARISON.md) covers 13 input profiles
and includes a separate schema-evolution benchmark. The [testing guide](docs/testing.md)
describes the bounded, reproducible property campaigns and their remaining gaps.

Release automation is configured; repository credentials and the final Central
Portal publication step are described in [releasing](docs/releasing.md). A review
of ergonomics/readability and schema registry integration remain future work.
Optional primitive-backed collections, input reuse and bulk block skipping remain
separate experiments. A dedicated build-tool plugin and streaming/container APIs
are future work; see [the architecture notes](docs/architecture.md).
