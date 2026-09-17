# Broader comparisons — 17 September 2026

Native avro2s-wire performs well across much of this corpus, but the earlier
Trade results did not generalize to string-heavy records. This run makes the
losses visible alongside the gains. It measures the existing native algorithms
under the new project name; no further runtime optimization is included.

There are **13 fixed workload profiles and 148 matching-schema read/write cases**,
plus **eight schema-evolution cases**. Versus avro2s, native has lower measured
times with non-overlapping reported intervals in **20 of 24 supported comparisons**;
the four losses are ASCII and Unicode reads and writes. Versus ordinary Java
specific records, the corresponding count is **17 lower, eight higher, one with
overlapping intervals**. These counts describe this selected corpus, not a
project-wide success rate. Decimal has no equivalent avro2s comparator.

## Important results

- **Bytes, enum/fixed and decimals show useful gains against Java specific.**
  Native read/write means are 170/85 ns for 4 KB bytes, 398/161 ns for enum/fixed,
  and 87/53 ns for the decimal pair. Java-specific means are 262/335, 1,106/440,
  and 158/173 ns respectively.
- **Nested unions are fast, but allocate more than Java's models.** Native reads
  take 1.29 µs versus 1.94 µs for Java specific and 3.59 µs for avro2s; native writes
  take 0.66 µs versus 2.04 and 3.56 µs. Native read allocation is 9,920 B versus
  Java-specific 4,192 B; write allocation is 1,760 versus 1,488 B.
- **Strings are a real weakness against avro2s as well as Java.** In the broad run,
  native takes about 2.00×/3.92× as long for ASCII read/write and 1.74×/1.88× for
  Unicode. Native writes allocate approximately zero B/op, versus 1,040/6,688 B
  for the Java-backed ASCII/Unicode paths. Native string reads also allocate less
  than avro2s. Allocation savings do not imply lower elapsed time.
- **Java remains competitive elsewhere.** Native wide-int, mixed-long and populated
  collection reads take about 1.52×, 1.29× and 3.11× as long as Java specific.
  Java custom coders lead several numeric writes; the combined boolean/float/double
  write is 595 ns versus native 1,414 ns. Populated-collection writes are similar
  between native and ordinary Java specific, with overlapping intervals.
- **Empty collections produce large ratios on tiny operations.** The record is
  only two wire bytes. Keep its roughly 7 ns read and 4 ns write separate from
  claims about substantial payloads or whole-application throughput.

Native allocation is lower than avro2s in all 24 supported read/write comparisons.
Allocation versus Java is mixed; see below and the [full tables](comparison-2026-09-17/tables.md).
Temporal/UUID reads are fairly close: native 203 ns, avro2s 226 ns and Java specific
178 ns. The native/avro2s read intervals only narrowly separate, so this is not a
strong headline gain. Temporal/UUID writes favor native: 77 ns versus about 191 ns.

## How to read the tables

A ratio is **comparison mean / native mean**: above 1 favors native, below 1 favors
the comparison implementation. Ratios are not speedup confidence intervals.
`†` means the reported 99.9% JMH intervals overlap; avoid ranking that pair.
The full tables retain the means, intervals and allocated B/op for every method.
The decimal Java-primitives write had an unstable broad-run result and is marked
explicitly; no observations were discarded. `—` means an unsupported comparator.

Java normally returns `Utf8`/`CharSequence`, mutable records and Java collections.
Native returns decoded `String`, immutable Scala models/collections and owned
bytes. The extremely small Java Unicode read time therefore does **not** mean it
constructs the same String 28 times faster. Avro2s and the Java-primitives adapter
provide more relevant comparisons for String materialization. Native strict UTF-8
validation and resource limits also remain enabled; Java uses its own policy.
All initial writer text is String, not pre-encoded Utf8.

## Read: comparison time / native time

| Workload | Native ns/op | avro2s | Java specific | Java custom | Java generic | Java primitives |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ints-small | 3,613.9 | 3.15× | 1.09× | 4.00× | 1.08× | 1.00× † |
| ints-wide | 7,798.3 | 1.65× | 0.66× | 2.06× | 0.66× | 0.62× |
| longs-mixed | 7,949.8 | 1.77× | 0.78× | 2.56× | 0.78× | 0.68× |
| string-ascii | 234.9 | 0.50× | 0.29× | 0.41× | 0.29× | 0.45× |
| string-unicode | 4,211.3 | 0.58× | 0.04× | 0.04× | 0.04× | 0.55× |
| bytes | 169.9 | 2.06× | 1.55× | 1.58× | 1.57× | 2.02× |
| collections-empty | 7.0 | 8.77× | 6.79× | 11.87× | 10.26× | 4.25× |
| collections-full | 9,705.8 | 1.64× | 0.32× | 0.48× | 0.32× | 1.11× |
| enum-fixed | 397.5 | 4.48× | 2.78× | 4.32× | 2.05× | 1.00× † |
| numerics | 1,480.3 | 4.47× | 1.71× | 4.24× | 1.73× | 1.11× |
| nested | 1,294.6 | 2.78× | 1.50× | — | 2.10× | 1.05× |
| logical | 203.1 | 1.11× | 0.88× | — | 0.85× | 1.04× † |
| decimal | 86.8 | — | 1.82× | — | 1.37× | 1.06× |

## Write: comparison time / native time

| Workload | Native ns/op | avro2s | Java specific | Java custom | Java generic | Java primitives |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ints-small | 2,340.1 | 6.11× | 1.95× | 0.78× | 1.94× | 1.12× |
| ints-wide | 4,498.6 | 3.45× | 1.31× | 0.74× | 1.34× | 0.93× |
| longs-mixed | 5,271.9 | 3.28× | 1.28× | 0.74× | 1.27× | 0.92× |
| string-ascii | 391.8 | 0.26× | 0.27× | 0.24× | 0.25× | 0.24× |
| string-unicode | 3,706.2 | 0.53× | 0.52× | 0.52× | 0.53× | 0.52× |
| bytes | 85.1 | 4.17× | 3.94× | 3.64× | 3.77× | 1.39× |
| collections-empty | 4.2 | 11.72× | 7.68× | 7.01× | 7.76× | 6.99× |
| collections-full | 2,595.5 | 1.56× | 1.01× † | 0.86× | 1.02× | 1.17× |
| enum-fixed | 160.9 | 6.98× | 2.73× | 1.12× | 3.25× | 1.27× |
| numerics | 1,414.0 | 4.06× | 1.38× | 0.42× | 1.39× | 0.85× |
| nested | 655.7 | 5.43× | 3.10× | — | 2.89× | 1.71× |
| logical | 77.5 | 2.47× | 2.46× | — | 3.70× | 1.21× |
| decimal | 52.6 | — | 3.29× | — | 3.49× | unstable |

## Read allocations (B/op)

| Workload | Native | avro2s | Java specific | Java custom | Java generic | Java primitives |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ints-small | 5,072 | 33,160 | 4,344 | 4,368 | 4,376 | 5,176 |
| ints-wide | 21,456 | 65,928 | 20,728 | 20,752 | 20,760 | 21,560 |
| longs-mixed | 27,488 | 77,992 | 26,760 | 26,784 | 26,792 | 27,592 |
| string-ascii | 1,128 | 2,264 | 1,232 | 1,256 | 1,264 | 2,264 |
| string-unicode | 11,144 | 14,072 | 3,024 | 3,048 | 3,056 | 14,072 |
| bytes | 4,192 | 8,400 | 4,328 | 4,352 | 4,360 | 8,400 |
| collections-empty | 72 | 432 | 320 | 344 | 344 | 256 |
| collections-full | 23,668 | 51,768 | 12,336 | 11,864 | 12,360 | 23,832 |
| enum-fixed | 2,688 | 4,864 | 2,944 | 3,232 | 2,440 | 2,776 |
| numerics | 7,800 | 23,224 | 7,064 | 7,048 | 7,088 | 7,856 |
| nested | 9,920 | 13,176 | 4,192 | — | 4,912 | 9,432 |
| logical | 472 | 1,008 | 808 | — | 832 | 640 |
| decimal | 544 | — | 688 | — | 696 | 680 |

## Schema evolution

This is one Account writer/reader pair with aliases, reordering, promotion,
defaults, enum fallback, union selection and projection. Both resolved readers
reuse their plans, allocate fresh results, and check end-of-input. Native returns
the generated Scala reader model; Java returns GenericRecord/Utf8.

| Discarded bytes | Native resolved ns/op ± CI | Java resolved ns/op ± CI | Native B/op | Java B/op |
| --- | ---: | ---: | ---: | ---: |
| 0 | 331.77 ± 11.26 | 364.50 ± 2.40 | 640 | 1,840 |
| 4096 | 333.66 ± 12.49 | 369.35 ± 21.19 | 640 | 1,840 |

Skipping the 4 KB payload adds no per-record allocation in either resolved path.
The native 640 B/op versus Java 1,840 B/op is a clearer distinction here than the
small timing gap. New native plan construction costs approximately 23.3–23.4 µs
and 130 KB per operation in an already warmed JVM; reuse the constructed reader.
No Java plan-construction comparison is included.

`nativeSameSchema` reads the original model and retains the byte payload. Its
142/216 ns and 432/4,544 B results at 0/4,096 bytes are a reference cost, not the
same work as resolution. The no-payload timing is noisy (±33 ns); see the full table.

## Longer confirmation runs

The important losses were repeated with five 1 s warmups and five 1 s measurements,
still with two forks and unchanged sources. The direction persisted. The table
uses the same comparison/native ratio convention as above.

| Workload | Operation | Comparison | Native ns/op ± CI | Comparison ns/op ± CI | Comparison / native |
| --- | --- | --- | ---: | ---: | ---: |
| string-ascii | read | avro2s | 234.34 ± 2.97 | 117.70 ± 3.17 | 0.50× |
| string-ascii | write | avro2s | 393.07 ± 3.33 | 106.51 ± 9.96 | 0.27× |
| string-unicode | read | avro2s | 4,300.90 ± 235.51 | 2,354.04 ± 45.08 | 0.55× |
| string-unicode | write | avro2s | 3,691.94 ± 102.13 | 1,931.00 ± 67.23 | 0.52× |
| ints-wide | read | javaSpecific | 7,801.89 ± 218.17 | 5,356.22 ± 482.12 | 0.69× |
| collections-full | read | javaSpecific | 9,888.13 ± 136.66 | 3,251.97 ± 47.98 | 0.33× |

The decimal Java-primitives writer remained unstable in its longer repeat:
**92.02 ± 64.52 ns**, versus native **54.40 ± 2.44 ns**.
The broad run was 97.44 ± 110.49 ns for that adapter. Both raw runs are retained;
no slow observations were removed. Its aggregate interval still overlaps native,
so this adapter comparison is inconclusive. The stable Java-specific/generic
decimal comparisons are separate measurements and retain their reported results.


## Scope and protocol

Measured source commit: `553f32f2b0e834c0a58715c7f199be7e76c40296`.
All 147 correctness tests passed, including the expanded schema/value, evolution,
wire mutation, budget and ownership campaigns. Genuine Java and avro2s baseline
sources regenerate byte-for-byte; see [provenance](../../benchmarks/generator/README.md).

Machine: Apple M1 Pro, 16 GiB RAM, macOS 15.4 arm64. Fork JVM: Corretto
21.0.0.35.1 / JDK 21+35. Scala 3.3.6, Apache Avro 1.12.1, JMH 1.37.
The broad and evolution runs use one thread, two forks, three 500 ms warmup
iterations and five 500 ms measurement iterations per fork, a fixed 512 MB heap,
and the GC profiler. Confirmation runs use five 1 s warmups and five 1 s measurements
per fork. Jobs run serially. Timing is average nanoseconds per operation, not tail
latency. Reported intervals do not account for every source of system bias.

Matching-schema reads allocate fresh inputs/results. Writes reuse warmed output
buffers, flush Java encoders, and omit the final byte-array copy. Every available
writer is checked against every reader during setup. Java standard/generic/avro2s
use fast readers; custom readers disable fast-reader bypass and have their actual
custom dispatch verified. The optional Java-primitives adapter uses our Scala
models/codecs with Java I/O; differing primitive validation means it is not an
experiment that isolates only the varint loop.

The [corpus guide](../../benchmarks/COMPARISON.md) describes the exact inputs:
integer widths, two 1,024-code-point strings, one 4 KB byte payload, empty/64-entry
collections, combined enum/fixed and numeric records, one 15-node tree, temporal
values and UUIDs, and a 50-digit scale-10 decimal pair. ASCII and Unicode have
different byte lengths. Small integers use JVM boxing caches. This does not
establish scaling, arbitrary union frequency/depth, all logical types, invalid-input
cost, Java datum reuse, allocating convenience API performance, JVM startup,
container/compression/registry/I/O overhead, or concurrent service throughput.

The results suggest distinct follow-up investigations: string validation/encoding,
wide-integer decoding, and the model/construction costs of map-heavy reads.
The same Scala model with Java primitives greatly narrows the wide-integer read
gap, whereas populated-collection reads remain comparatively expensive with either
primitive engine. Those are observations to profile, not proof of a single cause
or additional optimizations included in this change. Primitive-backed collections,
input reuse and bulk block skipping remain deferred.

## Reproduce and audit

Use a separate checkout of the measured source commit. The checked-in baselines
are sufficient for tests and timing; their optional regeneration procedure uses
the pinned avro2s source commit. Choose an executable JVM path and a fresh output
directory, then run:

```sh
sbt test
benchmark_java=/absolute/path/to/java
benchmark_results=/absolute/path/to/new-results
python3 scripts/run-performance.py --java "$benchmark_java" --output "$benchmark_results" --label comparison --profile comparison
python3 scripts/run-performance.py --java "$benchmark_java" --output "$benchmark_results" --label evolution --profile evolution
```

Run the longer confirmations with:

```sh
python3 scripts/run-performance.py --java "$benchmark_java" --output "$benchmark_results" --label confirm-strings --profile comparison --filter '^avro2s[.]wire[.]benchmarks[.]ComparisonBenchmark[.](native|avro2s)(Read|Write)$' --param profile=string-ascii,string-unicode --forks 2 --warmup-iterations 5 --measurement-iterations 5 --warmup-time 1s --measurement-time 1s
python3 scripts/run-performance.py --java "$benchmark_java" --output "$benchmark_results" --label confirm-reads --profile comparison --filter '^avro2s[.]wire[.]benchmarks[.]ComparisonBenchmark[.](native|javaSpecific)Read$' --param profile=ints-wide,collections-full --forks 2 --warmup-iterations 5 --measurement-iterations 5 --warmup-time 1s --measurement-time 1s
python3 scripts/run-performance.py --java "$benchmark_java" --output "$benchmark_results" --label confirm-decimal --profile comparison --filter '^avro2s[.]wire[.]benchmarks[.]DecimalComparisonBenchmark[.](native|javaPrimitives)Write$' --forks 2 --warmup-iterations 5 --measurement-iterations 5 --warmup-time 1s --measurement-time 1s
```

Use `scripts/summarize-comparison.py` on result JSON files to regenerate full
tables; exclude `.environment.json`, and summarize broad/confirmation files in
separate invocations because they repeat benchmark keys. Metadata records the
exact executed sbt/JMH commands, JVM version, source hashes and completeness
checks. All five runs completed and their 147 source-file hashes match the
measured commit and current code.

- [Broad run metadata](comparison-2026-09-17/comparison.environment.json)
- [Evolution metadata](comparison-2026-09-17/evolution.environment.json)
- [String confirmation metadata](comparison-2026-09-17/confirm-strings.environment.json)
- [Integer/map confirmation metadata](comparison-2026-09-17/confirm-reads.environment.json)
- [Decimal confirmation metadata](comparison-2026-09-17/confirm-decimal.environment.json)
- [Full tables](comparison-2026-09-17/tables.md) and [raw-file checksums](comparison-2026-09-17/SHA256SUMS)

The JSON files linked from the full tables retain every timing and allocation
sample. Local JMH logs remain ignored by Git. Earlier Trade and optimization
reports retain their original names, measurements and hashes.
