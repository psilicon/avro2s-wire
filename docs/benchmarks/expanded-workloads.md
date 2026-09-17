# Expanded matching-schema workloads

These classes retain ordinary generated immutable Scala models and compare the
native engine with the **same generated codec** using Java Avro's primitive
engine. `TradeBenchmark` remains the separate six-implementation comparison with
avro2s, Java specific/custom/generic, and both Avrogen engines.

| Class | Parameters | Datum per operation |
| --- | --- | --- |
| `IntegerBenchmark` | `kind=int,long`; `distribution=one-byte,medium,wide,mixed` | 1,024 integers in a generated Vector field |
| `StringBenchmark` | `profile=empty,ascii-short,ascii-long,multilingual-short,multilingual-long,emoji-short,emoji-long` | One generated string field |
| `BytesBenchmark` | `byteCount=0,32,4096` | One owned Bytes field |
| `CollectionsBenchmark` | `collectionSize=0,4,128` | An array of ints and a map of strings, each containing that many entries |
| `NestedUnionBenchmark` | `depth=0,1,4` | Binary tree containing 1, 3, or 31 records, with null/int/string/named-record union branches at every node |

The integer workloads exercise positive and negative values. `one-byte` covers
-64 through 63; `medium` uses three-byte values; `wide` uses five-byte ints or
ten-byte longs close to the signed extremes. `mixed` selects signed varint
boundaries with a fixed seed and covers every encoded width. Unlike the original
Trade fixture, the one-byte distribution falls inside Java's boxing caches;
its low allocation cannot be extrapolated to arbitrary integer data.

Nonempty short strings contain 32 Unicode code points; long strings contain
4,096. ASCII therefore occupies 32/4,096 UTF-8 bytes, multilingual text
80/10,240 bytes, and emoji 128/16,384 bytes. These are equal-code-point workloads,
not equal-byte-length workloads. Multilingual text repeats `λé漢字`; emoji repeats
`😀🚀🎉🌍`. Construction, schema parsing and expected-value conversion are untimed.

## Operations and correctness

Each class exposes eight methods:

| Method suffix | Native | Java primitives |
| --- | --- | --- |
| `Write` | Reused BinaryOutput; reset, write and return size | Reused stream/encoder; reset, write, flush and return size |
| `Read` | Fresh BinaryInput and result over existing payload | Fresh decoder/adapter and result over existing payload |
| `Encode` | Public codec.encode: allocate buffers and return an owned byte array | Equivalent allocating helper: fresh stream/encoder/adapter, flush and return an owned byte array |
| `Decode` | Public codec.decode: fresh input/result and check for trailing data | Equivalent helper with fresh decoder/adapter/result and trailing-data check |

There is **no input or datum reuse**. Returning results lets JMH consume them;
read/decode are intentionally separate because only decode checks end-of-input.
Reused writers are sized naturally during trial setup. Allocating helpers retain
the engines' normal buffer policies, so their costs include those policies rather
than representing a pure primitive-loop comparison. Native strict UTF-8 checks
and resource limits remain enabled; the Java paths retain Java's own policy.

Trial setup checks every writer against the native reader, the Java primitive
reader, and an independent Java GenericDatumReader. A separate GenericDatumWriter
receives independently constructed schema fields, rather than a record produced
by the codec under test. Schema-aware comparison preserves union branch identity
and ignores map iteration order. `CodecWorkloadSuite` exercises all 24 workload
combinations, advertised encoded widths, text sizes, owned outputs and complete
reused-buffer reset behavior before measurements are accepted.

## Running selected measurements

Run the tests first:

```sh
sbt 'benchmarks/test'
```

The full matrix contains 192 benchmark cases. Prefer selected workloads when
investigating a change; the full matrix is useful for broad smoke checks and
periodic measurements. Always use the same explicit JDK, heap, warmup and
measurement parameters on both versions. For example:

```sh
sbt 'benchmarks/Jmh/run -jvm /absolute/path/to/java -jvmArgs "-Xms512m -Xmx512m" -wi 5 -i 5 -w 1s -r 1s -f 2 -prof gc -p kind=int,long -p distribution=one-byte,medium,wide,mixed -rf json -rff /absolute/path/to/integers.json .*IntegerBenchmark.nativeWrite'
sbt 'benchmarks/Jmh/run -jvm /absolute/path/to/java -jvmArgs "-Xms512m -Xmx512m" -wi 5 -i 5 -w 1s -r 1s -f 2 -prof gc -rf json -rff /absolute/path/to/strings.json .*StringBenchmark.native.*'
```

Every-case execution check (these short timings are not performance evidence):

```sh
sbt 'benchmarks/Jmh/run -jvm /absolute/path/to/java -wi 0 -i 1 -r 50ms -f 1 .*(Integer|String|Bytes|Collections|NestedUnion)Benchmark.*'
```

The selector is a Java regular expression passed through sbt.
The historical Trade summarizer assumes Trade's parameter names: inspect raw JMH
JSON or use a general result summarizer for these classes. Report both
nanoseconds/operation and `gc.alloc.rate.norm` bytes/operation; distinguish these
exploratory workloads from the original recorded six-way baseline.

## Reproducible profile runner

`scripts/run-performance.py` runs sbt serially and retains each group's raw JMH
JSON and log, plus an environment manifest containing exact command arguments,
fork JDK version, platform, sbt version and source SHA-256 hashes before and after
measurement. It fails if sources change, JMH fails, scores are non-finite, or the
result set differs from the expected benchmark names and parameter combinations.
Existing result files are never overwritten. Run tests separately before invoking
it, and avoid editing or other CPU-heavy tasks during a measurement.

```sh
python3 scripts/run-performance.py --java /absolute/path/to/java \
  --output /absolute/path/to/results --label before --profile focused
```

| Profile | Cases | Defaults |
| --- | ---: | --- |
| `pilot` | 48 | Native read/write for all five new classes; 1 fork, 2 × 200 ms warmup, 3 × 200 ms measurement |
| `focused` | 25 | Integer native write, string native read/write, collection native read; 2 forks, 3 × 500 ms warmup, 5 × 500 ms measurement |
| `api` | 14 | Native encode/decode: mixed ints, short ASCII/long emoji, empty/128-entry collections, 4,096 bytes, depth-1 nested unions; same timing as focused |
| `trade` | 36 | Original six-way Trade comparison and all three collection sizes; same timing as focused |
| `evolution` | 8 | All evolution methods and both discarded payload sizes; same timing as focused |

All profiles use the selected fork JVM, a 512 MiB fixed heap, the GC profiler and
fail-on-error. The short profiles support investigation; use longer warmup and
measurement for confirmation when compilation or noise makes results uncertain.
For example, narrow a confirmation without modifying source files:

```sh
python3 scripts/run-performance.py --java /absolute/path/to/java \
  --output /absolute/path/to/results --label confirm-empty --profile focused \
  --filter 'CollectionsBenchmark.nativeRead' --param collectionSize=0 \
  --forks 3 --warmup-iterations 5 --measurement-iterations 5 \
  --warmup-time 1s --measurement-time 1s
```

`--filter` narrows the profile's benchmark names; repeat `--param name=value1,value2`
to override applicable parameters. `--root` selects another project checkout;
otherwise the script uses its containing project. A profile writes one result
and log per benchmark class and one `LABEL.environment.json` manifest. Source
hashes cover Scala/Java/schema/build/script files, excluding generated target
and IDE directories. The manifest identifies dirty source content even when Git
revision alone does not; retain or commit the actual source version as well as
its hashes when publishing results.

## Recreating the pre-optimisation production baseline

The old production commit does not contain this expanded harness. Prepare a
separate snapshot with the same current benchmark sources on both sides:

```sh
python3 scripts/prepare-performance-baseline.py /absolute/path/to/avrogen-before
python3 scripts/run-performance.py --root /absolute/path/to/avrogen-before \
  --java /absolute/path/to/java --output /absolute/path/to/results \
  --label before --profile focused
python3 scripts/run-performance.py \
  --java /absolute/path/to/java --output /absolute/path/to/results \
  --label after --profile focused
```

The preparation script defaults to production commit
`c73469839df8caf895042c36e20432fa89355945`; `--revision` can select another commit.
The destination must not exist. It exports committed files with `git archive`,
then replaces `benchmarks/src` with the current harness and overlays only the six
`Perf*.avsc` schemas and `scripts/run-performance.py`. Runtime, compiler, build
configuration and other production files retain their historical contents.
Generated files are rebuilt normally by sbt, rather than copied from a cache.

`performance-baseline-provenance.json` in the snapshot records the resolved old
commit, archive SHA-256 and every overlay's SHA-256. Retain that provenance with
the runner's result manifests and the matching source versions. The snapshot is
an exported directory without `.git`, so its runner manifest has a null Git
revision; the preparation provenance supplies the production commit explicitly.

When preparing from a staged source copy, `--repository /path/to/git/checkout`
selects the repository used for the historical export. Overlays still come from
the project containing the preparation script. This makes the source choice
explicit and avoids copying production files by hand.
