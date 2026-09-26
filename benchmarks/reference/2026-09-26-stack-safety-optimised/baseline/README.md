# Original direct-codec baseline

These are all three completed baseline campaigns from the isolated checkout at
`419c58b51d93650788d1a784110ebccd6daca649`, before stack-safe execution was added.
The original runtime, compiler and resolver implementations were unchanged.
Only the benchmark harness, its five additional fixture schemas, and runner/report
tooling were added or adapted. All campaigns passed `benchmarks/test` and the
per-trial workload checks; their recorded source hashes were unchanged during
measurement.

The three campaigns have identical `sourceSha256` manifests. The shared
[`source-snapshot.tar.gz`](source-snapshot.tar.gz) preserves those measured sources,
including the exact harness used, independently of the local worktree.

Raw result and environment files are exact copies, including every measured fork
and iteration. None of the campaigns or individual forks is omitted. The original
logs, reports, source snapshots and checksums remain under
`/Users/sam/.codex/worktrees/direct-baseline/avro2s-wire/benchmarks/results/`.
The environment manifests retain their original absolute paths and reproduction
commands; they have not been rewritten to make them appear to originate here.

| Directory | Original campaign | Scope | Forks | Warm-up per fork | Measurement per fork |
| --- | --- | --- | ---: | --- | --- |
| `initial-9cases` | `20260926T150325.170477Z-stack-safety` | Encode, decode and resolved decode for shallow, collections and 32-record recursive inputs | 2 | 3 × 500 ms | 5 × 500 ms |
| `collections-encode-repeat` | `20260926T151229.376205Z-stack-safety` | Collections encode | 4 | 5 × 1 s | 5 × 1 s |
| `shallow-decode-repeat` | `20260926T151350.294945Z-stack-safety` | Shallow decode | 4 | 5 × 1 s | 5 × 1 s |

All runs used Amazon Corretto 21.0.0.35.1 (`21+35-LTS`) on an Apple M1 Pro,
one benchmark thread, a fixed 512 MiB heap, JMH average-time mode in ns/op and
the GC allocation profiler. Inputs, output types, fresh-buffer ownership and
end-of-input checks match the corresponding current benchmark operations.

## Why the targeted repetitions are retained separately

The initial collections-encode campaign had two persistently different fork means,
about 1,174 and 2,650 ns/op. Its combined result was 1,911.78 ± 1,176.61 ns/op.
That combined mean is not a useful favourable reference for claiming a speed-up.
The longer four-fork repetition produced 1,156.40 ± 7.99 ns/op, with fork means
1,161.99, 1,155.06, 1,154.58 and 1,153.99 ns/op. Both campaigns remain available.

The initial shallow-decode forks averaged about 69.85 and 58.39 ns/op; their
combined result was 64.12 ± 9.20 ns/op. The longer repetition produced
50.64 ± 3.64 ns/op, with all four fork means retained: 48.31, 48.22, 48.38 and
57.66 ns/op. Variation remained; the slower fourth fork was not discarded.

The ± values are JMH's reported 99.9% confidence errors for each campaign mean.
They do not establish an interval for a before/after ratio or a paired significance
test. The longer repetitions used a deliberate validation protocol; they do not
replace or get averaged with the initial campaign.

## Harness provenance and comparison boundaries

`direct-baseline-harness.json` records the copied files, hashes and exact initial
adaptations. The baseline has no `stackSafeCodec`, so those four references became
references to the existing `codec`; the advertised execution choices became
direct-only. Fixture values and the timed method bodies were unchanged.

All three campaigns above ran with that initial harness. A later setup-only
refinement made the unused alternative-codec arguments lazy in both checkouts.
`direct-baseline-lazy-harness.json` records the old and new hashes and this
chronology. The refinement prevents current direct-only trials from initializing
a stack-safe implementation that normal direct-only application code would leave
uninitialized. In the original baseline all alternative arguments already referred
to the same, already-loaded direct codec. The refinement does not change datum
values, timed operations, output ownership or end-of-input checks. The old source
snapshots preserve the exact harness used for the measurements here.

The initial nine-case campaign used shorter warm-up/measurement periods than the
final optimized campaign. The two targeted repetitions use the final campaign's
iteration durations and counts, but have four forks rather than two. These are
separate campaigns with explicit setting differences, not a randomized paired
experiment. Preserve that qualification when comparing their means. No original
baseline was measured for the optional 256-record or recursive-container shapes;
those current measurements compare the two current execution modes only.

These files support a performance comparison for the stated workloads. Claims
about unchanged direct implementation should also use source/bytecode checks;
microbenchmarks alone cannot prove absence of regressions for all applications.
