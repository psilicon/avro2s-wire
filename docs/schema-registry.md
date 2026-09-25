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

Each adapter has a bounded access-order cache. The serializer caches schema IDs
by subject; the deserializer caches compiled writer-to-reader plans by schema ID.
`SerializerSettings.cacheCapacity` and `DeserializerSettings.cacheCapacity` control
their respective adapter caches and default to 1024. The underlying
`CachedSchemaRegistryClient` has its own separately bounded cache, controlled by
`RegistryConnection.cacheCapacity` when the adapter creates it. Each adapter
synchronizes cache access, serialization and deserialization. Its role and
settings are immutable.

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
generated reader model. They do not select subjects or register schemas, so they
have no registration, normalization or subject-strategy settings.

Registry IDs are the identity used by the classic frame and deserializer cache.
Avro parsing-canonical fingerprints omit data such as defaults and aliases that
affects Wire resolution, so they are not substitutes for schema JSON or registry
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

`RegistryConnection` rejects properties for unsupported Confluent modes, including
schema GUIDs in Kafka headers, latest-schema selection, fixed schema IDs or GUIDs,
custom subject/context naming and rule executors. Inactive selector defaults
(`use.latest.version = false`, `use.schema.id = -1`, `use.schema.guid = null`) are
accepted for migration and omitted from the underlying client properties.
Use plain Avro subjects without
registry-attached rules: the serializer does not execute those rules, and the
deserializer rejects writer schemas carrying registry metadata or rules.
Only TopicName, RecordName and TopicRecordName subject naming is supported.
Context is determined by the registry URL/client; the module does
not implement per-topic context-name strategies or cross-context lookup. It does
not claim the full behavior of Confluent's Avro SerDes or compatibility across
other vendors' registry implementations.

Malformed/truncated frames, unsupported magic bytes, unknown IDs, incompatible
writer data, unsupported logical types and decode-limit violations fail with a
Kafka `SerializationException` whose cause describes the underlying failure.
Interrupted registry work restores the thread's interrupt flag before failing.
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
