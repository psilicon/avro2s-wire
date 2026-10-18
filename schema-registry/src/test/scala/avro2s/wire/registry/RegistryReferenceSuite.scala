package avro2s.wire.registry

import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput}
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.{Schema, SchemaReference}
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.{AuthenticationException, AuthorizationException, DisconnectException,
  SerializationException, ThrottlingQuotaExceededException, TimeoutException}
import org.apache.kafka.common.header.internals.RecordHeaders

final class RegistryReferenceSuite extends munit.FunSuite:
  private case class Pair(x: Int, y: Int)
  private val child = """{"type":"record","name":"Child","fields":[{"name":"x","type":"int"},{"name":"y","type":"int"}]}"""
  private val reversed = """{"type":"record","name":"Child","fields":[{"name":"y","type":"int"},{"name":"x","type":"int"}]}"""
  private val root = """{"type":"record","name":"Root","fields":[{"name":"child","type":"Child"}]}"""
  private val full = root.replace("\"Child\"", child)
  private val value = Pair(10, 20)
  private val codec: AvroCodec[Pair] = new AvroCodec[Pair]:
    val schemaJson = full
    def read(in: AvroInput): Pair = Pair(in.readInt(), in.readInt())
    def write(value: Pair, out: AvroOutput): Unit =
      out.writeInt(value.x)
      out.writeInt(value.y)
    override def construct(values: Array[Any]): Pair = values(0).asInstanceOf[Pair]
    override def namedCodec(name: String): AvroCodec[?] =
      if name == "Child" then childCodec else super.namedCodec(name)
  private val childCodec: AvroCodec[Pair] = new AvroCodec[Pair]:
    val schemaJson = child
    def read(in: AvroInput): Pair = codec.read(in)
    def write(value: Pair, out: AvroOutput): Unit = codec.write(value, out)
    override def construct(values: Array[Any]): Pair = Pair(values(0).asInstanceOf[Int], values(1).asInstanceOf[Int])

  private val guid = "bdaf9d85-2510-e880-76fb-c5d00869763d"
  private def headers: RecordHeaders =
    val uuid = UUID.fromString(guid)
    val result = new RecordHeaders()
    result.add("__value_schema_id", ByteBuffer.allocate(17).put(1.toByte)
      .putLong(uuid.getMostSignificantBits).putLong(uuid.getLeastSignificantBits).array())
    result
  private val datum = codec.encode(value)
  private val frame = Array[Byte](0, 0, 0, 0, 7) ++ datum
  private def entity(subject: String, json: String, refs: SchemaReference*): Schema =
    new Schema(subject, 1, 7, "AVRO", java.util.List.of(refs*), json)

  private class Client extends MockSchemaRegistryClient:
    var rootEntity = entity(":.a:events", root, new SchemaReference("Child", "child", 1))
    var childEntity = entity(":.a:child", child)
    var failure: Throwable = null
    var failGuidLookup = false
    var fetchedSubjects = Vector.empty[String]
    var guidFetches = 0
    var guidSchema = new AvroSchema(root, java.util.List.of(new SchemaReference("Child", ":.a:child", 1)),
      java.util.Map.of("Child", reversed), null)
    override def getSchemaEntityBySubjectAndId(subject: String, id: Int): Schema = rootEntity
    override def getSchemaById(id: Int): ParsedSchema = throw new AssertionError("Parsed ID lookup is unsafe")
    override def getSchemaBySubjectAndId(subject: String, id: Int): ParsedSchema =
      throw new AssertionError("Parsed subject/ID lookup is unsafe")
    override def getSchemaByGuid(value: String, format: String): ParsedSchema =
      guidFetches += 1
      if failGuidLookup && failure != null then
        throw new IllegalArgumentException("Invalid referenced schema", new RuntimeException(failure))
      guidSchema
    override def getByVersion(subject: String, version: Int, deleted: Boolean): Schema =
      fetchedSubjects :+= subject
      assert(deleted)
      if failure != null then throw new RuntimeException(failure)
      childEntity

  test("numeric parsing keeps server context and falls back to the lookup hint only when absent") {
    for serverSubject <- Vector(":.a:events", null, "") do
      val client = new Client
      client.rootEntity.setSubject(serverSubject)
      val hint = if serverSubject == ":.a:events" then ":.other:ignored" else ":.a:events"
      val reader = RegistryDeserializer.forValue(codec, client,
        DeserializerSettings(schemaLookup = SchemaLookup.Subject(hint)))
      try
        assertEquals(reader.deserialize("ignored", frame), value)
        assertEquals(client.fetchedSubjects, Vector(":.a:child"))
        assertEquals(client.rootEntity.getSubject, serverSubject)
      finally reader.close()
  }

  test("custom clients lacking raw schema lookup fail clearly without falling back to parsed schemas") {
    val client = new Client:
      override def getSchemaEntityBySubjectAndId(subject: String, id: Int): Schema =
        throw new UnsupportedOperationException()
    val reader = RegistryDeserializer.forValue(codec, client)
    try
      val error = intercept[SerializationException](reader.deserialize("events", frame))
      assert(error.getCause.getMessage.contains("getSchemaEntityBySubjectAndId"))
      assert(error.getCause.getCause.isInstanceOf[UnsupportedOperationException])
    finally reader.close()
  }

  test("GUID parsing resolves qualified references again instead of trusting a stale expanded schema") {
    val client = new Client
    val reader = RegistryDeserializer.forValue(codec, client)
    try
      assertEquals(reader.deserialize("events", headers, datum), value)
      assertEquals(reader.deserialize("events", headers, datum), value)
      assertEquals(client.fetchedSubjects, Vector(":.a:child"))
      assertEquals(client.guidFetches, 1)
    finally reader.close()
  }

  test("relative, default-context, wildcard and subjectless GUID references fail without a cached plan") {
    for subject <- Vector("child", ":.:child", ":*:child", ":.a:") do
      val client = new Client
      client.guidSchema = new AvroSchema(root, java.util.List.of(new SchemaReference("Child", subject, 1)),
        java.util.Map.of("Child", child), null)
      val reader = RegistryDeserializer.forValue(codec, client,
        DeserializerSettings(schemaLookup = SchemaLookup.Context("a")))
      try
        for _ <- 0 until 2 do
          val error = intercept[SerializationException](reader.deserialize("events", headers, datum))
          assert(error.getCause.getMessage.contains("relative references"))
        assertEquals(client.guidFetches, 2)
        assertEquals(client.fetchedSubjects, Vector.empty)
      finally reader.close()
  }

  test("resolving latest reference versions never mutates cached root or nested descriptors") {
    val client = new Client
    val middle = """{"type":"record","name":"Middle","fields":[{"name":"child","type":"Child"}]}"""
    val top = root.replace("Child", "Middle")
    val topEntity = entity(":.a:top", top, new SchemaReference("Middle", "middle", -1))
    val middleEntity = entity(":.a:middle", middle, new SchemaReference("Child", "child", -1))
    val nested = new Client:
      override def getSchemaEntityBySubjectAndId(subject: String, id: Int): Schema = topEntity
      override def getByVersion(subject: String, version: Int, deleted: Boolean): Schema =
        if subject == ":.a:middle" then middleEntity else client.childEntity
    val resolved = RegistrySchemas.byId(nested, None, 7)
    assert(resolved.contains("Middle"))
    assertEquals(topEntity.getReferences.get(0).getVersion.intValue(), -1)
    assertEquals(middleEntity.getReferences.get(0).getVersion.intValue(), -1)
  }

  test("wrapped reference fetch failures preserve Kafka categories and remain retryable") {
    val failures = Vector[(Throwable, Class[?])](
      new RestClientException("auth", 401, 40101) -> classOf[AuthenticationException],
      new RestClientException("access", 403, 40301) -> classOf[AuthorizationException],
      new RestClientException("quota", 429, 42901) -> classOf[ThrottlingQuotaExceededException],
      new RestClientException("timeout", 504, 50401) -> classOf[TimeoutException],
      new RestClientException("gateway", 502, 50201) -> classOf[DisconnectException],
      new SocketTimeoutException("reference timeout") -> classOf[TimeoutException],
      new InterruptedException("reference interrupted") -> classOf[SerializationException]
    )
    for (failure, expected) <- failures; path <- Vector("id", "guid-lookup", "guid-reparse") do
      val client = new Client
      client.failure = failure
      client.failGuidLookup = path == "guid-lookup"
      val reader = RegistryDeserializer.forValue(codec, client)
      def read(): Pair = if path != "id" then reader.deserialize("events", headers, datum)
        else reader.deserialize("events", frame)
      try
        val error = intercept[KafkaException](read())
        assertEquals(error.getClass.getName, expected.getName)
        if expected != classOf[ThrottlingQuotaExceededException] then assert(error.getCause eq failure)
        assertEquals(Thread.currentThread().isInterrupted, failure.isInstanceOf[InterruptedException])
        Thread.interrupted()
        client.failure = null
        assertEquals(read(), value)
      finally
        Thread.interrupted()
        reader.close()
  }
