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

Supply an adapter instance to Kafka's `KafkaProducer` or `KafkaConsumer`
constructor. The adapters are configured instances; the no-argument configured
class-name path cannot supply a generated codec and is unsupported.

This example injects a shared Confluent client. The client remains owned by the
application, while Kafka calls `close` on the serializer when the producer closes.
Closing an injected adapter does not close that client.

```scala
import avro2s.wire.fixtures.Trade
import avro2s.wire.registry.{RegistrySerializer, RegistrySettings, SubjectNameStrategy}
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import java.util.Properties
import scala.jdk.CollectionConverters.*
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

val registryUrl = "http://localhost:18081"
val registry = new CachedSchemaRegistryClient(registryUrl, 256)
val settings = RegistrySettings(
  subjectNameStrategy = SubjectNameStrategy.TopicName,
  autoRegisterSchemas = true
)
val serializer = new RegistrySerializer(Trade.codec, registry, settings)

val kafkaConfig = new Properties()
kafkaConfig.put("bootstrap.servers", "localhost:19092")
kafkaConfig.put("schema.registry.url", registryUrl)
serializer.configure(kafkaConfig.stringPropertyNames().asScala
  .map(key => key -> kafkaConfig.getProperty(key)).toMap.asJava, false)
val producer = new KafkaProducer[String, Trade](
  kafkaConfig, new StringSerializer(), serializer
)
try
  producer.send(new ProducerRecord("trades", "key", Trade(42L, "ABC", 12.5, Vector(1, 2))))
finally
  producer.close()
  registry.close()
```

For example, construct a consumer with the same injected client and settings:

```scala
import avro2s.wire.fixtures.Trade
import avro2s.wire.registry.{RegistryDeserializer, RegistrySettings}
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import java.util.Properties
import scala.jdk.CollectionConverters.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer

val registry = new CachedSchemaRegistryClient("http://localhost:18081", 256)
val deserializer = new RegistryDeserializer(Trade.codec, registry, RegistrySettings())
val consumerConfig = new Properties()
consumerConfig.put("bootstrap.servers", "localhost:19092")
consumerConfig.put("schema.registry.url", "http://localhost:18081")
consumerConfig.put("group.id", "trade-reader")
deserializer.configure(consumerConfig.stringPropertyNames().asScala
  .map(key => key -> consumerConfig.getProperty(key)).toMap.asJava, false)
val consumer = new KafkaConsumer[String, Trade](
  consumerConfig, new StringDeserializer(), deserializer
)
try
  consumer.subscribe(java.util.List.of("trades"))
  val records = consumer.poll(java.time.Duration.ofSeconds(1))
finally
  consumer.close()
  registry.close()
```

When you inject a client, pass authentication and TLS properties to that client
when constructing it. Kafka properties do not reconfigure an injected client.
For example:

```scala
val registryConfig = new java.util.HashMap[String, Object]()
registryConfig.put("basic.auth.credentials.source", "USER_INFO")
registryConfig.put("basic.auth.user.info", "<api-key>:<api-secret>")
val registry = new CachedSchemaRegistryClient(registryUrl, 256, registryConfig)
```

Alternatively, pass those settings in the `config` map to
`RegistrySerializer.fromConfig` or `RegistryDeserializer.fromConfig`.

The client accepts standard Confluent Schema Registry settings, including TLS
settings. See Confluent's [Schema Registry client configuration reference](https://docs.confluent.io/platform/current/schema-registry/sr-client-configs.html).

## Client creation and ownership

Pass an existing `SchemaRegistryClient` to the adapter when the application owns
client lifecycle or shares one client across serializers and deserializers:

```scala
val registry = new CachedSchemaRegistryClient(registryUrl, 256)
val serializer = new RegistrySerializer(Trade.codec, registry, settings)
val deserializer = new RegistryDeserializer(Trade.codec, registry, settings)
```

The supplied client is caller-owned. Closing either adapter does not close that
client; close the shared client yourself after all Kafka clients and adapters
have closed.

`RegistrySerializer.fromConfig` and `RegistryDeserializer.fromConfig` create and
own a cached registry client. They accept registry URLs, the Confluent client
capacity, a map of Confluent client properties, and `RegistrySettings`. Close the
adapter to close its owned registry client. The URL list sets the client's
registry endpoints; the properties map is for authentication, TLS and other
client properties.

For example, the adapter can own an authenticated client:

```scala
val serializer = RegistrySerializer.fromConfig(
  Trade.codec,
  List("https://registry.example"),
  config = Map[String, AnyRef](
    "basic.auth.credentials.source" -> "USER_INFO",
    "basic.auth.user.info" -> "<api-key>:<api-secret>"
  ),
  settings = settings
)
```

In this form, closing the producer closes the serializer and its registry client.

Each adapter has a bounded access-order cache. The serializer caches schema IDs
by subject; the deserializer caches compiled writer-to-reader plans by schema ID.
`RegistrySettings.cacheCapacity` controls each adapter cache and defaults to 1024.
The underlying `CachedSchemaRegistryClient` has its own separately bounded cache.
Each adapter synchronizes configuration, cache access, serialization and
deserialization. Configure it before first use; configuration after a message has
been handled fails.

## Subjects and registration

The supported subject strategies follow Confluent's standard naming forms:

| Strategy | Value subject | Key subject |
| --- | --- | --- |
| `TopicName` (default) | `<topic>-value` | `<topic>-key` |
| `RecordName` | Avro record fullname | Avro record fullname |
| `TopicRecordName` | `<topic>-<record-fullname>` | `<topic>-<record-fullname>` |

Kafka does not call `configure` on serializer or deserializer instances supplied
to its constructors. Call it explicitly before constructing the Kafka client:
`configure(config, false)` for values and `configure(config, true)` for keys.
This also validates registry-related properties against the typed settings.
An adapter used without configuration defaults to values. Topic-based
strategies require a non-empty topic. Record name strategies derive the name from
the generated Avro schema.

`RegistrySettings.autoRegisterSchemas` defaults to `true`: the serializer registers
the codec's schema under the selected subject and lets Schema Registry apply that
subject's compatibility policy. With `false`, it only looks up the ID of that
exact schema under the subject. Pre-register the same schema under that subject
before sending data; a missing match is an error. The setting controls Wire's
serializer and must agree with `auto.register.schemas` in the map passed to
`configure` or `fromConfig` when present.
`normalizeSchemas` similarly controls lookup/registration normalization and must
agree with `normalize.schemas` when present.

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

The adapters reject configuration for unsupported Confluent modes, including
schema GUIDs in Kafka headers, latest-schema selection, fixed schema IDs, custom
subject/context naming and rule executors. Use plain Avro subjects without
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
Native decode limits still apply to the framed Avro datum; the five framing bytes
are outside the datum limit.

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
