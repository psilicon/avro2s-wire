# Avro codec benchmarks

This is the maintained benchmark suite for avro2s-wire. The exact inputs and
avro2s subset are defined in [the catalogue](src/main/resources/suite/catalog.json).
The [generated-baseline manifest](generator/suite-baselines.json) pins Apache
Avro, the avro2s revision and Scala-enum setting, generated source hashes, and
actual Java custom-coder capabilities. Unsupported custom coders are N/A;
ordinary readers are never relabelled as custom coders.

There are 61 controlled input cases: 58 encode/decode pairs and three decode-only
schema-resolution cases. Every supported operation runs against Wire, Java
specific and Java generic. Genuine generated Java custom coders run where supported. The 17-case
avro2s subset covers the agreed primitives, arrays/maps, ASCII strings and
unions; its enum is a Scala 3 enum. There is no weighted overall score or mixed
payload headline benchmark.

Apache Avro 1.12.1's timestamp-nanos and local-timestamp-nanos conversions encode
fractional pre-epoch values incorrectly (a 999 ms shift). Java-specific and
Java-generic encoding for L06 and L09 is therefore N/A with an explicit reason;
their decoding remains measured. The agreed corpus keeps its negative dates.
No reduced date range or replacement conversion is substituted to obtain a timing.

## What an operation includes

- Encoding starts with the library's public model and returns a **fresh byte
  array**, including output allocation and the final byte-array copy.
- Decoding consumes a prepared encoded datum, creates a **fresh result model**,
  and verifies complete consumption. Text is materialized as **String** for
  every implementation, including generated Java custom coders.
- Each engine retains its actual public collection, binary and logical types.
  Allocation differences caused by those models remain visible.
- Schemas, readers, resolution plans and a deterministic 256-input corpus are
  prepared outside measurement. One invocation processes one corpus element;
  fixture construction, correctness assertions and manually batched codec loops
  do not enter the timed path.
- The catalogue names text sizes explicitly: 48 bytes, 3 KiB and 192 KiB of
  UTF-8 text. These are separate cases for ASCII, Latin-1, two-byte BMP,
  three-byte BMP and supplementary Unicode characters.

Correctness tests check cross-engine values and wire interoperability, fresh
results, generated custom dispatch, String output, and the agreed input shapes.
Passing those tests is necessary before measurement. Performance stability is a
separate question; a complete run is not automatically a publishable claim.

## Commands

Use Python 3.9 or later and sbt on PATH. Supply the absolute path to the exact
native JDK executable; the same JDK is used for compilation and every JMH fork.

```sh
# Inspect exact membership, execution order and timed-stage duration first.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile full --dry-run

# Execution smoke check: P03, Wire and Java specific, encode/decode.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile smoke

# Short pilot: P03, 192 KiB ASCII (T11), 1024-int array (C02).
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile pilot

# Complete measurement campaign, after reviewing the pilot.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile full
```

`--dry-run` does not run sbt, start JMH or create output. It validates the pinned
generated-source hashes and prints every selected case, operation and engine,
including the deterministic schedule. Use `--case P03` and/or `--engine wire`
(repeatable) to narrow diagnostic work. Any narrowed run is marked diagnostic,
even when it uses the full timing settings.

The runner calls sbt **once** for `benchmarks/test`, `benchmarks/Jmh/compile` and
the compiled JMH classpath. Measurements then launch `org.openjdk.jmh.Main`
directly, with one fresh fork per selected cell per round. It does not repeatedly
start sbt thousands of times. `--skip-tests` is an explicit escape hatch after
separately validating the exact same sources; its use is recorded.

## Frozen measurement protocol

| Setting | Full campaign | Short pilot | Smoke check |
| --- | --- | --- | --- |
| Independent rounds / fresh JVMs per cell | 5 | 2 | 1 |
| Warmup in each JVM | 10 × 1 second | 10 × 1 second | 1 × 100 ms |
| Measurement in each JVM | 10 × 1 second | 10 × 1 second | 1 × 100 ms |
| Worker threads | 1 | 1 | 1 |
| Mode | Average time, ns/op | Same | Same |
| Heap and collector | `-Xms1g -Xmx1g -XX:+UseG1GC` | Same | Same |
| Profiler | JMH `gc`, including allocated B/op | Same | Same |

The pilot runs 12 case/operation/engine cells, each in two fresh JVMs: **24 forks,
480 seconds** of warmup and measurement, approximately 10–15 minutes including
startup/setup and inspection. It covers small-operation overhead, large strings
and returned byte arrays, and collection allocation. It does not establish the
stability of every engine or logical/resolution case.

The exact full matrix depends on the verified custom-coder capabilities. At
468 cells, five rounds mean 2,340 fresh JVM forks and **13 hours** of warmup
and measurement. Compilation, tests, process startup,
corpus/reader setup, reporting and GC overruns add time. The dry-run plan is the
authority if the capabilities change. No timing estimate includes implementation
work or an independent confirmation campaign.

Within each round, engines for the same case and operation run adjacent to one
another. Their initial order is seeded and their positions rotate between
rounds. The seed and entire schedule are preserved. This reduces systematic
engine/order confounding; it does not eliminate heat, background work or drift.

## Reviewing stability and uncertainty

Inspect warmup and measured iteration traces and the independent JVM means
before quoting results. Look for continuing warmup improvement, long pauses,
session drift or forks that settle at different levels. More measurement time
inside a single JVM does not provide additional independent JVM starts.

Reports show timing and allocation with **pointwise 95% Student t intervals over
round means**. Each independently started JVM contributes one observation,
regardless of how many iterations it contains. The five-fork protocol therefore
has four degrees of freedom, not 49. Approximate normality and independence of
round means are assumptions, not facts established by five forks.

A reference/Wire ratio is computed within each matched round; its geometric
mean and interval use Student t on the five log ratios. The predeclared practical
ratio band is **0.95–1.05**. An interval fully inside that band supports practical
similarity under the measured conditions; one entirely beyond the band supports
a meaningful directional difference under the statistical assumptions. An
interval crossing a boundary leaves that conclusion unresolved. The report
keeps the numerical intervals and does not assign winner labels.

These are individual comparison intervals, not a simultaneous guarantee over
hundreds of comparisons. There is no overall speedup or win count. A quotable
claim needs independent-session confirmation. Do not select the fastest fork,
discard a slower valid observation, or repeatedly run until an interval favours
an implementation. If further measurement is needed, agree its budget and
include every competing engine in that comparison; retain the original results.

`gc.alloc.rate.norm` measures allocated heap bytes per operation, including
temporary objects. It is not retained heap or peak process memory. GC count/time
and all JMH secondary metrics remain in the raw JSON. Timing intervals describe
mean-operation uncertainty, not p95 or p99 latency.

## Machine preparation

Use an adequately rated charger, disable Low Power Mode, finish compilation and
downloads before measurement, and pause substantial background builds, VMs,
backups and syncs. Keep the laptop on a ventilated surface with the lid open and
leave it unused. On macOS the runner uses a temporary `caffeinate -i` process to
prevent idle system sleep while permitting display sleep; it does not change
permanent power settings. Avoid changing machine settings midway through a
campaign. Power/load observations are diagnostics, not proof of a quiet system.

## Artifacts and provenance

Every run uses a new, ignored `benchmarks/results/<timestamp>-<profile>/`
directory; `--output` can choose another new directory. No artifact is silently
overwritten or committed. Each directory contains:

- `environment.json`: exact Java executable/version, JVM flags, bounded child
  environment, source revision/status/hashes, catalogue, baseline manifest,
  complete execution order, commands and status.
- `source-snapshot.tar.gz`: every fingerprinted source input, including modified
  and untracked sources. Restore it over the recorded Git revision and remove
  the recorded deleted source paths to reproduce the measured source tree.
- `raw/`: separate JSON and console log for each round/case/operation/engine.
  Logs retain warmup measurements; JSON retains all measured iteration data.
- `records.json`: the same raw measurements with explicit round identity.
- `report.md`: per-case timing/allocation intervals, paired ratios, individual
  JVM means, raw-result links, and capability-based N/A explanations.
- `SHA256SUMS`: checksums for all retained artifacts, including nested raw files.

The runner fails on missing, duplicate, nonfinite or mismatched measurements,
missing allocation data, source changes, changed compiled classpath, wrong JVM
flags or incomplete rounds. Failed campaigns keep their evidence and have no
successful report. Ambient Java/sbt option variables are not inherited; their
names are recorded when present. Standard JIT optimization remains enabled.

Regenerate a completed campaign's report without rerunning measurements:

```sh
python3 scripts/benchmark_suite_report.py benchmarks/results/<run>
python3 -m unittest discover -s scripts/tests -p 'test_benchmark_suite*.py' -v
```

Keep the maintained sources, schemas, tests, generator tools, manifests and this
protocol in Git. Local measurements, failed runs, logs and source snapshots stay
under ignored results. Earlier exploratory suites and reports are historical
material recoverable from Git; they are not another current headline suite.
