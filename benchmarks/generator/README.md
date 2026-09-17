# Benchmark baseline provenance

The checked-in baseline sources are genuine generator output. Normal compilation,
tests, and JMH runs use those sources and do not need an avro2s checkout or its
generator dependencies.

- `../src/main/scala/avro2s/wire/benchmarks/avro2s/Trade.scala` comes from avro2s
  commit `8342d5bd467aca16265b1f083b8b6b667929ee37`, project version
  `0.29.0-SNAPSHOT`, compiled with Scala `3.3.6`. The configuration is
  `Scala_3`, `logicalTypesEnabled = false`, and `EnumType.JavaEnum`.
- `../src/main/java/avro2s/wire/benchmarks/javaavro/Trade.java` comes from Apache
  Avro `SpecificCompiler` version `1.12.1`, using its default options and UTF-8
  output. In particular, its string field uses the default `CharSequence`
  representation. The ordinary specific-record and generated custom-coder
  benchmarks use this same generated class. Decoding may return `Utf8`, unlike
  the Scala models' `String` fields.

Both inputs come from `fixtures/src/main/resources/avro/Trade.avsc`. Only the
namespace changes, to `avro2s.wire.benchmarks.avro2s` or
`avro2s.wire.benchmarks.javaavro`, so the named classes can coexist. The input
schemas are saved in this directory. Field order, field schemas, and binary
layout are unchanged.

From the avro2s-wire repository root, regenerate with a clean checkout of
[avro2s](https://github.com/psilicon/avro2s) at the pinned commit:

```sh
git clone https://github.com/psilicon/avro2s.git /path/to/avro2s-checkout
git -C /path/to/avro2s-checkout checkout --detach 8342d5bd467aca16265b1f083b8b6b667929ee37
./scripts/regenerate-baselines.sh /path/to/avro2s-checkout
```

The script requires Git, a JDK suitable for the Scala 3 build, and `sbt` on
`PATH`. Set `AVRO2S_WIRE_SBT` to an sbt executable path if needed. It adds
`GenerateBaselines.scala` to avro2s's Scala 3 compilation using an in-memory
sbt session setting, then invokes the actual avro2s and Apache Avro generators.
It writes build outputs in that checkout but does not change its tracked source
or build files. No machine-specific path is stored in generated artifacts.

`baselines.properties` records generator versions/options and SHA-256 hashes of
the fixture schema, the two namespace-adjusted inputs, and the two generated
source files. It has no timestamp. Regeneration overwrites the generated files;
do not edit those files by hand.

## Broader comparison corpus

The same regeneration command also processes the schemas in
`fixtures/src/main/resources/avro/comparison`. Their outputs live below
`avro2s.wire.benchmarks.comparison.avro2s` and
`avro2s.wire.benchmarks.comparison.javaavro`. This corpus enables avro2s logical
types and Java's decimal logical types, retaining Java's default string mapping.
The original Trade generation settings remain unchanged.

`comparison-baselines.properties` hashes every corpus input, relocated schema,
generated model, and generated dispatch-probe source. It records whether each
Java root actually contains generated custom coders. The probes subclass genuine
generated classes only during setup/tests; timed classes are unmodified.

Avro2s generation is explicitly omitted for ComparisonDecimal because the pinned
generator does not implement decimal logical conversions. Java custom methods are
absent from the nested union, logical and decimal baseline schemas; the benchmark
does not label their fallback readers/writers as custom implementations. See
[comparative workloads](../COMPARISON.md) for the complete matrix and semantics.
