package avro2s.wire.registry

import avro2s.wire.fixtures.Trade
import avro2s.wire.fixtures.unions.UnionFixed
import avro2s.wire.runtime.*
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import java.nio.{ByteBuffer, ByteOrder}
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders

final class RegistryBufferSuite extends munit.FunSuite:
  private val value = Trade(123L, "café-🚀", 42.5, Vector(10, 20))
  private val schemaId = 0x01020304
  private val kinds = Vector("heap", "slice", "read-only", "direct")

  private def frame(body: Array[Byte], id: Int = schemaId): Array[Byte] =
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

  private final class FetchClient(json: String = Trade.schemaJson) extends MockSchemaRegistryClient:
    var fetches = 0
    override def getSchemaById(id: Int): ParsedSchema =
      assertEquals(id, schemaId)
      fetches += 1
      new AvroSchema(json)

  test("buffer variants honor range, array offset and frame byte order without moving the cursor") {
    for
      codec <- Vector[AvroCodec[Trade]](Trade.codec, Trade.stackSafeCodec)
      kind <- kinds
    do
      val client = new FetchClient()
      val reader = RegistryDeserializer.forValue(codec, client)
      val input = buffer(frame(codec.encode(value)), kind)
      val start = input.position()
      val end = input.limit()
      assertEquals(reader.deserialize("trades", null, input), value)
      assertEquals(input.position(), start)
      assertEquals(input.limit(), end)
      assertEquals(input.order(), ByteOrder.LITTLE_ENDIAN)
      input.reset() // A discarded mark throws, even if position/limit were restored.
      assertEquals(input.position(), start)
      assertEquals(reader.deserialize("trades", null, input), value)
      assertEquals(client.fetches, 1)
      reader.close()
  }

  test("buffer framing and configured size checks run before registry lookup") {
    val encoded = frame(Trade.codec.encode(value))
    for kind <- kinds do
      val client = new FetchClient()
      val reader = RegistryDeserializer.forValue(Trade.codec, client)
      for invalid <- Vector(Array.emptyByteArray, encoded.take(4), encoded.updated(0, 1.toByte),
          frame(encoded.drop(5), -1)) do
        val input = buffer(invalid, kind)
        val start = input.position()
        intercept[SerializationException](reader.deserialize("trades", null, input))
        assertEquals(input.position(), start)
        input.reset()
      val limited = RegistryDeserializer.forValue(Trade.codec, client,
        DeserializerSettings(decodeLimits = DecodeLimits(maxInputBytes = Some(encoded.length - 6))))
      val error = intercept[SerializationException](limited.deserialize("trades", null, buffer(encoded, kind)))
      assert(error.getCause.getMessage.contains("maxInputBytes"))
      assertEquals(client.fetches, 0)
      val exact = RegistryDeserializer.forValue(Trade.codec, client,
        DeserializerSettings(decodeLimits = DecodeLimits(maxInputBytes = Some(encoded.length - 5))))
      assertEquals(exact.deserialize("trades", null, buffer(encoded, kind)), value)
      reader.close()
      limited.close()
      exact.close()
  }

  test("truncated and trailing buffer payloads cannot consume bytes beyond their limit") {
    val encoded = frame(Trade.codec.encode(value))
    for kind <- kinds do
      val client = new FetchClient()
      val reader = RegistryDeserializer.forValue(Trade.codec, client)
      val truncated = buffer(encoded, kind)
      truncated.limit(truncated.limit() - 1)
      intercept[SerializationException](reader.deserialize("trades", null, truncated))
      val trailing = buffer(encoded ++ Array[Byte](0), kind)
      intercept[SerializationException](reader.deserialize("trades", null, trailing))
      assertEquals(reader.deserialize("trades", null, buffer(encoded, kind)), value)
      assertEquals(client.fetches, 1)
      reader.close()
  }

  test("ByteBuffer tombstones, role headers and closed readers follow the array API") {
    val encoded = frame(Trade.codec.encode(value))
    for key <- Vector(true, false) do
      val client = new FetchClient()
      val reader = if key then RegistryDeserializer.forKey(Trade.codec, client)
        else RegistryDeserializer.forValue(Trade.codec, client)
      val own = new RecordHeaders().add(if key then "__key_schema_id" else "__value_schema_id", Array[Byte](1))
      val other = new RecordHeaders().add(if key then "__value_schema_id" else "__key_schema_id", Array[Byte](1))
      assertEquals(reader.deserialize("trades", own, null: ByteBuffer), null)
      for kind <- kinds do
        intercept[SerializationException](reader.deserialize("trades", own, buffer(encoded, kind)))
      assertEquals(client.fetches, 0)
      for kind <- kinds do
        assertEquals(reader.deserialize("trades", other, buffer(encoded, kind)), value)
      reader.close()
      intercept[SerializationException](reader.deserialize("trades", null, null: ByteBuffer))
      intercept[SerializationException](reader.deserialize("trades", null, buffer(encoded, "direct")))
      assertEquals(client.fetches, 1)
  }

  test("decoded fixed bytes own storage after reading a frame or array-backed buffer") {
    val expected = UnionFixed(Bytes.fromArray(Array[Byte](7, 8)))
    val reader = RegistryDeserializer.forValue(UnionFixed.codec, new FetchClient(UnionFixed.schemaJson))
    for useBuffer <- Vector(false, true) do
      val encoded = frame(UnionFixed.codec.encode(expected))
      val decoded = if useBuffer then reader.deserialize("fixed", null, ByteBuffer.wrap(encoded))
        else reader.deserialize("fixed", encoded)
      java.util.Arrays.fill(encoded, 0.toByte)
      assertEquals(decoded, expected)
    reader.close()
  }

  test("framed slice decoding resolves reordered fields, promotions and skipped strings") {
    val writer = """{"type":"record","name":"Trade","namespace":"avro2s.wire.fixtures","fields":[
      {"name":"symbol","type":"string"},{"name":"id","type":"int"},
      {"name":"unused","type":"string"},{"name":"price","type":"double"},
      {"name":"quantities","type":{"type":"array","items":"int"}}]}"""
    val out = new BinaryOutput()
    out.writeString(value.symbol)
    out.writeInt(value.id.toInt)
    out.writeString("skip café")
    val lastSkippedByte = out.size - 1
    out.writeDouble(value.price)
    out.writeIntArray(value.quantities)
    val body = out.toByteArray
    for codec <- Vector[AvroCodec[Trade]](Trade.codec, Trade.stackSafeCodec) do
      val client = new FetchClient(writer)
      val reader = RegistryDeserializer.forValue(codec, client)
      for kind <- kinds do
        assertEquals(reader.deserialize("trades", null, buffer(frame(body), kind)), value)
        // Corrupt the writer-only string's last continuation byte; skipped fields
        // still undergo UTF-8 validation through a bounded input.
        val invalid = body.clone()
        invalid(lastSkippedByte) = 0xff.toByte
        intercept[SerializationException](reader.deserialize("trades", null, buffer(frame(invalid), kind)))
      assertEquals(client.fetches, 1)
      reader.close()
  }

  test("five-byte frames permit empty record payloads with a zero-byte input budget") {
    val empty = new AvroCodec[Unit]:
      val schemaJson = """{"type":"record","name":"Empty","fields":[]}"""
      def read(in: AvroInput): Unit = ()
      def write(value: Unit, out: AvroOutput): Unit = ()
    val client = new FetchClient(empty.schemaJson)
    val reader = RegistryDeserializer.forValue(empty, client,
      DeserializerSettings(decodeLimits = DecodeLimits(maxInputBytes = Some(0))))
    for kind <- kinds do
      assertEquals(reader.deserialize("empty", null, buffer(frame(Array.emptyByteArray), kind)), ())
    reader.close()
  }

  test("returned frames remain independent across writes and failures") {
    for codec <- Vector[AvroCodec[Trade]](Trade.codec, Trade.stackSafeCodec) do
      val client = new MockSchemaRegistryClient()
      val writer = RegistrySerializer.forValue(codec, client, SerializerSettings(autoRegisterSchemas = true))
      val reader = RegistryDeserializer.forValue(codec, client)
      val first = writer.serialize("trades", value)
      val saved = first.clone()
      intercept[SerializationException](writer.serialize("trades", value.copy(symbol = "broken" + 0xd800.toChar)))
      val secondValue = value.copy(symbol = "second", quantities = Vector(1))
      val second = writer.serialize("trades", secondValue)
      assertEquals(first.toVector, saved.toVector)
      java.util.Arrays.fill(first, 0.toByte)
      assertEquals(reader.deserialize("trades", second), secondValue)
      assertEquals(reader.deserialize("trades", saved), value)
      writer.close()
      reader.close()
  }
