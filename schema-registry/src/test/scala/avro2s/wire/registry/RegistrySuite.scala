package avro2s.wire.registry

import avro2s.wire.fixtures.{Status, Trade}
import avro2s.wire.fixtures.unions.UnionFixed
import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput, Bytes, DecodeLimits}
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.{Metadata, RuleSet}
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroSerializer}
import java.io.IOException
import java.util.{HashMap, Map as JMap}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import scala.jdk.CollectionConverters.*

final class RegistrySuite extends munit.FunSuite:
  private val trade = Trade(123L, "ABC", 42.5, Vector(10, 20))
  private val schema = new Schema.Parser().parse(Trade.schemaJson)

  private def genericTrade: GenericData.Record =
    val record = new GenericData.Record(schema)
    record.put("id", trade.id)
    record.put("symbol", trade.symbol)
    record.put("price", trade.price)
    record.put("quantities", trade.quantities.map(Int.box).asJava)
    record

  private def config: JMap[String, AnyRef] =
    val result = new HashMap[String, AnyRef]()
    result.put("schema.registry.url", "mock://wire")
    result

  private def id(bytes: Array[Byte]): Int =
    ((bytes(1) & 0xff) << 24) | ((bytes(2) & 0xff) << 16) |
      ((bytes(3) & 0xff) << 8) | (bytes(4) & 0xff)

  private def frame(id: Int, body: Array[Byte]): Array[Byte] =
    Array[Byte](0, (id >>> 24).toByte, (id >>> 16).toByte, (id >>> 8).toByte, id.toByte) ++ body

  private def failure(body: => Any): SerializationException =
    val error = intercept[SerializationException](body)
    assert(error.getCause != null)
    error

  test("native and Confluent serializers produce the same classic frame") {
    val client = new MockSchemaRegistryClient()
    val native = new RegistrySerializer(Trade.codec, client)
    val confluent = new KafkaAvroSerializer(client)
    confluent.configure(config, false)
    val ours = native.serialize("trades", trade)
    val theirs = confluent.serialize("trades", genericTrade)
    assertEquals(ours.toVector, theirs.toVector)
    assertEquals(ours.head, 0.toByte)
    assertEquals(ours.drop(5).toVector, Trade.codec.encode(trade).toVector)
    assertEquals(new RegistryDeserializer(Trade.codec, client).deserialize("trades", theirs), trade)
    val decoded = new KafkaAvroDeserializer(client)
    decoded.configure(config, false)
    val generic = decoded.deserialize("trades", ours).asInstanceOf[GenericData.Record]
    assertEquals(generic.get("id").asInstanceOf[Long], trade.id)
    assertEquals(generic.get("symbol").toString, trade.symbol)
    native.close()
    confluent.close()
    decoded.close()
  }

  test("named enum and fixed roots interoperate with Confluent") {
    val client = new MockSchemaRegistryClient()
    val enumNative = new RegistrySerializer(Status.codec, client)
    val enumConfluent = new KafkaAvroSerializer(client)
    enumConfluent.configure(config, false)
    val enumSchema = new Schema.Parser().parse(Status.schemaJson)
    val symbol = new GenericData.EnumSymbol(enumSchema, "CLOSED")
    val enumBytes = enumNative.serialize("status", Status.CLOSED)
    assertEquals(enumBytes.toVector, enumConfluent.serialize("status", symbol).toVector)
    assertEquals(new RegistryDeserializer(Status.codec, client).deserialize("status", enumBytes), Status.CLOSED)

    val fixedNative = new RegistrySerializer(UnionFixed.codec, client)
    val fixedSchema = new Schema.Parser().parse(UnionFixed.schemaJson)
    val fixedValue = UnionFixed(Bytes.fromArray(Array[Byte](1, 2)))
    val fixedGeneric = new GenericData.Fixed(fixedSchema, Array[Byte](1, 2))
    val fixedBytes = fixedNative.serialize("fixed", fixedValue)
    assertEquals(fixedBytes.toVector, enumConfluent.serialize("fixed", fixedGeneric).toVector)
    assertEquals(new RegistryDeserializer(UnionFixed.codec, client).deserialize("fixed", fixedBytes), fixedValue)
  }

  test("null tombstones avoid registry and framing") {
    val client = new CountingClient
    val serializer = new RegistrySerializer(Trade.codec, client)
    val deserializer = new RegistryDeserializer(Trade.codec, client)
    assertEquals(serializer.serialize("trades", null), null)
    assertEquals(deserializer.deserialize("trades", null), null)
    assertEquals(client.registerCalls, 0)
    assertEquals(client.fetchCalls, 0)
    serializer.close()
    deserializer.close()
    assertEquals(client.closeCalls, 0)
    failure(serializer.serialize("trades", trade))
    failure(deserializer.deserialize("trades", null))
  }

  test("subject strategies use Avro full name and configured key flag") {
    for (strategy, expected) <- Seq(
      SubjectNameStrategy.TopicName -> "trades-key",
      SubjectNameStrategy.RecordName -> "avro2s.wire.fixtures.Trade",
      SubjectNameStrategy.TopicRecordName -> "trades-avro2s.wire.fixtures.Trade"
    ) do
      val client = new CountingClient
      val serializer = new RegistrySerializer(Trade.codec, client,
        RegistrySettings(subjectNameStrategy = strategy))
      serializer.configure(config, true)
      serializer.serialize("trades", trade)
      assertEquals(client.subjects.toVector, Vector(expected))
      failure(serializer.configure(config, false))
  }

  test("lookup mode uses getId and retries failed lookups") {
    val client = new CountingClient
    val preexisting = client.register("trades-value", new AvroSchema(Trade.schemaJson), false)
    client.failLookupOnce = true
    val serializer = new RegistrySerializer(Trade.codec, client,
      RegistrySettings(autoRegisterSchemas = false))
    failure(serializer.serialize("trades", trade))
    val bytes = serializer.serialize("trades", trade)
    assertEquals(id(bytes), preexisting)
    serializer.serialize("trades", trade)
    assertEquals(client.lookupCalls, 2)
    assertEquals(client.registerCalls, 1)
  }

  test("normalization flag reaches register and getId") {
    val client = new CountingClient
    val options = RegistrySettings(normalizeSchemas = true)
    new RegistrySerializer(Trade.codec, client, options).serialize("trades", trade)
    assertEquals(client.registerNormalize.toVector, Vector(true))
    new RegistrySerializer(Trade.codec, client,
      options.copy(autoRegisterSchemas = false)).serialize("trades", trade)
    assertEquals(client.lookupNormalize.toVector, Vector(true))
  }

  test("bounded subject and reader caches evict the least recently used entry") {
    val client = new CountingClient
    val serializer = new RegistrySerializer(Trade.codec, client, RegistrySettings(cacheCapacity = 1))
    val a = serializer.serialize("a", trade)
    serializer.serialize("a", trade)
    serializer.serialize("b", trade)
    serializer.serialize("a", trade)
    assertEquals(client.registerCalls, 3)

    // Distinct IDs with identical schema exercise reader-plan eviction.
    val sameLayout = "{\"doc\":\"alternate\"," + Trade.schemaJson.drop(1)
    val alternate = client.register("alternate-value", new AvroSchema(sameLayout), false)
    assert(alternate != id(a))
    val deserializer = new RegistryDeserializer(Trade.codec, client, RegistrySettings(cacheCapacity = 1))
    deserializer.deserialize("a", a)
    deserializer.deserialize("a", a)
    deserializer.deserialize("a", frame(alternate, Trade.codec.encode(trade)))
    deserializer.deserialize("a", a)
    assertEquals(client.fetchCalls, 3)
  }

  test("malformed frames, trailing data, and limits reject before returning a model") {
    val client = new MockSchemaRegistryClient()
    val bytes = new RegistrySerializer(Trade.codec, client).serialize("trades", trade)
    val deserializer = new RegistryDeserializer(Trade.codec, client)
    failure(deserializer.deserialize("trades", Array.emptyByteArray))
    failure(deserializer.deserialize("trades", bytes.take(4)))
    failure(deserializer.deserialize("trades", bytes.updated(0, 1.toByte)))
    failure(deserializer.deserialize("trades", frame(0, bytes.drop(5))))
    failure(deserializer.deserialize("trades", frame(999999, bytes.drop(5))))
    failure(deserializer.deserialize("trades", bytes.dropRight(1)))
    failure(deserializer.deserialize("trades", bytes ++ Array[Byte](0)))
    val limited = new RegistryDeserializer(Trade.codec, client,
      RegistrySettings(decodeLimits = DecodeLimits(maxInputBytes = 1)))
    failure(limited.deserialize("trades", bytes))
    val headers = new RecordHeaders().add("__value_schema_id", Array[Byte](1))
    failure(deserializer.deserialize("trades", headers, bytes))
    failure(new RegistrySerializer(Trade.codec, client).serialize("trades", headers, trade))
    assertEquals(deserializer.deserialize("trades", headers, null), null)
    assertEquals(deserializer.deserialize("trades", new RecordHeaders(), bytes), trade)
  }

  test("payload limit rejects before registry lookup and copying") {
    val client = new CountingClient
    val bytes = frame(1, Trade.codec.encode(trade))
    val deserializer = new RegistryDeserializer(Trade.codec, client,
      RegistrySettings(decodeLimits = DecodeLimits(maxInputBytes = 1)))
    failure(deserializer.deserialize("trades", bytes))
    assertEquals(client.fetchCalls, 0)
  }

  test("reader-plan fetch failures are not cached") {
    val client = new CountingClient
    val bytes = new RegistrySerializer(Trade.codec, client).serialize("trades", trade)
    client.failFetchOnce = true
    val deserializer = new RegistryDeserializer(Trade.codec, client)
    failure(deserializer.deserialize("trades", bytes))
    assertEquals(deserializer.deserialize("trades", bytes), trade)
    assertEquals(deserializer.deserialize("trades", bytes), trade)
    assertEquals(client.fetchCalls, 2)
  }

  test("a missing schema ID can be fetched after it becomes available") {
    val unavailable = 54321
    var available = false
    var fetches = 0
    val client = new MockSchemaRegistryClient:
      override def getSchemaById(id: Int): ParsedSchema = synchronized {
        fetches += 1
        if id == unavailable && available then new AvroSchema(Trade.schemaJson)
        else throw new IOException("schema ID unavailable")
      }
    val bytes = frame(unavailable, Trade.codec.encode(trade))
    val deserializer = new RegistryDeserializer(Trade.codec, client)
    failure(deserializer.deserialize("trades", bytes))
    available = true
    assertEquals(deserializer.deserialize("trades", bytes), trade)
    assertEquals(deserializer.deserialize("trades", bytes), trade)
    assertEquals(fetches, 2)
  }

  test("invalid returned ID is not cached") {
    val client = new CountingClient
    client.invalidIdOnce = true
    val serializer = new RegistrySerializer(Trade.codec, client)
    failure(serializer.serialize("trades", trade))
    val bytes = serializer.serialize("trades", trade)
    assert(id(bytes) > 0)
    assertEquals(client.registerCalls, 2)
  }

  test("non-Avro schemas and metadata or rules are rejected") {
    val nonAvro = java.lang.reflect.Proxy.newProxyInstance(
      classOf[ParsedSchema].getClassLoader,
      Array(classOf[ParsedSchema]),
      (_, _, _) => null
    ).asInstanceOf[ParsedSchema]
    val metadata = new Metadata(java.util.Collections.emptyMap(),
      java.util.Collections.emptyMap(), java.util.Collections.emptySet())
    val ruleSet = new RuleSet(java.util.Collections.emptyList(), java.util.Collections.emptyList())
    val withMetadata = new AvroSchema(Trade.schemaJson, java.util.Collections.emptyList(),
      java.util.Collections.emptyMap(), metadata, null, null, false)
    val withRules = new AvroSchema(Trade.schemaJson, java.util.Collections.emptyList(),
      java.util.Collections.emptyMap(), null, ruleSet, null, false)
    for parsed <- Seq(nonAvro, withMetadata, withRules) do
      val client = new MockSchemaRegistryClient:
        override def getSchemaById(id: Int): ParsedSchema = parsed
      val deserializer = new RegistryDeserializer(Trade.codec, client)
      failure(deserializer.deserialize("trades", frame(7, Trade.codec.encode(trade))))
  }

  test("interrupted registry calls restore the interrupt flag and wrap the cause") {
    val client = new MockSchemaRegistryClient:
      override def getSchemaById(id: Int): ParsedSchema = throw new InterruptedException("interrupted")
    val deserializer = new RegistryDeserializer(Trade.codec, client)
    try
      val error = failure(deserializer.deserialize("trades", frame(7, Trade.codec.encode(trade))))
      assert(error.getCause.isInstanceOf[InterruptedException])
      assert(Thread.currentThread().isInterrupted)
    finally Thread.interrupted()
  }

  test("owned adapters close their clients once and injected adapters leave them open") {
    val owned = new CountingClient
    val serializer = new RegistrySerializer(Trade.codec, owned, RegistrySettings(), true)
    serializer.close()
    serializer.close()
    assertEquals(owned.closeCalls, 1)
    val other = new CountingClient
    val deserializer = new RegistryDeserializer(Trade.codec, other, RegistrySettings(), true)
    deserializer.close()
    deserializer.close()
    assertEquals(other.closeCalls, 1)
    val injected = new CountingClient
    new RegistrySerializer(Trade.codec, injected).close()
    new RegistryDeserializer(Trade.codec, injected).close()
    assertEquals(injected.closeCalls, 0)
  }

  test("unsafe Confluent options and unsupported root schemas are rejected") {
    val client = new MockSchemaRegistryClient()
    for key -> value <- Seq(
      "use.latest.version" -> "true", "use.schema.id" -> "4",
      "context.name.strategy" -> "custom", "value.subject.name.strategy" -> "custom",
      "rule.executors.foo" -> "custom", "value.schema.id.serializer" -> "header"
    ) do
      val cfg = config
      cfg.put(key, value)
      failure(new RegistrySerializer(Trade.codec, client).configure(cfg, false))
      intercept[IllegalArgumentException](RegistrySerializer.fromConfig(Trade.codec,
        List("mock://wire"), config = cfg.asScala.toMap))
    val primitive = new AvroCodec[Int]:
      override def schemaJson: String = "\"int\""
      override def read(in: AvroInput): Int = in.readInt()
      override def write(value: Int, out: AvroOutput): Unit = out.writeInt(value)
    intercept[IllegalArgumentException](new RegistrySerializer(primitive, client))
    intercept[IllegalArgumentException](new RegistryDeserializer(primitive, client))
  }

  test("one serializer safely shares its bounded cache across threads") {
    val client = new CountingClient
    val serializer = new RegistrySerializer(Trade.codec, client)
    val pool = Executors.newFixedThreadPool(8)
    try
      val jobs = (1 to 32).map { _ =>
        pool.submit(new Callable[Array[Byte]]:
          override def call(): Array[Byte] = serializer.serialize("trades", trade))
      }
      val bytes = jobs.map(_.get(10, TimeUnit.SECONDS).toVector)
      assert(bytes.forall(_ == bytes.head))
      assertEquals(client.registerCalls, 1)
    finally
      pool.shutdownNow()
  }

  test("one deserializer fetches and compiles one plan across threads") {
    val client = new CountingClient
    val bytes = new RegistrySerializer(Trade.codec, client).serialize("trades", trade)
    val deserializer = new RegistryDeserializer(Trade.codec, client)
    val pool = Executors.newFixedThreadPool(8)
    try
      val jobs = (1 to 32).map { _ =>
        pool.submit(new Callable[Trade]:
          override def call(): Trade = deserializer.deserialize("trades", bytes))
      }
      assert(jobs.forall(_.get(10, TimeUnit.SECONDS) == trade))
      assertEquals(client.fetchCalls, 1)
    finally pool.shutdownNow()
  }

  private final class CountingClient extends MockSchemaRegistryClient:
    var registerCalls = 0
    var lookupCalls = 0
    var fetchCalls = 0
    var closeCalls = 0
    var failLookupOnce = false
    var failFetchOnce = false
    var invalidIdOnce = false
    val subjects = scala.collection.mutable.ArrayBuffer.empty[String]
    val registerNormalize = scala.collection.mutable.ArrayBuffer.empty[Boolean]
    val lookupNormalize = scala.collection.mutable.ArrayBuffer.empty[Boolean]

    override def register(subject: String, schema: ParsedSchema, normalize: Boolean): Int = synchronized {
      registerCalls += 1
      subjects += subject
      registerNormalize += normalize
      if invalidIdOnce then
        invalidIdOnce = false
        0
      else super.register(subject, schema, normalize)
    }

    override def getId(subject: String, schema: ParsedSchema, normalize: Boolean): Int = synchronized {
      lookupCalls += 1
      lookupNormalize += normalize
      if failLookupOnce then
        failLookupOnce = false
        throw new IOException("temporary failure")
      super.getId(subject, schema, normalize)
    }

    override def getSchemaById(id: Int): ParsedSchema = synchronized {
      fetchCalls += 1
      if failFetchOnce then
        failFetchOnce = false
        throw new IOException("temporary failure")
      super.getSchemaById(id)
    }

    override def close(): Unit = synchronized { closeCalls += 1 }
