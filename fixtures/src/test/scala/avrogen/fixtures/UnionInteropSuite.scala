package avrogen.fixtures

import avrogen.fixtures.unions.*
import avrogen.interop.{JavaAvroInput, JavaAvroOutput}
import avrogen.runtime.{AvroDecodingException, Bytes}
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericEnumSymbol, GenericFixed, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

class UnionInteropSuite extends munit.FunSuite:
  private lazy val schema = new Schema.Parser().parse(UnionValues.codec.schemaJson)
  private def sample: UnionValues = UnionValues(
    "hello", 1.25d, None, Vector[Int | String](1, "two"), UnionLeft(3), 42, null, None
  )

  private def generic(value: UnionValues): GenericRecord =
    val record = new GenericData.Record(schema)
    record.put("simple", value.simple.asInstanceOf[AnyRef])
    record.put("numbers", value.numbers.asInstanceOf[AnyRef])
    record.put("nullable", value.nullable.map(_.asInstanceOf[AnyRef]).orNull)
    val containers: AnyRef = value.containers match
      case values: Vector[?] => values.asInstanceOf[Vector[Any]].map(_.asInstanceOf[AnyRef]).asJava
      case values: Map[?, ?] => values.asInstanceOf[Map[String, Option[Long | String]]]
        .map((key, entry) => key -> entry.map(_.asInstanceOf[AnyRef]).orNull).asJava
    record.put("containers", containers)
    val namedSchemas = schema.getField("named").schema.getTypes.asScala
    val named: AnyRef = value.named match
      case left: UnionLeft =>
        val item = new GenericData.Record(namedSchemas(0))
        item.put("id", left.id)
        item
      case right: UnionRight =>
        val item = new GenericData.Record(namedSchemas(1))
        item.put("text", right.text)
        item
      case flag: UnionFlag => new GenericData.EnumSymbol(namedSchemas(2), flag.toString)
      case fixed: UnionFixed => new GenericData.Fixed(namedSchemas(3), fixed.value.toArray)
      case bytes: Bytes => ByteBuffer.wrap(bytes.toArray)
    record.put("named", named)
    record.put("single", value.single)
    record.put("nullOnly", null)
    record.put("recursive", value.recursive.map {
      case nested: UnionValues => generic(nested)
      case text: String => text
    }.orNull)
    record

  private def javaEncode(record: GenericRecord): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    new GenericDatumWriter[GenericRecord](schema).write(record, encoder)
    encoder.flush()
    out.toByteArray

  private def normalise(value: Any): Any = value match
    case null => null
    case record: GenericRecord => (record.getSchema.getFullName,
      record.getSchema.getFields.asScala.map(field => field.name -> normalise(record.get(field.name))).toMap)
    case fixed: GenericFixed => (fixed.getSchema.getFullName, Bytes.fromArray(fixed.bytes()))
    case enumeration: GenericEnumSymbol[?] => (enumeration.getSchema.getFullName, enumeration.toString)
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

  private def check(value: UnionValues): Unit =
    val expected = generic(value)
    val nativeBytes = UnionValues.codec.encode(value)
    assertEquals(UnionValues.codec.decode(nativeBytes), value)
    assertEquals(UnionValues.codec.decode(javaEncode(expected)), value)
    val decoder = DecoderFactory.get().binaryDecoder(nativeBytes, null)
    val actual = new GenericDatumReader[GenericRecord](schema).read(null, decoder)
    // Keep exact numeric classes: Scala universal equality considers some
    // differently boxed numbers equal. Map iteration order is not significant.
    assertEquals(normalise(actual), normalise(expected))
    assert(decoder.isEnd)
    val output = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(output, null)
    UnionValues.codec.write(value, new JavaAvroOutput(encoder))
    encoder.flush()
    assertEquals(UnionValues.codec.decode(output.toByteArray), value)
    assertEquals(UnionValues.codec.read(new JavaAvroInput(DecoderFactory.get().binaryDecoder(nativeBytes, null))), value)

  test("every primitive and nominal union branch interoperates with Java Avro") {
    val values = Vector(
      sample,
      sample.copy(simple = 17),
      sample.copy(numbers = Long.MinValue),
      sample.copy(numbers = Int.MinValue),
      sample.copy(numbers = 1.25f),
      sample.copy(numbers = true),
      sample.copy(nullable = Some(123L)),
      sample.copy(nullable = Some("present")),
      sample.copy(named = UnionRight("right")),
      sample.copy(named = UnionFlag.B),
      sample.copy(named = UnionFixed(Bytes.fromArray(Array[Byte](1, 2)))),
      sample.copy(named = Bytes.fromArray(Array[Byte](3, 4, 5)))
    )
    values.foreach(check)
  }

  test("union collection branches retain their element types and recursive alternatives") {
    check(sample.copy(containers = Vector.empty[Int | String]))
    check(sample.copy(containers = Map.empty[String, Option[Long | String]]))
    check(sample.copy(containers = Map("none" -> None, "long" -> Some(99L), "text" -> Some("ok"))))
    check(sample.copy(recursive = Some(sample.copy(recursive = Some("tail")))))
  }

  test("branch order belongs to the schema and invalid indices are rejected") {
    assertEquals(UnionOrder.codec.encode(UnionOrder(7)).head, 0.toByte)
    assertEquals(UnionOrder.codec.encode(UnionOrder("seven")).head, 2.toByte)
    assertEquals(UnionValues.codec.encode(sample).head, 0.toByte)
    assertEquals(UnionValues.codec.encode(sample.copy(simple = 7)).head, 2.toByte)
    intercept[AvroDecodingException](UnionOrder.codec.decode(Array[Byte](4)))
  }
