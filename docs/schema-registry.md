# Confluent Schema Registry

The optional `avro2s-wire-schema-registry` module wraps generated native codecs
with Confluent's classic schema-ID framing. It lets Kafka applications serialize
and deserialize generated Scala models without converting them to `GenericRecord`.
The serializer calls the generated codec directly; the deserializer fetches the
writer schema by ID and uses Wire's native schema resolver to construct the
generated reader model.

The module is separate from `avro2s-wire-runtime`. Add it only to applications
that use Schema Registry. Its published artifact is
`io.psilicon:avro2s-wire-schema-registry_3`; the module depends on Confluent's
`kafka-schema-registry-client` 8.1.5 and Apache Avro 1.12.1. Confluent artifacts
are hosted at `https://packages.confluent.io/maven`. The published POM suppresses
repository declarations, so add the Confluent repository to the consuming build:

```scala
resolvers += "Confluent" at "https://packages.confluent.io/maven"

libraryDependencies +=
  "io.psilicon" %% "avro2s-wire-schema-registry" % "0.1.0-SNAPSHOT"
```

Use the Wire version from `build.sbt`. `Trade` below is a fixture model; replace
`avro2s.wire.fixtures.Trade` with your generated model and companion codec.
Fixtures are not published. Apache Avro and Confluent
dependencies are not required by applications that use only generated native
codecs and the runtime.

## Use as a Kafka serializer or deserializer

For a complete generated Avro key and value example, see
[Avro keys and values](schema-registry-examples.md).

Supply an adapter instance to Kafka's `KafkaProducer` or `KafkaConsumer`
constructor. Use `forKey` or `forValue` to choose its role and pass immutable
settings when constructing it. The adapter is ready to use immediately; there is
no separate configuration step. The no-argument class-name path cannot supply a
generated codec and is unsupported.

This example injects a shared Confluent client. The client remains owned by the
application, while Kafka calls `close` on the serializer when the producer closes.
Closing an injected adapter does not close that client. The `trades` topic and
its `trades-value` schema must already exist; the example looks up the schema
without registering it.

```scala
import avro2s.wire.fixtures.Trade
import avro2s.wire.registry.{RegistrySerializer, SerializerSettings, SubjectNameStrategy}
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import scala.jdk.CollectionConverters.*
import scala.util.Using
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

val settings = SerializerSettings(
  subjectNameStrategy = SubjectNameStrategy.TopicName,
  autoRegisterSchemas = false
)
val kafkaConfig = Map[String, AnyRef](
  "bootstrap.servers" -> "localhost:19092"
).asJava

Using.Manager { use =>
  val registry = use(new CachedSchemaRegistryClient("http://localhost:18081", 256))
  val serializer = RegistrySerializer.forValue(Trade.codec, registry, settings)
  val producer = use(new KafkaProducer[String, Trade](
    kafkaConfig, new StringSerializer(), serializer
  ))
  producer.send(new ProducerRecord("trades", "key", Trade(42L, "ABC", 12.5, Vector(1, 2))))
    .get()
}.get
```

The producer uses lookup-only schema selection, which is also the default.
For a producer and consumer specifying every Wire setting, see the
[complete configuration example](schema-registry-examples.md).

Construct a consumer with a corresponding reader codec:

```scala
import avro2s.wire.fixtures.Trade
import avro2s.wire.registry.RegistryDeserializer
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import scala.jdk.CollectionConverters.*
import scala.util.Using
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer

val kafkaConfig = Map[String, AnyRef](
  "bootstrap.servers" -> "localhost:19092",
  "group.id" -> "trade-reader"
).asJava

Using.Manager { use =>
  val registry = use(new CachedSchemaRegistryClient("http://localhost:18081", 256))
  val deserializer = RegistryDeserializer.forValue(Trade.codec, registry)
  val consumer = use(new KafkaConsumer[String, Trade](
    kafkaConfig, new StringDeserializer(), deserializer
  ))
  consumer.subscribe(java.util.List.of("trades"))
  val records = consumer.poll(java.time.Duration.ofSeconds(1))
  records.asScala.foreach(record => println(record.value()))
}.get
```

`Using.Manager` closes resources in reverse order, so each Kafka client closes
before the registry client. Its final `.get` propagates any operation or cleanup
failure.

When you inject a client, pass authentication and TLS properties to that client
when constructing it. Kafka properties do not reconfigure an injected client.
For example:

```scala
val registryConfig = Map[String, AnyRef](
  "basic.auth.credentials.source" -> "USER_INFO",
  "basic.auth.user.info" -> "<api-key>:<api-secret>"
).asJava
val registry = new CachedSchemaRegistryClient("https://registry.example", 256, registryConfig)
```

Alternatively, use `RegistryConnection` to let an adapter create and own its
registry client, as shown below.

The client accepts standard Confluent Schema Registry settings, including TLS
settings. See Confluent's [Schema Registry client configuration reference](https://docs.confluent.io/platform/current/schema-registry/sr-client-configs.html).

## Client creation and ownership

Pass an existing `SchemaRegistryClient` to the adapter when the application owns
client lifecycle or shares one client across serializers and deserializers:

```scala
val registry = new CachedSchemaRegistryClient("http://localhost:18081", 256)
val serializer = RegistrySerializer.forValue(Trade.codec, registry, SerializerSettings())
val deserializer = RegistryDeserializer.forValue(Trade.codec, registry)
```

The supplied client is caller-owned. Closing either adapter does not close that
client; close the shared client yourself after all Kafka clients and adapters
have closed.

An injected client must support concurrent use. Writers use Confluent's
`registerWithResponse` and `getIdWithResponse` methods so they can inspect rules
attached by the server. Custom client implementations must implement these
methods and retain the response's rule information; returning only an ID cannot
communicate unsupported registry rules. Confluent's cached and mock clients
provide these methods.

For in-memory tests, inject a `MockSchemaRegistryClient`. `RegistryConnection`
rejects `mock://` URLs at construction: it creates real HTTP clients and does not
participate in Confluent's shared mock-scope factory.

The same `forKey` and `forValue` factories also accept a `RegistryConnection`.
Each such call creates and owns a cached registry client. Closing the adapter
closes that client. The connection describes registry endpoints, client cache
capacity, authentication, TLS and other registry-client properties.

For example, the adapter can own an authenticated client:

```scala
import avro2s.wire.registry.{RegistryConnection, RegistrySerializer}

val connection = RegistryConnection(
  urls = List("https://registry.example"),
  cacheCapacity = 256,
  properties = Map[String, AnyRef](
    "basic.auth.credentials.source" -> "USER_INFO",
    "basic.auth.user.info" -> "<api-key>:<api-secret>"
  )
)
val serializer = RegistrySerializer.forValue(Trade.codec, connection)
```

In this form, closing the producer closes the serializer and its registry client.
For direct use outside Kafka, close the adapter yourself, for example with
`Using.resource`. Reusing a `RegistryConnection` shares connection settings;
it does not share a client. To share one actual client across keys, values or
Kafka clients, inject an application-owned `SchemaRegistryClient` instead.

Put URLs in `RegistryConnection.urls`. Its `properties` map accepts registry-client
properties only; serializer behavior belongs to `SerializerSettings`. Supplying
`schema.registry.url`, `auto.register.schemas` or `normalize.schemas` in the map
fails at connection construction. There is no duplicated configuration to keep in
sync, and Kafka's producer/consumer property maps do not configure Wire adapters.

Custom HTTP headers can be supplied as properties, for example
`"request.header.X-Tenant" -> "trading"`. Wire extracts these properties and passes
them to the HTTP client separately. The `schema.registry.request.header.` prefix
is also accepted; duplicate names, including case-insensitive aliases, are
rejected. Recognized unsupported Confluent serializer/deserializer options fail
at construction instead of being silently forwarded as client settings.

Each adapter has a bounded concurrent cache. The serializer caches validated
schema-ID prefixes by subject; the deserializer caches compiled writer-to-reader
plans by schema ID and the selected subject/context lookup hint. Cached calls can
encode or decode concurrently. Calls for the same missing key share a load, and
an unrelated load does not block cached calls.
Failed loads are not retained by the adapter; an underlying client's own
configured negative caches can still affect retries.
`SerializerSettings.cacheCapacity` and `DeserializerSettings.cacheCapacity` control
their respective adapter entry limits and default to 1024. Eviction does not
promise exact least-recently-used ordering. The underlying
`CachedSchemaRegistryClient` has several separately bounded caches, controlled by
`RegistryConnection.cacheCapacity` when the adapter creates it. These capacities
are not total retained-byte budgets. Its own cache-miss synchronization can
still serialize registry requests; Wire does not bypass it.

To refresh after an administrative change, reset both cache layers together:

```scala
import avro2s.wire.registry.RegistryCaches

RegistryCaches.refresh(serializer, deserializer)
```

Include every live Wire adapter sharing that client. The operation waits for
admitted calls, resets the shared client once, clears all participating adapter
caches, and then lets waiting calls proceed. It never closes the client. Duplicate
adapters are harmless; different client instances, closed adapters, and refresh
from inside an active participant are rejected. Concurrent refreshes acquire
adapters in a stable order. If reset fails, local caches are still cleared and the
failure is propagated; the client refresh is unverified and can be retried.

Wire does not track adapters globally. Calling `client.reset()` alone leaves
warm adapter caches intact, and omitting an adapter from `RegistryCaches.refresh`
leaves that adapter's entries intact. Coordinate direct client users separately.
Alternatively, drain and replace all adapters together with their client.

All URLs for one connection must identify the same registry and use consistent
context routing. An injected client must not switch registry identity under a
live adapter.
Independent registries and contexts can assign the same numeric ID to different
schemas. Reader cache keys include the selected lookup hint, and adapter caches
are not shared across connections.

The role and settings are immutable. Generated codecs and completed reader plans
are shared, while every call owns its mutable input/output state. Handwritten
codecs must support concurrent use too. Wire does not keep buffers in thread-local
or fiber-local storage. `close()` drains admitted calls, clears the caches, and attempts to close an
owned client once. It stays closed even if that cleanup fails. Concurrent close
callers wait for cleanup; close does not cancel an HTTP request or impose a new
timeout. Closing an adapter from inside one of its own operations is rejected.
Custom client/codec/reset callbacks must not synchronously wait for another
thread to use, close, or refresh a participating adapter: an exclusive operation
can create a cycle between that thread and the active callback. Same-thread
use/close/refresh from a client's reset callback is rejected. Lock acquisition
and shared-load waiting are not promptly cancellable; use an appropriate blocking
execution context for this synchronous API when integrating with an effect runtime.

On JDK 21–23, blocking inside Guava's cache loader can pin a virtual thread's
carrier, including with a concurrent custom registry client. Confluent's cached
client has its own monitor around several HTTP miss paths. JDK 24+ removes this
monitor-based pinning; the absence of Wire thread-local buffers alone is not a
virtual-thread scalability guarantee. JDK 21 also limits concurrent read-lock
holds to 65,535; JDK 25 raises this to `Integer.MAX_VALUE`. These are in-flight
operation limits, unrelated to datum sizes or nesting.

Serialization writes the prefix and datum into one working buffer and returns
one independently owned frame. Array deserialization reads the payload as a
bounded range without copying it. Kafka's `ByteBuffer` entry point uses the
backing array when accessible; direct/read-only buffers initially copy only the
validated payload. The caller's position, limit and mark are preserved. Do not
mutate input bytes during decoding. Decoded binary fields own their storage and
remain valid after the input buffer is reused.

String encoding is controlled by `SerializerSettings.writerSettings`. Its default
is `WriterSettings()`, which rejects unpaired UTF-16 surrogates. To replace them
with `?`, matching Java's UTF-8 conversion, construct the serializer with:

```scala
import avro2s.wire.registry.SerializerSettings
import avro2s.wire.runtime.{MalformedStringPolicy, WriterSettings}

val settings = SerializerSettings(
  writerSettings = WriterSettings(malformedStrings = MalformedStringPolicy.Replace)
)
```

Pass these settings to either `RegistrySerializer.forKey` or `forValue`. The
policy covers string fields and map keys in both standard and stack-safe codecs.
Replacement produces valid UTF-8 but changes malformed values; it does not
change deserializer validation or schema registration settings.

## Subjects and registration

The supported subject strategies follow Confluent's standard naming forms:

| Strategy | Value subject | Key subject |
| --- | --- | --- |
| `TopicName` (default) | `<topic>-value` | `<topic>-key` |
| `RecordName` | Avro record fullname | Avro record fullname |
| `TopicRecordName` | `<topic>-<record-fullname>` | `<topic>-<record-fullname>` |

`RegistrySerializer.forKey` selects the key subject;
`RegistrySerializer.forValue` selects the value subject. The role is fixed when
the adapter is created. Topic-based strategies require a non-empty topic. Record
name strategies derive the name from the generated Avro schema.

`SerializerSettings.autoRegisterSchemas` defaults to `false`: the serializer only
looks up the ID of the codec's exact schema under the selected subject.
Pre-register that schema under that subject before sending data; a missing match
is an error. Setting `autoRegisterSchemas = true` explicitly opts into registering
the schema and lets Schema Registry apply the subject's compatibility policy.
`normalizeSchemas` controls lookup/registration normalization and defaults to
`false`. Normalization makes representation differences such as JSON property
order and qualified names consistent for registry schema identity; it does not
change record-field order, encoded data or compatibility rules. See Confluent's
[schema normalization documentation](https://docs.confluent.io/platform/7.9/schema-registry/fundamentals/serdes-develop/index.html#schema-normalization).

```scala
val settings = SerializerSettings(
  autoRegisterSchemas = false,
  normalizeSchemas = true,
  subjectNameStrategy = SubjectNameStrategy.TopicName
)
val serializer = RegistrySerializer.forValue(Trade.codec, registry, settings)
```

Readers use `DeserializerSettings` for cache capacity and optional native
`decodeLimits`. Every `DecodeLimits` field defaults to `None`; `Some(n)` opts into
that ceiling, and `Some(0)` is a zero ceiling rather than an off switch.
They fetch the writer schema by the ID in the message and resolve it into the
generated reader model. Readers do not register or normalize schemas.

### Reader lookup policies

`DeserializerSettings.schemaLookup` controls the subject/context hint supplied
when looking up a numeric schema ID. The default, `SchemaLookup.ById`, preserves
lookup through `client.getSchemaById(id)`. The other policies use
`client.getSchemaBySubjectAndId(hint, id)`:

| Policy | Hint for a value on the `orders` topic |
| --- | --- |
| `SchemaLookup.ById` (default) | No hint; use the client's namespace |
| `SchemaLookup.TopicName()` | `orders-value`; a key reader uses `orders-key` |
| `SchemaLookup.TopicName(Some("sales"))` | `:.sales:orders-value`; a key reader uses `:.sales:orders-key` |
| `SchemaLookup.Subject(":.sales:chosen-subject")` | The explicit subject, unchanged |
| `SchemaLookup.Context("sales")` | `:.sales:` without a subject name |

For example, a value reader for a named context can use:

```scala
import avro2s.wire.registry.{DeserializerSettings, SchemaLookup}

val readerSettings = DeserializerSettings(
  schemaLookup = SchemaLookup.TopicName(Some("sales"))
)
val deserializer = RegistryDeserializer.forValue(Trade.codec, registry, readerSettings)
```

Context names `sales`, `.sales` and `:.sales:` normalize to the same hint;
`.` selects the default context. TopicName lookup requires a nonempty topic for
non-null data. Fixed Subject and Context lookups do not need a topic. Invalid or
empty configured hints fail when constructing `DeserializerSettings`.

Confluent treats the subject as a lookup hint for finding the schema's context,
not as proof that the ID is registered under that subject. These policies do not
enforce subject membership; lookup and authorization remain the registry's
responsibility. See Confluent's
[schema-by-ID API explanation](https://docs.confluent.io/cloud/current/sr/sr-rest-apis.html#get-schema-by-id).
Reader lookup settings do not change writer registration subjects. Confluent
strategy classes such as `context.name.strategy` remain unsupported.

Within a selected lookup scope, registry IDs identify the cached reader plans.
Avro parsing-canonical fingerprints omit data such as defaults and aliases that
affect Wire resolution, so they are not substitutes for schema JSON or registry
IDs. When a writer schema is fetched, `AvroSchema.rawSchema().toString` preserves
resolved references, defaults and aliases for Wire's resolver. Schema evolution
continues to follow Wire's stricter supported logical-type and decoding policies;
the adapter does not change them.

## Frame, errors and limits

The supported frame is Confluent's classic payload prefix: one zero magic byte,
four big-endian schema-ID bytes, then the Avro datum. The ID is looked up with the
injected registry client; the writer schema is resolved against the generated
reader codec. Kafka tombstones (`null` values) pass through as `null` without a
frame.

The codec root must be a named Avro record, enum or fixed schema. Primitive roots
and unnamed unions are rejected. This also excludes Avro's special raw-bytes
payload case. Standard Avro logical types supported by the generated model work
through native codec handling and Wire's resolver.
RecordName and TopicRecordName subject strategies require a record root, matching
Confluent. Enum and fixed roots use TopicName. Schema ID zero is accepted for
imported schemas; negative IDs are rejected.

`RegistryConnection` rejects properties for unsupported Confluent modes, including
schema GUIDs in Kafka headers, latest-schema selection, fixed schema IDs or GUIDs,
Confluent subject/context strategy classes and rule executors. Configure numeric
reader lookup through `DeserializerSettings.schemaLookup`. Inactive selector defaults
(`use.latest.version = false`, `use.schema.id = -1`, `use.schema.guid = null`) are
accepted for migration and omitted from the underlying client properties.
Descriptive registry metadata and empty rule sets are accepted. Nonempty
migration, domain and encoding rule sets are unsupported: writers inspect the
registration/lookup response before caching its ID or emitting bytes, and readers
check fetched writer schemas before compiling a plan. Neither adapter executes
rules. These checks happen on cache loads; adapters do not poll for administrative
changes to an already-cached schema.
Only TopicName, RecordName and TopicRecordName writer subject naming is supported.
Confluent's associated-subject options (`subject.name.strategy.*`) are rejected
rather than silently ignored; Wire does not perform topic-association lookups.
Readers can select a subject or fixed context through `SchemaLookup`; arbitrary
per-topic context strategy classes are unsupported. The module does not claim the
full behavior of Confluent's Avro SerDes or compatibility across other vendors'
registry implementations.

Malformed/truncated frames, unsupported magic bytes, unknown IDs, incompatible
writer data, unsupported logical types and decode-limit violations fail with a
Kafka `SerializationException` whose cause describes the underlying failure.
Registry HTTP failures retain Confluent's Kafka exception categories: 401 is
`AuthenticationException`, 403 is `AuthorizationException`, 429 is
`ThrottlingQuotaExceededException`, 408/500/503/504 are `TimeoutException`, and
502 is `DisconnectException`. Other registry HTTP errors remain
`SerializationException`. Interrupted I/O and socket timeouts are reported as
`TimeoutException`; an I/O timeout does not mark the calling thread interrupted.
Applications can therefore distinguish registry availability/access failures
from invalid Avro data when applying their own retry or rejection policies.
When a registry operation propagates `InterruptedException` to Wire, Wire restores
the interrupted thread's flag before failing. A shared load failure does not
interrupt its other callers. Waiting for a shared load is not promptly
cancellable; a waiter's own interruption is preserved. Cancellation and retries
inside the supplied client remain that client's responsibility; Wire cannot
restore an interruption swallowed by that client.
Configured native decode limits apply to the framed Avro datum; the five framing
bytes are outside `maxInputBytes`. All five ceilings are disabled by default.
Byte sizes and collection counts are independent of nesting depth. Mandatory
checks for malformed encodings, available input, arithmetic overflow and JVM
representability remain enabled regardless of these options. The [complete
configuration example](schema-registry-examples.md) explicitly supplies every
setting and explains these distinctions.

## Migrating from the initial API

Replace public constructor calls with `forKey` or `forValue`, and remove manual
`configure` calls. The Kafka interfaces still expose `configure`, but Wire
inherits their no-op implementation; it does not change an adapter's role,
settings or connection.

Replace `RegistrySettings` with `SerializerSettings` for writers and
`DeserializerSettings` for readers. Replace `fromConfig` with a `forKey` or
`forValue` call accepting `RegistryConnection`. Move registration and
normalization choices out of property maps into `SerializerSettings`; put
registry endpoints in `RegistryConnection.urls` and connection/authentication/TLS
properties in `RegistryConnection.properties`.

## Verification

The ordinary `sbt test` command does not need Docker. Run the isolated broker and
registry interoperability tests explicitly with:

```sh
scripts/test-schema-registry.sh
```

The script starts Kafka and Schema Registry 8.1.5 in an isolated Compose project,
waits for the registry at `http://localhost:18081`, runs
`sbt 'registryIntegration/test'`, then removes only the containers, network and
anonymous volumes it created. It prints container logs on failure. Override the
published ports if needed:

```sh
AVRO2S_WIRE_KAFKA_PORT=29092 AVRO2S_WIRE_REGISTRY_PORT=28081 scripts/test-schema-registry.sh
```
