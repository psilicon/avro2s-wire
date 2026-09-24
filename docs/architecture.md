# Architecture

## Data path

Schema generation:

```text
.avsc -> Apache Avro parser -> typed schema graph -> Scala models + direct codecs
```

Application execution:

```text
Scala model <-> generated AvroCodec[A] <-> native binary input/output <-> bytes
```

Apache Avro's parser is a build-time dependency. Generated sources only reference
the small Scala runtime. An optional adapter can replace native binary operations
with Java encoder/decoder operations. Both engines emit the same Avro datum wire
format. The adapter integrates with callers already using Java Encoder/Decoder
APIs and isolates primitive costs in benchmarks.

## Why generation comes first

Record codecs call fields directly and construct each result once. Collection
codecs traverse the application collection directly and build the requested Scala
collection during reading. There is no intermediate GenericRecord, Java collection
copy, positional get/put interface, or runtime reflective class lookup.

Named references in the schema graph terminate traversal and preserve recursion.
Companion codecs refer to other codecs inside read/write methods, so recursive
schemas do not require recursively constructing codec instances at startup.

Metadata stays in schemaJson; it is not parsed during native codec initialization.
Large schema literals are assembled from bounded chunks to avoid JVM string constant
limits. Very large generated methods still need future splitting and compile-size
benchmarks.

## Performance choices that remain open

The first model uses Vector and immutable Map for predictable semantics. Generic
primitive collection elements can box. Specialised contiguous storage should be
evaluated against that baseline before changing the public model.

Bytes are immutable and own decoded storage. Borrowed views, input pooling, and
mutable record reuse would be separate APIs with explicit lifetimes. Native output
already supports buffer reuse. Direct field access is the main starting hypothesis;
a handwritten varint loop alone is not evidence of a faster library.

The first measured optimisations preserve these APIs and validation policies.
Integer writers reserve enough space for the maximum encoded width, then use a
local offset and publish the final position once. Generated collection readers
validate the initial block header before allocating a builder; empty collections
return the standard immutable empty value. String readers retain strict UTF-8
validation and reuse its ASCII result to select a simpler JDK decoding path;
string writers validate before emitting bytes and copy all-ASCII text with local
indices. Unicode fallback behaviour is unchanged. See the
[historical paired measurements](benchmarks/HISTORY.md) for benefits and limits.

## Compatibility boundary

Direct codecs require matching schemas. The optional resolution module now compiles
reader plans with writer-order traversal, field skipping, defaults, aliases,
promotions, enum remapping, and union selection. Each reader instance retains its
plan; exact matching schema JSON bypasses resolution and uses the generated codec.
Resolution constructs Scala models through generated factories, using a temporary
array of reader-ordered values per record. Parsing-canonical fingerprints omit
resolution metadata such as defaults and aliases and are not used as cache keys.

General unions now use Scala union syntax, with schema-owned branch indices.
Named records, enums and fixed wrappers retain their runtime identity. Nullable
unions map to Option of the non-null alternatives. The only overlapping logical
mapping in the current supported set is time-millis plus time-micros: their union
uses distinct TimeMillis/TimeMicros wrappers, while unambiguous fields stay
LocalTime. Opaque aliases alone would not preserve runtime branch identity.

The supported subset has binary interoperability tests in both directions with
Java Avro. Broader wire compatibility, JVM API compatibility, file containers,
and registry framing are separate milestones.

See [schema evolution](schema-evolution.md) for plan construction, validation, and
compatibility boundaries. The runtime remains independent of Apache Avro; the
optional resolver uses Jackson for JSON and implements resolution itself.
