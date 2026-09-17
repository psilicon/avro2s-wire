# Performance source provenance and audit

Keep this document, `verify_performance_artifacts.py`, the patches, completed
environment manifests, raw JMH JSON and logs together. Measurements retain their
original source snapshots; a rejected experiment is never relabeled as final
performance. Run the commands below from this directory: manifests, results and
gzip logs are in `raw/`, source patches are in `patches/`, and audit reports are
in `audit/`.

Logs may be retained as `.log.gz`; the original `.log` paths in the manifests
remain unchanged. The verifier prefers an existing `.log` and otherwise reads
the corresponding `.log.gz`, validates the gzip data, and decodes the original
bytes as UTF-8. Each report preserves `logSha256` for the decompressed original
log and separately records the stored artifact's name, compression and SHA-256.
Thus the original log hash remains comparable with earlier uncompressed audits,
while the compressed file can also be checked in the artifact checksum inventory.

## Recorded snapshots

`59d35ee06fe8bcb624ce448470018accaa9fcb9e` is the original source base.
`6e6c0effdec43d61cd978ec1a970a645459487f2` contains the accepted runtime changes.
`5e3f95f85ea397c6f3f6a478279600caa815f6b2` retains that runtime and extends the
String harness and regression tests.

| Completed campaign | Recorded base | Patch relative to recorded base | Cases | Source hashes verified |
| --- | --- | --- | ---: | ---: |
| `before` | `59d35ee` | `before-harness.patch` | 26 | 148 |
| `before-strings` | `59d35ee` | `baseline-harness.patch` | 26 | 148 |
| `before-emoji-confirm` | `59d35ee` | `baseline-harness.patch` | 2 | 148 |
| `candidate-pilot` | `59d35ee` | `candidate-pilot.patch` | 26 | 152 |
| `unboxed-pilot` | `59d35ee` | `unboxed-pilot.patch` | 26 | 153 |
| `iterator-pilot` | `59d35ee` | `iterator-pilot.patch` | 5 | 153 |
| `tiny-strings-pilot` | `59d35ee` | `tiny-strings-pilot.patch` | 5 | 153 |
| `scanner-pilot` | `59d35ee` | `scanner-pilot.patch` | 4 | 153 |
| `bulk-pilot` | `59d35ee` | `bulk-pilot.patch` | 6 | 155 |
| `after-strings` | `6e6c0ef` | none | 26 | 155 |
| `decoded-strings` | `6e6c0ef` | none | 15 | 155 |
| `supplementary-control` | `6e6c0ef` | `supplementary-control.patch` | 10 | 155 |
| `supplementary-direct` | `6e6c0ef` | `supplementary-direct.patch` | 10 | 156 |
| `after` | `5e3f95f` | none | 148 | 156 |
| `after-trade` | `5e3f95f` | none | 12 | 156 |
| `before-api` | `59d35ee` | `baseline-harness.patch` | 14 | 148 |
| `after-api` | `5e3f95f` | none | 14 | 156 |
| `after-evolution` | `5e3f95f` | none | 8 | 156 |
| `after-supplementary` | `5e3f95f` | none | 8 | 156 |
| `confirm-small-ints` | `5e3f95f` | none | 2 | 156 |

These are full patches against the listed base, not a series to apply
cumulatively. The only deliberate patch chain is reconstruction of an otherwise
unavailable temporary commit, described below. Every listed campaign has complete
case membership, finite primary/allocation samples, stable recorded source hashes
and successful logs. The pilot settings are shorter than confirmation settings;
see each manifest rather than treating all rows as one statistical experiment.

The boxing candidate, wider scanner and supplementary direct-encoder experiments
were rejected. Their original raw observations remain available. The
supplementary direct encoder eliminated its temporary allocation but slowed mixed
supplementary/ASCII/BMP inputs, so it is absent from the accepted runtime.

The completed final `after` campaign contains 120 broad, ten nested, ten logical
and eight decimal comparisons. All 1,480 primary, allocation and GC-count
observations are present and finite, with 1,150 valid GC-time event samples.
Its four result files and gzip-only copies of all four logs were independently
audited from `59d35ee` plus `committed-5e3f95f.patch`; no temporary Git commit was
needed. All original result hashes and decompressed log hashes were preserved.
The subsequent Trade comparison has 12 cases and 120 primary/allocation
observations. The allocating-API baseline has 14 cases and 140 such observations
across Integer, String, Collections, Bytes and NestedUnion classes. Both are
complete and match their exact recorded source snapshots. The corresponding
completed `after-api` campaign also has 14 cases and 140 primary/allocation
observations, matching all 156 recorded source hashes at `5e3f95f`.
The final evolution and supplementary-String campaigns each contain eight cases
and 80 primary/allocation observations, also matching those 156 source hashes.

## Final packaged-artifact verification

All 20 campaigns were reconstructed afresh from permanent base `59d35ee` and
preserved patches, using only the packaged manifests, JSON results and gzip logs.
The audit verified 393 recorded cases and 3,222 raw observations each for latency,
allocation rate, normalized allocation and GC count; the 2,538 available GC-time
event observations are valid. These totals include rejected experiments and
repeated confirmations, so they are not counts of distinct workloads in the
final implementation.

The 20 manifests, 42 result files and 42 compressed logs exactly match the
manifest inventory. Every packaged manifest and result preserves its original
bytes; every decompressed log preserves its original bytes. All gzip modification
times are zero, and reports retain hashes for both original and compressed logs.
`audit/packaged-summary.json` summarizes this audit, `audit/packaged/` contains its
20 detailed reports, and `audit/packaging-checks.json` records the independent
copy checks. `audit/README.md` includes the complete reproduction command.

The final small-integer confirmation contains two cases with 20 observations
each for latency and allocation, matching all 156 recorded source hashes. Its
longer measurement settings remain recorded in its own manifest.

## Runtime equivalence across harness commits

`audit/runtime-equivalence-6e-5e.json` records identical Git trees and per-file SHA-256
hashes for `6e6c0ef` and `5e3f95f` across:

- Seven production runtime files and three compiler files.
- The two Java-adapter and two resolution production files.
- All 30 fixture schemas/resources.
- The 16 Java comparison model files and 14 Scala comparison implementation/model
  files.

Eight additional files, including build settings and the decoded-String/broad
comparison benchmark entry points, are byte-for-byte identical. Only five paths
differ: the String workload factory, its benchmark parameter list, its tests, the
new two-test `SupplementaryStringSuite`, and the runner's String profile matrix.
The factory adds four named input profiles; the earlier profiles and timed codec
methods are retained.

Therefore the 26 `after-strings` and 15 `decoded-strings` cases measure the same
production implementation present in `5e3f95f`. Their recorded harness commit is
still `6e6c0ef`; they are not claimed to come from the later source snapshot.
Source equivalence does not guarantee identical timings under every compilation
or machine condition. In particular, decoded-String Java results remain a
separate comparison from ordinary Java readers returning `Utf8`.

## Reproduce the artifact audit

The verifier uses Git and Python's standard library. It creates fresh source
exports under a new output directory, applies patches, verifies every recorded
source hash, and checks exact benchmark/parameter sets and raw observations.
It does not compile, run a benchmark, change the repository or inspect unfinished
campaigns.
The same commands work with uncompressed logs or `.log.gz`; decompression is
automatic and does not write uncompressed files into the results directory.

For an available committed snapshot:

```sh
python3 verify_performance_artifacts.py \
  --repository /path/to/repository --results raw \
  --output /private/tmp/string-audit \
  --campaign after-strings --campaign decoded-strings
```

For a base plus one full source patch:

```sh
python3 verify_performance_artifacts.py \
  --repository /path/to/repository --results raw \
  --output /private/tmp/baseline-audit \
  --campaign before=patches/before-harness.patch \
  --campaign before-strings=patches/baseline-harness.patch
```

The temporary commits need not remain available. `committed-6e6c0ef.patch` and
`committed-5e3f95f.patch` preserve their complete changes against `59d35ee`.
For example, reconstruct the supplementary control from the permanent base using
two ordered patches:

```sh
python3 verify_performance_artifacts.py \
  --repository /path/to/repository --results raw \
  --output /private/tmp/supplementary-audit \
  --base-revision 59d35ee06fe8bcb624ce448470018accaa9fcb9e \
  --campaign supplementary-control=patches/committed-6e6c0ef.patch \
  --patch supplementary-control=patches/supplementary-control.patch
```

That reconstruction was independently verified from the user's original
repository, which did not contain the temporary `6e6c0ef` commit. The report keeps
the manifest's recorded revision separate from the reconstruction base and lists
the ordered patch hashes. Both supplementary campaigns passed this path.

The final broad campaign can likewise be verified from its preserved source:

```sh
python3 verify_performance_artifacts.py \
  --repository /path/to/repository --results raw \
  --output /private/tmp/final-comparison-audit \
  --base-revision 59d35ee06fe8bcb624ce448470018accaa9fcb9e \
  --campaign after=patches/committed-5e3f95f.patch
```

GC-time samples are event-based: [JMH 1.37 emits them only when GC count or time
changes](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/GCProfiler.java#L132).
The verifier checks those available observations without treating no-GC
iterations as missing data. Primary, allocation and GC-count observations retain
the full iteration counts. Confidence intervals and raw timing results should
be interpreted separately from this provenance/completeness audit.
