# Logical types and generator options

## Supported mappings

The [official logical-types specification](https://avro.apache.org/docs/1.12.0/specification/#logical-types)
is the canonical list. Its `big-decimal` description is inside the Decimal section,
which makes it easy to miss. Wire supports these standard logical type names:

| Logical type | Default generated value |
| --- | --- |
| `decimal` | `scala.BigDecimal`; named wrapper for fixed storage |
| `big-decimal` | `scala.BigDecimal` |
| `uuid` | `java.util.UUID`; named wrapper for fixed storage |
| `date` | `java.time.LocalDate` |
| `time-millis`, `time-micros` | `java.time.LocalTime` |
| `timestamp-millis`, `timestamp-micros`, `timestamp-nanos` | `java.time.Instant` |
| `local-timestamp-millis`, `local-timestamp-micros`, `local-timestamp-nanos` | `java.time.LocalDateTime` |
| `duration` | Named wrapper around `AvroDuration` |

Time unions use distinct `TimeMillis`/`TimeMicros` wrappers when both units use
converted representations. A raw branch retains its physical `Int` or `Long`;
a remaining converted branch uses `LocalTime` directly.
`time-nanos` is a proposal, [AVRO-4043](https://issues.apache.org/jira/browse/AVRO-4043),
not part of the published list as checked on 24 September 2026.

## Decimal representation and equality

`GeneratorConfig(decimalType = DecimalType.Java)` or CLI `--decimal-type java`
selects `java.math.BigDecimal` for both decimal logical types. The default is
`DecimalType.Scala`. This option applies throughout a generation call, including
nested records, arrays, maps, unions and named fixed wrappers. Model representation
metadata travels with the generated codec and is used by `ResolvingReader`.

Scala decimals compare numerically: `BigDecimal("1.0") == BigDecimal("1.00")`.
Java `BigDecimal.equals` also compares scale, so the corresponding Java values
are unequal; `compareTo` still considers them numerically equal. Generated case
classes inherit their fields' equality semantics. See the
[Java BigDecimal API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html#equals(java.lang.Object)).

For `decimal`, scale and maximum precision belong to the schema. Encoding can
add or remove trailing zeros exactly; it never rounds. Decoding returns the
schema's scale. A Java decimal supplied at a different scale can therefore be
numerically equal to its round trip but fail `equals`.

For `big-decimal`, scale belongs to each value. No schema precision/scale is
required. It uses bytes storage, retaining the signed unscaled integer and its
32-bit scale, including negative scales and trailing zeros. Scala decoding uses
`BigDecimal.exact`, retaining the decoded Java value and choosing an arithmetic
context large enough for its precision (at least 34 digits). The two logical
types have different bytes representations; changing a schema's logical annotation
does not convert previously stored data between them.

For both logical types, `value.bigDecimal` exposes the exact underlying Java
value, so `left.bigDecimal.equals(right.bigDecimal)` provides scale-sensitive
equality. Ordinary decimal retains the schema's scale; big-decimal retains the
encoded value's scale. `BigDecimal(javaValue)` would also preserve that value and
scale at construction; `exact` additionally selects the larger arithmetic context
when required by the value's precision.

The native runtime implements this encoding without Apache Avro dependencies.
Tests compare with Java's `Conversions.BigDecimalConversion`, compile generated
models in both configurations, and check schema evolution as well as exact
unscaled values and scales.

## Namespace mapping

```scala
GeneratorConfig(namespaceMappings = Map(
  "com.acme" -> "myapp.model",
  "com.acme.events" -> "myapp.events"
))
```

Mappings match namespace prefixes on dot boundaries, and the longest source prefix
wins. They are applied once to the original namespace; targets are not remapped.
Thus `com.acme.orders.Order` becomes `myapp.model.orders.Order`,
`com.acme.events.audit.Entry` becomes `myapp.events.audit.Entry`, and
`com.acmeother.Order` is unchanged. Input map iteration order has no effect.

Only Scala packages, qualified type references and output paths change. Avro
full names, aliases, schema JSON and named-codec lookup keys remain original,
so namespace mapping does not change interoperability or Avro name resolution.
Mappings apply to reachable records, enums and fixed types, including recursion.

An empty source (`"" -> "myapp.model"`) maps only types with no Avro namespace.
An empty target (`"com.acme" -> ""`) removes that prefix: `com.acme.Order` moves
to the default package, while `com.acme.orders.Order` moves to `orders.Order`.
The compiler rejects unsafe identifiers, collisions and references from named
Scala packages into the default package. Directory generation validates the
whole output before writing, including collisions between separate input files.

CLI: repeat `--namespace-map from=to`. For default namespaces, use
`--namespace-map =myapp.model` or `--namespace-map com.acme=`.

## Raw and converted logical types

```scala
GeneratorConfig(logicalTypes = Map(
  LogicalType.Date -> LogicalTypeMode.Raw,
  LogicalType.Uuid -> LogicalTypeMode.Raw,
  LogicalType.TimestampMicros -> LogicalTypeMode.Converted
))
```

Every supported logical type has an independent choice. `Converted` is the
default for missing entries and uses the domain types in the first table.
`Raw` uses the following physical representations:

| Logical annotation / storage | Raw generated value |
| --- | --- |
| `date`, `time-millis` / int | `Int` |
| `time-micros`, all timestamp and local-timestamp units / long | `Long` |
| `uuid` / string | `String` |
| `decimal`, `big-decimal` / bytes | `Bytes` |
| `decimal`, `uuid`, `duration` / fixed | Existing named fixed wrapper around `Bytes` |

The setting applies to all occurrences of the logical name in that generation
call, including both bytes and fixed storage for decimal, and both string and
fixed storage for UUID. The decimal representation choice matters only for
converted decimals. Fixed wrappers keep exact byte-length checks and preserve
nominal distinctions between union branches.

Raw mode retains logical annotations in schema JSON and still validates schema
annotations and decimal schema compatibility. It skips value conversion and its
semantic checks: for example, a raw UUID string need not parse as a UUID, and raw
big-decimal bytes are not inspected as a decimal payload. Callers producing data
for converted readers must supply valid logical values in their physical form.
UTF-8, binary decoding limits and other physical-format checks still apply.

Reader defaults and schema evolution use the chosen representation automatically.
The settings for fields belong to their generated named model; separately generated
child models can have different choices. Named-codec lookup remains lazy for
unused alternatives. Matching-schema codecs emit direct physical operations for
raw values; they do not inspect options at runtime.

CLI: repeat `--logical-type date=raw` or `--logical-type timestamp-micros=converted`.
The logical names are the Avro spellings in the first table. Duplicate keys and
unknown names or modes are rejected. Unknown logical annotations in a schema
remain errors, including when other annotations are configured as raw.

## Pre-epoch nanosecond timestamps

Java Avro [AVRO-4269](https://issues.apache.org/jira/browse/AVRO-4269) encoded some
pre-epoch fractional instants incorrectly. For example, half a second before the
epoch must encode as `-500000000`, but the affected conversion produced `499000000`.
The fix shipped in [Avro 1.12.2](https://avro.apache.org/blog/2026/08/12/avro-1.12.2/).

Wire performs its own logical conversions with floor division and checked
arithmetic. This also applies when using the Java backend: the adapter delegates
binary primitives, not time conversion. Tests cover negative fractions, both
signed long boundaries, out-of-range values, and the literal upstream regression
case for both instant and local timestamps. The build still pins Java Avro 1.12.1;
its buggy time conversion is not used as the oracle for these cases.

## Options and extensions to consider later

Decimal representation, namespace mapping and per-logical-type representations
are implemented. An explicit unknown-logical-type policy could be a future option. Collection choices such as Vector
versus List should follow measured use cases, since each adds API and testing
combinations. Constructor defaults and documentation generation could improve
usability without changing how Avro defaults operate on the wire.

For custom logical types, prefer a small explicit build-time registry of bindings:
logical name, schema validator, target Scala type, and qualified conversion methods
between the Avro storage value and that type. Generated codecs would call those
methods directly. Schema resolution and defaults must use the same binding, and
union validation must know the target's runtime identity after erasure. A custom
mapping requires its conversion code on the consuming application's classpath.

This extension API is a proposal, not implemented behavior. Start with a concrete
custom-type use case before freezing it. Avoid reflection, global registration,
or runtime plugin discovery; keep ordinary built-in mappings direct. The current
compiler explicitly rejects unknown logical annotations, rather than applying
the specification's permissive underlying-type fallback.
