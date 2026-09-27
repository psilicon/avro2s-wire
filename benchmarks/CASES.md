# Maintained benchmark cases

This catalogue is the approved controlled comparison. Each row below identifies one input distribution, not a group of hidden benchmarks. Cases have separate encode and decode measurements except E01–E03, which measure decode only. The machine-readable source is [`src/main/resources/suite/catalog.json`](src/main/resources/suite/catalog.json).

There are **61 cases, 119 operations per fully supporting engine, and 468 default implementation/operation combinations**: Wire 119, Java specific 117, Java generic 117, generated Java custom 81, and avro2s 34. The opt-in `wire-stack-safe`, `wire-java` and `wire-java-stack-safe` engines each support all 119 operations, without expanding the default campaign. Custom capabilities come from genuine generated code and are checked by dispatch probes before measurement. Unsupported combinations are omitted with a reason, not measured through a fallback.

## Operation contract

The following describes fresh usage. Reuse usage and the additional Wire engines
are documented in [the harness guide](README.md); those choices change the I/O
lifecycle, not the case definitions below.

- Encode starts from an already constructed public model containing Strings and domain values, and returns an independent byte array. Buffer creation, encoding, flushing and the final output copy are included.
- Decode starts from prepared Avro bytes, creates a fresh model and its contents, and consumes the complete input. String fields and map keys are Strings for every engine.
- Model creation, oracle payload generation, schema parsing, reader/writer creation and resolution-plan compilation are outside measurement. Readers and writers can be retained; output arrays and decoded models cannot be reused across invocations.
- One codec operation is performed per JMH invocation. The benchmark rotates through a fixed corpus of 256 inputs, prepared with seed 1374496521. There is no manually batched timing loop.
- Scalar, string, binary, collection, union and logical cases use a record with a single field named `value`. This retains the same record boundary while isolating the field type. Structural and evolution cases use the shapes below.
- Comparisons use each implementation's public model, including its actual collection types. They do not assert that immutable Scala collections and mutable Java collections have identical construction costs.
- Both ns/op and allocated B/op are reported. Allocation means allocated heap bytes, not retained memory or process RSS. There is no weighted overall score or aggregate speedup.

The avro2s column explicitly selects Scala 3 enums (`EnumType.ScalaEnum`). Java specific uses generated Java enums. Both produce the same Avro enum symbols on the wire. Java generated models use `String`; decimal, big-decimal and duration use Apache Avro's official logical conversions. Generated sources and their provenance are recorded in [`generator/suite-baselines.json`](generator/suite-baselines.json).

## Primitive fields

Integer ranges below refer to the signed value before Avro zig-zag encoding. Positive and negative values are balanced. Every fixed-width range includes its smallest and largest magnitude; wider values then use the fixed seed. The one-byte cases deliberately include Java's usual small boxed-integer cache range and must be interpreted with that fact visible.

| ID | Input | avro2s |
| --- | --- | --- |
| P01 | Boolean, exactly half true and half false | Yes |
| P02 | Int, exactly 1 encoded byte: −64…63 | — |
| P03 | Int, exactly 3 encoded bytes: −1,048,576…−8,193 or 8,192…1,048,575 | Yes |
| P04 | Int, exactly 5 encoded bytes: −2³¹…−134,217,729 or 134,217,728…2³¹−1 | — |
| P05 | Long, exactly 1 encoded byte: −64…63 | — |
| P06 | Long, exactly 5 encoded bytes: −17,179,869,184…−134,217,729 or 134,217,728…17,179,869,183 | Yes |
| P07 | Long, exactly 10 encoded bytes: −2⁶³…−2⁶²−1 or 2⁶²…2⁶³−1 | — |
| P08 | Float, finite values between −1,024 and 1,024, including signed zeros and both endpoints | — |
| P09 | Double, finite values between −1,024 and 1,024, including signed zeros and both endpoints | Yes |
| P10 | Enum, eight symbols S0…S7 used equally often; Scala enum for avro2s | Yes |

## String fields

Sizes are **UTF-8 text bytes**, excluding the Avro length prefix. Each character family is measured separately, at 48 bytes, 3 KiB and 192 KiB. Characters vary within the stated family; no string consists of a single repeated character. Code-point counts differ intentionally so that encoded text size is controlled. Supplementary characters occupy two UTF-16 code units each.

| ID | Character family | UTF-8 bytes | Code points | avro2s |
| --- | --- | ---: | ---: | --- |
| T01 | Printable ASCII (U+0020…U+007E) | 48 | 48 | Yes |
| T02 | Printable ASCII (U+0020…U+007E) | 3,072 | 3,072 | Yes |
| T03 | Non-ASCII Latin-1 (U+00A0…U+00FF) | 48 | 24 | — |
| T04 | Non-ASCII Latin-1 (U+00A0…U+00FF) | 3,072 | 1,536 | — |
| T05 | Two-byte BMP beyond Latin-1 (U+0370…U+03FF) | 48 | 24 | — |
| T06 | Two-byte BMP beyond Latin-1 (U+0370…U+03FF) | 3,072 | 1,536 | — |
| T07 | Three-byte BMP (U+4E00…U+4FFF) | 48 | 16 | — |
| T08 | Three-byte BMP (U+4E00…U+4FFF) | 3,072 | 1,024 | — |
| T09 | Supplementary (U+1F600…U+1F63F) | 48 | 12 | — |
| T10 | Supplementary (U+1F600…U+1F63F) | 3,072 | 768 | — |
| T11 | Printable ASCII (U+0020…U+007E) | 196,608 | 196,608 | Yes |
| T12 | Non-ASCII Latin-1 (U+00A0…U+00FF) | 196,608 | 98,304 | — |
| T13 | Two-byte BMP beyond Latin-1 (U+0370…U+03FF) | 196,608 | 98,304 | — |
| T14 | Three-byte BMP (U+4E00…U+4FFF) | 196,608 | 65,536 | — |
| T15 | Supplementary (U+1F600…U+1F63F) | 196,608 | 49,152 | — |

## Binary fields

All bytes are deterministic pseudorandom data. Encoded lengths include an Avro length prefix for `bytes` and no length prefix for `fixed`.

| ID | Input | avro2s |
| --- | --- | --- |
| B01 | Bytes, 32 bytes | — |
| B02 | Bytes, 4,096 bytes | — |
| B03 | Bytes, 65,536 bytes | — |
| B04 | Fixed, 16 bytes | — |

## Collections

Array elements use the P03 three-byte-int distribution. Map values use the P06 five-byte-long distribution. Map keys are unique eight-character ASCII strings within each map, constructed without deliberate hash collisions. Map equality is semantic; iteration order is not a serialization requirement.

| ID | Input | avro2s |
| --- | --- | --- |
| C01 | Array of 8 ints | Yes |
| C02 | Array of 1,024 ints | Yes |
| C03 | Map of 8 longs | Yes |
| C04 | Map of 128 longs | Yes |

## Record structure

Each flat/nested pair contains the same ordered long values from P06 and has identical Avro data bytes. The difference is model structure and traversal. Chains are finite schema shapes, with one long and a child record at each level except the terminal record, which has one long. There are no unions, optional links or recursive schema references in these cases.

| ID | Input | avro2s |
| --- | --- | --- |
| R01 | One record containing 4 long fields, f0…f3 | — |
| R02 | Chain of exactly 4 record objects, containing 4 longs total | — |
| R03 | One record containing 16 long fields, f0…f15 | — |
| R04 | Chain of exactly 16 record objects, containing 16 longs total | — |

## Unions

Branches are separate measurements. No weighted branch mixture is hidden in these cases.

| ID | Schema and selected branch | avro2s |
| --- | --- | --- |
| U01 | `["null", "int"]`, null branch | Yes |
| U02 | `["null", "int"]`, P03 int branch | Yes |
| U03 | `["int", "string", record]`, P03 int branch | Yes |
| U04 | `["int", "string", record]`, T01 string branch | Yes |
| U05 | `["int", "string", record]`, record of four P06 long fields | Yes |

Apache Avro generates custom coders for the optional union model, but does not generate them for the three-branch union model. U03–U05 therefore have no Java-custom results.

## Logical types

These measurements use logical domain values throughout, rather than silently falling back to the physical representation. Dates and timestamps cover 1960–2030, including dates before the Unix epoch. Times vary throughout the day. Fractional values respect the declared precision. Decimals have exactly the stated number of unscaled decimal digits and balanced signs. Java uses `java.math.BigDecimal`; Wire's default public representation is Scala `BigDecimal`.

| ID | Logical type and physical encoding | Corpus constraint |
| --- | --- | --- |
| L01 | date / int | `LocalDate`, 1960–2030 |
| L02 | time-millis / int | `LocalTime`, exact millisecond precision |
| L03 | time-micros / long | `LocalTime`, exact microsecond precision |
| L04 | timestamp-millis / long | `Instant`, exact millisecond precision |
| L05 | timestamp-micros / long | `Instant`, exact microsecond precision |
| L06 | timestamp-nanos / long | `Instant`, nanosecond precision |
| L07 | local-timestamp-millis / long | `LocalDateTime`, exact millisecond precision |
| L08 | local-timestamp-micros / long | `LocalDateTime`, exact microsecond precision |
| L09 | local-timestamp-nanos / long | `LocalDateTime`, nanosecond precision |
| L10 | uuid / string | Deterministic UUID values |
| L11 | duration / fixed[12] | Months 0…24; days 0…31; milliseconds 0…86,399,999 |
| L12 | decimal / bytes | Precision 18, scale 4, exactly 18 unscaled digits |
| L13 | decimal / fixed[8] | Precision 18, scale 4, exactly 18 unscaled digits |
| L14 | decimal / bytes | Precision 50, scale 10, exactly 50 unscaled digits |
| L15 | decimal / fixed[32] | Precision 50, scale 10, exactly 50 unscaled digits |
| L16 | big-decimal / bytes | Exactly 50 unscaled digits, scale 6 |

The avro2s comparison does not include these cases. Apache Avro does not generate custom coders for these logical models, so the Java comparisons are ordinary generated specific and generic readers/writers. Duration uses Wire `AvroDuration` and Java `TimePeriod`; both preserve the same months, days and milliseconds.

**L06 and L09: Java decode only.** Apache Avro 1.12.1 incorrectly shifts fractional pre-epoch values by +999 milliseconds in its official timestamp-nanos and local-timestamp-nanos encoding conversions. The approved 1960–2030 input range remains unchanged. Java specific and Java generic encodes fail semantic correctness for these cases and are therefore explicitly unavailable; their valid decodes remain measured. The harness does not patch the Java implementation. Oracle payloads for these nanosecond cases use independent epoch-nanosecond arithmetic, and the capability manifest records the exclusion reason per operation.

## Schema evolution

These are decode-only cases with cached readers and resolution plans. Source payloads use the writer schema; readers construct a fresh model using the reader schema. Reader and writer records keep the same fully qualified name within each implementation.

| ID | Writer → reader | Purpose |
| --- | --- | --- |
| E01 | `{id: long, blob: bytes[4096]}` → `{id: long}` | Skip an unrequested binary field |
| E02 | `{id: long}` → `{id: long, enabled: boolean = true}` | Insert a reader default |
| E03 | `{value: int}` → `{value: long}` | Legal Avro int-to-long promotion, using P03 inputs |

The id fields use P06 values. The avro2s comparison does not include evolution. Generated Java custom dispatch must be verified with these differing writer/reader schemas before timing; a normal-reader fallback cannot be labelled custom.

## Scope

This suite excludes Kafka/Schema Registry framing, cold reader/plan construction, malformed-input timings, empty collection diagnostics and reused-buffer-only writes. Those need their own explicitly approved questions rather than silently becoming part of a summary. Stack-safe codecs and the Wire Java backend are selectable on the same controlled inputs; these finite record shapes do not measure extreme-depth stack safety. Full results retain every approved case and each engine's supported/not-supported status.
