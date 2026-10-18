package avro2s.wire.registry

import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput}
import com.sun.net.httpserver.HttpServer
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, MockSchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import org.apache.kafka.common.errors.SerializationException
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

final class RegistryLookupSuite extends munit.FunSuite:
  private final case class Point(left: Int, right: Int)
  private val json = """{"type":"record","name":"LookupPoint","fields":[{"name":"left","type":"int"},{"name":"right","type":"int"}]}"""
  private val reversedJson = """{"type":"record","name":"LookupPoint","fields":[{"name":"right","type":"int"},{"name":"left","type":"int"}]}"""
  private val id = 7
  private val value = Point(3, 9)
  private val reversed = Point(9, 3)

  private val codec: AvroCodec[Point] = new AvroCodec[Point]:
    override def schemaJson: String = json
    override def read(in: AvroInput): Point = Point(in.readInt(), in.readInt())
    override def write(value: Point, out: AvroOutput): Unit =
      out.writeInt(value.left)
      out.writeInt(value.right)
    override def construct(values: Array[Any]): Point =
      Point(values(0).asInstanceOf[Int], values(1).asInstanceOf[Int])

  private def frame: Array[Byte] = Array[Byte](0, 0, 0, 0, id.toByte) ++ codec.encode(value)

  private case class Fetch(subject: Option[String], id: Int)

  private final class Client(schemas: Map[Option[String], String] = Map.empty) extends MockSchemaRegistryClient:
    val fetches = ArrayBuffer.empty[Fetch]
    private def fetch(subject: Option[String], id: Int): ParsedSchema =
      fetches += Fetch(subject, id)
      new AvroSchema(schemas.getOrElse(subject, json))
    override def getSchemaById(id: Int): ParsedSchema = fetch(None, id)
    override def getSchemaBySubjectAndId(subject: String, id: Int): ParsedSchema = fetch(Some(subject), id)

  test("the default ID-only lookup preserves topic-independent caching") {
    val client = new Client
    val reader = RegistryDeserializer.forValue(codec, client)
    try
      for topic <- Seq(null, "", "one", "two") do
        assertEquals(reader.deserialize(topic, frame), value)
      assertEquals(client.fetches.toVector, Vector(Fetch(None, id)))
    finally reader.close()
  }

  test("topic lookup supplies the adapter role and optional context to the client") {
    val client = new Client
    for
      context <- Seq(None, Some("sales"))
      key <- Seq(false, true)
    do
      val settings = DeserializerSettings(schemaLookup = SchemaLookup.TopicName(context))
      val reader = if key then RegistryDeserializer.forKey(codec, client, settings)
        else RegistryDeserializer.forValue(codec, client, settings)
      try
        assertEquals(reader.deserialize("orders", frame), value)
        assertEquals(reader.deserialize("orders", frame), value)
      finally reader.close()
    assertEquals(client.fetches.toVector, Vector(
      Fetch(Some("orders-value"), id), Fetch(Some("orders-key"), id),
      Fetch(Some(":.sales:orders-value"), id), Fetch(Some(":.sales:orders-key"), id)
    ))
  }

  test("one reader caches the same numeric ID separately for different topic hints") {
    val client = new Client(Map(Some("one-value") -> json, Some("two-value") -> reversedJson))
    val reader = RegistryDeserializer.forValue(codec, client,
      DeserializerSettings(schemaLookup = SchemaLookup.TopicName()))
    try
      for _ <- 1 to 3 do
        assertEquals(reader.deserialize("one", frame), value)
        assertEquals(reader.deserialize("two", frame), reversed)
      assertEquals(client.fetches.toVector,
        Vector(Fetch(Some("one-value"), id), Fetch(Some("two-value"), id)))
    finally reader.close()
  }

  test("readers sharing a client resolve the same ID independently in fixed contexts") {
    val client = new Client(Map(Some(":.live:") -> json, Some(":.archive:") -> reversedJson))
    val live = RegistryDeserializer.forValue(codec, client,
      DeserializerSettings(schemaLookup = SchemaLookup.Context("live")))
    val archive = RegistryDeserializer.forValue(codec, client,
      DeserializerSettings(schemaLookup = SchemaLookup.Context(":.archive:")))
    try
      for topic <- Seq(null, "one", "two") do
        assertEquals(live.deserialize(topic, frame), value)
        assertEquals(archive.deserialize(topic, frame), reversed)
      assertEquals(client.fetches.toVector,
        Vector(Fetch(Some(":.live:"), id), Fetch(Some(":.archive:"), id)))
    finally
      live.close()
      archive.close()
  }

  test("an explicit qualified subject is passed intact and does not depend on topic or role") {
    val subject = ":.sales:chosen/+ café"
    val client = new Client
    val reader = RegistryDeserializer.forKey(codec, client,
      DeserializerSettings(schemaLookup = SchemaLookup.Subject(subject)))
    try
      for topic <- Seq(null, "", "unrelated") do
        assertEquals(reader.deserialize(topic, frame), value)
      assertEquals(client.fetches.toVector, Vector(Fetch(Some(subject), id)))
    finally reader.close()
  }

  test("buffer lookups use the same context and subject cache without changing the cursor") {
    val client = new Client
    val reader = RegistryDeserializer.forKey(codec, client,
      DeserializerSettings(schemaLookup = SchemaLookup.TopicName(Some("finance"))))
    try
      for direct <- Seq(false, true) do
        val surrounded = Array[Byte](99, 98) ++ frame ++ Array[Byte](97)
        val input = if direct then
          val buffer = ByteBuffer.allocateDirect(surrounded.length)
          buffer.put(surrounded)
          buffer.flip()
          buffer
        else ByteBuffer.wrap(surrounded)
        input.position(2)
        input.limit(2 + frame.length)
        input.order(ByteOrder.LITTLE_ENDIAN)
        input.mark()
        assertEquals(reader.deserialize("orders", null, input), value)
        assertEquals(input.position(), 2)
        assertEquals(input.limit(), 2 + frame.length)
        assertEquals(input.order(), ByteOrder.LITTLE_ENDIAN)
        input.reset()
        assertEquals(input.position(), 2)
      assertEquals(reader.deserialize("orders", frame), value)
      assertEquals(client.fetches.toVector, Vector(Fetch(Some(":.finance:orders-key"), id)))
    finally reader.close()
  }

  test("settings reject invalid lookup policies before an adapter can be created") {
    val invalid = Seq(
      null, SchemaLookup.Subject(null), SchemaLookup.Subject(""), SchemaLookup.Subject(" "),
      SchemaLookup.Context(null), SchemaLookup.Context(""), SchemaLookup.Context("a:b"),
      SchemaLookup.Context("*"), SchemaLookup.Context(" sales "),
      SchemaLookup.TopicName(null), SchemaLookup.TopicName(Some(null)),
      SchemaLookup.TopicName(Some("")), SchemaLookup.TopicName(Some("*"))
    )
    invalid.foreach(policy => intercept[IllegalArgumentException](DeserializerSettings(schemaLookup = policy)))
  }

  test("topic lookup rejects missing topics before fetching while tombstones remain valid") {
    val client = new Client
    val reader = RegistryDeserializer.forValue(codec, client,
      DeserializerSettings(schemaLookup = SchemaLookup.TopicName()))
    try
      for topic <- Seq(null, "") do
        val arrayError = intercept[SerializationException](reader.deserialize(topic, frame))
        val bufferError = intercept[SerializationException](reader.deserialize(topic, null, ByteBuffer.wrap(frame)))
        assert(arrayError.getCause.isInstanceOf[IllegalArgumentException])
        assert(bufferError.getCause.isInstanceOf[IllegalArgumentException])
        assertEquals(reader.deserialize(topic, null: Array[Byte]), null)
        assertEquals(reader.deserialize(topic, null, null: ByteBuffer), null)
      assertEquals(client.fetches.toVector, Vector.empty[Fetch])
    finally reader.close()
  }

  private case class HttpRequest(method: String, path: String, rawQuery: String)

  private def withServer[A](response: String)(run: (String, ConcurrentLinkedQueue[HttpRequest]) => A): A =
    val requests = new ConcurrentLinkedQueue[HttpRequest]()
    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(executor)
    server.createContext("/", exchange =>
      try
        exchange.getRequestBody.readAllBytes()
        requests.add(HttpRequest(exchange.getRequestMethod, exchange.getRequestURI.getPath,
          exchange.getRequestURI.getRawQuery))
        val bytes = response.getBytes(UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/vnd.schemaregistry.v1+json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
      finally exchange.close()
    )
    server.start()
    try run(s"http://127.0.0.1:${server.getAddress.getPort}", requests)
    finally
      server.stop(0)
      executor.shutdownNow()

  test("the real cached client sends the qualified subject as an encoded HTTP query parameter") {
    val subject = ":.sales:orders/+ café"
    val response = new RegisterSchemaResponse(new Schema(subject, 1, id, new AvroSchema(json))).toJson
    withServer(response) { (url, requests) =>
      val properties = Map[String, AnyRef](
        "http.connect.timeout.ms" -> "2000", "http.read.timeout.ms" -> "2000", "max.retries" -> "0")
      val client = new CachedSchemaRegistryClient(url, 16, properties.asJava)
      val reader = RegistryDeserializer.forValue(codec, client,
        DeserializerSettings(schemaLookup = SchemaLookup.Subject(subject)))
      try
        assertEquals(reader.deserialize("ignored", frame), value)
        assertEquals(reader.deserialize("also-ignored", frame), value)
        val actual = requests.asScala.toVector
        assertEquals(actual.size, 1)
        assertEquals(actual.head.method, "GET")
        assertEquals(actual.head.path, s"/schemas/ids/$id")
        val subjectQuery = actual.head.rawQuery.split("&").find(_.startsWith("subject=")).get
        assertEquals(subjectQuery, "subject=%3A.sales%3Aorders%2F%2B%20caf%C3%A9")
        assertEquals(URLDecoder.decode(subjectQuery.stripPrefix("subject="), UTF_8), subject)
      finally
        reader.close()
        client.close()
    }
  }
