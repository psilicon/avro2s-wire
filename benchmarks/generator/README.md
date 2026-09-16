# Benchmark baseline provenance

The checked-in baseline sources are genuine generator output. Normal compilation,
tests, and JMH runs use those sources and do not need an avro2s checkout or its
generator dependencies.

- `../src/main/scala/avrogen/benchmarks/avro2s/Trade.scala` comes from avro2s
  commit `8342d5bd467aca16265b1f083b8b6b667929ee37`, project version
  `0.29.0-SNAPSHOT`, compiled with Scala `3.3.6`. The configuration is
  `Scala_3`, `logicalTypesEnabled = false`, and `EnumType.JavaEnum`.
- `../src/main/java/avrogen/benchmarks/javaavro/Trade.java` comes from Apache
  Avro `SpecificCompiler` version `1.12.1`, using its default options and UTF-8
  output. In particular, its string field uses the default `CharSequence`
  representation. The ordinary specific-record and generated custom-coder
  benchmarks use this same generated class. Decoding may return `Utf8`, unlike
  the Scala models' `String` fields.

Both inputs come from `fixtures/src/main/resources/avro/Trade.avsc`. Only the
namespace changes, to `avrogen.benchmarks.avro2s` or
`avrogen.benchmarks.javaavro`, so the named classes can coexist. The input
schemas are saved in this directory. Field order, field schemas, and binary
layout are unchanged.

From the avrogen repository root, regenerate with a clean checkout of
[avro2s](https://github.com/psilicon/avro2s) at the pinned commit:

```sh
git clone https://github.com/psilicon/avro2s.git /path/to/avro2s-checkout
git -C /path/to/avro2s-checkout checkout --detach 8342d5bd467aca16265b1f083b8b6b667929ee37
./scripts/regenerate-baselines.sh /path/to/avro2s-checkout
```

The script requires Git, a JDK suitable for the Scala 3 build, and `sbt` on
`PATH`. Set `AVROGEN_SBT` to an sbt executable path if needed. It adds
`GenerateBaselines.scala` to avro2s's Scala 3 compilation using an in-memory
sbt session setting, then invokes the actual avro2s and Apache Avro generators.
It writes build outputs in that checkout but does not change its tracked source
or build files. No machine-specific path is stored in generated artifacts.

`baselines.properties` records generator versions/options and SHA-256 hashes of
the fixture schema, the two namespace-adjusted inputs, and the two generated
source files. It has no timestamp. Regeneration overwrites the generated files;
do not edit those files by hand.
