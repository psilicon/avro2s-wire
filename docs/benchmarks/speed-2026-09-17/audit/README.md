# Artifact audit reports

These reports check source provenance and measurement completeness. They do not
establish that a timing difference is statistically or practically significant.
See [the provenance guide](../PROVENANCE.md) for source snapshots, rejected
experiments, gzip handling, and reproduction instructions.

| Reports | Scope |
| --- | --- |
| `audit.json` | Original broad and String baselines |
| `candidate-pilot.audit.json` | Rejected boxing experiment |
| `completed-pilots-verified.json` | Unboxed, iterator, tiny-String and scanner pilots |
| `completed-string-confirmations.json` | String confirmations and decoded-String comparison |
| `completed-supplementary.json` | Supplementary control and rejected direct-encoder experiment |
| `runtime-equivalence-6e-5e.json` | Exact production-source equivalence across the two final harness commits |
| `completed-final-campaigns.json` | Completed final broad, Trade, API, evolution, supplementary and small-integer confirmation campaigns |
| `packaged-summary.json`, `packaged/*.audit.json` | Independent reconstruction and gzip-only verification of all 20 campaigns |
| `packaging-checks.json` | Exact raw inventory and byte-preserving result/log packaging checks |
| `after*.audit.json`, `before-api.audit.json` | Detailed source, case, observation, result and log checks |
| `after-portable-gzip.audit.json`, `after-portable-gzip.checks.json` | Independent reconstruction of all 148 broad cases from the permanent base with gzip-only logs |

The earlier detailed reports record uncompressed log artifacts; their
`logSha256` values remain the hashes of the original decompressed logs retained
in `../raw/`. The portable gzip report additionally records stored gzip hashes.
The final packaged-artifact audit under `packaged/` checks all 20 campaigns,
393 cases and 3,222 primary/allocation observations from the permanent source
base plus patches. It also verifies all compressed-log hashes. These totals
include repeated confirmations and rejected experiments.

To repeat the newly completed campaigns using only the permanent base and saved
patches, run from the package root, choosing fresh output directories:

```sh
python3 verify_performance_artifacts.py \
  --repository /path/to/avro2s-wire --results raw \
  --output /private/tmp/final-extra-audit \
  --base-revision 59d35ee06fe8bcb624ce448470018accaa9fcb9e \
  --campaign after-trade=patches/committed-5e3f95f.patch \
  --campaign after-api=patches/committed-5e3f95f.patch \
  --campaign after-evolution=patches/committed-5e3f95f.patch \
  --campaign after-supplementary=patches/committed-5e3f95f.patch

python3 verify_performance_artifacts.py \
  --repository /path/to/avro2s-wire --results raw \
  --output /private/tmp/before-api-audit \
  --campaign before-api=patches/baseline-harness.patch
```

The verifier requires only Python's standard library and Git. It exports source
into the chosen audit output directory and does not compile, benchmark, or modify
the repository. Each audit checks exact benchmark/parameter membership, source
hashes and stability, finite raw samples, JVM/timing settings, and successful log
completion. Event-based GC-time sample counts need not equal iteration counts.

## Repeat the complete packaged audit

Run from the package root with a repository containing permanent commit
`59d35ee`. No temporary commit is required. Choose a new output directory.

```sh
python3 verify_performance_artifacts.py \
  --repository /path/to/avro2s-wire --results raw \
  --output /private/tmp/all-performance-audit \
  --base-revision 59d35ee06fe8bcb624ce448470018accaa9fcb9e \
  --campaign after=patches/committed-5e3f95f.patch \
  --campaign after-api=patches/committed-5e3f95f.patch \
  --campaign after-evolution=patches/committed-5e3f95f.patch \
  --campaign after-strings=patches/committed-6e6c0ef.patch \
  --campaign after-supplementary=patches/committed-5e3f95f.patch \
  --campaign after-trade=patches/committed-5e3f95f.patch \
  --campaign before=patches/before-harness.patch \
  --campaign before-api=patches/baseline-harness.patch \
  --campaign before-emoji-confirm=patches/baseline-harness.patch \
  --campaign before-strings=patches/baseline-harness.patch \
  --campaign bulk-pilot=patches/bulk-pilot.patch \
  --campaign candidate-pilot=patches/candidate-pilot.patch \
  --campaign confirm-small-ints=patches/committed-5e3f95f.patch \
  --campaign decoded-strings=patches/committed-6e6c0ef.patch \
  --campaign iterator-pilot=patches/iterator-pilot.patch \
  --campaign scanner-pilot=patches/scanner-pilot.patch \
  --campaign supplementary-control=patches/committed-6e6c0ef.patch \
  --patch supplementary-control=patches/supplementary-control.patch \
  --campaign supplementary-direct=patches/committed-6e6c0ef.patch \
  --patch supplementary-direct=patches/supplementary-direct.patch \
  --campaign tiny-strings-pilot=patches/tiny-strings-pilot.patch \
  --campaign unboxed-pilot=patches/unboxed-pilot.patch
```
