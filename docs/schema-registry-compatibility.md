# Compatibility with Confluent Avro SerDes 8.1.5

Wire implements a generated-model adapter, not the complete configurable behavior
of `KafkaAvroSerializer` and `KafkaAvroDeserializer`. This accounting is pinned to
the 8.1.5 source artifacts used by this build:
[Avro serializer](https://packages.confluent.io/maven/io/confluent/kafka-avro-serializer/8.1.5/kafka-avro-serializer-8.1.5-sources.jar),
[shared SerDe](https://packages.confluent.io/maven/io/confluent/kafka-schema-serializer/8.1.5/kafka-schema-serializer-8.1.5-sources.jar),
[registry client](https://packages.confluent.io/maven/io/confluent/kafka-schema-registry-client/8.1.5/kafka-schema-registry-client-8.1.5-sources.jar),
and [extended Avro types](https://packages.confluent.io/maven/io/confluent/kafka-avro-types/8.1.5/kafka-avro-types-8.1.5-sources.jar).
It does not assert behavior for other releases or registry vendors.

See the [adapter guide](schema-registry.md),
[complete settings example](schema-registry-examples.md),
[settings definitions](../schema-registry/src/main/scala/avro2s/wire/registry/RegistrySettings.scala),
and [generated root-union example](schema-registry-unions.md) for usage.

## Models, schemas and selection

| Feature | Confluent 8.1.5 | Wire support and boundary |
| --- | --- | --- |
| Application model | Generic, Specific and reflection-based Avro models; runtime schema discovery. | Generated Scala models through the supplied `AvroCodec[A]`. No GenericRecord, SpecificRecord or reflection mode. Handwritten codecs must supply resolution hooks where needed. |
| Root schemas | Supports primitive and container schemas as well as named types; explicit-schema serialization can select a complete union. | Record, enum, fixed and generated root unions. Primitive, array and map roots are rejected, including Confluent's special raw-bytes root encoding. Primitive and collection branches within a union use ordinary Avro encoding. |
| Root unions and null | Explicit-schema APIs can encode a whole union; Java null remains a Kafka tombstone. | One codec and registry identity describe the whole union. Nullable unions use `Option`: `None` is encoded Avro null; JVM null is a tombstone. The degenerate `["null"]` model cannot distinguish these through the registry adapter. |
| Logical values | Avro data models and optional logical converters, with Confluent extensions. | Wire's [documented logical types](logical-types.md), including generated raw/converted representations. Unknown or invalid annotations are rejected, including Confluent `map` and `variant`; arbitrary conversion plugins are unsupported. |
| Evolution | Avro datum-reader resolution, with optional reader selection and rule migrations. | Fixed generated reader schema with Wire's [native resolution rules](schema-evolution.md): aliases, defaults, field changes, promotions, enums, fixed and unions. Logical compatibility and malformed-data checks remain Wire's stricter policy. |
| Writer selection | Auto-register, exact lookup, fixed ID/GUID, latest version or latest matching metadata; optional compatibility checks. | Exact codec-schema lookup by default; explicit auto-registration and normalization supported. Fixed-ID/GUID and latest selectors are unsupported. Header GUID placement selects framing, not a different writer schema. |
| Registration defaults | `auto.register.schemas=true`; normalization defaults to false. | `autoRegisterSchemas=false`; `normalizeSchemas=false`. Registration delegates subject compatibility enforcement to the server. |
| Reader selection | Generic writer-schema reads, Specific/reflection readers, explicit reader schemas, or latest/metadata-selected readers. | The construction-time codec always supplies the reader model/schema. Readers never register or normalize schemas. No runtime latest-reader selection. |
| Writer subjects | Configurable strategy classes; the 8.1.5 default is `AssociatedNameStrategy`, with TopicName fallback. | TopicName by default; RecordName and TopicRecordName supported for record roots only. Enum, fixed and union roots require TopicName. No association queries or custom strategy classes; `subject.name.strategy.*` properties are rejected. |
| Numeric reader scope | Subject/context-aware lookup based on configured strategies. | `SchemaLookup.ById` defaults to the client's namespace. TopicName, explicit Subject and fixed Context policies supply subject/context hints and separate cache entries. Hints do not prove subject membership. |
| Contexts | Configurable context-name strategy and qualified subjects. | Explicit numeric-reader contexts are supported. No writer context-strategy hook or arbitrary per-topic context routing. GUID lookup bypasses numeric lookup policy. |
| References | Registry client resolves referenced schema versions; schemas can carry reference descriptors. | Reads use resolved `AvroSchema.rawSchema()` including references, defaults and aliases. Writers submit their codec's self-contained schema; no API authors reference descriptors, metadata, tags or rule sets. Deploy those through registry tooling. |
| Metadata and rules | Descriptive metadata, validation, domain and encoding rules, migrations, executors/actions and associated hooks. | Descriptive metadata and empty rule sets accepted. Nonempty migration, domain or encoding rules are rejected on writer identity loads and reader plan loads. No rule execution, encryption/decryption, migration, rule metrics or validation plugins. Native Avro validation is independent of registry rules. |
| Additional APIs | Explicit-schema serialization, schema/version result wrappers and rule-result APIs. | Typed values in/out through Kafka interfaces. No per-call schema override, `NonRecordContainer`, schema/version wrapper or rule-result output. |

## Framing and validation

| Feature | Wire behavior relative to 8.1.5 |
| --- | --- |
| Classic numeric prefix | Supported: byte `0`, four big-endian ID bytes, then Avro. ID zero is valid; negative IDs are rejected. This is the default writer placement. |
| GUID header | Supported: raw Avro payload with `__key_schema_id` or `__value_schema_id` containing byte `1` and a 16-byte big-endian GUID. Writers require the registry-issued GUID in the registration/lookup response; they do not derive it or fetch it separately. |
| Header precedence | Matches `DualSchemaIdDeserializer`: the last own-role header wins; an absent or null-valued last header falls back to the payload prefix. The other role's header is ignored. Wire requires exactly 17 bytes/version 1 for a non-null header and fails without fallback when malformed. |
| Other identity formats | Confluent's `SchemaId.fromBytes` can parse numeric or GUID identity in either location. Wire rejects numeric-ID headers and GUID payload prefixes; custom schema-ID serializer/deserializer plugins are unsupported. |
| Header writes and tombstones | Header mode requires `Headers` for non-null values and appends an own-role header after successful encoding, or reuses an identical last header. Added headers own their byte arrays. Null values pass through without registry calls or header changes. Classic writers reject an existing own-role schema header. |
| Buffer input | Byte arrays and Kafka's ByteBuffer entry point are supported. Buffer range, position, limit, mark and byte order are preserved; direct/read-only input copies the validated datum. Empty GUID payloads are valid for empty records. |
| Native validation | Wire always rejects trailing datum bytes and malformed encodings. Confluent's `avro.fail.on.trailing.data` defaults to false. Wire's five optional `DecodeLimits` ceilings are disabled by default; framing bytes do not count toward `maxInputBytes`. |
| Strings | Wire rejects malformed UTF-16 by default; `WriterSettings` can select replacement. This is a native codec policy, not a Confluent serializer property. |
| Registry failures | HTTP 401/403/429 map to Authentication/Authorization/ThrottlingQuotaExceeded exceptions; 408/500/503/504 to Timeout; 502 to Disconnect. Other registry errors become SerializationException. These categories follow `AbstractKafkaSchemaSerDe.toKafkaException`. Interrupted I/O becomes Timeout; propagated InterruptedException restores the loader thread's interrupt flag. |

## Clients, caches and concurrency

Authentication, bearer-token providers, TLS, proxying, URL failover, HTTP timeouts,
retries and client cache policies belong to the supplied Confluent client. They
are delegated capabilities, not missing Wire implementations. Configure an
injected client yourself, or use `RegistryConnection` for an owned
`CachedSchemaRegistryClient`. Its `request.header.*` properties are passed as
HTTP headers. Injected clients must support concurrent use and the response APIs
`registerWithResponse`/`getIdWithResponse`, retaining attached rules and, for
header mode, GUIDs. `mock://` connections are rejected; inject
`MockSchemaRegistryClient` for tests.

Wire adds two bounded caches above the client: writer identities by subject and
reader plans by GUID or numeric ID plus lookup hint. Each adapter defaults to
1024 entries, has no TTL, shares one load for the same missing key, and permits
unrelated loads and warm calls concurrently. Failed loads are not retained.
Capacity is an entry bound, not a retained-byte budget or an exact LRU guarantee.
Confluent's SerDes also cache datum readers/writers and optional latest-schema
results, but ordinary registry identity lookups still pass through their client;
a warm Wire adapter can bypass that client entirely.

The underlying 8.1.5 `CachedSchemaRegistryClient` has several separate caches,
including latest and configurable negative caches. Its registration, schema/ID
lookup and GUID lookup cold-miss paths still hold a **client-wide monitor across
registry I/O**. Different cold keys sharing that client can therefore serialize,
even though Wire's outer cache permits concurrency. Wire does not remove that
limitation or override client retries/negative caching. Latest-cache TTL settings
do not give Wire's identity or reader-plan caches a TTL.

Use [`RegistryCaches.refresh`](schema-registry.md#client-creation-and-ownership)
with every live adapter sharing a client to drain calls, reset the client once,
and clear participating caches together. Calling `client.reset()` alone leaves
warm Wire entries intact. Rule checks also occur only on loads; neither adapter
polls an already-cached schema for administrative changes.

Each call owns its mutable encoding/decoding state; generated codecs and completed
plans are shared. Handwritten codecs must be thread-safe, and concurrent writes
must use separate mutable Kafka Headers. Close drains admitted calls and closes
an owned client once; injected clients remain caller-owned. Blocking client/cache
waits are synchronous and not promptly cancellable. Wire uses no thread-local
buffers, but client/Guava monitor behavior still matters for virtual threads;
see the [lifecycle discussion](schema-registry.md#client-creation-and-ownership).

Mixing an official Confluent key serializer with a Wire value serializer does
not reproduce Confluent's rule-hook lifecycle: `AbstractKafkaSchemaSerDe.postOp`
stores the key in a static ThreadLocal and normally clears it during the official
value operation. Wire does not clear that upstream state or supply rule context
to an official value serializer paired with a Wire key serializer.

## Configuration and scope

Adapters are ready-to-use instances with fixed codec, role and immutable settings.
There is no no-argument class-name construction path; inherited Kafka `configure`
is a no-op. Kafka property maps do not reconfigure Wire or an injected client.
`RegistryConnection.properties` accepts client settings and rejects recognized
unsupported SerDe modes; use typed settings for registration, normalization,
subjects, lookup and framing. Inactive selector defaults are accepted as described
in the [guide](schema-registry.md#supported-schemas-and-errors).

Registry administration—compatibility policies, modes, imports, deletion, schema
deployment, associations and access management—remains the responsibility of the
server and registry client/tooling. Wire does not expose these as Kafka adapter
features or duplicate their implementation. JSON Schema, Protobuf, Kafka Connect
converters and other Confluent integrations are also outside this Avro adapter's
scope.
