# Optimised stack-safe codec reference

See [the report](../../stack-safety-optimisation.md) for interpretation, workloads,
comparison boundaries, changes and reproduction commands.

- `raw/`: final 30-case campaign, raw JMH JSON, original metadata and complete
  measured-source snapshot.
- `shallow-direct-repeat/`: four-fork final-source repetition of the variable
  shallow direct decode case, matching the original repetition's protocol.
- `baseline/`: all three original-direct campaigns from commit `419c58b`, harness
  provenance and their shared measured-source snapshot.
- `experiments/`: all four completed intermediate optimisation campaigns,
  including every measured fork and their exact source snapshots.

The original prototype's data remain in the adjacent `2026-09-26-stack-safety`
archive. One deliberately stopped intermediate run is excluded from comparisons;
its failed metadata and partial logs remain in the ignored local results directory.
No selected raw result, metadata or source archive has been rewritten.
`SHA256SUMS` covers all files in this directory except itself. Verify from here:

```sh
shasum -a 256 -c SHA256SUMS
```
