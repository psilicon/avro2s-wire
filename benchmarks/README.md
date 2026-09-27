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

## Implementations and Wire configurations

Use repeatable `--engine` flags to choose the implementations in a comparison.
The existing default campaigns keep their original five engines (four in reuse
mode); the additional Wire configurations are explicitly selected.

| Engine | Generated model/codec | Binary I/O |
| --- | --- | --- |
| `wire` | Wire, direct codec | Native |
| `wire-stack-safe` | Wire, stack-safe codec | Native |
| `wire-java` | Wire, direct codec | Java Avro through `JavaAvroInput`/`JavaAvroOutput` |
| `wire-java-stack-safe` | Wire, stack-safe codec | Java Avro through the same adapters |
| `java-specific` | Official generated Java specific records | Java Avro specific datum readers/writers |
| `java-generic` | Official generic records | Java Avro generic datum readers/writers |
| `java-custom` | Official generated Java custom coders, where supported | Java Avro |
| `avro2s` | Pinned base avro2s generated Scala records | Java Avro specific machinery |

All four Wire engines support the complete catalogue in fresh and reuse modes,
including schema evolution. The benchmark build generates both Wire codecs;
this does not change the library generator's default. The Java backend retains
Wire's Scala model and logical conversions, so it is a different comparison
from official `java-specific`. The avro2s engine supports fresh mode only.

`--reference-engine` chooses the denominator for timing ratios (default `wire`).
For example, with reference `wire-java`, a `wire-stack-safe / wire-java` ratio
above one means native stack-safe execution took longer. Results always retain
the absolute timing and allocation values as well.

Apache Avro 1.12.1's timestamp-nanos and local-timestamp-nanos conversions encode
fractional pre-epoch values incorrectly (a 999 ms shift). Java-specific and
Java-generic encoding for L06 and L09 is therefore N/A with an explicit reason;
their decoding remains measured. The agreed corpus keeps its negative dates.
No reduced date range or replacement conversion is substituted to obtain a timing.

## What an operation includes

The default `fresh` usage mode preserves the original campaign's contract:

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

The `reuse-check` diagnostic uses a separate `reuse` usage mode:

- Wire retains a caller-owned `BinaryOutput`, calls `reset()` and `codec.write`,
  and returns `toByteArray()`. Wire decoding still calls `codec.decode`; its
  current `BinaryInput` has no reset API.
- Java retains Apache Avro's official `RawMessageEncoder` and `RawMessageDecoder`,
  with the same specific, generic or custom data model. These helpers internally
  reuse their direct encoder/decoder and streams. The encoder's default copying
  mode returns an independently owned `ByteBuffer`; Wire returns an independently
  owned byte array. Each natural result reaches JMH without an extra conversion.
- All decoders return fresh result models and materialize text as `String`.
  The official Java raw decoder accepts trailing bytes; Wire rejects them. This
  is a valid-message performance comparison, not validation-equivalent decoding.
- This changes Java's encoder/decoder kind as well as reuse: the original fresh
  path uses buffered encoding. It measures the official raw-message API and
  must not be described as isolating buffer reuse alone. No concurrency policy
  or new reuse API is added to the Wire runtime.

Here, the `BinaryOutput` path means native `wire`/`wire-stack-safe`, and the raw
message helpers mean official `java-specific`/`java-generic`/`java-custom`.
The Wire Java backend (`wire-java`/`wire-java-stack-safe`) instead uses the public
`JavaAvroOutput`/`JavaAvroInput` adapters. Fresh mode creates a buffered encoder
and array decoder; reuse mode reconfigures and retains those same kinds of
encoder/decoder, adapters and output stream. It always copies the output into
an owned array and checks complete input consumption. Its buffered/array I/O
therefore differs from the direct stream I/O inside the official raw helpers.

Wire encoding rejects unpaired UTF-16 surrogates by default. Use
`--wire-string-policy replace` to select the runtime's immutable writer setting
that permits the JDK's replacement with ASCII `?`, matching Java Avro's string
encoding. The policy is selected outside measurement for both fresh and reused
outputs; Java code paths and all decoders are unchanged. The valid text corpus is
unchanged, so this measures the cost of the policy rather than malformed inputs.
Replacement runs are explicitly marked diagnostic, and the policy is frozen in
the plan, JMH parameters, raw records and report. Archived runs without this
parameter retain their original contract and report; they are not relabelled.

The policy applies to `wire` and `wire-stack-safe`. The Java-backed Wire engines
use Java Avro's replacement behavior regardless of this flag, because the public
`JavaAvroOutput` API delegates string encoding to Java. Select `replace` for a
comparison where both backends use that policy. Their input adapters also retain
Java's UTF-8 decoding behavior; this is not validation-equivalent to native input.

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

# Approved ten-operation diagnostic using the official Java raw-message helpers.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile reuse-check

# Targeted follow-up: large strings/binary, collections and five-byte-long decode.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick --usage reuse \
  --select T11:encode --select T11:decode --select B03:encode \
  --select C02:decode --select C04:decode --select P06:decode

# Java-compatible string replacement: Wire versus Java specific, same reuse APIs.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick --usage reuse \
  --wire-string-policy replace --select T11:encode --select P06:decode \
  --engine wire --engine java-specific

# Every primitive case, both operations: native versus Java-backed Wire.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick --usage reuse \
  --case 'P*' --engine wire --engine wire-java --dry-run

# Every text encoding: compare direct and stack-safe codecs on both backends.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick --usage reuse \
  --select 'T*:encode' --wire-string-policy replace \
  --engine wire --engine wire-stack-safe --engine wire-java --engine wire-java-stack-safe --dry-run

# Compare two variants without the ordinary native direct engine.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick --usage reuse \
  --case 'R*' --engine wire-java --engine wire-java-stack-safe \
  --reference-engine wire-java --dry-run
```

`--dry-run` does not run sbt, start JMH or create output. It validates the pinned
generated-source hashes and prints every selected case, operation and engine,
including the deterministic schedule. Use `--case P03` and/or `--engine wire`
(repeatable) to narrow diagnostic work. Any narrowed run is marked diagnostic,
even when it uses the full timing settings.

Case selectors accept case-sensitive glob patterns against catalogue IDs:
`--case 'P*'` selects primitives, `--case 'T*'` selects text, and
`--select 'T*:encode'` selects only text encoding. Quote patterns so the shell
does not expand them. Repeat selectors to combine families. Overlapping patterns
do not repeat measurements; unmatched patterns fail instead of producing an
empty or broader campaign. `--case` and `--select` cannot be mixed. The expanded
case/operation/engine list is frozen in the dry-run plan and run metadata.

`quick` reduces time per measurement, not default membership: without selectors
it still runs the complete default matrix. `reuse-check` has fixed membership;
use `quick` or `full` for custom engine/case selections. Remove `--dry-run` from
the examples above to execute after checking the estimated duration.

## Comparing configurations in one campaign

Use repeatable `--variant 'name=LABEL,engine=ENGINE,usage=USAGE,string-policy=POLICY'`
to vary usage or native string policy within a single campaign. The name identifies the report row and
raw files. Each configuration gets its own JVM per round; configurations for
the same case and operation are adjacent, with order rotated between rounds.
Output ownership remains the same in fresh and reuse mode.

```sh
# Native Wire fresh versus native Wire reuse, with the same replacement policy.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick \
  --select 'P*:encode' \
  --variant 'name=fresh-output,engine=wire,usage=fresh,string-policy=replace' \
  --variant 'name=reused-output,engine=wire,usage=reuse,string-policy=replace' \
  --reference-variant fresh-output --dry-run

# Strict versus replacement encoding, with reuse held constant.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick \
  --select 'T*:encode' \
  --variant 'name=strict,engine=wire,usage=reuse,string-policy=reject' \
  --variant 'name=replacement,engine=wire,usage=reuse,string-policy=replace' \
  --reference-variant strict --dry-run

# Compare multiple libraries under both lifecycle settings in one campaign.
python3 scripts/run-benchmarks.py --java "$JAVA_HOME/bin/java" --profile quick \
  --select P03:encode \
  --variant 'name=wire-fresh,engine=wire,usage=fresh,string-policy=replace' \
  --variant 'name=wire-reuse,engine=wire,usage=reuse,string-policy=replace' \
  --variant 'name=java-fresh,engine=java-specific,usage=fresh' \
  --variant 'name=java-reuse,engine=java-specific,usage=reuse' \
  --reference-variant wire-fresh --dry-run
```

Each variant requires `name`, `engine` and `usage`. `name` is an arbitrary report
label; it does not change behavior. `usage=reuse` retains working buffers while
still returning independently owned output. `string-policy=replace` concerns
malformed UTF-16, not buffer reuse. Fields can appear in any order. Duplicate,
unknown or empty fields are rejected.

For native Wire, fresh versus reuse changes encoding only: both decode modes
still use `codec.decode` and create a `BinaryInput`. Select encode operations
when investigating native output reuse; native decode rows would exercise the
same implementation twice. Java-backed Wire and the official Java engines also
have different fresh/reuse decoder lifecycles as documented above.

In variant mode, `--reference-variant` names the ratio denominator and defaults
to the first declared variant. For `reused-output / fresh-output`, a ratio below one means reuse
took less time. The optional policy defaults to `--wire-string-policy` (itself
`reject` by default); Java-backed engines retain their fixed replacement policy.
Do not combine `--variant` with `--engine`, `--usage` or `--reference-engine`:
each variant already specifies those settings. `reuse-check` keeps its fixed
membership and cannot accept variants. Plans and reports retain each variant's
effective engine, lifecycle, string policy and API contract.

Use repeatable `--select CASE:OPERATION` to choose exact case/operation pairs
instead of `--case`. The `quick` profile uses two independent rounds, with
5 × 1-second warmup and 5 × 1-second measurement per JVM. It defaults to fresh
usage; select `--usage reuse` explicitly for the official-helper comparison.
The six-operation follow-up above runs all four Wire/Java variants: 24 cells,
48 JVM forks and eight minutes of timed work (roughly nine minutes with startup,
plus compilation and correctness checks). Like every quick run, it is diagnostic.

The runner calls sbt **once** for `benchmarks/test`, `benchmarks/Jmh/compile` and
the compiled JMH classpath. Measurements then launch `org.openjdk.jmh.Main`
directly, with one fresh fork per selected cell per round. It does not repeatedly
start sbt thousands of times. `--skip-tests` is an explicit escape hatch after
separately validating the exact same sources; its use is recorded.

## Comparing source revisions

There is no dedicated `--baseline-ref`/`--compare-ref` command or automatic
cross-revision report in this runner. Each campaign records its Git revision,
dirty state and exact measured sources. Separate checkouts can be measured with
the same selections, JDK, usage, string policy and timing settings; `--root`
selects which checkout to build. It does not check out a revision or adapt an
older Scala harness to newer parameters.

For a runtime before/after comparison, use a compatible copy of the same harness
and corpus in both checkouts and run correctness checks for each. If the harness
or API contract changed between commits, the resulting campaign timings do not
isolate the runtime change. Retain both results directories and their original
reports; do not combine separate campaigns as if they were paired JVM rounds.

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

The reuse check runs exactly these operations against Wire and every supported
Java variant (specific, generic and genuine generated custom coders):

| Case | Operation | Input |
| --- | --- | --- |
| P03 | Encode | Three-byte int |
| R01 | Encode | Flat record containing four longs |
| L12 | Encode and decode | 18-digit decimal as bytes |
| L16 | Encode | BigDecimal logical type |
| T01 | Encode | 48-byte ASCII string |
| P02 | Decode | One-byte int |
| P10 | Decode | Eight-symbol enum |
| R02 | Decode | Chain of four nested records |
| L08 | Decode | Local timestamp, microseconds |

This is **36 cells in two independent rounds: 72 JVM forks**. Each fork has
**5 × 1-second warmup and 5 × 1-second measurement**, with the same heap,
collector, worker count and allocation profiler as above. The timed stages total
**12 minutes**, approximately **13–15 minutes with JVM startup**; compilation and
correctness checks happen first. There is no avro2s column in this diagnostic.
Two JVM observations per cell provide a quick directional check, not the
certainty of the full campaign. Retain and inspect both JVM means and all
allocation results; do not turn this selected subset into a library-wide score.

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
mean and interval use Student t on the log ratios (five in a full campaign).
The predeclared practical
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
