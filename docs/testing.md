# Testing

Testing has two complementary layers: mechanically generated schema/value
properties, and targeted regressions for particular wire-format and API rules.
The latter started with **128 MUnit tests across 16 suites** and remain in place.
A named test often exercises many inputs; declaration counts do not measure
schema variety or conformance.

## Run the correctness suite

From the project root, with JDK 11 or newer and sbt installed:

```sh
sbt test
```

The build pins Scala 3.3.6, sbt 1.11.0, MUnit 1.0.4, ScalaCheck 1.18.1,
and Apache Avro 1.12.1.
It regenerates and compiles fixture models before running their integration
tests. A clean checkout and downloaded build dependencies are sufficient; no
manually unpacked JARs or existing `target` output are required. Select modules,
for example `sbt 'runtime/test' 'fixtures/test'`.

## Property-driven schema exploration

The `property-tests` module generates schemas and corresponding Java generic
datums, generates Avrogen source, and compiles that source with the pinned Scala
3 compiler. An independent test renderer constructs Scala model expressions
from the physical Avro values. It does not ask the codec under test to construct
its own expected result.

The central properties check both directions: Java Avro reads native Avrogen
output, and Avrogen reads independently written Java output. Native round trips
provide an additional check. Comparisons must retain union branch identity and
allow different map iteration order. Logical values are derived independently
from their physical wire representations, without Avrogen conversion helpers.

Input generation combines required type/context coverage with bounded random
schemas. Required cases compose primitive, named, and logical types with record,
array, map, and union contexts, including nullable branch ordering. Random cases
explore additional nesting and combinations. Required coverage labels prevent
passing random samples from being mistaken for coverage of every supported type.

Run just this module, or choose a larger reproducible campaign:

```sh
sbt 'propertyTests/test'
AVROGEN_TEST_SEED=42 AVROGEN_TEST_CASES=200 AVROGEN_TEST_DEPTH=4 AVROGEN_TEST_VALUES=12 sbt 'propertyTests/test'
```

The default random campaign uses 48 schemas, maximum depth 3 and 8 values per
schema, in addition to **149 required schemas**: 142 mechanically composed
type/context cells and seven union, recursion and empty-record composites.
The default run therefore compiles **197 schemas and exercises 2,174 values**.
`AVROGEN_TEST_SEED` takes a numeric Long; the default is `20260917`.
Coverage includes 283 required value labels checked against the actual generated values as well as the schema
graph, including all 13 general-union branch categories, signed numeric extrema,
noncanonical NaN payloads, UTF-8 widths, nullable branch orders, empty/nonempty
collections, and every supported logical mapping. Random luck cannot substitute
for the required corpus. Each run writes a `coverage.txt` report beneath
`property-tests/target/schema-properties/`.

Failures retain the seed, schema, values, and generated source. For runtime
interoperability failures, a bounded shrinker reduces values and simplifies
schemas together while retaining valid data and the failing property direction.
It removes record fields and selects terminal union branches; it does not yet
shrink to a smaller multi-branch union or unwrap containers. Up to 32 attempts
produce a smaller reproduction where possible, not a guaranteed global minimum.
Compilation failures retain each input case in the failing batch for replay;
they are not automatically shrunk. The failure folder
contains `schema.avsc` and `value-*.bin`; replay it with:

```sh
AVROGEN_TEST_REPLAY=/absolute/path/to/failure-folder sbt 'propertyTests/test'
```

The four property-suite tests also check 100 ScalaCheck-generated cases and their
shrinks for Java validity, exercise shrink/save/load/compile replay end to end,
and inject writer and decoder faults to verify that the oracle rejects them.
Numeric runtime types remain distinct: `Int(7)` is not interchangeable with
`Long(7)` in a union. Floating-point comparisons preserve raw bits, including
signed zero and NaN payloads.

Generated sources are compiled against **runtime and Scala only**. The Scala
compiler, ScalaCheck and Java reference implementation belong to the unpublished
test project; no new dependency is added to generated applications. The full
suite now contains **132 tests across 17 suites**, including the 128 retained
regressions below.

## Targeted regression coverage

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

## Why targeted tests remain

The 20 checked-in `.avsc` fixture schemas are hand-written regressions.
The build generates the Scala models and codecs from those schemas, then
compiles and exercises that generated source. Some compiler and resolver tests
also construct specific schema JSON examples directly in the test code.

Boundary tables, explicitly chosen records and fixed-seed random loops supply
additional regression values. They preserve examples that have a specific
expected byte layout or failure mode. A schema/value generator producing valid
data cannot replace tests for malformed encodings, resource limits, ownership,
or writer/reader schema resolution.

## Relationship to avro2s tests

The avro2s tests were reviewed for useful behaviours, not copied wholesale.
Avrogen has a different generated API and its own native binary engine. The
goal is to automate the combinations that avro2s enumerated by hand and retain
small regressions for behaviours needing an explicit oracle.

| avro2s scenario | Avrogen treatment |
| --- | --- |
| Arrays/maps of primitives and named types; nested containers; nullable and general unions | Generate type/context combinations, compile them, and check Java interoperability; keep existing union and empty-collection regressions. |
| Logical values, including negative epochs and precision boundaries | Generate valid physical values; retain exact conversion/range regressions. Avrogen rejects precision loss, whereas some avro2s tests expect truncation. |
| Namespaces, reserved identifiers, enum member collisions, recursion, large schema literals | Retain compiler regressions. These are compiler and naming properties as well as datum properties. |
| Mutable Java collection getters, positional `get`/`put`, reused `SpecificRecord` instances, shared no-argument defaults | These APIs are absent from immutable Avrogen models. Test byte ownership and caller-owned I/O state instead. |
| Java enum wrappers, `SCHEMA$` reflection and Confluent-style class lookup | These exercise avro2s's Java `SpecificRecord` integration, not Avrogen's native model contract. |
| Schema defaults | Defaults are used by the optional resolver, not Scala constructor defaults. Existing resolution tests check collections, bytes, fixed, logical defaults and fresh construction slots. |

The main reviewed sources were avro2s's Scala 3 `SerializationTest`,
`CodeGeneratorTest`, `LogicalTypesTest`, `EnumSerializationTest`,
`GeneratedArrayRuntimeTest`, `MapConversionRuntimeTest`, and
`GeneratedByteDefaultsTest`, plus its shared schema-literal and naming tests.
This establishes a scenario audit, not a claim of identical test-by-test coverage.

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

Valid, matching-schema datum generation leaves several distinct dimensions to
explore mechanically:

1. **Writer/reader schema pairs:** compose aliases, field removal/reordering,
   promotions, reader defaults, enum changes and union branch changes; compare
   resolved Scala models with Java resolution. Existing resolver and evolution
   regressions cover examples, not generated pairs.
2. **Alternative legal wire layouts:** repartition arrays and maps into multiple
   positive and sized negative blocks, including nested blocks and zero-byte
   items. Generic writers do not explore every legal block layout.
3. **Malformed input and budgets:** mutate lengths, indices, varints and UTF-8,
   truncate data, and vary cumulative item/depth/byte limits. Native rejection
   rules have their own oracle; Java acceptance is not the intended policy.
4. **Schema text and file graphs:** generate naming collisions, metadata escaping,
   default-package interactions, separate-file references and JVM string-constant
   boundaries, beyond the existing focused compiler regressions.
5. **State and ownership:** generate sequences of reset/write/read/export
   operations and mutation attempts around byte boundaries. A stateless datum
   round trip alone does not establish independence of retained results.

There is no published code-coverage percentage or repository CI matrix across
JDK/Scala versions. The suite does not establish exhaustive Avro conformance.
Correctness coverage also does not establish performance across the same types:
the comparative Java/avro2s/Avrogen measurements still use the Trade schema;
expanded native workloads cover a wider, but separate, set of cases.
