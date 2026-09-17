# Measurement artifacts

These files support [the performance report](../performance-2026-09-17.md).
`baseline` is production commit `c73469839df8caf895042c36e20432fa89355945`
with the expanded harness. `candidate` is the retained implementation in this
change; `candidate-v1.patch` records its three production-file changes.

- `baseline-focused` and `candidate-focused-r1-*`: integer/string/collection operations.
- `baseline-api-*` and `candidate-api-*`: allocating convenience APIs.
- `baseline-trade-Trade` and `candidate-trade-Trade`: all six implementations.
- `confirm-*`: longer collection and empty-Trade checks.
- `probe-*`: isolated reader alternatives, **not retained**. Their patches apply
  on top of the retained candidate, and their manifests identify the variants.

Each JSON result retains samples and JMH configuration. Environment manifests
retain exact commands and source hashes; the initial focused baseline uses a
separately captured manifest from before the profile runner existed. Exported
source snapshots have no Git directory, so runner Git revisions are null. The
baseline commit plus the implementation/variant patches and harness sources
supply their source identity. Transient absolute paths describe the original
run locations; use the preparation helper and runner to reproduce elsewhere.

`checksums.json` contains SHA-256 hashes of the result, environment and patch files.
Logs and compiled output are intentionally not included. All 128 correctness
tests passed for the retained candidate and both reader probes; probe test runs
completed before either probe benchmark began. No benchmark ran alongside a build.
