# Historical benchmark evidence

The full pre-cleanup evidence is retained in Git commit
`61e0a14d8b0febb7cd870e77f57394982ecdbac8`. No Git history was rewritten.
That commit contains all 237 previously tracked files under `docs/benchmarks`,
including original reports, raw results, environment manifests, logs, rejected
experiment patches, source-reconstruction patches and verification programs.
Original runner and report scripts are present at the same commit.

To recover the package without changing your checkout, run from the repository root:

```sh
mkdir -p benchmarks/results/history-61e0a14
git archive 61e0a14d8b0febb7cd870e77f57394982ecdbac8 docs/benchmarks scripts | tar -x -C benchmarks/results/history-61e0a14
```

Use a full repository clone, or fetch the recorded history if a shallow clone does
not contain that commit. The original detailed reports and verifier instructions
are under the recovered `docs/benchmarks` tree. Temporary benchmark commits named
in old manifests may not exist: the preserved `PROVENANCE.md` explains rebuilding
them from permanent base `59d35ee06fe8bcb624ce448470018accaa9fcb9e` and its patches.
Do not apply these historical patches to the current checkout.

Cleanup also made a byte-verified local archive at
`benchmarks/results/archive/history-61e0a14.tar.gz`, with `SHA256SUMS` alongside it.
It contains the original tracked evidence plus local ignored logs, an embedded
file/hash manifest and the original scripts/docs. This convenience archive is
intentionally untracked; the recorded Git commit is the shared recovery source.
The local big-decimal run was moved intact from `benchmarks/target/` to
`benchmarks/results/big-decimal-2026-09-24/` so `sbt clean` cannot remove it.
Neither local artifact is required to build, test or reproduce new measurements.

The later benchmark harness, selected references, stack-safety experiments,
generated baselines and report tools are retained in commit
`eec752b7e4c7a2032c0782f620cbee190f38fdf6`. The consolidated suite replaces those
tracked snapshots; it does not reinterpret their results as current measurements.

Recover that complete source revision into an ignored local directory:

```sh
mkdir -p benchmarks/results/history-eec752b
git archive eec752b7e4c7a2032c0782f620cbee190f38fdf6 | tar -x -C benchmarks/results/history-eec752b
```

New pilot, smoke and full-campaign outputs remain local under ignored
`benchmarks/results/`. Only explicitly selected publication evidence should be
promoted into version control after review; the runner never commits results.
