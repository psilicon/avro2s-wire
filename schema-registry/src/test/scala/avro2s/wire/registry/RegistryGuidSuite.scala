package avro2s.wire.registry

import io.confluent.kafka.schemaregistry.client.rest.entities.{Schema as RegistrySchema}

import avro2s.wire.fixtures.Trade
import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput, DecodeLimits}
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.{MockSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.{Rule, RuleKind, RuleMode, RuleSet}
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroSerializer}
import io.confluent.kafka.serializers.schema.id.HeaderSchemaIdSerializer
import io.confluent.kafka.serializers.subject.TopicNameStrategy
import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.apache.avro.generic.GenericData
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import scala.jdk.CollectionConverters.*

final class RegistryGuidSuite extends munit.FunSuite:
  private val trade = Trade(123L, "café-🚀", 42.5, Vector(10, 20))
  private val schema = new AvroSchema(Trade.schemaJson)
  private val guid = "12345678-1234-5678-9abc-123456789abc"
  private val bufferKinds = Vector("heap", "slice", "read-only", "direct")

  private def headerName(key: Boolean): String = if key then "__key_schema_id" else "__value_schema_id"
  private def subject(key: Boolean): String = if key then "trades-key" else "trades-value"

  private def guidBytes(value: String): Array[Byte] =
    val uuid = UUID.fromString(value)
    ByteBuffer.allocate(17).put(1.toByte).putLong(uuid.getMostSignificantBits)
      .putLong(uuid.getLeastSignificantBits).array()

  private def headers(key: Boolean, value: String = guid): RecordHeaders =
    val result = new RecordHeaders()
    result.add(headerName(key), guidBytes(value))
    result

  private def frame(id: Int, body: Array[Byte]): Array[Byte] =
    Array[Byte](0, (id >>> 24).toByte, (id >>> 16).toByte, (id >>> 8).toByte, id.toByte) ++ body

  private def buffer(bytes: Array[Byte], kind: String): ByteBuffer =
    val surrounded = Array[Byte](11, 22, 33) ++ bytes ++ Array[Byte](44, 55)
    val (result, start) = kind match
      case "heap" => (ByteBuffer.wrap(surrounded), 3)
      case "slice" =>
        val outer = ByteBuffer.wrap(surrounded)
        outer.position(1)
        (outer.slice(), 2)
      case "read-only" => (ByteBuffer.wrap(surrounded).asReadOnlyBuffer(), 3)
      case "direct" =>
        val direct = ByteBuffer.allocateDirect(surrounded.length)
        direct.put(surrounded)
        direct.flip()
        (direct, 3)
    result.position(start)
    result.limit(start + bytes.length)
    result.order(ByteOrder.LITTLE_ENDIAN)
    result.mark()
    result

  private def writer(key: Boolean, client: SchemaRegistryClient, registering: Boolean = true,
      codec: AvroCodec[Trade] = Trade.codec): RegistrySerializer[Trade] =
    val settings = SerializerSettings(autoRegisterSchemas = registering,
      schemaIdPlacement = SchemaIdPlacement.HeaderGuid)
    if key then RegistrySerializer.forKey(codec, client, settings)
    else RegistrySerializer.forValue(codec, client, settings)

  private def reader(key: Boolean, client: SchemaRegistryClient,
      settings: DeserializerSettings = DeserializerSettings(),
      codec: AvroCodec[Trade] = Trade.codec): RegistryDeserializer[Trade] =
    if key then RegistryDeserializer.forKey(codec, client, settings)
    else RegistryDeserializer.forValue(codec, client, settings)

  private def config(key: Boolean, registering: Boolean = true, useHeader: Boolean = false)
      : java.util.Map[String, AnyRef] =
    val values = Map[String, AnyRef](
      "schema.registry.url" -> "mock://guid-compatibility",
      "auto.register.schemas" -> Boolean.box(registering),
      "key.subject.name.strategy" -> classOf[TopicNameStrategy].getName,
      "value.subject.name.strategy" -> classOf[TopicNameStrategy].getName
    )
    (if useHeader then values.updated(
      (if key then "key" else "value") + ".schema.id.serializer", classOf[HeaderSchemaIdSerializer].getName)
    else values).asJava

  private def genericTrade: GenericData.Record =
    val value = new GenericData.Record(schema.rawSchema())
    value.put("id", trade.id)
    value.put("symbol", trade.symbol)
    value.put("price", trade.price)
    value.put("quantities", trade.quantities.map(Int.box).asJava)
    value

  private def assertGenericTrade(value: Any): Unit =
    val record = value.asInstanceOf[GenericData.Record]
    assertEquals(record.get("id").asInstanceOf[Long], trade.id)
    assertEquals(record.get("symbol").toString, trade.symbol)
    assertEquals(record.get("price").asInstanceOf[Double], trade.price)
    assertEquals(record.get("quantities").asInstanceOf[java.util.Collection[Integer]]
      .asScala.iterator.map(_.intValue()).toVector, trade.quantities)

  private class GuidClient extends MockSchemaRegistryClient:
    val guidFetches = new AtomicInteger()
    val idFetches = new AtomicInteger()
    override def getSchemaByGuid(value: String, format: String): ParsedSchema =
      assertEquals(value, guid)
      assertEquals(format, null)
      guidFetches.incrementAndGet()
      schema
    override def getSchemaEntityBySubjectAndId(subject: String, id: Int): RegistrySchema =
      idFetches.incrementAndGet()
      new RegistrySchema(subject, 1, id, schema)

  test("GUID header writes and reads interoperate with Confluent for both roles and selection modes") {
    for
      key <- Vector(false, true)
      registering <- Vector(false, true)
      codec <- Vector[AvroCodec[Trade]](Trade.codec, Trade.stackSafeCodec)
    do
      val client = new MockSchemaRegistryClient()
      if !registering then client.register(subject(key), schema)
      val nativeWriter = writer(key, client, registering, codec)
      val nativeReader = reader(key, client, codec = codec)
      val confluentWriter = new KafkaAvroSerializer(client)
      confluentWriter.configure(config(key, registering, useHeader = true), key)
      val confluentReader = new KafkaAvroDeserializer(client)
      confluentReader.configure(config(key), key)
      try
        val nativeHeaders = new RecordHeaders()
        nativeHeaders.add("application", Array[Byte](7))
        nativeHeaders.add(headerName(!key), Array[Byte](99))
        val nativeBytes = nativeWriter.serialize("trades", nativeHeaders, trade)
        val confluentHeaders = new RecordHeaders()
        val confluentBytes = confluentWriter.serialize("trades", confluentHeaders, genericTrade)
        assertEquals(nativeBytes.toVector, codec.encode(trade).toVector)
        assertEquals(nativeBytes.toVector, confluentBytes.toVector)
        assertEquals(nativeHeaders.lastHeader(headerName(key)).value().toVector,
          confluentHeaders.lastHeader(headerName(key)).value().toVector)
        assertEquals(nativeHeaders.lastHeader(headerName(key)).value().length, 17)
        assertEquals(nativeHeaders.lastHeader(headerName(key)).value().head, 1.toByte)
        assertEquals(nativeHeaders.lastHeader("application").value().toVector, Vector[Byte](7))
        assertEquals(nativeHeaders.lastHeader(headerName(!key)).value().toVector, Vector[Byte](99))
        assertEquals(nativeReader.deserialize("trades", confluentHeaders, confluentBytes), trade)
        assertGenericTrade(confluentReader.deserialize("trades", nativeHeaders, nativeBytes))
      finally
        nativeWriter.close()
        nativeReader.close()
        confluentWriter.close()
        confluentReader.close()
  }

  test("one reader accepts interleaved classic and GUID messages from Confluent") {
    for key <- Vector(false, true) do
      val client = new MockSchemaRegistryClient()
      val classic = new KafkaAvroSerializer(client)
      classic.configure(config(key), key)
      val modern = new KafkaAvroSerializer(client)
      modern.configure(config(key, useHeader = true), key)
      val native = reader(key, client)
      try
        val modernHeaders = new RecordHeaders()
        val classicBytes = classic.serialize("trades", genericTrade)
        val modernBytes = modern.serialize("trades", modernHeaders, genericTrade)
        for _ <- 0 until 3 do
          assertEquals(native.deserialize("trades", classicBytes), trade)
          assertEquals(native.deserialize("trades", modernHeaders, modernBytes), trade)
          assertEquals(native.deserialize("trades", null, buffer(classicBytes, "direct")), trade)
          assertEquals(native.deserialize("trades", modernHeaders, buffer(modernBytes, "direct")), trade)
      finally
        native.close()
        classic.close()
        modern.close()
  }

  test("GUID buffer reads respect ranges and preserve cursor, mark and byte order") {
    val body = Trade.codec.encode(trade)
    for key <- Vector(false, true) do
      val client = new GuidClient()
      val native = reader(key, client)
      try
        for kind <- bufferKinds do
          val input = buffer(body, kind)
          val start = input.position()
          val end = input.limit()
          assertEquals(native.deserialize("trades", headers(key), input), trade)
          assertEquals(input.position(), start)
          assertEquals(input.limit(), end)
          assertEquals(input.order(), ByteOrder.LITTLE_ENDIAN)
          input.reset()
          assertEquals(input.position(), start)
          for badBody <- Vector(body.dropRight(1), body ++ Array[Byte](0)) do
            intercept[SerializationException](native.deserialize("trades", headers(key), buffer(badBody, kind)))
        assertEquals(client.guidFetches.get(), 1)
        assertEquals(client.idFetches.get(), 0)
      finally native.close()
  }

  test("GUID payload size limits count raw bytes and reject before lookup") {
    val body = Trade.codec.encode(trade)
    for kind <- bufferKinds do
      val client = new GuidClient()
      val limited = reader(false, client,
        DeserializerSettings(decodeLimits = DecodeLimits(maxInputBytes = Some(body.length - 1))))
      val exact = reader(false, client,
        DeserializerSettings(decodeLimits = DecodeLimits(maxInputBytes = Some(body.length))))
      try
        intercept[SerializationException](limited.deserialize("trades", headers(false), body))
        intercept[SerializationException](limited.deserialize("trades", headers(false), buffer(body, kind)))
        assertEquals(client.guidFetches.get(), 0)
        assertEquals(exact.deserialize("trades", headers(false), buffer(body, kind)), trade)
        for badBody <- Vector(body.dropRight(1), body ++ Array[Byte](0)) do
          intercept[SerializationException](exact.deserialize("trades", headers(false), buffer(badBody, kind)))
      finally
        limited.close()
        exact.close()
  }

  test("GUID framing permits zero-byte records with a zero-byte input budget") {
    val empty = new AvroCodec[Unit]:
      val schemaJson = """{"type":"record","name":"EmptyGuidRecord","fields":[]}"""
      def read(in: AvroInput): Unit = ()
      def write(value: Unit, out: AvroOutput): Unit = ()
    for key <- Vector(false, true) do
      val client = new MockSchemaRegistryClient()
      val writeSettings = SerializerSettings(autoRegisterSchemas = true,
        schemaIdPlacement = SchemaIdPlacement.HeaderGuid)
      val readSettings = DeserializerSettings(decodeLimits = DecodeLimits(maxInputBytes = Some(0)))
      val nativeWriter = if key then RegistrySerializer.forKey(empty, client, writeSettings)
        else RegistrySerializer.forValue(empty, client, writeSettings)
      val nativeReader = if key then RegistryDeserializer.forKey(empty, client, readSettings)
        else RegistryDeserializer.forValue(empty, client, readSettings)
      try
        val outputHeaders = new RecordHeaders()
        val body = nativeWriter.serialize("empty", outputHeaders, ())
        assertEquals(body.length, 0)
        assertEquals(outputHeaders.lastHeader(headerName(key)).value().length, 17)
        assertEquals(nativeReader.deserialize("empty", outputHeaders, body), ())
        for kind <- bufferKinds do
          val input = buffer(body, kind)
          val start = input.position()
          assertEquals(nativeReader.deserialize("empty", outputHeaders, input), ())
          assertEquals(input.position(), start)
          assertEquals(input.limit(), start)
          assertEquals(input.order(), ByteOrder.LITTLE_ENDIAN)
          input.reset()
      finally
        nativeWriter.close()
        nativeReader.close()
  }

  test("the last role header wins and a null last header falls back to classic framing") {
    for key <- Vector(false, true) do
      val client = new MockSchemaRegistryClient()
      val id = client.register(subject(key), schema)
      val registeredGuid = client.getGuid(subject(key), schema)
      val native = reader(key, client)
      val confluent = new KafkaAvroDeserializer(client)
      confluent.configure(config(key), key)
      val body = Trade.codec.encode(trade)
      val classic = frame(id, body)
      try
        val duplicate = new RecordHeaders()
        duplicate.add(headerName(key), Array[Byte](99))
        duplicate.add(headerName(key), guidBytes(registeredGuid))
        assertEquals(native.deserialize("trades", duplicate, body), trade)
        assertGenericTrade(confluent.deserialize("trades", duplicate, body))
        duplicate.add(headerName(key), null)
        assertEquals(native.deserialize("trades", duplicate, classic), trade)
        assertGenericTrade(confluent.deserialize("trades", duplicate, classic))
        for kind <- bufferKinds do
          assertEquals(native.deserialize("trades", duplicate, buffer(classic, kind)), trade)
        val otherRole = new RecordHeaders()
        otherRole.add(headerName(!key), Array[Byte](99))
        assertEquals(native.deserialize("trades", otherRole, classic), trade)
      finally
        native.close()
        confluent.close()
  }

  test("malformed nonnull GUID headers fail before lookup without falling back to a valid prefix") {
    val classic = frame(7, Trade.codec.encode(trade))
    val malformed = Vector(Array.emptyByteArray, guidBytes(guid).take(16), guidBytes(guid) ++ Array[Byte](0),
      guidBytes(guid).updated(0, 0.toByte), guidBytes(guid).updated(0, 2.toByte))
    for key <- Vector(false, true) do
      val client = new GuidClient()
      val native = reader(key, client)
      try
        for value <- malformed do
          val badHeaders = headers(key)
          badHeaders.add(headerName(key), value)
          intercept[SerializationException](native.deserialize("trades", badHeaders, classic))
          for kind <- bufferKinds do
            val input = buffer(classic, kind)
            val start = input.position()
            intercept[SerializationException](native.deserialize("trades", badHeaders, input))
            assertEquals(input.position(), start)
            input.reset()
        assertEquals(client.guidFetches.get(), 0)
        assertEquals(client.idFetches.get(), 0)
      finally native.close()
  }

  test("valid GUID headers take precedence over payload prefixes and numeric lookup policies") {
    val client = new GuidClient()
    val native = reader(false, client,
      DeserializerSettings(schemaLookup = SchemaLookup.TopicName(Some("team"))))
    try
      // A numeric lookup would need the topic to compute its qualified subject.
      assertEquals(native.deserialize(null, headers(false), Trade.codec.encode(trade)), trade)
      intercept[SerializationException](native.deserialize("trades", headers(false),
        frame(7, Trade.codec.encode(trade))))
      assertEquals(client.guidFetches.get(), 1)
      assertEquals(client.idFetches.get(), 0)
    finally native.close()
  }

  test("header writes append after stale values and never expose cached mutable header storage") {
    for key <- Vector(false, true) do
      val client = new MockSchemaRegistryClient()
      val native = writer(key, client)
      try
        val first = new RecordHeaders()
        first.add(headerName(key), Array[Byte](99))
        native.serialize("trades", first, trade)
        val expected = first.lastHeader(headerName(key)).value().clone()
        assertEquals(first.headers(headerName(key)).asScala.size, 2)
        native.serialize("trades", first, trade)
        assertEquals(first.lastHeader(headerName(key)).value().toVector, expected.toVector)
        val second = new RecordHeaders()
        native.serialize("trades", second, trade)
        assert(first.lastHeader(headerName(key)).value() ne second.lastHeader(headerName(key)).value())
        java.util.Arrays.fill(first.lastHeader(headerName(key)).value(), 0.toByte)
        assertEquals(second.lastHeader(headerName(key)).value().toVector, expected.toVector)
        val third = new RecordHeaders()
        native.serialize("trades", third, trade)
        assertEquals(third.lastHeader(headerName(key)).value().toVector, expected.toVector)
        native.serialize("trades", first, trade)
        assertEquals(first.lastHeader(headerName(key)).value().toVector, expected.toVector)
      finally native.close()
  }

  test("header mode uses only the registry response GUID and retries incomplete responses") {
    for registering <- Vector(false, true) do
      var responseGuid: String = null
      var calls = 0
      val client = new MockSchemaRegistryClient:
        override def getIdWithResponse(subject: String, parsed: ParsedSchema,
            normalize: Boolean): RegisterSchemaResponse =
          calls += 1
          val response = new RegisterSchemaResponse(7)
          response.setGuid(responseGuid)
          response
        override def registerWithResponse(subject: String, parsed: ParsedSchema,
            normalize: Boolean, propagate: Boolean): RegisterSchemaResponse =
          getIdWithResponse(subject, parsed, normalize)
        override def getSchemaEntityBySubjectAndId(subject: String, id: Int): RegistrySchema =
          throw new AssertionError("Unexpected ID lookup")
        override def getSchemaByGuid(guid: String, format: String): ParsedSchema =
          throw new AssertionError("Unexpected GUID lookup")
        override def getGuid(subject: String, parsed: ParsedSchema, normalize: Boolean): String =
          throw new AssertionError("Unexpected separate GUID lookup")
      val native = writer(false, client, registering)
      try
        intercept[SerializationException](native.serialize("trades", new RecordHeaders(), trade))
        responseGuid = "not-a-guid"
        intercept[SerializationException](native.serialize("trades", new RecordHeaders(), trade))
        // UUID.fromString accepts this abbreviation, but registry GUIDs must be canonical.
        responseGuid = "1-1-1-1-1"
        intercept[SerializationException](native.serialize("trades", new RecordHeaders(), trade))
        responseGuid = guid
        for _ <- 0 until 2 do
          val outputHeaders = new RecordHeaders()
          assertEquals(native.serialize("trades", outputHeaders, trade).toVector, Trade.codec.encode(trade).toVector)
          assertEquals(outputHeaders.lastHeader(headerName(false)).value().toVector, guidBytes(guid).toVector)
        assertEquals(calls, 4)
      finally native.close()
  }

  test("failed codec writes leave every supplied header unchanged") {
    val failure = new IllegalStateException("codec write failed after producing bytes")
    val failingCodec = new AvroCodec[Trade]:
      val schemaJson = Trade.schemaJson
      def read(in: AvroInput): Trade = Trade.codec.read(in)
      def write(value: Trade, out: AvroOutput): Unit =
        out.writeLong(value.id)
        throw failure
    for key <- Vector(false, true) do
      val client = new MockSchemaRegistryClient()
      val native = writer(key, client, codec = failingCodec)
      val supplied = new RecordHeaders()
      supplied.add("application", Array[Byte](7))
      supplied.add(headerName(!key), Array[Byte](88))
      supplied.add(headerName(key), Array[Byte](99))
      supplied.add(headerName(key), null)
      def snapshot = supplied.toArray.toVector.map(header =>
        (header.key(), Option(header.value()).map(_.toVector)))
      val before = snapshot
      try
        // Exercise both the initial registry lookup and the cached identity.
        for _ <- 0 until 2 do
          val error = intercept[SerializationException](native.serialize("trades", supplied, trade))
          assert(error.getCause eq failure)
          assertEquals(snapshot, before)
      finally native.close()
  }

  test("header writers require Headers but tombstones bypass headers and registry lookup") {
    intercept[IllegalArgumentException](SerializerSettings(schemaIdPlacement = null))
    for key <- Vector(false, true) do
      val client = new MockSchemaRegistryClient:
        override def registerWithResponse(subject: String, parsed: ParsedSchema,
            normalize: Boolean, propagate: Boolean): RegisterSchemaResponse =
          throw new AssertionError("Tombstones and missing headers must not reach the registry")
        override def getSchemaByGuid(guid: String, format: String): ParsedSchema =
          throw new AssertionError("Tombstones must not reach the registry")
      val nativeWriter = writer(key, client)
      val nativeReader = reader(key, client)
      val malformedHeaders = new RecordHeaders()
      malformedHeaders.add(headerName(key), Array[Byte](99))
      try
        intercept[SerializationException](nativeWriter.serialize("trades", trade))
        assertEquals(nativeWriter.serialize("trades", null: Trade), null)
        assertEquals(nativeWriter.serialize("trades", malformedHeaders, null), null)
        assertEquals(nativeReader.deserialize("trades", malformedHeaders, null: Array[Byte]), null)
        assertEquals(nativeReader.deserialize("trades", malformedHeaders, null: ByteBuffer), null)
        assertEquals(malformedHeaders.lastHeader(headerName(key)).value().toVector, Vector[Byte](99))
      finally
        nativeWriter.close()
        nativeReader.close()
  }

  test("GUID lookup rejects every attached rule phase and failed plans are not cached") {
    for phase <- Vector("migration", "domain", "encoding") do
      val rule = new Rule("check", null, RuleKind.CONDITION,
        if phase == "migration" then RuleMode.UPGRADE else RuleMode.WRITE,
        "CEL", null, null, "true", null, null, false)
      val one = java.util.List.of(rule)
      val none = java.util.List.of[Rule]()
      val rules = new RuleSet(if phase == "migration" then one else none,
        if phase == "domain" then one else none, if phase == "encoding" then one else none)
      var calls = 0
      val client = new MockSchemaRegistryClient:
        override def getSchemaByGuid(guid: String, format: String): ParsedSchema =
          calls += 1
          schema.copy(null, rules)
      val native = reader(false, client)
      try
        for _ <- 0 until 2 do
          val error = intercept[SerializationException] {
            native.deserialize("trades", headers(false), Trade.codec.encode(trade))
          }
          assert(error.getCause.getMessage.contains("rules are unsupported"))
        assertEquals(calls, 2)
      finally native.close()
  }

  test("concurrent GUID reads share one schema lookup and completed plan") {
    val pool = Executors.newFixedThreadPool(6)
    val ready = new CountDownLatch(6)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger()
    val client = new MockSchemaRegistryClient:
      override def getSchemaByGuid(guid: String, format: String): ParsedSchema =
        calls.incrementAndGet()
        entered.countDown()
        if !release.await(10, TimeUnit.SECONDS) then throw new AssertionError("GUID load was not released")
        schema
    val native = reader(false, client)
    val body = Trade.codec.encode(trade)
    try
      val jobs = Vector.fill(6) {
        pool.submit(new Callable[Trade]:
          override def call(): Trade =
            ready.countDown()
            if !ready.await(10, TimeUnit.SECONDS) then throw new AssertionError("Workers did not start")
            native.deserialize("trades", headers(false), body)
        )
      }
      val started = entered.await(10, TimeUnit.SECONDS)
      assert(started)
      release.countDown()
      jobs.foreach(job => assertEquals(job.get(10, TimeUnit.SECONDS), trade))
      assertEquals(calls.get(), 1)
      assertEquals(native.deserialize("trades", headers(false), body), trade)
      assertEquals(calls.get(), 1)
    finally
      release.countDown()
      pool.shutdownNow()
      val stopped = pool.awaitTermination(10, TimeUnit.SECONDS)
      native.close()
      assert(stopped)
  }
