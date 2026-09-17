# Testing

The current suite contains **128 MUnit tests across 16 suites**. A named test
often exercises many inputs: the count is of test declarations, not individual
values, schema combinations, or assertions.

## Run the correctness suite

From the project root, with JDK 11 or newer and sbt installed:

```sh
sbt test
```

The build pins Scala 3.3.6, sbt 1.11.0, MUnit 1.0.4, and Apache Avro 1.12.1.
It regenerates and compiles fixture models before running their integration
tests. A clean checkout and downloaded build dependencies are sufficient; no
manually unpacked JARs or existing `target` output are required. Select modules,
for example `sbt 'runtime/test' 'fixtures/test'`.

## Coverage by module

| Module | Suites | Tests | Coverage |
| --- | --- | ---: | --- |
| `runtime` | `BinaryRuntimeSuite` (22), `BinarySkippingSuite` (4), `LogicalValuesSuite` (13) | 39 | Binary wire bytes, malformed input, truncation, block boundaries, resource limits, ownership, skipping, logical-type precision and ranges. |
| `compiler` | `CodeGeneratorSuite` (18) | 18 | Recursive definitions, unions, logical-type validation, names, metadata escaping, deterministic generation and cross-file schemas. |
| `java-interop` | `JavaAvroInputSuite` (4), `IntegerOutputSuite` (3), `StringEncodingSuite` (5) | 12 | Buffer slices and ownership, validating null hooks, integer widths and buffer growth, Unicode encoding and malformed strings. |
| `resolution` | `ResolvingReaderSuite` (20) | 20 | Aliases, reordered/skipped fields, defaults, promotions, union selection, enums, fixed values, recursion, logical types and limits. |
| `fixtures` | `InteropSuite` (12), `EvolutionSuite` (5), `UnionInteropSuite` (3), `LogicalInteropSuite` (3), `UnionLogicalSuite` (2), `EmptyCollectionsSuite` (4) | 29 | Compiled generated codecs, Java interoperability, unions, logical types, schema evolution, nested empty collections and malformed records. |
| `benchmarks` | `TradeBenchmarkSuite` (5), `CodecWorkloadSuite` (5) | 10 | Benchmark correctness, genuine implementation dispatch, workload distributions, fresh results and buffer reuse. |

The runtime tests include exact zigzag and little-endian wire examples, signed
extremes, 2,000 seeded random ints and 2,000 seeded random longs. Negative tests
cover malformed UTF-8 and varints, invalid block sizes, byte and item budgets,
nesting depth, and truncated inputs. Representative encoded records are truncated
at every byte boundary. Logical tests check negative epochs, wire extrema,
decimal precision, UUID byte layout and unsigned duration fields.

The integer output tests compare Java wire bytes across every encoded width,
both signs, multiple initial buffer capacities and starting offsets. String
tests cover ASCII boundaries, multilingual text, supplementary code points and
250 seeded generated strings. Failed malformed-string writes must preserve
previously written output. Empty-collection tests include following fields,
sequential records and enclosing sized blocks, retaining validation and limits.

## Where test inputs come from

Test programs and the 20 checked-in `.avsc` fixture schemas are hand-written.
The build generates the Scala models and codecs from those schemas, then
compiles and exercises that generated source. Some compiler and resolver tests
also construct specific schema JSON examples directly in the test code.

Boundary tables, explicitly chosen records and fixed-seed random loops supply
test values. Seeds make failures reproducible. These loops do not randomly
generate schemas and do not provide automatic shrinking of failing examples.

## Independent interoperability checks

Integration tests read Avrogen output with Java Avro's generic datum reader and
read independently constructed Java generic output with Avrogen. Logical-type
reference values are built from independent primitive representations rather
than Avrogen's conversion helpers. Relevant tests also compare exact wire bytes
and resolved reader values; map comparisons allow differing entry order.

The Trade benchmark tests check six writers against six readers at three
collection sizes, including avro2s, Java specific/custom/generic and both
Avrogen engines. The expanded workloads verify 24 configurations against Java
generic codecs and check their advertised integer widths and Unicode sizes.

Java Avro is a compatibility reference for valid data. Native malformed-input
rejection and resource limits are tested separately: the two engines do not
have identical validation policies.

## Performance and remaining gaps

`sbt test` checks correctness, not performance thresholds. JMH measurements are
separate; see the [benchmark protocol](benchmarks/README.md) and
[expanded workloads](benchmarks/expanded-workloads.md). Short JMH smoke runs
establish that workloads execute, not that an optimisation is faster.

There is currently no randomized schema generator, property-test shrinking,
fuzzing harness, published code-coverage percentage, or repository CI matrix
across JDK/Scala versions. The existing tests are targeted coverage of supported
features, not an exhaustive Avro conformance claim.
