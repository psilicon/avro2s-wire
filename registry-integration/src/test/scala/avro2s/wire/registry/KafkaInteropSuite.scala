package avro2s.wire.registry

import avro2s.wire.fixtures.Trade
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroSerializer}
import java.time.Duration
import java.util.{Properties, UUID}
import java.util.concurrent.TimeUnit
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import scala.jdk.CollectionConverters.*

/** Broker-level interoperability with explicitly configured adapter instances. */
final class KafkaInteropSuite extends munit.FunSuite:
  private def properties(): Properties =
    val props = new Properties()
    props.put("bootstrap.servers", sys.env.getOrElse("AVRO2S_WIRE_KAFKA_BOOTSTRAP",
      sys.error("Run scripts/test-schema-registry.sh to start the isolated integration services")))
    props.put("schema.registry.url", sys.env.getOrElse("AVRO2S_WIRE_REGISTRY_URL",
      sys.error("AVRO2S_WIRE_REGISTRY_URL is required")))
    props.put("group.id", s"wire-integration-${UUID.randomUUID()}")
    props.put("auto.offset.reset", "earliest")
    props.put("enable.auto.commit", "false")
    props.put("max.block.ms", "15000")
    props.put("request.timeout.ms", "10000")
    props.put("delivery.timeout.ms", "15000")
    props

  private def receive[A](consumer: KafkaConsumer[String, A], topic: String): Vector[A] =
    consumer.subscribe(List(topic).asJava)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
    var values = Vector.empty[A]
    while values.size < 2 && System.nanoTime() < deadline do
      values ++= consumer.poll(Duration.ofMillis(500)).iterator().asScala.map(_.value()).toVector
    assertEquals(values.size, 2, s"Expected a datum followed by a tombstone on $topic")
    values

  test("real Kafka carries Wire and Confluent messages and tombstones in both directions") {
    val props = properties()
    val adapterConfig = props.stringPropertyNames().asScala.map(key => key -> props.getProperty(key)).toMap.asJava
    val registry = new CachedSchemaRegistryClient(props.getProperty("schema.registry.url"), 32)
    val admin = AdminClient.create(props)
    val suffix = UUID.randomUUID().toString
    val nativeTopic = s"wire-native-$suffix"
    val javaTopic = s"wire-java-$suffix"
    val topics = List(nativeTopic, javaTopic)
    val expected = Trade(42L, "café-🚀", 12.5, Vector(3, 8))
    try
      admin.createTopics(topics.map(new NewTopic(_, 1, 1.toShort)).asJava).all().get(20, TimeUnit.SECONDS)
      val nativeSerializer = new RegistrySerializer(Trade.codec, registry)
      nativeSerializer.configure(adapterConfig, false)
      val nativeProducer = new KafkaProducer[String, Trade](props, new StringSerializer(), nativeSerializer)
      try
        nativeProducer.send(new ProducerRecord(nativeTopic, "key", expected)).get(20, TimeUnit.SECONDS)
        nativeProducer.send(new ProducerRecord[String, Trade](nativeTopic, "key", null)).get(20, TimeUnit.SECONDS)
      finally nativeProducer.close(Duration.ofSeconds(5))

      val javaDeserializer = new KafkaAvroDeserializer()
      javaDeserializer.configure(adapterConfig, false)
      val javaConsumer = new KafkaConsumer[String, AnyRef](props, new StringDeserializer(), javaDeserializer)
      try
        val values = receive(javaConsumer, nativeTopic)
        val value = values.head.asInstanceOf[GenericRecord]
        assertEquals(value.get("id"), Long.box(expected.id))
        assertEquals(value.get("symbol").toString, expected.symbol)
        assertEquals(value.get("price"), Double.box(expected.price))
        assertEquals(value.get("quantities").asInstanceOf[java.util.List[Integer]].asScala.map(_.intValue).toVector,
          expected.quantities)
        assertEquals(values(1), null)
      finally javaConsumer.close(Duration.ofSeconds(5))

      val datum = new GenericData.Record(new Schema.Parser().parse(Trade.codec.schemaJson))
      datum.put("id", expected.id)
      datum.put("symbol", expected.symbol)
      datum.put("price", expected.price)
      datum.put("quantities", expected.quantities.map(Int.box).asJava)
      val javaSerializer = new KafkaAvroSerializer()
      javaSerializer.configure(adapterConfig, false)
      val javaProducer = new KafkaProducer[String, AnyRef](props, new StringSerializer(), javaSerializer)
      try
        javaProducer.send(new ProducerRecord[String, AnyRef](javaTopic, "key", datum)).get(20, TimeUnit.SECONDS)
        javaProducer.send(new ProducerRecord[String, AnyRef](javaTopic, "key", null)).get(20, TimeUnit.SECONDS)
      finally javaProducer.close(Duration.ofSeconds(5))
      val nativeDeserializer = new RegistryDeserializer(Trade.codec, registry)
      nativeDeserializer.configure(adapterConfig, false)
      val nativeConsumer = new KafkaConsumer[String, Trade](props, new StringDeserializer(), nativeDeserializer)
      try
        val values = receive(nativeConsumer, javaTopic)
        assertEquals(values.head, expected)
        assertEquals(values(1), null)
      finally nativeConsumer.close(Duration.ofSeconds(5))
    finally
      try
        admin.deleteTopics(topics.asJava).all().get(15, TimeUnit.SECONDS)
        topics.foreach { topic =>
          try registry.deleteSubject(s"$topic-value")
          catch case e: RestClientException if e.getStatus == 404 => ()
        }
      finally
        admin.close(Duration.ofSeconds(5))
        registry.close()
  }
