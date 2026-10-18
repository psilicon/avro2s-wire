package avro2s.wire.fixtures

import avro2s.wire.fixtures.rootunions.*
import avro2s.wire.javabackend.{JavaAvroInput, JavaAvroOutput}
import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

final class RootUnionInteropSuite extends munit.FunSuite:
  private def javaEncode(schema: Schema, datum: Any): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    new GenericDatumWriter[Any](schema).write(datum, encoder)
    encoder.flush()
    out.toByteArray

  private def normalise(value: Any): Any = value match
    case null => null
    case record: GenericRecord => (record.getSchema.getFullName,
      record.getSchema.getFields.asScala.map(field => field.name -> normalise(record.get(field.name))).toMap)
    case buffer: ByteBuffer =>
      val duplicate = buffer.duplicate()
      val bytes = new Array[Byte](duplicate.remaining())
      duplicate.get(bytes)
      Bytes.fromArray(bytes)
    case values: java.util.Collection[?] => values.asScala.iterator.map(normalise).toVector
    case values: java.util.Map[?, ?] => values.asScala.iterator.map((key, entry) => key.toString -> normalise(entry)).toMap
    case text: CharSequence => text.toString
    case number: java.lang.Number => (number.getClass.getName, number.toString)
    case other => other

  private def check[A](codec: AvroCodec[A], value: A, datum: Any): Unit =
    val schema = new Schema.Parser().parse(codec.schemaJson)
    val encoded = codec.encode(value)
    val javaBytes = javaEncode(schema, datum)
    assertEquals(encoded.toSeq, javaBytes.toSeq)
    assertEquals(codec.decode(javaBytes), value)
    val decoder = DecoderFactory.get().binaryDecoder(encoded, null)
    assertEquals(normalise(new GenericDatumReader[Any](schema).read(null, decoder)), normalise(datum))
    assert(decoder.isEnd)
    val javaOut = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(javaOut, null)
    codec.write(value, new JavaAvroOutput(encoder))
    encoder.flush()
    assertEquals(javaOut.toByteArray.toSeq, encoded.toSeq)
    assertEquals(codec.read(new JavaAvroInput(DecoderFactory.get().binaryDecoder(encoded, null))), value)

  private def genericEvent(schema: Schema, value: RootEvents): GenericRecord =
    val branches = schema.getTypes.asScala
    value match
      case RootCreated(id) =>
        val record = new GenericData.Record(branches.find(_.getName == "RootCreated").get)
        record.put("id", id)
        record
      case RootCancelled(reason) =>
        val record = new GenericData.Record(branches.find(_.getName == "RootCancelled").get)
        record.put("reason", reason)
        record

  test("named root union branches interoperate with Java and retain their registered union schema") {
    val values: Vector[RootEvents] = Vector(RootCreated(42L), RootCancelled("closed"))
    val schema = new Schema.Parser().parse(RootEvents.schemaJson)
    assertEquals(schema.getType, Schema.Type.UNION)
    for codec <- Vector(RootEvents.codec, RootEvents.stackSafeCodec); value <- values do
      check(codec, value, genericEvent(schema, value))
      val expected = if value.isInstanceOf[RootCreated] then 0.toByte else 2.toByte
      assertEquals(codec.encode(value).head, expected)
      assertEquals(codec.namedCodec("avro2s.wire.fixtures.rootunions.RootCreated").execution, codec.execution)
  }

  test("a nullable root union encodes None as Avro null at its declared branch index") {
    val values: Vector[RootNullableEvents] = Vector(None, Some(RootCreated(-42L)), Some(RootCancelled("cancelled")))
    val schema = new Schema.Parser().parse(RootNullableEvents.schemaJson)
    for codec <- Vector(RootNullableEvents.codec, RootNullableEvents.stackSafeCodec); value <- values do
      check(codec, value, value.map(genericEvent(schema, _)).orNull)
    assertEquals(RootNullableEvents.codec.encode(None).toSeq, Seq(2.toByte))
  }

  test("primitive-only root unions distinguish boxed numeric branches and bytes") {
    val values: Vector[RootPrimitives] = Vector(None, Some("hello"), Some(-42), Some(Long.MinValue),
      Some(1.5f), Some(2.5d), Some(true), Some(Bytes.fromArray(Array[Byte](0, 1, -1))))
    for codec <- Vector(RootPrimitives.codec, RootPrimitives.stackSafeCodec); value <- values do
      val datum = value.map {
        case bytes: Bytes => ByteBuffer.wrap(bytes.toArray)
        case other => other.asInstanceOf[AnyRef]
      }.orNull
      check(codec, value, datum)
  }

  test("single-branch null-only and optional roots preserve their existing field representations") {
    for codec <- Vector(RootSingle.codec, RootSingle.stackSafeCodec) do check(codec, 42L, 42L)
    for codec <- Vector(RootNull.codec, RootNull.stackSafeCodec) do check[Null](codec, null, null)
    for codec <- Vector(RootOptional.codec, RootOptional.stackSafeCodec) do
      check[RootOptional](codec, Some("present"), "present")
      check[RootOptional](codec, None, null)
  }

  test("root collection branches keep nested union and collection representations") {
    val arrays: Vector[Vector[Option[Long | String]]] = Vector(Vector.empty, Vector(None, Some(7L), Some("seven")))
    val maps = Vector(Map.empty[String, Vector[Int]], Map("one" -> Vector(1, 2), "empty" -> Vector.empty[Int]))
    for codec <- Vector(RootContainers.codec, RootContainers.stackSafeCodec) do
      for value <- arrays do
        check[RootContainers](codec, value, value.map(_.map(_.asInstanceOf[AnyRef]).orNull).asJava)
      for value <- maps do
        val datum = new java.util.LinkedHashMap[String, java.util.List[Int]]()
        value.foreach((key, items) => datum.put(key, items.asJava))
        check[RootContainers](codec, value, datum)
  }

  test("root unions resolve reordered branches added fields and primitive promotions") {
    val original = new Schema.Parser().parse(RootEvents.schemaJson)
    val older = new Schema.Parser().parse("""[
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCancelled","fields":[
        {"name":"reason","type":"string"},{"name":"extra","type":"int"}
      ]},
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCreated","fields":[{"name":"id","type":"int"}]}
    ]""")
    val created = new GenericData.Record(older.getTypes.get(1))
    created.put("id", 12)
    val cancelled = new GenericData.Record(older.getTypes.get(0))
    cancelled.put("reason", "old")
    cancelled.put("extra", 42)
    for codec <- Vector(RootEvents.codec, RootEvents.stackSafeCodec) do
      val reader = new ResolvingReader(older.toString, codec)
      assertEquals(reader.decode(javaEncode(older, created)), RootCreated(12L))
      assertEquals(reader.decode(javaEncode(older, cancelled)), RootCancelled("old"))
      val schema = new Schema.Parser().parse("[\"int\",\"string\"]")
      val primitives = new ResolvingReader(schema.toString, RootPrimitives.stackSafeCodec)
      assertEquals(primitives.decode(javaEncode(schema, 42)), Some(42))
    assertEquals(original.getType, Schema.Type.UNION)
  }

  test("root unions reject invalid indexes truncated values and trailing data") {
    for codec <- Vector(RootEvents.codec, RootEvents.stackSafeCodec) do
      intercept[AvroDecodingException](codec.decode(Array[Byte](4)))
      intercept[AvroDecodingException](codec.decode(Array[Byte](1)))
      intercept[AvroDecodingException](codec.decode(Array[Byte](0)))
      intercept[AvroDecodingException](codec.decode(codec.encode(RootCreated(42L)) :+ 0.toByte))
  }

  test("a stack-safe root union traverses 100000 recursive records on a small stack") {
    val depth = 100000
    val value = (1 until depth).foldLeft(RootNode(None, 0))((tail, i) => RootNode(Some(tail), i))
    val codec = RootRecursive.stackSafeCodec
    val writerSchema = RootRecursive.schemaJson.replace("\"name\":\"RootNode\"", "\"doc\":\"writer\",\"name\":\"RootNode\"")
    assert(writerSchema != RootRecursive.schemaJson)
    val resolver = new ResolvingReader(writerSchema, codec)
    val failure = new AtomicReference[Throwable]()
    val thread = new Thread(null, () => {
      try
        val encoded = codec.encode(value)
        for result <- Vector(codec.decode(encoded), resolver.decode(encoded)) do
          var current = Option(result.asInstanceOf[RootNode])
          var expected = depth - 1
          while current.nonEmpty do
            assertEquals(current.get.value, expected)
            current = current.get.next
            expected -= 1
          assertEquals(expected, -1)
      catch case error: Throwable => failure.set(error)
    }, "wire-root-union-stack-safety", 256 * 1024L)
    thread.setDaemon(true)
    thread.start()
    thread.join(60000L)
    assert(!thread.isAlive, "Root union stack-safety test did not finish within 60 seconds")
    Option(failure.get()).foreach(error => throw error)
  }
