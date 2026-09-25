package avro2s.wire.registry

import avro2s.wire.fixtures.{Status, Trade}
import io.confluent.kafka.schemaregistry.avro.AvroSchema
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
import scala.jdk.CollectionConverters.*

/** Broker-level interoperability with immutable key/value adapters passed directly to Kafka. */
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

  private def receive[K, V](consumer: KafkaConsumer[K, V], topic: String): Vector[(K, V)] =
    consumer.subscribe(List(topic).asJava)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
    var records = Vector.empty[(K, V)]
    while records.size < 2 && System.nanoTime() < deadline do
      records ++= consumer.poll(Duration.ofMillis(500)).iterator().asScala.map(record =>
        record.key() -> record.value()).toVector
    assertEquals(records.size, 2, s"Expected a datum followed by a tombstone on $topic")
    records

  test("real Kafka carries preregistered Wire key/value schemas and Confluent messages in both directions") {
    val props = properties()
    val adapterConfig = props.stringPropertyNames().asScala.map(key => key -> props.getProperty(key)).toMap.asJava
    val connection = RegistryConnection(List(props.getProperty("schema.registry.url")), cacheCapacity = 32)
    val registry = new CachedSchemaRegistryClient(props.getProperty("schema.registry.url"), 32)
    val admin = AdminClient.create(props)
    val suffix = UUID.randomUUID().toString
    val nativeTopic = s"wire-native-$suffix"
    val javaTopic = s"wire-java-$suffix"
    val topics = List(nativeTopic, javaTopic)
    val expectedKey = Status.OPEN
    val expected = Trade(42L, "café-🚀", 12.5, Vector(3, 8))
    try
      admin.createTopics(topics.map(new NewTopic(_, 1, 1.toShort)).asJava).all().get(20, TimeUnit.SECONDS)
      registry.register(s"$nativeTopic-key", new AvroSchema(Status.codec.schemaJson))
      registry.register(s"$nativeTopic-value", new AvroSchema(Trade.codec.schemaJson))
      val nativeProducer = new KafkaProducer[Status, Trade](props,
        RegistrySerializer.forKey(Status.codec, connection),
        RegistrySerializer.forValue(Trade.codec, connection))
      try
        nativeProducer.send(new ProducerRecord(nativeTopic, expectedKey, expected)).get(20, TimeUnit.SECONDS)
        nativeProducer.send(new ProducerRecord[Status, Trade](nativeTopic, expectedKey, null)).get(20, TimeUnit.SECONDS)
      finally nativeProducer.close(Duration.ofSeconds(5))

      val javaKeyDeserializer = new KafkaAvroDeserializer()
      val javaDeserializer = new KafkaAvroDeserializer()
      javaKeyDeserializer.configure(adapterConfig, true)
      javaDeserializer.configure(adapterConfig, false)
      val javaConsumer = new KafkaConsumer[AnyRef, AnyRef](props, javaKeyDeserializer, javaDeserializer)
      try
        val records = receive(javaConsumer, nativeTopic)
        assertEquals(records.map(_._1.toString), Vector("OPEN", "OPEN"))
        val key = records.head._1.asInstanceOf[GenericData.EnumSymbol]
        assertEquals(key.getSchema, new Schema.Parser().parse(Status.codec.schemaJson))
        val value = records.head._2.asInstanceOf[GenericRecord]
        assertEquals(value.get("id"), Long.box(expected.id))
        assertEquals(value.get("symbol").toString, expected.symbol)
        assertEquals(value.get("price"), Double.box(expected.price))
        assertEquals(value.get("quantities").asInstanceOf[java.util.List[Integer]].asScala.map(_.intValue).toVector,
          expected.quantities)
        assertEquals(records(1)._2, null)
      finally javaConsumer.close(Duration.ofSeconds(5))

      val keyDatum = new GenericData.EnumSymbol(new Schema.Parser().parse(Status.codec.schemaJson), "OPEN")
      val datum = new GenericData.Record(new Schema.Parser().parse(Trade.codec.schemaJson))
      datum.put("id", expected.id)
      datum.put("symbol", expected.symbol)
      datum.put("price", expected.price)
      datum.put("quantities", expected.quantities.map(Int.box).asJava)
      val javaKeySerializer = new KafkaAvroSerializer()
      val javaSerializer = new KafkaAvroSerializer()
      javaKeySerializer.configure(adapterConfig, true)
      javaSerializer.configure(adapterConfig, false)
      val javaProducer = new KafkaProducer[AnyRef, AnyRef](props, javaKeySerializer, javaSerializer)
      try
        javaProducer.send(new ProducerRecord[AnyRef, AnyRef](javaTopic, keyDatum, datum)).get(20, TimeUnit.SECONDS)
        javaProducer.send(new ProducerRecord[AnyRef, AnyRef](javaTopic, keyDatum, null)).get(20, TimeUnit.SECONDS)
      finally javaProducer.close(Duration.ofSeconds(5))
      val nativeConsumer = new KafkaConsumer[Status, Trade](props,
        RegistryDeserializer.forKey(Status.codec, connection),
        RegistryDeserializer.forValue(Trade.codec, connection))
      try
        val records = receive(nativeConsumer, javaTopic)
        assertEquals(records.head, expectedKey -> expected)
        assertEquals(records(1)._1, expectedKey)
        assertEquals(records(1)._2, null)
      finally nativeConsumer.close(Duration.ofSeconds(5))

      topics.foreach { topic =>
        val keySchema = registry.getLatestSchemaMetadata(s"$topic-key")
        val valueSchema = registry.getLatestSchemaMetadata(s"$topic-value")
        assertEquals(new Schema.Parser().parse(keySchema.getSchema), new Schema.Parser().parse(Status.codec.schemaJson))
        assertEquals(new Schema.Parser().parse(valueSchema.getSchema), new Schema.Parser().parse(Trade.codec.schemaJson))
        assert(keySchema.getId != valueSchema.getId)
      }
    finally
      try
        admin.deleteTopics(topics.asJava).all().get(15, TimeUnit.SECONDS)
        for topic <- topics; role <- List("key", "value") do
          try registry.deleteSubject(s"$topic-$role")
          catch case e: RestClientException if e.getStatus == 404 => ()
      finally
        admin.close(Duration.ofSeconds(5))
        registry.close()
  }
