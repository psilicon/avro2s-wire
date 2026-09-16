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
with Java encoder/decoder operations for measurement and interoperability.

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

## Compatibility boundary

Current codecs require the writer and reader to share a schema. The future resolver
must produce cached reader plans with writer-order traversal, field skipping,
defaults, aliases, promotions, enum remapping, and union selection. It must preserve
metadata relevant to resolution when constructing cache keys; parsing-canonical
fingerprints alone omit some of that metadata.

General Scala union syntax is only safe when runtime values retain each Avro
branch's identity. Where mappings overlap, generated tagged alternatives are
needed. Opaque aliases alone cannot solve runtime branch ambiguity.

The supported subset has binary interoperability tests in both directions with
Java Avro. Broader wire compatibility, JVM API compatibility, file containers,
and registry framing are separate milestones.
