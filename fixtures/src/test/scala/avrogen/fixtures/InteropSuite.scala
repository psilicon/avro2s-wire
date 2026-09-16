package avrogen.fixtures

import avrogen.runtime.*
import avrogen.interop.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericEnumSymbol, GenericFixed, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

class InteropSuite extends munit.FunSuite:
  private lazy val schema = new Schema.Parser().parse(Envelope.codec.schemaJson)

  private def sample(i: Int): Envelope = Envelope(
    id = Long.MinValue + i,
    count = Int.MaxValue - i,
    active = i % 2 == 0,
    ratio = i.toFloat / 3,
    price = -i.toDouble / 7,
    label = s"Avro λ 日本語 🚀 $i\u0000",
    payload = Bytes.fromArray(Array[Byte](0, -1, i.toByte)),
    nothing = null,
    note = if i % 2 == 0 then Some("optional ✓") else None,
    reverse = if i % 2 == 0 then None else Some(Long.MaxValue),
    status = if i % 2 == 0 then Status.OPEN else Status.CLOSED,
    digest = Digest(Bytes.fromArray(Array[Byte](0, 1, -1, i.toByte))),
    samples = Vector(Int.MinValue, -1, 0, 1, Int.MaxValue),
    tags = Map("α" -> "β", "empty" -> ""),
    children = Vector(Child("first", 1.5), Child("second", -2.25)),
    matrix = Vector(Vector.empty, Vector(Long.MinValue, Long.MaxValue)),
    lookup = Map("some" -> Some(Bytes.fromArray(Array[Byte](4, 5))), "none" -> None)
  )

  private def generic(value: Envelope): GenericRecord =
    val record = new GenericData.Record(schema)
    record.put("id", value.id)
    record.put("count", value.count)
    record.put("active", value.active)
    record.put("ratio", value.ratio)
    record.put("price", value.price)
    record.put("label", value.label)
    record.put("payload", ByteBuffer.wrap(value.payload.toArray))
    record.put("nothing", null)
    record.put("note", value.note.orNull)
    record.put("reverse", value.reverse.map(Long.box).orNull)
    record.put("status", new GenericData.EnumSymbol(schema.getField("status").schema, value.status.toString))
    record.put("digest", new GenericData.Fixed(schema.getField("digest").schema, value.digest.value.toArray))
    record.put("samples", value.samples.map(Int.box).asJava)
    record.put("tags", value.tags.asJava)
    val childSchema = schema.getField("children").schema.getElementType
    record.put("children", value.children.map { child =>
      val r = new GenericData.Record(childSchema)
      r.put("name", child.name)
      r.put("score", child.score)
      r
    }.asJava)
    record.put("matrix", value.matrix.map(_.map(Long.box).asJava).asJava)
    record.put("lookup", value.lookup.map { case (k, v) => k -> v.map(b => ByteBuffer.wrap(b.toArray)).orNull }.asJava)
    record

  private def javaEncode(record: GenericRecord): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    new GenericDatumWriter[GenericRecord](record.getSchema).write(record, encoder)
    encoder.flush()
    out.toByteArray

  private def normalise(value: Any): Any = value match
    case null => null
    case r: GenericRecord => r.getSchema.getFields.asScala.map(f => f.name -> normalise(r.get(f.name))).toMap
    case f: GenericFixed => Bytes.fromArray(f.bytes())
    case e: GenericEnumSymbol[?] => e.toString
    case b: ByteBuffer =>
      val copy = b.duplicate()
      val bytes = new Array[Byte](copy.remaining())
      copy.get(bytes)
      Bytes.fromArray(bytes)
    case c: java.util.Collection[?] => c.asScala.iterator.map(normalise).toVector
    case m: java.util.Map[?, ?] => m.asScala.iterator.map((k, v) => k.toString -> normalise(v)).toMap
    case s: CharSequence => s.toString
    case other => other

  test("native writer interoperates with independent Java GenericDatumReader") {
    for i <- 0 until 50 do
      val value = sample(i)
      val encoded = Envelope.codec.encode(value)
      val decoder = DecoderFactory.get().binaryDecoder(encoded, null)
      val actual = new GenericDatumReader[GenericRecord](schema).read(null, decoder)
      assertEquals(normalise(actual), normalise(generic(value)))
      assert(decoder.isEnd)
  }

  test("native reader interoperates with independent Java GenericDatumWriter") {
    for i <- 0 until 50 do
      val expected = sample(i)
      assertEquals(Envelope.codec.decode(javaEncode(generic(expected))), expected)
  }

  test("same generated codec runs on Java binary primitives") {
    val value = sample(1)
    val out = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    Envelope.codec.write(value, new JavaAvroOutput(encoder))
    encoder.flush()
    assertEquals(Envelope.codec.decode(out.toByteArray), value)
    val input = new JavaAvroInput(DecoderFactory.get().binaryDecoder(Envelope.codec.encode(value), null))
    assertEquals(Envelope.codec.read(input), value)
  }

  test("empty arrays and maps end exactly once") {
    val value = Blocks(Vector.empty, Map.empty)
    assertEquals(Blocks.codec.encode(value).toVector, Vector[Byte](0, 0))
    assertEquals(Blocks.codec.decode(Array[Byte](0, 0)), value)
  }

  test("decode externally specified mixed positive and negative collection blocks") {
    // Array: sized block [1,-2], unsized block [3]. Map: sized block {x:y}.
    val bytes = Array[Byte](3, 4, 2, 3, 2, 6, 0, 1, 8, 2, 120, 2, 121, 0)
    val expected = Blocks(Vector(1L, -2L, 3L), Map("x" -> "y"))
    assertEquals(Blocks.codec.decode(bytes), expected)
    val javaSchema = new Schema.Parser().parse(Blocks.codec.schemaJson)
    val javaRecord = new GenericDatumReader[GenericRecord](javaSchema)
      .read(null, DecoderFactory.get().binaryDecoder(bytes, null))
    assertEquals(normalise(javaRecord), Map("values" -> Vector(1L, -2L, 3L), "labels" -> Map("x" -> "y")))
  }

  test("sequential reads consume precisely one record") {
    val a = sample(2)
    val b = sample(3)
    val input = new BinaryInput(Envelope.codec.encode(a) ++ Envelope.codec.encode(b))
    assertEquals(Envelope.codec.read(input), a)
    assertEquals(Envelope.codec.read(input), b)
    intercept[AvroDecodingException](Envelope.codec.decode(Envelope.codec.encode(a) ++ Array[Byte](0)))
  }

  test("recursive named types initialise and round trip") {
    val value = Node(1, Some(Node(2, Some(Node(3, None)))))
    assertEquals(Node.codec.decode(Node.codec.encode(value)), value)
  }

  test("recursive record depth is limited") {
    val value = (1 to 200).foldLeft(Node(0, None))((tail, i) => Node(i, Some(tail)))
    intercept[AvroDecodingException](Node.codec.decode(Node.codec.encode(value)))
  }

  test("truncated generated records fail at every byte boundary") {
    val bytes = Envelope.codec.encode(sample(4))
    for length <- 0 until bytes.length do
      intercept[AvroDecodingException](Envelope.codec.decode(bytes.take(length)))
  }

  test("invalid nullable and enum branch indices fail") {
    val output = new BinaryOutput()
    output.writeInt(42)
    output.writeIndex(2)
    intercept[AvroDecodingException](Node.codec.decode(output.toByteArray))
    intercept[AvroDecodingException](Status.codec.decode(Array[Byte](4)))
  }

  test("fixed values validate their size") {
    intercept[IllegalArgumentException](Digest(Bytes.empty))
  }

  test("nullable collection elements, optional collections and zero-byte values interoperate") {
    val value = EdgeCases(
      Vector(Some(1), None, Some(Int.MinValue)),
      Some(Vector(Map("x" -> 9L), Map.empty)),
      Vector(null, null),
      Empty()
    )
    assertEquals(EdgeCases.codec.decode(EdgeCases.codec.encode(value)), value)
    val s = new Schema.Parser().parse(EdgeCases.codec.schemaJson)
    val record = new GenericData.Record(s)
    record.put("options", java.util.Arrays.asList(Integer.valueOf(1), null, Integer.valueOf(Int.MinValue)))
    record.put("optionalMaps", java.util.Arrays.asList(Map("x" -> Long.box(9L)).asJava, Map.empty[String, java.lang.Long].asJava))
    record.put("nulls", java.util.Arrays.asList(null, null))
    record.put("empty", new GenericData.Record(s.getField("empty").schema))
    assertEquals(EdgeCases.codec.decode(javaEncode(record)), value)
    val decoded = new GenericDatumReader[GenericRecord](s)
      .read(null, DecoderFactory.get().binaryDecoder(EdgeCases.codec.encode(value), null))
    assertEquals(normalise(decoded), normalise(record))
    assertEquals(EdgeCases.codec.decode(EdgeCases.codec.encode(value.copy(optionalMaps = None))).optionalMaps, None)
  }
