# Stack-safe codec optimisation: 26 September 2026

This compares the optimised implementation with the direct codec and the
[initial prototype](stack-safety-results.md). The direct codec remains the
default. Applications still opt in with `Model.stackSafeCodec`; the public
selection API and immutable model types have not changed.

The large prototype penalties are substantially reduced. On the original three
workloads, stack-safe/direct time ratios are now **0.79–2.71×**, compared with
**1.90–18.27×** in the prototype. Shallow and flat-collection work is close to
direct performance; nested native traversal still has a material cost.

For the 32-record chain, encode improved from **1,241 to 292 ns**, decode from
**2,997 to 447 ns**, and resolved decode from **7,512 to 2,086 ns**. Allocation
fell from **12,232 to 1,480 B**, **24,864 to 2,872 B**, and **61,112 to 8,536 B**
respectively. These are separate campaigns, with the protocol differences and
raw results preserved below. They are evidence for this implementation and
machine, not an inherent limit of stack-safe execution.

I would retain this as an explicit option for deep values. Direct remains a good
default: the largest relative native decode penalty here is still 2.71×, and the
mixed-container encoder allocates 5.18× as many bytes despite costing 2.31× in time.
The faster resolved-read results do not erase those tradeoffs.

## What changed

The compiler batches primitive fields and flat scalar collections into one
operation. It emits a typed execution frame when a record must suspend for a
child, preserving unboxed primitive fields and resuming the same frame afterward.
Union tags and branches are handled within that frame. Nested collections yield
to the shared runtime even when their schemas contain no child records; only
one collection layer containing scalar elements uses the flat fast path.

The runtime reuses a private pending-work array, resumes frames in place, and
has a small entry point for leaf operations. These changes remove most of the
per-field continuation objects. Each collection
that needs to suspend reuses its own frame, iterator or builder across elements.
Self-recursive reads also reuse their owning codec directly; writers retain
the companion lookup because
the self-reference experiment increased writer allocation without a convincing
time benefit.

Resolution batches scalar operations and uses reusable per-record frames for
structural work, including defaults and skipped fields. Direct resolution plans
use the original action implementation class. Execution is selected once when
the reader is constructed, with no mode check per datum or field.

This is private mutable execution state, confined to one call. Models remain
immutable and codecs/plans remain shareable. Record cleanup still follows
`try`/`finally` semantics, including failed entry, child reads, construction and
cleanup itself. No validation or decode-limit defaults were relaxed.

## Final measurements

Each time is ns per complete datum, with JMH's reported 99.9% confidence error.
Allocation is bytes per datum, rounded to the nearest byte; it includes temporary
objects, not just retained results. Ratios divide campaign means and do not have
a claimed confidence interval. A ratio below one is an observation from this
microbenchmark, not a general promise that stack-safe traversal is faster.

| Workload | Operation | Direct ns/op | Stack-safe ns/op | Time ratio | Direct B/op | Stack-safe B/op |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Shallow | Encode | 28.1 ± 0.1 | 28.9 ± 0.3 | 1.03× | 336 | 336 |
| Shallow | Decode | 56.3 ± 1.4 | 47.7 ± 1.7 | 0.85× | 208 | 208 |
| Shallow | Resolved decode | 140.4 ± 4.4 | 111.3 ± 1.1 | 0.79× | 488 | 488 |
| Collections | Encode | 1,151.4 ± 12.4 | 1,153.5 ± 11.8 | 1.00× | 2,960 | 3,000 |
| Collections | Decode | 3,828.8 ± 16.7 | 3,921.7 ± 155.3 | 1.02× | 14,160 | 14,192 |
| Collections | Resolved decode | 4,833.7 ± 166.5 | 5,180.5 ± 235.3 | 1.07× | 14,328 | 14,348 |
| 32 records | Encode | 188.5 ± 3.6 | 292.1 ± 11.9 | 1.55× | 440 | 1,480 |
| 32 records | Decode | 165.1 ± 0.9 | 447.0 ± 8.0 | 2.71× | 1,320 | 2,872 |
| 32 records | Resolved decode | 2,328.4 ± 46.6 | 2,085.9 ± 68.1 | 0.90× | 3,368 | 8,536 |
| 256 records | Encode | 1,945.9 ± 46.7 | 2,507.3 ± 10.1 | 1.29× | 4,968 | 13,224 |
| 256 records | Decode | 1,878.9 ± 26.2 | 3,609.7 ± 15.6 | 1.92× | 10,280 | 22,632 |
| 256 records | Resolved decode | 21,412.0 ± 611.8 | 16,660.3 ± 300.5 | 0.78× | 26,664 | 67,720 |
| 64 records + containers | Encode | 794.8 ± 1.3 | 1,832.2 ± 10.8 | 2.31× | 1,664 | 8,624 |
| 64 records + containers | Decode | 1,403.0 ± 10.9 | 2,437.8 ± 15.0 | 1.74× | 10,496 | 16,984 |
| 64 records + containers | Resolved decode | 6,555.8 ± 65.0 | 5,392.0 ± 30.1 | 0.82× | 13,056 | 27,592 |

The original three inputs are unchanged: a shallow Trade record with an empty
integer vector; 128 integers plus a 32-entry string map; and 32 linked records
whose primitive field follows the optional child. Recursive resolution reorders
fields, promotes Int to Long and adds a Boolean default. Additional workloads
use the same chain at depth 256, and 64 records alternating through direct child,
array and map union branches. Each pair consumes identical data and produces
identical model types and bytes.

Encoding includes a fresh output and owned byte array. Decoding includes a fresh
input and the end-of-input check. Resolution uses genuinely different schemas;
fixture creation, codec selection and plan compilation are outside timing.
The unused alternative codec is lazy during direct benchmark setup, matching
ordinary direct-only application code. The
[benchmark source](src/main/scala/avro2s/wire/benchmarks/StackSafetyBenchmark.scala)
and correctness suite specify these workloads.

## Is the original direct implementation preserved?

The existing generated model/direct-codec source prefix retains its golden hashes
in both decimal modes. The direct emitter and native binary input/output code
are unchanged. Direct resolution retains its reading algorithms and original
action-plan implementation class. The complete build nevertheless gains classes,
members and support APIs; default materializers also have a wrapper, and the
generator's reserved-name rules expand. It would be inaccurate to describe the
entire library or every JVM compilation decision as unchanged.

An isolated checkout of `419c58b51d93650788d1a784110ebccd6daca649`, before stack-safe
execution, was measured using the same datum values and timed operations. Its
runtime, compiler and resolver were unchanged; only the benchmark harness,
fixtures and runner were added. The
[baseline provenance](reference/2026-09-26-stack-safety-optimised/baseline/README.md)
records every adaptation, campaign and fork.

| Workload | Operation | Original direct ns/op | Current direct ns/op | Current/original |
| --- | --- | ---: | ---: | ---: |
| Shallow | Encode | 28.3 ± 0.7 | 28.1 ± 0.1 | 0.992× |
| Shallow | Decode | 50.6 ± 3.6 | 52.2 ± 2.6 | 1.031× |
| Shallow | Resolved decode | 141.4 ± 10.0 | 140.4 ± 4.4 | 0.993× |
| Collections | Encode | 1,156.4 ± 8.0 | 1,151.4 ± 12.4 | 0.996× |
| Collections | Decode | 3,828.6 ± 56.2 | 3,828.8 ± 16.7 | 1.000× |
| Collections | Resolved decode | 4,898.6 ± 94.8 | 4,833.7 ± 166.5 | 0.987× |
| 32 records | Encode | 187.2 ± 1.2 | 188.5 ± 3.6 | 1.007× |
| 32 records | Decode | 166.1 ± 19.2 | 165.1 ± 0.9 | 0.994× |
| 32 records | Resolved decode | 2,323.8 ± 19.9 | 2,328.4 ± 46.6 | 1.002× |

The initial baseline used shorter iterations. Longer four-fork repetitions are
shown for shallow decode and collection encode because the initial forks varied
substantially; both original campaigns remain available. The final campaign uses
two forks. Current shallow decode is also shown from a matching four-fork
repetition; its two-fork final-campaign mean remains in the mode-comparison table
above. These are separate local campaigns, not a randomized paired experiment
or proof of zero regression for every schema. Plan-compilation time and retained
plan memory were not measured. No historical baseline exists for the two added
scaling workloads.

The eight other current direct means are within 1.4% of their original baselines,
and all nine allocation counts match. Shallow decode has persistent variation
between forks: the final two-fork campaign averaged 56.3 ns; the four-fork repeat
averaged **52.2 ± 2.6 ns**, versus the original repeat's **50.6 ± 3.6 ns**. Current
fork means were 49.69, 49.14, 56.19 and 53.83 ns; the original's were 48.31, 48.22,
48.38 and 57.66 ns. Every fork is retained in the
[repeat artifacts](reference/2026-09-26-stack-safety-optimised/shallow-direct-repeat/README.md).
The measured evidence is consistent with preserved direct performance, while
this variation prevents a claim of exactly zero effect.

Some final mode comparisons also have overlapping timing intervals, including
collection resolution. Treat its 1.07× ratio as the observed ratio of means,
rather than a precisely established 7% penalty.

## Experiment record

All completed intermediate experiments are retained, including results that
prompted further changes:

1. [Initial batching](reference/2026-09-26-stack-safety-optimised/experiments/batching/README.md):
   six recursive cases, one fork with shorter iterations. Batching leaf work and
   simplifying continuation nodes reduced recursive decode to about 946 ns, but
   still allocated 7,776 bytes per datum. That motivated typed frames.
2. [Typed frames](reference/2026-09-26-stack-safety-optimised/experiments/frames/README.md):
   all 30 cases, two forks with five 1-second warmups and measurements. Flat
   collections reached approximate parity, but recursive decode still cost
   926 ns for 32 records, versus 161 ns direct. Allocation was much lower than
   the initial prototype, showing that allocation reduction alone was insufficient.
3. [Driver diagnostic](reference/2026-09-26-stack-safety-optimised/experiments/driver/README.md):
   six cases, one fork with shorter iterations. In-place resumption and the small
   leaf entry reduced recursive decode to about 506 ns and encode to 289 ns.
4. [Self-reference diagnostic](reference/2026-09-26-stack-safety-optimised/experiments/self-alias/README.md):
   two cases under the same short protocol. Decode reached about 444 ns with
   unchanged allocation. Writer allocation increased, so only the read-side
   change was retained for the final full campaign.

The short diagnostics guide implementation choices; they are not substitutes for
the final measurements. One earlier campaign was deliberately stopped when
review found that an overly broad scalar fast path could recurse through nested
collection writers. That path was fixed and regression-tested before subsequent
measurements; the stopped campaign is not used in any comparison.

## Verification and reproduction

The final sources pass 349 Scala tests, including generated Java interoperability,
schema evolution, malformed wire data, logical values, collection ordering,
registry framing and cleanup failures. All 34 Python benchmark-tool tests pass.
The complete usage example reads, writes and resolves 100,000 nested immutable
records. Small-stack regressions cover deep self-recursive and mutually recursive
records, mixed unions/collections, and 48 collection-only schema layers.

The stack-safe guarantee is value traversal. Schema parsing, code generation,
plan/default compilation and generated case-class equality/hashCode/toString
retain the boundaries documented in [the usage guide](../docs/stack-safety.md).
The original eight real Kafka/Schema Registry integration tests also passed
during the initial implementation; they were not rerun for this optimisation.

Environment: Apple M1 Pro, macOS 15.4 arm64, Amazon Corretto 21.0.0.35.1
(`21+35-LTS`), JMH 1.37. The final campaign uses two forks, five 1-second warmups
and five 1-second measurements per fork, one thread, a 512 MiB fixed heap and the
GC profiler. No tests or competing benchmark campaigns ran concurrently.

```sh
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" \
  --profile stack-safety \
  --param shape=shallow,collections,recursive,recursive-256,recursive-containers \
  --warmup-iterations 5 --warmup-time 1s --measurement-time 1s
```

The [final raw results](reference/2026-09-26-stack-safety-optimised/raw/StackSafety.json),
[environment and source hashes](reference/2026-09-26-stack-safety-optimised/raw/environment.json)
and [measured-source snapshot](reference/2026-09-26-stack-safety-optimised/raw/source-snapshot.tar.gz)
are exact copies from campaign `20260926T154750.828864Z-stack-safety`. All 30 cases
passed the correctness and source-stability gates. The manifest records base
revision `e86b556` and a dirty source tree; the base revision alone does not contain
the optimisations. The snapshot preserves the exact measured sources.

The [reference archive](reference/2026-09-26-stack-safety-optimised/README.md)
contains every baseline and completed intermediate experiment, with their original
metadata. Its `SHA256SUMS` covers the selected artifacts. To verify:

```sh
cd benchmarks/reference/2026-09-26-stack-safety-optimised
shasum -a 256 -c SHA256SUMS
```

To regenerate the detailed final report without measuring again:

```sh
python3 scripts/benchmark_report.py \
  benchmarks/reference/2026-09-26-stack-safety-optimised/raw/StackSafety.json \
  --metadata benchmarks/reference/2026-09-26-stack-safety-optimised/raw/environment.json \
  -o /tmp/stack-safety-optimised-report.md
```
