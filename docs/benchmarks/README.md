# Benchmark protocol

The original measurements compare one matching-schema `Trade` workload. Its fields are
`id: long`, `symbol: string`, `price: double`, and `quantities: array<int>`.
Collection sizes are 0, 32, and 1024. Integer elements are outside the JVM's
small-integer cache so allocation measurements include boxing. This is a focused
baseline, not a claim about every Avro workload.

Additional matching-schema workloads now cover integers, strings, bytes,
collections and nested unions. See the [expanded workload guide](expanded-workloads.md)
for the 192-case matrix, allocating APIs and reproducible profile runner. The
[performance follow-up](performance-2026-09-17.md) records paired optimisation
results and a fresh six-way Trade comparison; the historical report below remains
unchanged.

The [broader comparison corpus](../../benchmarks/COMPARISON.md) adds 13 input
profiles, with genuine Java and avro2s generated baselines where supported.
It covers 148 read/write cases, including temporal/UUID values and high-precision
decimals. Unsupported custom coders and avro2s logical decimal models are excluded
explicitly. The separate evolution profile contains eight cases.

## Implementations

| Prefix | Implementation | Returned model |
| --- | --- | --- |
| `avro2s` | Pinned avro2s Scala 3 generated class, stock specific reader/writer, fast reader enabled | Scala String and List[Int] |
| `javaSpecific` | Apache Avro 1.12.1 generated Java class, custom coders disabled, fast reader enabled | CharSequence (normally Utf8 on read) and Java List[Integer] |
| `javaCustom` | Same generated Java class, custom coders enabled, fast reader disabled | CharSequence and Java List[Integer] |
| `native` | avro2s-wire-generated codec and native binary input/output | Scala String and Vector[Int] |
| `javaPrimitives` | Same avro2s-wire-generated codec with Java primitive adapters | Scala String and Vector[Int] |
| `javaGeneric` | Java Avro generic reader/writer | GenericRecord, Utf8, and generic Java collection |

The checked-in comparison classes are genuine generator output. See
[baseline provenance](../../benchmarks/generator/README.md) and the
[hash manifest](../../benchmarks/generator/baselines.properties). Only namespaces
differ so all models can coexist. Field order, types, values, and datum wire layout
are the same. This does not measure schema resolution between different versions.

## Fairness and interpretation

- All implementations run in the same JVM configuration, on the same machine.
- Writers receive preconstructed values and reuse their output buffers and codec
  objects. Java encoders are flushed on each operation. No timed writer copies
  the final output array; they return the byte count.
- All string inputs are the same String, including Java's CharSequence fields.
  Java inputs are not pre-encoded Utf8 objects.
- Readers get the same encoded bytes, create fresh decoder/input objects, and
  return fresh results. Datum readers and their cached schema-resolution machinery
  are reused. Mutable record reuse is deliberately a separate future experiment.
- Result models remain in their normal representation. Converting Java's result
  to Scala inside timing would measure a different API. Generic records are shown
  separately from generated specific records. Java's Utf8 result postpones Unicode
  decoding; the Scala results already contain String values. This comparison
  measures the normal returned representations, not equal string-materialization
  work. All of these collection representations retain boxed integer elements.
- Native validation remains enabled. It includes strict UTF-8 and decode limits;
  Java paths use Apache Avro's validation policy. Performance therefore includes
  both representation and validation differences.
- Trial setup checks every writer against every reader before accepting timed
  work. Tests separately verify custom-coder activation and standard-path behavior.

Apache Avro 1.12.1's fast reader bypasses generated `customDecode`. The custom
baseline therefore disables that reader explicitly. Standard Java, avro2s, and
generic baselines keep it enabled. All flags are set on isolated data models so
external JVM-global properties cannot silently change a comparison path.

## Running

From the project root, choose an absolute results path and an explicit fork JVM:

```sh
sbt 'benchmarks/Jmh/run -jvm /absolute/path/to/java -jvmArgs "-Xms512m -Xmx512m" -wi 5 -i 5 -w 1s -r 1s -f 2 -prof gc -rf json -rff /absolute/path/to/baseline.json .*TradeBenchmark.*'
python3 scripts/summarize-benchmarks.py /absolute/path/to/baseline.json
```

JMH reports average nanoseconds per operation. Lower is better. `gc.alloc.rate.norm`
reports allocated bytes per operation; use that instead of MB/s when comparing
allocation costs across implementations with different throughput. Printed JMH
score errors use a 99.9% confidence interval for each score; simple ratios of means
do not themselves have confidence intervals.

Retain raw JSON alongside conclusions, disclose JVM and warmup settings, and avoid
generalising a single schema's results to files, compression, registries, schema
evolution, or application-level latency. Container and registry overhead are not
part of this datum-codec benchmark.

## Recorded run

The [17 September 2026 report](results-2026-09-17.md) retains the
[raw JMH JSON](baseline-2026-09-17.json) and
[environment and source hashes](baseline-2026-09-17.environment.json).
This run predates the union, logical-type, and schema-resolution additions. Its
measured sources are preserved in commit `ee45079b83471a34cf1fe457fa0ee141fbaf4fdd`.
To reproduce that source version, create a separate checkout of that commit.
The source verifier will correctly report differences on newer working sources.
From the historical checkout, verify the source hashes and regenerate its tables:

```sh
python3 scripts/summarize-benchmarks.py docs/benchmarks/baseline-2026-09-17.json --verify-sources docs/benchmarks/baseline-2026-09-17.environment.json
```

Run `sbt test` before collecting a new baseline. A clean checkout resolves its
pinned dependencies through sbt; no source-JAR inspection or manual cache setup is
part of testing or measurement. Generator output is checked in; the separate
[regeneration script](../../scripts/regenerate-baselines.sh) requires the exact
avro2s commit documented in the provenance. Reproducing the procedure does not
guarantee identical timings on a different JVM, machine, or system load.

## Schema-evolution harness

`EvolutionBenchmark` exercises generated old/new Account schemas with aliases,
field reordering, defaults, enum fallback, union selection, numeric promotion, and
skipped byte payloads of 0 or 4096 bytes. It separates reusable native and Java
readers from native plan construction. `nativeSameSchema` reads the old model and
retains the byte payload, so it is a reference cost rather than identical work.
Java returns GenericRecord/Utf8; native returns the generated Scala model.

```sh
sbt 'benchmarks/Jmh/run -jvm /absolute/path/to/java -jvmArgs "-Xms512m -Xmx512m" -wi 5 -i 5 -w 1s -r 1s -f 2 -prof gc -rf json -rff /absolute/path/to/evolution.json .*EvolutionBenchmark.*'
```

The historical Trade summarizer expects Trade's `collectionSize` parameter.
Use `scripts/summarize-comparison.py` for the broader comparison and evolution
results. Reproducible profiles retain the exact commands, source hashes, raw JSON,
and local logs, and reject incomplete results or source changes during a run:

```sh
python3 scripts/run-performance.py --java /absolute/path/to/java --output /absolute/path/to/results --label comparison --profile comparison
python3 scripts/run-performance.py --java /absolute/path/to/java --output /absolute/path/to/results --label evolution --profile evolution
python3 scripts/summarize-comparison.py /absolute/path/to/results/comparison-*.json /absolute/path/to/results/evolution-Evolution.json
```

Pass only JMH result JSON files to the summarizer, excluding `.environment.json`.
Short smoke runs only check execution and setup invariants.
