package avro2s.wire.fixtures

import avro2s.wire.runtime.*
import avro2s.wire.fixtures.logical.*
import avro2s.wire.resolution.ResolvingReader
import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.util.UUID
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

class LogicalInteropSuite extends munit.FunSuite:
  private lazy val schema = new Schema.Parser().parse(LogicalRecord.schemaJson)
  private val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
  private val sample = LogicalRecord(
    LocalDate.of(1960, 2, 29), LocalTime.of(23, 59, 59, 123000000), LocalTime.of(0, 0, 0, 123000),
    Instant.ofEpochSecond(-1, 123000000), Instant.ofEpochSecond(-1, 123456000), Instant.ofEpochSecond(-1, 123456789),
    LocalDateTime.of(1969, 12, 31, 23, 59, 59, 123000000),
    LocalDateTime.of(1969, 12, 31, 23, 59, 59, 123456000),
    LocalDateTime.of(1969, 12, 31, 23, 59, 59, 123456789),
    uuid, WireUuid(uuid), BigDecimal("-12345678.90"), WireAmount(BigDecimal("-12345678.9012")),
    WireDuration(AvroDuration(0xffffffffL, 2L, 4000000000L)), uuid)

  // Independent primitive representations for Java's writer; do not call native logical helpers.
  private def generic(value: LogicalRecord): GenericRecord =
    val record = new GenericData.Record(schema)
    def instant(v: Instant, units: Long): Long = v.getEpochSecond * units + v.getNano / (1000000000L / units)
    def local(v: LocalDateTime, units: Long): Long = v.toEpochSecond(ZoneOffset.UTC) * units + v.getNano / (1000000000L / units)
    def uuidBytes(v: UUID): Array[Byte] =
      ByteBuffer.allocate(16).putLong(v.getMostSignificantBits).putLong(v.getLeastSignificantBits).array()
    record.put("day", value.day.toEpochDay.toInt)
    record.put("timeMs", (value.timeMs.toNanoOfDay / 1000000L).toInt)
    record.put("timeUs", value.timeUs.toNanoOfDay / 1000L)
    record.put("timestampMs", instant(value.timestampMs, 1000L))
    record.put("timestampUs", instant(value.timestampUs, 1000000L))
    record.put("timestampNs", instant(value.timestampNs, 1000000000L))
    record.put("localMs", local(value.localMs, 1000L))
    record.put("localUs", local(value.localUs, 1000000L))
    record.put("localNs", local(value.localNs, 1000000000L))
    record.put("uuid", value.uuid.toString)
    record.put("binaryUuid", new GenericData.Fixed(schema.getField("binaryUuid").schema, uuidBytes(value.binaryUuid.value)))
    record.put("amount", ByteBuffer.wrap(value.amount.bigDecimal.setScale(2).unscaledValue.toByteArray))
    val fixedDecimal = ByteBuffer.allocate(8).putLong(value.fixedAmount.value.bigDecimal.setScale(4).unscaledValue.longValueExact()).array()
    record.put("fixedAmount", new GenericData.Fixed(schema.getField("fixedAmount").schema, fixedDecimal))
    val duration = value.duration.value
    val durationBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(duration.months.toInt).putInt(duration.days.toInt).putInt(duration.millis.toInt).array()
    record.put("duration", new GenericData.Fixed(schema.getField("duration").schema, durationBytes))
    record.put("identifier", value.identifier match
      case id: UUID => id.toString
      case id: WireUuid => new GenericData.Fixed(schema.getField("binaryUuid").schema, uuidBytes(id.value)))
    record

  private def javaWrite(value: GenericRecord): Array[Byte] =
    val buffer = new ByteArrayOutputStream()
    val out = EncoderFactory.get().binaryEncoder(buffer, null)
    new GenericDatumWriter[GenericRecord](schema).write(value, out)
    out.flush()
    buffer.toByteArray

  test("logical models cross-read and cross-write every supported mapping with Java Avro") {
    for value <- Vector(sample, sample.copy(identifier = WireUuid(uuid))) do
      val referenceBytes = javaWrite(generic(value))
      assertEquals(LogicalRecord.codec.encode(value).toVector, referenceBytes.toVector)
      assertEquals(LogicalRecord.codec.decode(referenceBytes), value)
      val decoded = new GenericDatumReader[GenericRecord](schema)
        .read(null, DecoderFactory.get().binaryDecoder(LogicalRecord.codec.encode(value), null))
      assertEquals(javaWrite(decoded).toVector, referenceBytes.toVector)
  }

  test("schema resolution constructs logical values and nominal fixed wrappers") {
    // Metadata-only difference exercises the native resolver without its exact-JSON shortcut.
    val writerJson = LogicalRecord.schemaJson.replace("\"fields\":", "\"doc\":\"writer metadata\",\"fields\":")
    val reader = new ResolvingReader(writerJson, LogicalRecord.codec)
    for value <- Vector(sample, sample.copy(identifier = WireUuid(uuid))) do
      assertEquals(reader.decode(javaWrite(generic(value))), value)
  }

  test("logical writes reject precision loss instead of silently changing the value") {
    intercept[IllegalArgumentException](LogicalRecord.codec.encode(sample.copy(timeMs = LocalTime.ofNanoOfDay(1))))
    intercept[IllegalArgumentException](LogicalRecord.codec.encode(sample.copy(timestampUs = Instant.ofEpochSecond(0, 1))))
    intercept[IllegalArgumentException](LogicalRecord.codec.encode(sample.copy(amount = BigDecimal("1.001"))))
    intercept[IllegalArgumentException](LogicalRecord.codec.encode(sample.copy(fixedAmount = WireAmount(BigDecimal("123456789012345.6789")))))
  }
