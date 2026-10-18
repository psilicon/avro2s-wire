package avro2s.wire.registry

import avro2s.wire.fixtures.Trade
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroSerializer}
import io.confluent.kafka.serializers.subject.TopicNameStrategy
import java.io.{IOException, InterruptedIOException}
import java.net.SocketTimeoutException
import org.apache.avro.generic.GenericData
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.{AuthenticationException, AuthorizationException, DisconnectException,
  SerializationException, ThrottlingQuotaExceededException, TimeoutException}
import scala.jdk.CollectionConverters.*

final class RegistryOperationalSuite extends munit.FunSuite:
  private val trade = Trade(1L, "ABC", 2.0, Vector(3))
  private val schema = new AvroSchema(Trade.schemaJson)
  private val frame = Array[Byte](0, 0, 0, 0, 7) ++ Trade.codec.encode(trade)

  private def genericTrade: GenericData.Record =
    val value = new GenericData.Record(schema.rawSchema())
    value.put("id", trade.id)
    value.put("symbol", trade.symbol)
    value.put("price", trade.price)
    value.put("quantities", trade.quantities.map(Int.box).asJava)
    value

  private final class Client(var failure: Throwable) extends MockSchemaRegistryClient:
    var calls = 0
    private def check(): Unit =
      calls += 1
      if failure != null then throw failure
    override def getIdWithResponse(subject: String, parsed: ParsedSchema,
        normalize: Boolean): RegisterSchemaResponse =
      check()
      new RegisterSchemaResponse(7)
    override def registerWithResponse(subject: String, parsed: ParsedSchema,
        normalize: Boolean, propagate: Boolean): RegisterSchemaResponse =
      getIdWithResponse(subject, parsed, normalize)
    override def getSchemaById(id: Int): ParsedSchema =
      check()
      schema
    override def getSchemaBySubjectAndId(subject: String, id: Int): ParsedSchema =
      getSchemaById(id)

  private def config(registering: Boolean): java.util.Map[String, AnyRef] = Map[String, AnyRef](
    "schema.registry.url" -> "mock://operational-errors",
    "auto.register.schemas" -> Boolean.box(registering),
    "value.subject.name.strategy" -> classOf[TopicNameStrategy].getName
  ).asJava

  private def assertClassification(failure: Throwable, expected: Class[?]): Unit =
    def check(ours: => Any, theirs: => Any): Unit =
      val native = intercept[KafkaException](ours)
      val confluent = intercept[KafkaException](theirs)
      assertEquals(native.getClass.getName, expected.getName)
      assertEquals(native.getClass.getName, confluent.getClass.getName)
      // Confluent deliberately creates the quota exception without a cause.
      if expected != classOf[ThrottlingQuotaExceededException] then
        assert(native.getCause eq failure)
        assert(confluent.getCause eq failure)

    for registering <- Seq(false, true) do
      val nativeClient = new Client(failure)
      val confluentClient = new Client(failure)
      val writer = RegistrySerializer.forValue(Trade.codec, nativeClient,
        SerializerSettings(autoRegisterSchemas = registering))
      val confluent = new KafkaAvroSerializer(confluentClient)
      confluent.configure(config(registering), false)
      try
        check(writer.serialize("trades", trade), confluent.serialize("trades", genericTrade))
        assertEquals(nativeClient.calls, 1)
      finally
        writer.close()
        confluent.close()

    val nativeClient = new Client(failure)
    val confluentClient = new Client(failure)
    val reader = RegistryDeserializer.forValue(Trade.codec, nativeClient)
    val confluent = new KafkaAvroDeserializer(confluentClient)
    confluent.configure(config(false), false)
    try
      check(reader.deserialize("trades", frame), confluent.deserialize("trades", frame))
      assertEquals(nativeClient.calls, 1)
    finally
      reader.close()
      confluent.close()

  test("registry HTTP errors retain Confluent exception categories through adapter caches") {
    val statuses = Seq[(Int, Class[?])](
      401 -> classOf[AuthenticationException],
      403 -> classOf[AuthorizationException],
      429 -> classOf[ThrottlingQuotaExceededException],
      408 -> classOf[TimeoutException],
      500 -> classOf[TimeoutException],
      503 -> classOf[TimeoutException],
      504 -> classOf[TimeoutException],
      502 -> classOf[DisconnectException],
      400 -> classOf[SerializationException],
      404 -> classOf[SerializationException],
      409 -> classOf[SerializationException],
      422 -> classOf[SerializationException]
    )
    for (status, expected) <- statuses do
      assertClassification(new RestClientException("registry failure", status, status * 100 + 1), expected)
  }

  test("I/O timeouts match Confluent without marking the calling thread interrupted") {
    assert(!Thread.currentThread().isInterrupted)
    assertClassification(new InterruptedIOException("request interrupted"), classOf[TimeoutException])
    assertClassification(new SocketTimeoutException("request timed out"), classOf[TimeoutException])
    assert(!Thread.currentThread().isInterrupted)
    assertClassification(new IOException("request failed"), classOf[SerializationException])
  }

  test("a registry failure is not retained after classification") {
    val failure = new RestClientException("unavailable", 503, 50301)
    for registering <- Seq(false, true) do
      val client = new Client(failure)
      val writer = RegistrySerializer.forValue(Trade.codec, client,
        SerializerSettings(autoRegisterSchemas = registering))
      try
        intercept[TimeoutException](writer.serialize("trades", trade))
        client.failure = null
        assertEquals(writer.serialize("trades", trade).toVector, frame.toVector)
        assertEquals(writer.serialize("trades", trade).toVector, frame.toVector)
        assertEquals(client.calls, 2)
      finally writer.close()

    val client = new Client(failure)
    val reader = RegistryDeserializer.forValue(Trade.codec, client)
    try
      intercept[TimeoutException](reader.deserialize("trades", frame))
      client.failure = null
      assertEquals(reader.deserialize("trades", frame), trade)
      assertEquals(reader.deserialize("trades", frame), trade)
      assertEquals(client.calls, 2)
    finally reader.close()
  }

  test("classified Kafka failures and existing interrupt flags are preserved") {
    val expected = new AuthenticationException("already classified")
    val cache = new BoundedCache[String, Int](1)
    val actual = intercept[AuthenticationException] {
      RegistrySupport.guard("test")(cache.getOrLoad("a")(throw expected))
    }
    assert(actual eq expected)
    Thread.currentThread().interrupt()
    try
      intercept[TimeoutException] {
        RegistrySupport.guard("test")(throw new InterruptedIOException("timeout"))
      }
      assert(Thread.currentThread().isInterrupted)
    finally Thread.interrupted()
  }

  test("unsupported association options fail before constructing a registry client") {
    val options = Map(
      "subject.name.strategy.kafka.cluster.id" -> "cluster",
      "subject.name.strategy.fallback.type" -> "NONE",
      "subject.name.strategy.cache.size" -> "100",
      "subject.name.strategy.cache.expiry.secs" -> "60"
    )
    for
      (key, value) <- options
      prefix <- Seq("", "schema.registry.")
    do
      val error = intercept[IllegalArgumentException] {
        RegistryConnection(List("http://localhost:18081"), properties = Map((prefix + key) -> value))
      }
      assert(error.getMessage.contains(prefix + key))
  }

  test("owned mock URLs fail with guidance while injected mock clients remain supported") {
    for urls <- Seq(
      List("mock://scope"),
      List("http://localhost:18081", "mock://scope"),
      List(" MOCK://scope ")
    ) do
      val error = intercept[IllegalArgumentException](RegistryConnection(urls))
      assert(error.getMessage.contains("inject a MockSchemaRegistryClient"))

    val client = new MockSchemaRegistryClient()
    val writer = RegistrySerializer.forValue(Trade.codec, client, SerializerSettings(autoRegisterSchemas = true))
    val reader = RegistryDeserializer.forValue(Trade.codec, client)
    try assertEquals(reader.deserialize("trades", writer.serialize("trades", trade)), trade)
    finally
      writer.close()
      reader.close()
      client.close()
  }
