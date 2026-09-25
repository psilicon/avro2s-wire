# Complete Schema Registry configuration

The [complete source](examples/schema-registry/FullConfiguration.scala) below
imports and constructs every Wire setting, then uses those settings for both Avro
keys and values in a Kafka producer and consumer. It sends one trade and waits up
to 30 seconds to read that trade back.

## Prerequisites and generated models

The example assumes Kafka is available at `localhost:19092`, Schema Registry at
`http://localhost:18081`, and the `trades` topic already exists. Before running it,
register these schemas through your normal schema deployment process:

| Schema source | Generated Scala model | Registry subject |
| --- | --- | --- |
| [TradeKey.avsc](examples/schema-registry/schemas/TradeKey.avsc) | `example.trading.TradeKey(id: Long)` | `trades-key` |
| [Trade.avsc](examples/schema-registry/schemas/Trade.avsc) | `example.trading.Trade(id: Long, symbol: String, price: Double)` | `trades-value` |

The program uses `autoRegisterSchemas = false`, which is also the default. It
looks up the exact generated schema under each subject and fails if it is absent.
It does not create the topic, register schemas or change compatibility policies.
Use a topic containing compatible trade keys and values; the consumer decodes
existing records while searching for the trade it just sent.

The key schema is an ordinary Avro record, defined independently from the value:

```json
{
  "type": "record",
  "name": "TradeKey",
  "namespace": "example.trading",
  "fields": [{"name": "id", "type": "long"}]
}
```

There is no key-specific schema-generation flag. `forKey` selects the key's
registry subject; it does not extract a key from a value. Named record, enum and
fixed roots are supported. Primitive roots such as `"long"` are currently
unsupported by the registry adapter.

Generate the Scala models, compile them with the example and run it from the
repository root:

```sh
sbt \
  'compiler/run docs/examples/schema-registry/schemas target/schema-registry-example-generated' \
  'set schemaRegistry / Compile / unmanagedSourceDirectories ++= Seq(file("docs/examples/schema-registry"), file("target/schema-registry-example-generated"))' \
  'schemaRegistry/runMain example.registry.FullConfiguration'
```

These source-directory changes apply only to that sbt session. In a consuming
application, generate the models into your application sources and add the
[registry module dependency](schema-registry.md).

## One complete program

All fields of `SerializerSettings`, `DeserializerSettings`, `DecodeLimits` and
`RegistryConnection` are explicit. The Kafka configuration maps contain the
settings needed for this example; Kafka's remaining settings use Kafka defaults.

```scala
package example.registry

import avro2s.wire.registry.{
  DeserializerSettings, RegistryConnection, RegistryDeserializer,
  RegistrySerializer, SerializerSettings, SubjectNameStrategy
}
import avro2s.wire.runtime.DecodeLimits
import example.trading.{Trade, TradeKey}
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Requires Kafka, Schema Registry, a trades topic and both schemas registered.
  * See docs/schema-registry-examples.md for generation and run commands.
  */
object FullConfiguration:
  def main(args: Array[String]): Unit =
    val topic = "trades"

    // Every SerializerSettings parameter is specified here.
    val writerSettings = SerializerSettings(
      autoRegisterSchemas = false,
      normalizeSchemas = false,
      subjectNameStrategy = SubjectNameStrategy.TopicName,
      cacheCapacity = 256
    )

    // Every DeserializerSettings and DecodeLimits parameter is specified here.
    // All resource ceilings are optional and disabled by default.
    val readerSettings = DeserializerSettings(
      cacheCapacity = 256,
      decodeLimits = DecodeLimits(
        maxInputBytes = None,
        maxStringBytes = None,
        maxBytesLength = None,
        maxCollectionItems = None,
        maxNestingDepth = None
      )
    )

    // Every RegistryConnection parameter is specified here.
    // Authentication and TLS properties would also go in this map.
    val connection = RegistryConnection(
      urls = List("http://localhost:18081"),
      cacheCapacity = 256,
      properties = Map[String, AnyRef](
        "http.connect.timeout.ms" -> "5000",
        "http.read.timeout.ms" -> "10000"
      )
    )

    val producerConfig = Map[String, AnyRef](
      "bootstrap.servers" -> "localhost:19092",
      "client.id" -> "trade-example-producer",
      "acks" -> "all",
      "max.block.ms" -> "10000",
      "delivery.timeout.ms" -> "30000",
      "request.timeout.ms" -> "10000"
    ).asJava

    val consumerConfig = Map[String, AnyRef](
      "bootstrap.servers" -> "localhost:19092",
      "client.id" -> "trade-example-consumer",
      "group.id" -> s"trade-example-${UUID.randomUUID()}",
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> "false"
    ).asJava

    Using.Manager { use =>
      val keyWriter = use(RegistrySerializer.forKey(TradeKey.codec, connection, writerSettings))
      val valueWriter = use(RegistrySerializer.forValue(Trade.codec, connection, writerSettings))
      val keyReader = use(RegistryDeserializer.forKey(TradeKey.codec, connection, readerSettings))
      val valueReader = use(RegistryDeserializer.forValue(Trade.codec, connection, readerSettings))

      val producer = use(new KafkaProducer[TradeKey, Trade](producerConfig, keyWriter, valueWriter))
      val consumer = use(new KafkaConsumer[TradeKey, Trade](consumerConfig, keyReader, valueReader))
      consumer.subscribe(java.util.List.of(topic))

      val id = UUID.randomUUID().getMostSignificantBits
      val key = TradeKey(id)
      val value = Trade(id, "ABC", 12.5)
      producer.send(new ProducerRecord(topic, key, value)).get(30, TimeUnit.SECONDS)

      val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos
      val received = Iterator
        .continually(consumer.poll(Duration.ofMillis(500)))
        .takeWhile(_ => System.nanoTime() < deadline)
        .flatMap(_.asScala)
        .find(record => record.key() == key && record.value() == value)
        .getOrElse(throw new IllegalStateException("Sent trade was not received within 30 seconds"))

      println(s"${received.key()} -> ${received.value()}")
    }.get
```

The settings are defined in these source files:

| Settings type | Source | Purpose |
| --- | --- | --- |
| `SerializerSettings` | [RegistrySettings.scala](../schema-registry/src/main/scala/avro2s/wire/registry/RegistrySettings.scala) | Registration, normalization, subject naming and the writer's schema-ID cache |
| `DeserializerSettings` | [RegistrySettings.scala](../schema-registry/src/main/scala/avro2s/wire/registry/RegistrySettings.scala) | Reader resolution-plan cache and decoding limits |
| `DecodeLimits` | [AvroIO.scala](../runtime/src/main/scala/avro2s/wire/runtime/AvroIO.scala) | Optional per-datum byte, collection-item and nesting ceilings; all default to `None` |
| `RegistryConnection` | [RegistryConnection.scala](../schema-registry/src/main/scala/avro2s/wire/registry/RegistryConnection.scala) | Registry endpoints, registry-client cache and network/authentication/TLS properties |

The two cache capacities control different caches: adapter settings bound the
writer's subject-to-ID cache or reader's schema-ID-to-resolution-plan cache;
`RegistryConnection.cacheCapacity` configures the underlying Confluent client.
Kafka broker and consumer-group settings remain in Kafka's maps.

## Client cleanup

Each of the four factories above receives `connection` and creates its own
registry client. Sharing an immutable connection value shares settings, not an
HTTP client. Closing an adapter closes the client it created. This is what the
implementation means by an **owned client**.

At the end of the `Using.Manager` block, including when the block throws,
`Using.Manager` closes the consumer and producer, which close their adapters and
the registry clients those adapters created. It
also registers the adapters directly so they are cleaned up if a subsequent
constructor fails; repeated adapter closes are harmless. Its final `.get`
propagates errors.

To share one existing `SchemaRegistryClient`, pass that instance in place of
`connection` to all four factories. In that case, the application creates and
closes the client after its Kafka clients are finished; closing an adapter does
not close the shared client. No ownership flag is part of the public API.

## Normalization and decoding

`normalizeSchemas` controls registry schema identity during registration or
lookup. It asks Schema Registry to normalize representational differences such
as the order of JSON object properties and qualified versus unqualified names.
It does not reorder Avro record fields, alter the encoded datum, or select a
compatible/latest schema. It defaults to `false`. See Confluent's
[schema normalization documentation](https://docs.confluent.io/platform/7.9/schema-registry/fundamentals/serdes-develop/index.html#schema-normalization).

Every `DecodeLimits` field is optional and defaults to `None`, as the complete
example shows. `None` disables that application policy. To opt into an individual
ceiling, use `Some(n)`; for example, `DecodeLimits(maxStringBytes = Some(1024))`
allows strings up to 1,024 encoded UTF-8 bytes without imposing the other four
ceilings. `Some(0)` permits zero and rejects positive values; it does not disable
the check.

Byte lengths and collection counts are separate from structural nesting.
`maxNestingDepth` counts nested records, arrays and maps, not string characters or
the number of items in one collection. Making a flat string or collection longer
does not itself make the datum deeper. Disabling the nesting ceiling does not
change how codecs traverse nested values.

Generated codecs and resolving readers currently use recursive calls for nested
records and can overflow the JVM stack on sufficiently deep data. Recursive
writes can also overflow; the decoder's depth policy never applied to writes.
This is an implementation limitation to fix with stack-safe traversal, separate
from optional size policies. Flat collection loops and string validation do not
grow the call stack with their length.

The resolver also has separate hardcoded 256-level checks in schema parsing and
default compilation. Those are existing implementation safeguards, not Avro
format requirements, and making `DecodeLimits` optional does not remove them.

Malformed encodings, input bounds and length conversion remain checked
regardless of `DecodeLimits`. For example,
Avro encodes string and byte lengths as a `long`, but the native input is an
`Array[Byte]` with `Int` indices. A length must fit that representation and the
remaining input before it can be converted to an `Int`; those are mandatory
checks, not configurable size policies.

This does not narrow ordinary Avro `long` field values. For example, the length
`4294967296L` would become `0` after an unchecked `.toInt`, which would decode the
wrong value and lose the correct position in the input. JVM arrays use `Int`
lengths and indices; choosing an `Array[Byte]` input therefore imposes a real
representation boundary on this API, not on Avro itself. Supporting larger
payloads requires a streaming or chunked input, and larger byte values require
a corresponding model representation. The native byte-array API does not
silently truncate such lengths.

The former default ceilings did not establish the largest supported values.
Tests now cover a string above 16 MiB, bytes and input above 64 MiB, and a generated
array with 1,000,001 elements using the default settings. Those tests demonstrate
that the previous policy thresholds were not implementation boundaries; they do
not imply unlimited memory or solve recursive traversal.

Avro null values consume no datum bytes. An array with schema `{"type":"array",
"items":"null"}` can therefore encode a large item count in a short block
header. The schema tells the decoder that its elements are null; this is valid
data and is accepted by default. An explicitly configured `maxCollectionItems`
would bound how many elements the decoder processes, including such arrays.
Here, a small encoded payload can describe many elements: it does not mean the
logical array is small, nor that the schema's deliberate choice is invalid.

See the [runtime source](../runtime/src/main/scala/avro2s/wire/runtime/AvroIO.scala)
and [registry behavior](schema-registry.md) for the API and current limitations.
