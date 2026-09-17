# Native performance follow-up — 17 September 2026

This change keeps the generated Scala API, ownership and validation rules intact.
It reduces position updates in integer writers, avoids collection builders when
an initial block is empty, and adds ASCII paths that retain strict UTF-8/UTF-16
validation. Primitive-backed model collections, input reuse and bulk skipping
remain deferred.

The clearest gains are empty collection allocation and ASCII writing. Integer
record writes improve across the measured distributions, but smaller gains carry
through to allocating `encode`. The work is not a universal performance win:
empty-string writing measured 0.147 ns slower, and empty-array Trade reading
trades about 18 ns of extra time for 200 fewer allocated bytes. The initial larger
collection `decode` slowdown did not reproduce in a longer follow-up. Full results, uncertainty and raw
artifacts are retained below.

## Method and source identity

- Baseline production source: `c73469839df8caf895042c36e20432fa89355945`, with the
  same expanded benchmark harness as the candidate. The three changed production
  files are recorded in [the implementation patch](performance-2026-09-17/candidate-v1.patch).
- Apple M1 Pro, 16 GiB, macOS 15.4; Corretto 21.0.0.35.1 arm64 fork JVM;
  Scala 3.3.6, sbt 1.11.0, Apache Avro 1.12.1 and JMH 1.37.
- One benchmark thread, fixed 512 MiB heap, GC profiler, two forks, three 500 ms
  warmup iterations and five 500 ms measurement iterations per fork. Longer
  targeted confirmations are identified separately. These initial runs have
  shorter warmup than the historical five-by-one-second report.
- Runs were serial, with no competing builds or benchmark processes. Sources were
  frozen during measurement. Runner manifests retain commands and before/after
  source hashes; the initial focused baseline predates the runner and has a
  separately captured environment/source manifest.
- All **128 correctness tests passed** and JMH sources compiled before collecting
  candidate measurements. Tests cover all 24 new workload configurations; timing
  covers selected profiles, not the whole 192-case expanded matrix. Evolution
  measurements remain future work.

Timings are means ± JMH's 99.9% confidence-interval half-width, in ns/op unless
specified. Lower is better. Allocation uses `gc.alloc.rate.norm`, rounded to whole
B/op. Percentage changes compare means, not confidence bounds. Overlapping
intervals neither prove equivalence nor establish a speedup; separated intervals
also do not account for all between-run drift or JVM compilation differences.
These are results for this machine and these workloads, not application latency
or guarantees across JDKs.

The baseline and candidate contain the same timed benchmark sources. The candidate
includes all three optimisations together, so these measurements do not isolate
individual changes causally. Reads allocate a fresh input/model; lower-level
writes reuse buffers and omit the final byte-array copy. `encode`/`decode` include
the allocating public APIs. Native validation remains enabled; Java paths use
Java's own policy and normal returned representations.

## Focused operations

### Integer

| Case | Baseline ns/op | Candidate ns/op | Time change | B/op before → after | CIs overlap? |
|---|---:|---:|---:|---:|:---:|
| write int, one-byte | 2,470.597 ± 38.466 | 2,326.646 ± 11.208 | -5.8% | 80 → 80 | no |
| write long, one-byte | 2,481.069 ± 27.300 | 2,312.986 ± 4.853 | -6.8% | 80 → 80 | no |
| write int, medium | 5,169.318 ± 32.634 | 4,254.234 ± 160.928 | -17.7% | 80 → 80 | no |
| write long, medium | 5,337.108 ± 214.475 | 4,278.010 ± 28.576 | -19.8% | 80 → 80 | no |
| write int, wide | 6,204.299 ± 982.062 | 4,507.988 ± 41.536 | -27.3% | 80 → 80 | no |
| write long, wide | 8,205.076 ± 93.241 | 7,178.439 ± 241.533 | -12.5% | 80 → 80 | no |
| write int, mixed | 5,452.716 ± 212.376 | 4,556.685 ± 37.035 | -16.4% | 80 → 80 | no |
| write long, mixed | 5,639.259 ± 452.343 | 5,255.546 ± 107.909 | -6.8% | 80 → 80 | yes |

### String

| Case | Baseline ns/op | Candidate ns/op | Time change | B/op before → after | CIs overlap? |
|---|---:|---:|---:|---:|:---:|
| read empty | 10.765 ± 0.297 | 10.677 ± 0.134 | -0.8% | 88 → 88 | yes |
| read ascii-short | 26.272 ± 1.218 | 22.568 ± 0.632 | -14.1% | 136 → 136 | no |
| read ascii-long | 930.971 ± 11.802 | 864.484 ± 7.775 | -7.1% | 4,200 → 4,200 | no |
| read multilingual-short | 142.085 ± 3.551 | 141.769 ± 4.199 | -0.2% | 440 → 440 | yes |
| read multilingual-long | 14,969.566 ± 161.003 | 14,914.073 ± 69.998 | -0.4% | 39,048 → 39,048 | yes |
| read emoji-short | 148.972 ± 1.143 | 148.999 ± 1.086 | +0.0% | 648 → 648 | yes |
| read emoji-long | 17,014.841 ± 698.356 | 16,854.394 ± 160.072 | -0.9% | 65,672 → 65,672 | yes |
| write empty | 4.471 ± 0.048 | 4.618 ± 0.071 | +3.3% | 0 → 0 | no |
| write ascii-short | 32.192 ± 0.303 | 27.546 ± 0.249 | -14.4% | 0 → 0 | no |
| write ascii-long | 2,599.404 ± 23.108 | 1,468.079 ± 6.484 | -43.5% | 0 → 0 | no |
| write multilingual-short | 80.938 ± 3.166 | 81.070 ± 3.566 | +0.2% | 0 → 0 | yes |
| write multilingual-long | 9,532.858 ± 66.540 | 9,584.184 ± 103.229 | +0.5% | 0 → 0 | yes |
| write emoji-short | 85.448 ± 5.737 | 83.860 ± 1.965 | -1.9% | 0 → 0 | yes |
| write emoji-long | 8,855.375 ± 36.808 | 8,822.444 ± 68.561 | -0.4% | 0 → 0 | yes |

### Collections

| Case | Baseline ns/op | Candidate ns/op | Time change | B/op before → after | CIs overlap? |
|---|---:|---:|---:|---:|:---:|
| read, 0 items in each collection | 15.528 ± 0.197 | 7.050 ± 0.034 | -54.6% | 272 → 72 | no |
| read, 4 items in each collection | 460.186 ± 18.325 | 370.847 ± 43.504 | -19.4% | 1,328 → 1,328 | no |
| read, 128 items in each collection | 19,813.119 ± 545.788 | 19,572.454 ± 230.096 | -1.2% | 43,024 → 43,024 | yes |

The integer rows each write a generated record containing 1,024 integers, not one
primitive value. Seven of eight timing intervals are separated; mixed longs
remain uncertain. Allocation stays around 80 B/op. This includes the generic
workload/codec call and collection traversal.

ASCII reads improved 7–14% and writes 14–44% in these runs. The Unicode intervals
overlap. Empty-string output increased from 4.471 to 4.618 ns/op (+3.3%); the tiny
absolute regression is retained in the report. The empty collection record holds
both an array and a map: avoiding their builders saves 200 B/op. Larger collection
allocation is unchanged.

## Allocating APIs

| Case | Baseline ns/op | Candidate ns/op | Time change | B/op before → after | CIs overlap? |
|---|---:|---:|---:|---:|:---:|
| decode 1,024 ints, mixed | 6,859.114 ± 79.749 | 6,593.487 ± 56.940 | -3.9% | 18,304 → 18,304 | no |
| encode 1,024 ints, mixed | 4,894.859 ± 17.093 | 4,777.839 ± 33.920 | -2.4% | 11,808 → 11,808 | no |
| decode ascii-short | 26.848 ± 0.561 | 23.176 ± 0.163 | -13.7% | 136 → 136 | no |
| decode emoji-long | 16,850.431 ± 266.387 | 16,619.028 ± 74.389 | -1.4% | 65,672 → 65,672 | yes |
| encode ascii-short | 43.190 ± 1.131 | 35.205 ± 0.598 | -18.5% | 352 → 352 | no |
| encode emoji-long | 9,939.794 ± 41.714 | 9,831.161 ± 55.489 | -1.1% | 33,112 → 33,112 | no |
| decode collections, size 0 | 16.001 ± 0.068 | 7.175 ± 0.029 | -55.2% | 272 → 72 | no |
| decode collections, size 128 | 19,189.219 ± 124.627 | 19,764.964 ± 271.301 | +3.0% | 43,024 → 43,024 | no |
| encode collections, size 0 | 10.355 ± 0.448 | 10.275 ± 0.035 | -0.8% | 296 → 296 | yes |
| encode collections, size 128 | 5,245.072 ± 207.801 | 5,257.517 ± 90.704 | +0.2% | 11,160 → 11,160 | yes |
| decode bytes, size 4096 | 169.488 ± 0.473 | 170.357 ± 4.401 | +0.5% | 4,192 → 4,192 | yes |
| encode bytes, size 4096 | 312.404 ± 2.409 | 323.718 ± 28.246 | +3.6% | 8,512 → 8,512 | yes |
| decode nested union, depth 1 | 263.061 ± 1.097 | 258.702 ± 2.223 | -1.7% | 2,304 → 1,904 | no |
| encode nested union, depth 1 | 147.279 ± 0.944 | 142.779 ± 2.008 | -3.1% | 712 → 712 | no |

Short ASCII gains remain visible in the public API. Mixed-int `encode` improves
2.4%, compared with 16.4% for reused-buffer `write`; buffer allocation, growth and
copying remain part of normal use. The depth-one nested record has two empty child
vectors: decoding saves 400 B/op. The small Unicode and nested timing movements
should not be extrapolated into broad claims.

## Targeted collection confirmation

The initial size-128 collection `decode` was 3.0% slower with separated intervals.
A targeted repeat used **three forks, five one-second warmups and five one-second
measurements**, retaining the same JVM, heap and source. Snapshot order was
candidate/baseline for `read`, then baseline/candidate for `decode`.

| Size-128 collection operation | Baseline ns/op | Candidate ns/op | Time change | B/op before → after |
| --- | ---: | ---: | ---: | ---: |
| read | 19,477.653 ± 320.899 | 19,945.386 ± 567.298 | +2.4% | 43,024 → 43,024 |
| decode | 19,561.038 ± 271.059 | 19,589.778 ± 186.814 | +0.1% | 43,024 → 43,024 |

Both pairs have overlapping intervals. The larger decode regression did not
reproduce in this longer run; no meaningful speedup is established either. The
read means remain mixed across runs, so the conclusion is limited to no clear
established timing change, with unchanged allocation. The initial result remains
above instead of being replaced by the more favourable repeat.

## Updated six-way Trade comparison

The Trade datum contains a long, the non-ASCII string `AVRO-λ`, a double and
an integer array. Java models return their normal Utf8/Java-collection values;
Scala models return String and Scala collections. The validation and representation
differences described in the [protocol](README.md) apply.

The results are mixed. Native reading at size 1,024 improves about 10%, while
large native writing is unchanged within the intervals. Java primitives remain
faster for the nonempty Trade writes: around 114 vs 126 ns at size 32, and 3.98 vs
4.27 µs at size 1,024. The new integer-distribution gains therefore do not close
the complete Trade workload's writer gap.

Compared with avro2s in this run, native read means are 1.50×/2.21×/3.24× faster
and write means 6.54×/4.85×/3.78× faster at sizes 0/32/1,024. These ratios describe
this workload only. External avro2s/Java datum implementations did not change;
all their before/after intervals overlap, so their shifted means must not be
credited to Avrogen. Some Java custom results are particularly noisy.

### Paired native before/after

| Case | Baseline ns/op | Candidate ns/op | Time change | B/op before → after | CIs overlap? |
|---|---:|---:|---:|---:|:---:|
| read, size 0 | 50.815 ± 0.816 | 69.771 ± 3.027 | +37.3% | 400 → 200 | no |
| read, size 32 | 229.709 ± 12.447 | 235.279 ± 8.381 | +2.4% | 960 → 960 | yes |
| read, size 1024 | 4,901.001 ± 120.125 | 4,407.084 ± 26.963 | -10.1% | 21,592 → 21,592 | no |
| write, size 0 | 19.812 ± 0.231 | 21.044 ± 0.099 | +6.2% | 0 → 0 | no |
| write, size 32 | 130.191 ± 5.569 | 125.601 ± 1.717 | -3.5% | 0 → 0 | yes |
| write, size 1024 | 4,227.750 ± 50.009 | 4,272.692 ± 38.387 | +1.1% | 80 → 80 | yes |

### Candidate six-way results: collection size 0

| Implementation | Read ns/op | Write ns/op | Read B/op | Write B/op |
|---|---:|---:|---:|---:|
| Avrogen native | 69.771 ± 3.027 | 21.044 ± 0.099 | 200 | 0 |
| Avrogen + Java primitives | 62.472 ± 1.352 | 42.190 ± 0.630 | 376 | 64 |
| avro2s | 104.631 ± 4.815 | 137.533 ± 4.940 | 528 | 160 |
| Java specific | 60.898 ± 1.189 | 114.616 ± 3.604 | 360 | 112 |
| Java custom | 83.358 ± 1.579 | 64.039 ± 32.654 | 336 | 64 |
| Java generic | 71.124 ± 3.582 | 65.673 ± 1.782 | 376 | 64 |

### Candidate six-way results: collection size 32

| Implementation | Read ns/op | Write ns/op | Read B/op | Write B/op |
|---|---:|---:|---:|---:|
| Avrogen native | 235.279 ± 8.381 | 125.601 ± 1.717 | 960 | 0 |
| Avrogen + Java primitives | 240.955 ± 11.957 | 113.898 ± 6.117 | 1,096 | 64 |
| avro2s | 519.199 ± 4.298 | 609.363 ± 50.964 | 2,600 | 1,724 |
| Java specific | 223.957 ± 3.316 | 263.392 ± 10.718 | 1,016 | 112 |
| Java custom | 602.453 ± 60.323 | 113.148 ± 1.729 | 992 | 64 |
| Java generic | 241.375 ± 5.512 | 238.285 ± 3.983 | 1,032 | 64 |

### Candidate six-way results: collection size 1024

| Implementation | Read ns/op | Write ns/op | Read B/op | Write B/op |
|---|---:|---:|---:|---:|
| Avrogen native | 4,407.084 ± 26.963 | 4,272.692 ± 38.387 | 21,592 | 80 |
| Avrogen + Java primitives | 4,580.496 ± 54.449 | 3,977.950 ± 209.834 | 21,736 | 144 |
| avro2s | 14,271.636 ± 383.684 | 16,170.210 ± 1,648.329 | 66,168 | 49,464 |
| Java specific | 5,214.487 ± 814.821 | 6,734.982 ± 166.742 | 20,856 | 168 |
| Java custom | 15,180.356 ± 490.850 | 3,418.814 ± 134.557 | 20,832 | 96 |
| Java generic | 4,834.225 ± 83.313 | 6,387.231 ± 203.801 | 20,872 | 120 |

### Empty Trade regression and isolated checks

The initial empty-array Trade result regressed despite halving read allocation.
Longer confirmation used three forks and five one-second warmup/measurement
iterations, baseline first then candidate:

| Operation | Baseline ns/op | Retained candidate ns/op | B/op before → after |
| --- | ---: | ---: | ---: |
| read | 49.898 ± 0.233 | 67.945 ± 1.640 | 400 → 200 |
| write | 19.230 ± 0.214 | 20.297 ± 0.318 | 0 → 0 |

This is a confirmed workload-specific tradeoff: about **18 ns (+36%) longer to
read**, and about **1 ns (+6%) longer to write**, in return for the read allocation
reduction. The initial empty-string writer also has the smaller regression
reported above. These are retained alongside the allocation and broader-workload
wins; the change is not presented as a uniform latency improvement.

Two tested reader variants were measured with the same longer protocol. Restoring
the original `BinaryInput` while retaining the generator/output changes yielded
68.274 ± 2.029 ns for empty Trade reading. Keeping ASCII validation but splitting
the two String constructor calls yielded 66.901 ± 0.731 ns. Both still regress
against baseline and neither establishes an improvement over the retained
candidate. Neither variant is included in the final code.

Restoring the original reader did not remove the regression, so the ASCII reader
alone does not explain it. Generated-code shape and JVM optimisation remain
possible contributors; no compilation trace was collected to establish a cause.
Further call-site/JIT tuning is deferred rather than adding unproven complexity.


## Reproduction and retained evidence

The [raw-results directory](performance-2026-09-17/) contains the focused, API,
Trade and targeted-confirmation JSON, their environment/source manifests, the
production patch, and a checksum index. The failed initial runner attempt is
excluded; it ran no timed benchmarks. Benchmark logs are local diagnostic output
and are not required to reproduce the runs.

Choose an explicit fork JDK and new output directories. From the final source
checkout:

```sh
sbt test
python3 scripts/prepare-performance-baseline.py /tmp/avrogen-before

AVROGEN_BENCH_JAVA=/absolute/path/to/java
AVROGEN_BENCH_RESULTS=/absolute/path/to/new-results
for profile in focused api trade; do
  python3 scripts/run-performance.py --root /tmp/avrogen-before \
    --java "$AVROGEN_BENCH_JAVA" --output "$AVROGEN_BENCH_RESULTS" \
    --label "before-$profile" --profile "$profile"
  python3 scripts/run-performance.py \
    --java "$AVROGEN_BENCH_JAVA" --output "$AVROGEN_BENCH_RESULTS" \
    --label "after-$profile" --profile "$profile"
done
```

The preparation helper exports baseline production sources and overlays only the
current benchmark harness, its six schemas and the runner. It records an archive
hash and overlay hashes. Generated Scala and JMH classes are rebuilt by sbt; no
compiled artifacts or unpacked dependency JARs are carried into the snapshot.
See [the workload guide](expanded-workloads.md) for parameter filters and timing
controls. The recorded manifests identify the precise measured sources; a future
harness change should be treated as a new comparison.

To repeat the collection confirmation, select `--profile focused --filter
'CollectionsBenchmark.nativeRead' --param collectionSize=128` or `--profile api
--filter 'CollectionsBenchmark.nativeDecode' --param collectionSize=128`, with
`--forks 3 --warmup-iterations 5 --measurement-iterations 5 --warmup-time 1s
--measurement-time 1s` on both snapshots. Alternate snapshot order and retain both
results. Do not run builds or other benchmarks concurrently.

The [test inventory](../testing.md) documents the 128 correctness checks and
remaining schema-generation/shrinking gaps. The [original benchmark report](results-2026-09-17.md)
remains a separate historical result with its own source identity and longer
timing protocol.
