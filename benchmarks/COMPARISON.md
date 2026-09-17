# Comparative workloads

These benchmarks compare complete implementations of the same Avro datum:
avro2s-wire's native engine, the same generated codec on Java primitives, avro2s,
Java generated specific records, Java generated custom coders where available,
and Java generic records. They complement the original Trade benchmark.

## Profiles and supported implementations

`ComparisonBenchmark` has a `profile` parameter. Each of its ten profiles has
six implementations and two operations (`Read`/`Write`): 120 measured cases.

| Profile | Contents |
| --- | --- |
| `ints-small` | 1,024 signed one-byte ints (-64 through 63); boxing caches apply. |
| `ints-wide` | 1,024 signed five-byte ints near both integer extrema. |
| `longs-mixed` | 1,024 signed longs sampled with a fixed seed at every varint-width boundary, including extrema. |
| `string-ascii` | 1,024 ASCII code points, 1,024 UTF-8 bytes. |
| `string-unicode` | 1,024 code points repeating lambda, accented Latin, CJK and emoji, 2,816 UTF-8 bytes. |
| `bytes` | 4,096 deterministic bytes. |
| `collections-empty` | An empty int array and string map. |
| `collections-full` | An array of 64 noncached ints and a map of 64 string pairs. |
| `enum-fixed` | Scalar enum/fixed fields plus 32 enum and 32 sixteen-byte fixed values. |
| `numerics` | Scalar boolean/float/double plus 128 values of each, including negative floating zero. |

Three further classes have no parameters:

| Class | Contents | Implementations | Cases |
| --- | --- | --- | ---: |
| `NestedComparisonBenchmark` | Fifteen recursive records, each with null/int/string/named-record union branches. | Five: excludes Java custom. | 10 |
| `LogicalComparisonBenchmark` | Date, both time precisions, millis/micros timestamps and local timestamps, UUID; values include negative epochs. | Five: excludes Java custom. | 10 |
| `DecimalComparisonBenchmark` | Decimal bytes and 32-byte fixed decimal, each with precision 50 and scale 10. | Four: excludes Java custom and avro2s. | 8 |

The full comparative matrix therefore has **148 cases** across thirteen workload
configurations. Apache Avro 1.12.1 does not generate custom coders for the nested
union, logical or decimal root schemas. Running its ordinary fallback under a
`javaCustom` label would misrepresent the implementation, so those methods are
absent. Generated-source capability flags are recorded in the provenance manifest.

The pinned avro2s generator has no decimal logical conversion. Its raw bytes/fixed
model would omit the decimal work performed by the other implementations, so it
is excluded from this workload. Java specific generation explicitly enables
Decimal logical types. Java generic data registers DecimalConversion; both Java
readers return java.math.BigDecimal, compared with Scala BigDecimal and a named
fixed wrapper in avro2s-wire. All retain the full 50-digit value without rounding.

## Models, timing and verification

All timed readers allocate a fresh decoder/input and result. No datum reuse,
input reset or result normalization occurs in a timed read. All timed writers
reuse their engine's output buffer, resetting it and flushing Java encoders;
they return size and omit the final byte-array copy. Buffers reach the required
capacity during untimed verification. These classes measure read/write; the
separate matching-schema API workloads measure allocating encode/decode helpers.

avro2s-wire returns immutable records, Vector, Map and owned Bytes. Avro2s returns
its genuine generated mutable records with Scala collections. Java uses its
normal specific/generic models and Java collections. Default Java strings decode
as Utf8/CharSequence. Every writer starts with String values and String map keys,
verified during setup; none receives pre-encoded Utf8 input. Native validation
and resource limits remain enabled. Java paths retain Java's policy. This is
therefore a comparison of usable implementations, including representation and
validation costs, rather than identical primitive work.

Logical benchmarks register the supported Java logical conversions in generic
and specific data models; readers construct actual dates/times/UUIDs rather than
returning cheaper underlying ints/longs/strings. Inputs are exactly representable
at the selected precision. avro2s-wire's precision rejection and avro2s/Java's behavior
outside that input set are not claimed to be equivalent.

Fixture values and independent Java generic records are built outside timing.
Genuine generated Java and avro2s input models are populated outside timing by
reading the independent writer's raw bytes with their own matching schemas.
Java text inputs are then normalized to String; avro2s already returns String.
Each writer's output is
read by every available implementation before measurement. A schema-aware
normalizer compares actual model fields without re-encoding them. It retains
union branch indices, exact primitive kinds and floating bits, and ignores map
iteration order. Each reader must consume the full payload during verification.

Standard Java, generic and avro2s retain the fast reader. The Java custom path
disables it because it bypasses customDecode. Setup/test-only subclasses count
actual customEncode/customDecode calls: zero for the standard path, one each for
the custom path. The timed generated baseline records are unmodified.

## Reproduction and provenance

Run `sbt 'benchmarks/test'` before collecting measurements. The benchmark runner
selects the comparison classes and records exact commands, JDK, settings, source
hashes and raw JMH results. Keep the same settings across comparisons; JMH smoke
runs establish execution only, not speed claims.

[Generator provenance](generator/README.md) explains regeneration from the pinned
avro2s checkout. `generator/comparison-baselines.properties` records input/output
hashes, generator settings, exclusions and actual Java custom-coder capability.
Only namespaces change between model schemas so the classes can coexist; these
cross-reading checks use identical raw datum layouts, not schema resolution
between the relocated names. Evolution is a separate benchmark.
