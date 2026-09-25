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
