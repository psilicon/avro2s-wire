package avrogen.fixtures

import avrogen.runtime.*
import avrogen.resolution.ResolvingReader
import avrogen.fixtures.evolution.v1.{Account as OldAccount, Status}
import avrogen.fixtures.evolution.v2.{Account as NewAccount, State, Stamp}
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericEnumSymbol, GenericFixed, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

class EvolutionSuite extends munit.FunSuite:
  private lazy val writerSchema = new Schema.Parser().parse(OldAccount.schemaJson)
  private lazy val readerSchema = new Schema.Parser().parse(NewAccount.schemaJson)
  private lazy val reader = new ResolvingReader(OldAccount.schemaJson, NewAccount.codec)

  private def old(choice: Option[Int | String], child: Option[OldAccount] = None): OldAccount =
    OldAccount(Int.MinValue, "λ account", Bytes.fromArray(Array.fill[Byte](1024)(42)),
      Status.LEGACY, Map("balance" -> -123456789L), choice, child)

  private def expected(value: OldAccount): NewAccount =
    NewAccount(value.name, value.id.toLong,
      value.status match
        case Status.ACTIVE => State.ACTIVE
        case Status.NEW => State.NEW
        case Status.LEGACY => State.UNKNOWN,
      value.attributes.view.mapValues(_.toDouble).toMap,
      value.choice.map {
        case n: Int => n.toLong
        case s: String => s
      }, value.child.map(expected), true, Vector("fresh"), Map("created" -> "by reader"),
      Stamp(Bytes.fromArray(Array[Byte](0, -1))))

  private def generic(value: OldAccount): GenericRecord =
    val record = new GenericData.Record(writerSchema)
    record.put("id", value.id)
    record.put("name", value.name)
    record.put("discarded", ByteBuffer.wrap(value.discarded.toArray))
    record.put("status", new GenericData.EnumSymbol(writerSchema.getField("status").schema, value.status.toString))
    record.put("attributes", value.attributes.map((k, v) => k -> Long.box(v)).asJava)
    record.put("choice", value.choice.map {
      case n: Int => Int.box(n)
      case s: String => s
    }.orNull)
    record.put("child", value.child.map(generic).orNull)
    record

  private def javaWrite(value: OldAccount): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out = EncoderFactory.get().binaryEncoder(bytes, null)
    new GenericDatumWriter[GenericRecord](writerSchema).write(generic(value), out)
    out.flush()
    bytes.toByteArray

  private def normalized(value: Any): Any = value match
    case null => null
    case r: GenericRecord => r.getSchema.getFields.asScala.map(f => f.name -> normalized(r.get(f.name))).toMap
    case f: GenericFixed => f.bytes().toVector
    case e: GenericEnumSymbol[?] => e.toString
    case s: CharSequence => s.toString
    case c: java.util.Collection[?] => c.asScala.iterator.map(normalized).toVector
    case m: java.util.Map[?, ?] => m.asScala.iterator.map((k, v) => k.toString -> normalized(v)).toMap
    case other => other

  test("generated codecs resolve aliases, reordering, promotions, enum defaults and recursive unions") {
    for choice <- Vector(None, Some(Int.MinValue), Some("selected string")) do
      val value = old(choice, Some(old(Some(42)).copy(status = Status.ACTIVE)))
      assertEquals(reader.decode(OldAccount.codec.encode(value)), expected(value))
      assertEquals(reader.decode(javaWrite(value)), expected(value))
  }

  test("resolved model agrees with independent Java schema resolution") {
    val value = old(Some(123), Some(old(None).copy(status = Status.NEW)))
    val bytes = javaWrite(value)
    val javaValue = new GenericDatumReader[GenericRecord](writerSchema, readerSchema)
      .read(null, DecoderFactory.get().binaryDecoder(bytes, null))
    val nativeValue = reader.decode(bytes)
    // Read our final model with the reader schema to compare every field/default,
    // including the original Avro field names and fixed byte defaults.
    val rewritten = NewAccount.codec.encode(nativeValue)
    val nativeAsGeneric = new GenericDatumReader[GenericRecord](readerSchema)
      .read(null, DecoderFactory.get().binaryDecoder(rewritten, null))
    assertEquals(normalized(nativeAsGeneric), normalized(javaValue))
  }

  test("one cached resolver reads concatenated records and enforces end-of-input") {
    val a = old(Some(1))
    val b = old(Some("second"))
    val bytes = OldAccount.codec.encode(a) ++ OldAccount.codec.encode(b)
    val input = new BinaryInput(bytes)
    assertEquals(reader.read(input), expected(a))
    assertEquals(reader.read(input), expected(b))
    input.requireEnd()
    intercept[AvroDecodingException](reader.decode(bytes))
  }

  test("resolution retains truncation and resource-limit checks while skipping fields") {
    val bytes = OldAccount.codec.encode(old(Some(1)))
    for end <- 0 until bytes.length do
      intercept[AvroDecodingException](reader.decode(bytes.take(end)))
    intercept[AvroDecodingException](reader.decode(bytes, DecodeLimits(maxBytesLength = 16)))
    val recursive = (1 to 5).foldLeft(old(None))((child, _) => old(None, Some(child)))
    intercept[AvroDecodingException](reader.decode(OldAccount.codec.encode(recursive), DecodeLimits(maxNestingDepth = 2)))
  }

  test("identical-schema resolution uses generated codecs normally") {
    val direct = new ResolvingReader(OldAccount.schemaJson, OldAccount.codec)
    val value = old(Some(123))
    assertEquals(direct.decode(OldAccount.codec.encode(value)), value)
  }
