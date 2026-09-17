package avrogen.fixtures

import avrogen.fixtures.unions.{UnionDecimal, UnionLogicals}
import avrogen.runtime.{TimeMicros, TimeMillis}
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import org.apache.avro.{Conversions, Schema}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

class UnionLogicalSuite extends munit.FunSuite:
  private lazy val schema = new Schema.Parser().parse(UnionLogicals.codec.schemaJson)
  private val time = LocalTime.ofNanoOfDay(12345000000L)

  private def generic(value: UnionLogicals): GenericRecord =
    def wireTime(value: TimeMillis | TimeMicros): AnyRef = value match
      case TimeMillis(value) => Int.box((value.toNanoOfDay / 1000000L).toInt)
      case TimeMicros(value) => Long.box(value.toNanoOfDay / 1000L)
    val record = new GenericData.Record(schema)
    record.put("time", wireTime(value.time))
    record.put("optional", value.optional.map(wireTime).orNull)
    val conversion = new Conversions.DecimalConversion()
    val branches = schema.getField("amount").schema.getTypes
    value.amount match
      case decimal: BigDecimal =>
        record.put("amount", conversion.toBytes(decimal.bigDecimal, branches.get(0), branches.get(0).getLogicalType))
      case UnionDecimal(decimal) =>
        record.put("amount", conversion.toFixed(decimal.bigDecimal, branches.get(1), branches.get(1).getLogicalType))
    record

  private def javaEncode(record: GenericRecord): Array[Byte] =
    val output = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(output, null)
    new GenericDatumWriter[GenericRecord](schema).write(record, encoder)
    encoder.flush()
    output.toByteArray

  test("ambiguous time branches retain units and nullable branch identity") {
    val cases = Vector(
      UnionLogicals(TimeMillis(time), None, BigDecimal("-1234.567")),
      UnionLogicals(TimeMicros(time), Some(TimeMillis(time)), UnionDecimal(BigDecimal("-987654.321"))),
      UnionLogicals(TimeMillis(time), Some(TimeMicros(time)), BigDecimal("0.000"))
    )
    cases.foreach { expected =>
      val encoded = UnionLogicals.codec.encode(expected)
      assertEquals(UnionLogicals.codec.decode(encoded), expected)
      assertEquals(UnionLogicals.codec.decode(javaEncode(generic(expected))), expected)
      val decoder = DecoderFactory.get().binaryDecoder(encoded, null)
      val actual = new GenericDatumReader[GenericRecord](schema).read(null, decoder)
      assertEquals(javaEncode(actual).toVector, javaEncode(generic(expected)).toVector)
      assert(decoder.isEnd)
    }
    assertEquals(UnionLogicals.codec.encode(cases(0)).head, 0.toByte)
    assertEquals(UnionLogicals.codec.encode(cases(1)).head, 2.toByte)
  }

  test("nullable wrappers and fixed logical factories are available to resolution") {
    val base = UnionLogicals(TimeMillis(time), Some(TimeMicros(time)), UnionDecimal(BigDecimal("12.345")))
    val constructed = UnionLogicals.codec.construct(Array(base.time, base.optional, base.amount))
    assertEquals(constructed, base)
    assertEquals(
      UnionLogicals.codec.namedCodec("avrogen.fixtures.unions.UnionDecimal").construct(Array(BigDecimal("12.345"))).asInstanceOf[UnionDecimal],
      UnionDecimal(BigDecimal("12.345"))
    )
  }
