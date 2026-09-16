# Avrogen

A Scala 3 schema compiler and native Avro binary runtime. Generated models are
ordinary immutable Scala values; generated codecs read and write their fields
directly. The application runtime depends only on the Scala standard library and
the JDK. Apache Avro is used by the schema compiler and an optional interoperability
module.

This is the first working slice, not a complete Avro implementation. It supports
matching writer/reader schemas. Schema evolution and general unions are the next
major pieces of work.

## Build and test

Requires JDK 11 or newer and sbt. The build pins Scala 3.3.6 and sbt 1.11.0.

```sh
sbt test
sbt 'fixtures/runMain avrogen.fixtures.Demo'
```

The build generates fixture sources from `.avsc` files and compiles them against
the native runtime alone. Tests cross-read and cross-write with Java Avro's
independent generic reader/writer, in addition to binary-format and generator tests.

## Generate Scala

From this project's root:

```sh
sbt 'compiler/run fixtures/src/main/resources/avro/Trade.avsc /tmp/avrogen-generated'
```

The two arguments are an `.avsc` file (or a directory recursively containing
`.avsc` files) and an output directory. Directory generation resolves references
between files. Generated sources need `avrogen-runtime` on their compile/runtime
classpath; they do not need Apache Avro. Artifacts are not published yet.

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
import avrogen.fixtures.Trade
import avrogen.runtime.*

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

`encode` and `decode` are convenience APIs that allocate their buffers. `decode`
requires exactly one complete datum and rejects trailing bytes. `read` and `write`
provide the lower-level APIs. Codecs are shareable; mutable input/output instances
must be confined to their caller. Raw datum bytes contain no schema identifier:
the caller must already know the exact writer schema.

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
| two-branch nullable union, in either order | Option[A], preserving wire branch indices |
| recursive named records | direct references to named codecs |

Top-level schemas must be named records, enums, or fixed types. Unsupported
schemas fail generation explicitly. Logical types, general unions, empty enums,
and the identifiers `_` and `_root_` are currently rejected. Keywords are escaped;
model and enum member collisions are renamed deterministically. Wire names and
schema metadata remain intact in `schemaJson`.

Default-package types are supported, but a type in a named package cannot refer
to a default-package type in Scala. Those schemas, and default-package type names
that collide with generated members, are rejected with guidance to add a namespace.

Defaults and aliases are retained as metadata. They do not yet drive reader-schema
resolution or Scala constructor defaults. The decoder does not accept an alternate
writer schema. Avro IDL, JSON datum encoding, object container files, compression,
schema registries, Kafka serializers, and Scala `derives` support are not yet
implemented.

`Bytes.fromArray` and `Bytes.toArray` copy to protect ownership. Decoded byte/fixed
values own their storage. `BinaryInput` borrows its input array for the duration of
decoding; callers must not mutate that array concurrently. Primitive values inside
Vector, Map, and Option can still box; this prototype does not claim zero allocation.

Native decoding validates bounds, varint widths, UTF-8, collection block sizes,
and generated union/enum indices. `DecodeLimits` controls input size, string/byte
lengths, cumulative collection item counts, and nesting depth. Limits apply to the
input instance, so a fresh input resets the budget. Invalid input raises
`AvroDecodingException`. Native string encoding rejects unpaired UTF-16 surrogates.

## Java Avro comparison adapter

`java-interop` supplies `JavaAvroInput` and `JavaAvroOutput`, which delegate
primitive calls to Java Avro's `Decoder` and `Encoder`. This lets the same generated
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
generated custom coders, native Avrogen codecs, those same codecs using Java
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

The [first measured baseline](docs/benchmarks/results-2026-09-17.md) compares
avro2s, Java Avro, and Avrogen with raw results and source hashes.

See the [benchmark protocol](docs/benchmarks/README.md) for allocation profiling,
reproduction commands, and interpretation limits. Default Java models retain Utf8
and Java collections; the Scala models return String and Scala collections. Native
readers also perform validation that the Java paths may not. Those representation
and policy differences are part of the measurements.

## Modules and next steps

- `runtime`: owned bytes, binary I/O, codec API, decode limits; no Apache Avro dependency.
- `compiler`: validated schema graph and deterministic Scala source generation.
- `java-interop`: optional Java primitive adapters.
- `fixtures`: generated-model compilation and interoperability checks.
- `benchmarks`: JMH comparisons.

Next: use the measured baselines to guide runtime changes; implement cached
writer/reader schema resolution; preserve branch identity for general unions;
add logical types; then build container/registry integrations. Broaden performance
coverage to strings, bytes, nesting, unions, and evolution. See
[the architecture notes](docs/architecture.md).
