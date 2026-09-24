# Selected reference: 17 September 2026

Read the [timing and allocation report](report.md).

These are selected completed historical measurements, retained to make the
comparison easy to find. They were not rerun during the benchmark cleanup and
do not claim to measure later additions such as generator options or big-decimal.
The current runner can produce a fresh comparison of the current checkout.

| Campaign | Measurements | Recorded source revision |
| --- | ---: | --- |
| Broad matching-schema comparison | 148 | `5e3f95f85ea397c6f3f6a478279600caa815f6b2` |
| Schema evolution | 8 | `5e3f95f85ea397c6f3f6a478279600caa815f6b2` |
| Explicit String-output controls | 15 | `6e6c0effdec43d61cd978ec1a970a645459487f2` |

The six result files and three environment manifests in `raw/` are byte-for-byte
copies of the original final campaigns. They retain their exact source hashes,
JVM, commands and settings, including original absolute paths. Those paths are
historical metadata and need not exist to read or re-render this report.
`SHA256SUMS` covers the retained raw files.

The two recorded revisions are distinct. The original audit established production
source equivalence across them; the String controls remain a separate campaign
and are not pooled with default Java Utf8 results. All three used Corretto
21.0.0.35.1, two forks, three 500 ms warmups and five 500 ms measurements on the
same recorded host. Consult the manifests for precise settings.

The [historical recovery instructions](../../../docs/benchmarks/HISTORY.md) locate
all original logs, source patches and verification tools in permanent Git history.
In particular, source-reconstruction patches remain available even when the
original temporary benchmark commits are absent. Keeping this provenance matters:
the retained numbers should not be attributed to a different source revision.

From this directory, check unchanged raw data:

```sh
shasum -a 256 -c SHA256SUMS
```

From the repository root, regenerate the report without running benchmarks:

```sh
python3 scripts/benchmark_report.py \
  benchmarks/reference/2026-09-17/raw/after-Comparison.json \
  benchmarks/reference/2026-09-17/raw/after-NestedComparison.json \
  benchmarks/reference/2026-09-17/raw/after-LogicalComparison.json \
  benchmarks/reference/2026-09-17/raw/after-DecimalComparison.json \
  benchmarks/reference/2026-09-17/raw/after-evolution-Evolution.json \
  benchmarks/reference/2026-09-17/raw/decoded-strings-DecodedString.json \
  --metadata benchmarks/reference/2026-09-17/raw/after.environment.json \
  --metadata benchmarks/reference/2026-09-17/raw/after-evolution.environment.json \
  --metadata benchmarks/reference/2026-09-17/raw/decoded-strings.environment.json \
  -o benchmarks/reference/2026-09-17/report.md
```

This formatter presents individual engines, timing errors and allocation. Missing
paths are `N/A`; Wire's Java backend is separate from official Java Avro, and
resolved reads are separate from same-schema controls and plan construction.
