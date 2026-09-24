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

Time unions use distinct `TimeMillis`/`TimeMicros` wrappers when both units appear.
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
`BigDecimal.exact`, avoiding rounding to a default math context. The two logical
types have different bytes representations; changing a schema's logical annotation
does not convert previously stored data between them.

The native runtime implements this encoding without Apache Avro dependencies.
Tests compare with Java's `Conversions.BigDecimalConversion`, compile generated
models in both configurations, and check schema evolution as well as exact
unscaled values and scales.

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

Only decimal representation is configurable today. Useful next options would be
namespace/package mapping, per-logical-type raw versus converted representations,
and an explicit unknown-logical-type policy. Collection choices such as Vector
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
