# Retained native speed measurements

Start with the [readable report](../speed-2026-09-17.md) or the
[complete timing and allocation tables](tables.md).

- `raw/` contains unmodified JMH JSON and completed environment manifests.
  Original logs are losslessly compressed as `.log.gz`. Raw JSON and patches
  retain original whitespace so their recorded hashes remain valid.
- `patches/` preserves source snapshots, including rejected experiments.
- `audit/` records source equivalence and independent completeness checks.
- `validation/` contains the final JDK 11 and JDK 21 test logs.
- `measurements.csv` retains full-precision scores, uncertainty and allocation.
- [PROVENANCE.md](PROVENANCE.md) explains source reconstruction and verification.
- [ANALYSIS.md](ANALYSIS.md) gives the command for rebuilding the tables.

The original absolute paths in manifests and logs describe where the measurements
ran. The portable verifier resolves recorded artifact basenames under `raw/`;
those original paths need not exist. Checksums in `SHA256SUMS` cover the retained
package files except the checksum file itself. On macOS, verify them from this
directory with `shasum -a 256 -c SHA256SUMS`.

Pilot, before, final and confirmation groups remain separate. An artifact audit
establishes provenance and completeness; it does not establish a performance
claim independently of the measurements and their limitations.
