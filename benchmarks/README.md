# Benchmarks

Start here for commands, measurement policy and results.

- [Stack-safety comparison: 26 September 2026](stack-safety-results.md) compares
  direct and stack-safe codecs across 18 cases, including latency, allocation,
  raw measurements and the limits of the comparison.

- [Selected reference results: 17 September 2026](reference/2026-09-17/README.md)
  contains 148 implementation comparisons, eight evolution measurements and 15
  explicit String-output controls. These are historical measurements, not a
  fresh run of the current checkout.
- [Comparative workloads](COMPARISON.md) describes the schemas, representations
  and supported implementations, including the targeted big-decimal workload.
- [Baseline provenance](generator/README.md) records the genuine generated Java
  and avro2s sources, pinned versions and regeneration procedure.
- [Historical evidence and recovery](../docs/benchmarks/HISTORY.md) explains where
  earlier experiments, patches, logs and reports can be recovered.

## Run

Run from the repository root with Python 3 and sbt on `PATH`. Select the exact
JDK executable for both sbt and JMH forks; JDK 21 is the reference environment.

```sh
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile comparison
```

Every run gets a new `benchmarks/results/<UTC timestamp>-<profile>/` directory.
This directory is ignored by Git and survives `sbt clean`. Successful runs produce
`report.md`, raw JMH JSON, logs and `environment.json`. The metadata records the
source revision and hashes, JVM, commands, settings and expected case counts.
Dirty source runs additionally retain a source snapshot. Existing artifacts are
never silently overwritten.

The runner first runs `benchmarks/test`, checks complete result membership and
finite timing/allocation scores, and rejects source changes during measurement.
Use `--skip-tests` only after separately validating the same sources. Reports are
written only for successful runs. `--output` chooses another destination;
`--label` optionally prefixes result filenames for older scripted commands.

| Profile | Purpose | Default measured cases |
| --- | --- | ---: |
| `comparison` | Native Wire, Wire Java backend, avro2s and supported official Java variants across 13 workload configurations | 148 |
| `evolution` | Different writer/reader schemas; resolved reads, same-schema control and plan construction reported separately | 8 |
| `decoded-strings` | Explicit String-output readers; distinct from default Java Utf8 results | 15 |
| `big-decimal` | Native Scala/Java decimal values and Java Avro conversion; 6/50/500 digits at scales 0/6/-6 | 54 |
| `api` | Allocating native encode/decode convenience APIs | 14 |
| `full` | All five profiles above without duplicate measurements | 239 |
| `trade` | Original six-engine Trade workload, collection sizes 0/32/1024 | 36 |
| `strings` | Native and Java-backend read/write/encode/decode across the detailed text corpus | 136 |
| `stack-safety` | Direct and stack-safe native codecs: allocating encode/decode and planned resolution across shallow, collection-heavy and recursive values | 18 |
| `focused` | Integer writes, string reads/writes and collection reads for investigations | — |
| `pilot` | Short native-only diagnostic runs; unsuitable for performance claims | — |

Plan commands and case counts without running sbt or writing output:

```sh
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile full --dry-run
```

Select a focused experiment or a quick execution smoke check:

```sh
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile big-decimal
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile stack-safety
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile comparison --param profile=string-ascii --filter '[.]ComparisonBenchmark[.].*Read$'
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile big-decimal --param digits=6 --param scale=0 --forks 1 --warmup-iterations 1 --measurement-iterations 1 --warmup-time 100ms --measurement-time 100ms
```

A smoke check establishes that a path executes; it does not establish speed.
For repeatable comparisons, keep timing settings, JDK, hardware and system load
consistent. Normal profiles use two forks, three 500 ms warmups and five 500 ms
measurements, one thread and a 512 MiB heap. `pilot` deliberately uses shorter
settings. All actual settings appear in the output metadata and report.

The `stack-safety` profile is an explicit experiment and is not included in `full`.
Its `execution=direct,stack-safe` parameter selects the codec before timing;
`shape=shallow,collections,recursive` selects a flat Trade, an array/map workload,
or a 32-record chain whose recursive field precedes another field. Each pair uses
identical inputs, wire bytes and immutable result types. `encode` includes a fresh
output and final owned byte-array copy; `decode` includes a fresh input and the
end-of-input check. `resolvedDecode` uses a cached resolution plan: shallow and
collection schemas differ in metadata to exercise plan execution, and the recursive
case also reorders fields, promotes an integer and supplies a reader default.
Codec selection, fixture creation and plan compilation are outside timing. The
report retains the execution parameter and separates these three operations;
latency is in ns/op and allocation in B/op. Deep small-stack correctness tests
are separate from this performance experiment, so the direct mode is measured
on inputs both implementations support.

## Reports

The runner and standalone formatter use the same report implementation:

```sh
python3 scripts/benchmark_report.py /path/to/run/Comparison.json /path/to/run/NestedComparison.json --metadata /path/to/run/environment.json -o /path/to/run/report.md
```

Supply only JMH result arrays as positional inputs, never `environment.json`.
Repeat `--metadata` for separate recorded campaigns. Duplicate measurements are
rejected: render repeated experiments separately instead of silently averaging
them or picking favorable runs. The selected reference includes its complete
regeneration command.

Reports show average ns/op, JMH's 99.9% confidence error and allocated B/op for
each explicitly named implementation. Missing comparisons are `N/A`.
Wire's Java backend is the same generated Scala codec using Java primitive IO;
it is distinct from official Java datum readers/writers. The Java-valued native
big-decimal path still uses native IO. Evolution plan construction and same-schema
controls do different work from resolved reads and have separate sections.

## Measurement policy

- Construct inputs outside timing. Compare the same valid Avro datum, retaining
  union branch identity, numeric kinds and exact logical values during verification.
- Read paths allocate fresh input contexts and fresh result models. Reusable
  reader/codec state is prepared outside timing; mutable result reuse is not timed.
- Direct writes reuse output buffers and flush Java encoders per operation. They
  return byte counts and exclude the final byte-array copy. `Encode`/`Decode`
  convenience APIs are reported separately because their allocation contracts differ.
- Java-specific/custom/generic and avro2s baselines retain their actual model types.
  Default Java readers may return Utf8; String controls explicitly materialize
  Strings. Scala collections, byte ownership and strict native validation also
  have real costs. Neither different representations nor validation policies are
  hidden inside a blanket "Java" label.
- `gc.alloc.rate.norm` is allocated heap bytes per operation, including temporary
  objects. It is neither retained heap nor peak process memory. Small infrastructure
  contributions and measurement variation remain possible.
- Small mean differences may be noise. Confidence errors are reported per score;
  they do not establish a confidence interval for a ratio or a paired significance
  test. Microbenchmarks do not establish application latency or throughput.

Benchmarks use JMH. Correctness tests verify cross-reading/writing and actual Java
custom-coder dispatch; their generated baselines are unmodified. Java fast readers
bypass custom decoding, so only the explicitly labeled custom path disables that
optimization. Unsupported custom coders and unsupported avro2s logical models
are left out rather than relabeled fallback implementations.

## What belongs in Git

Keep benchmark source, correctness tests, schemas, pinned generated reference
models, regeneration tools and documentation. Retain a small selected reference
with its raw measurements, environment metadata and source provenance when making
a performance claim. Reference results are copied deliberately, never automatically
promoted from local runs.

Keep development runs, smoke results, failed runs, logs, dirty-source snapshots and
experimental patches under ignored `benchmarks/results/`. Do not globally ignore
JSON, generated source, patches or archives: those formats have legitimate tracked
uses elsewhere. Older evidence remains recoverable from Git history; cleanup does
not rewrite history.

Check the Python tooling without running JMH:

```sh
python3 -m unittest discover -s scripts/tests -v
```
