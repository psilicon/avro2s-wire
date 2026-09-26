# Stack-safety comparison: 26 September 2026

The stack-safe implementation handles deep values but costs substantially more
CPU time and allocation than the direct implementation in this experiment.
Keep the direct codec as the default. Use the explicit alternative where deep
value traversal is required, and measure the application's own schema and data.

Across the nine operation/workload pairs below, mean time increased by
**1.90–18.27×** and allocated bytes by **3.24–27.80×**. These ratios describe this
implementation and these workloads, not an inherent cost of every possible
stack-safe implementation. In particular, the 32-record chain's matching-schema
decode has an exceptionally small direct baseline; the absolute times matter too.

## Measurements

Latency is ns per complete datum, with JMH's 99.9% confidence error. Allocation
is bytes allocated per datum, rounded to the nearest byte; it includes temporary
objects and is not retained heap. Ratios are ratios of means, without a claimed
confidence interval for the ratio. All 18 JMH cases completed successfully.

| Workload | Operation | Direct ns/op | Stack-safe ns/op | Time ratio | Direct B/op | Stack-safe B/op |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Shallow record | Encode | 27.7 ± 1.1 | 102.1 ± 2.7 | 3.68× | 336 | 1,088 |
| Shallow record | Decode | 51.5 ± 4.1 | 161.9 ± 9.9 | 3.14× | 208 | 1,304 |
| Shallow record | Resolved decode | 123.9 ± 27.9 | 287.6 ± 2.8 | 2.32× | 488 | 2,344 |
| Collections | Encode | 1,163.5 ± 15.6 | 2,424.1 ± 17.4 | 2.08× | 2,960 | 13,304 |
| Collections | Decode | 3,830.7 ± 60.1 | 8,670.2 ± 39.5 | 2.26× | 14,160 | 58,664 |
| Collections | Resolved decode | 5,205.5 ± 206.9 | 9,888.5 ± 32.4 | 1.90× | 14,328 | 69,760 |
| 32-record chain | Encode | 187.3 ± 1.9 | 1,241.2 ± 64.7 | 6.63× | 440 | 12,232 |
| 32-record chain | Decode | 164.1 ± 4.0 | 2,997.4 ± 134.4 | 18.27× | 1,320 | 24,864 |
| 32-record chain | Resolved decode | 1,943.0 ± 46.7 | 7,511.9 ± 58.5 | 3.87× | 3,368 | 61,112 |

Workloads in [StackSafetyBenchmark.scala](src/main/scala/avro2s/wire/benchmarks/StackSafetyBenchmark.scala):

- **Shallow:** a Trade record containing a Long, a short Unicode string, a Double
  and an empty integer vector.
- **Collections:** a vector of 128 integers and a map with 32 string keys/values.
- **Recursive:** 32 records connected by an optional `next` field, with the integer
  field after the child so traversal must resume the parent. Integers are outside
  the JVM's usual small-integer cache. The evolved reader reorders fields,
  promotes Int to Long and adds a Boolean default.

For shallow and collection resolution, a metadata difference forces a compiled
plan, avoiding the identical-schema shortcut. Each direct/safe pair consumes the
same values and bytes and returns the same immutable model types. Encode includes
a new output and owned byte-array result; decode includes a new input and the
end-of-input check. Codec selection, fixture creation and resolution-plan
compilation are outside timing. A correctness suite verifies every combination
before the run.

## Interpretation

The current trampoline represents suspended work and continuations as immutable
objects. Its driver keeps private per-call pending state. It removes recursive
JVM calls while adding short-lived objects and boxing. The allocation results are
consistent with that implementation, but this run does not isolate the cost of
each object type or prove which optimization would help most.

The next performance experiment could group consecutive primitive operations
into one step and avoid per-element suspension where an element cannot recurse.
A generated state machine with explicit continuation frames is another option
to compare. Both can retain immutable public models. Neither optimization was
applied to these measured sources; this report preserves a tested reference for
that discussion.

The direct implementation's generated method bodies remain unchanged, with golden
output checks. This run compares the two modes in the current build; it is not a
before/after benchmark proving the absence of every possible JVM performance
effect on direct mode.

Deep-value correctness is separate from these timings. Tests pass at 100,000
nested records on a thread requesting a 256 KiB stack, including matching-schema
reads/writes, evolved reads and skipped fields. Additional tests cover mutual
recursion, unions, arrays/maps, defaults, malformed data and registry framing.
Schema parsing/plan compilation and generated case-class equality remain outside
the stack-safe traversal scope. See [usage and boundaries](../docs/stack-safety.md).

## Environment and reproduction

- Apple M1 Pro, macOS 15.4 arm64.
- Amazon Corretto 21.0.0.35.1 (`21+35-LTS`), JMH 1.37.
- Two forks, three 500 ms warmup iterations and five 500 ms measurement iterations
  per fork; one thread, `-Xms512m -Xmx512m`, GC profiler, average-time mode.
- One complete campaign, with sources unchanged throughout. This is a local
  microbenchmark; deployment workload, CPU and JVM can change the results.
- Full correctness: 335 Scala tests on JDK 21, eight separate real Kafka/Schema
  Registry tests on JDK 25, and 33 Python benchmark-tool tests passed. The complete
  100,000-record example was compiled and run on JDK 21.

From the repository root, select an explicit JDK and run:

```sh
python3 scripts/run-performance.py --java "$JAVA_HOME/bin/java" --profile stack-safety
```

The selected [raw JMH results](reference/2026-09-26-stack-safety/raw/StackSafety.json)
and [environment/source manifest](reference/2026-09-26-stack-safety/raw/environment.json)
are unchanged copies of this run. [SHA256SUMS](reference/2026-09-26-stack-safety/SHA256SUMS)
covers those two files. Check them from `benchmarks/reference/2026-09-26-stack-safety`:

```sh
shasum -a 256 -c SHA256SUMS
```

The manifest records base revision `419c58b51d93650788d1a784110ebccd6daca649` and
`dirtySources: true`: **the base revision alone does not contain this
implementation**. Its before/after source hashes identify the measured working
tree, including the source files accompanying this report. The complete local
run, logs, generated report and source snapshot remain under
`benchmarks/results/20260926T135320.776378Z-stack-safety/`, ignored by Git.
Absolute paths in the manifest describe the measurement machine.

To re-render the detailed report without measuring again:

```sh
python3 scripts/benchmark_report.py \
  benchmarks/reference/2026-09-26-stack-safety/raw/StackSafety.json \
  --metadata benchmarks/reference/2026-09-26-stack-safety/raw/environment.json \
  -o /tmp/stack-safety-report.md
```
