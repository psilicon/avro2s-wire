package avrogen.runtime

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.util.UUID
import munit.FunSuite

final class LogicalValuesSuite extends FunSuite:
  private def encoded(write: AvroOutput => Unit): Array[Byte] =
    val out = new BinaryOutput()
    write(out)
    out.toByteArray

  private def input(write: AvroOutput => Unit): BinaryInput = new BinaryInput(encoded(write))

  test("date covers signed Avro day extrema and rejects dates outside the int range") {
    List(Int.MinValue, -1, 0, 1, Int.MaxValue).foreach { days =>
      val date = LogicalValues.dateFromDays(days)
      assertEquals(date.toEpochDay, days.toLong)
      val in = input(out => LogicalValues.writeDate(date, out))
      assertEquals(LogicalValues.readDate(in), date)
      in.requireEnd()
    }
    intercept[IllegalArgumentException] {
      LogicalValues.writeDate(LocalDate.ofEpochDay(Int.MaxValue.toLong + 1L), new BinaryOutput())
    }
    intercept[IllegalArgumentException] {
      LogicalValues.writeDate(LocalDate.ofEpochDay(Int.MinValue.toLong - 1L), new BinaryOutput())
    }
  }

  test("times are bounded within a day and preserve their specified precision") {
    List(0, 1, 43200123, 86399999).foreach { millis =>
      val time = LogicalValues.timeFromMillis(millis)
      val in = input(out => LogicalValues.writeTimeMillis(time, out))
      assertEquals(in.readInt(), millis)
      assertEquals(LogicalValues.readTimeMillis(input(_.writeInt(millis))), time)
    }
    List(0L, 1L, 43200123456L, 86399999999L).foreach { micros =>
      val time = LogicalValues.timeFromMicros(micros)
      val in = input(out => LogicalValues.writeTimeMicros(time, out))
      assertEquals(in.readLong(), micros)
      assertEquals(LogicalValues.readTimeMicros(input(_.writeLong(micros))), time)
    }
    List(-1, 86400000, Int.MaxValue).foreach { millis =>
      intercept[AvroDecodingException](LogicalValues.timeFromMillis(millis))
    }
    List(-1L, 86400000000L, Long.MaxValue).foreach { micros =>
      intercept[AvroDecodingException](LogicalValues.timeFromMicros(micros))
    }
    intercept[IllegalArgumentException](LogicalValues.writeTimeMillis(LocalTime.ofNanoOfDay(1), new BinaryOutput()))
    intercept[IllegalArgumentException](LogicalValues.writeTimeMicros(LocalTime.ofNanoOfDay(1), new BinaryOutput()))
  }

  private val instantMappings: List[(Long => Instant, (Instant, AvroOutput) => Unit, AvroInput => Instant)] = List(
    (LogicalValues.instantFromMillis, LogicalValues.writeTimestampMillis, LogicalValues.readTimestampMillis),
    (LogicalValues.instantFromMicros, LogicalValues.writeTimestampMicros, LogicalValues.readTimestampMicros),
    (LogicalValues.instantFromNanos, LogicalValues.writeTimestampNanos, LogicalValues.readTimestampNanos)
  )

  private val localMappings: List[(Long => LocalDateTime, (LocalDateTime, AvroOutput) => Unit, AvroInput => LocalDateTime)] = List(
    (LogicalValues.localDateTimeFromMillis, LogicalValues.writeLocalTimestampMillis, LogicalValues.readLocalTimestampMillis),
    (LogicalValues.localDateTimeFromMicros, LogicalValues.writeLocalTimestampMicros, LogicalValues.readLocalTimestampMicros),
    (LogicalValues.localDateTimeFromNanos, LogicalValues.writeLocalTimestampNanos, LogicalValues.readLocalTimestampNanos)
  )

  test("all timestamp precisions round-trip negative epochs and long extrema without intermediate overflow") {
    val random = new java.util.Random(19461203L)
    val values = List(Long.MinValue, Long.MinValue + 1L, -1000000001L, -1000001L, -1001L, -1L,
      0L, 1L, 1001L, 1000001L, Long.MaxValue - 1L, Long.MaxValue) ++ List.fill(1000)(random.nextLong())
    instantMappings.foreach { (fromLong, write, read) =>
      values.foreach { raw =>
        val value = fromLong(raw)
        val in = input(out => write(value, out))
        assertEquals(in.readLong(), raw)
        in.requireEnd()
        assertEquals(read(input(_.writeLong(raw))), value)
      }
    }
    localMappings.foreach { (fromLong, write, read) =>
      values.foreach { raw =>
        val value = fromLong(raw)
        val in = input(out => write(value, out))
        assertEquals(in.readLong(), raw)
        in.requireEnd()
        assertEquals(read(input(_.writeLong(raw))), value)
      }
    }
  }

  test("negative timestamp fractions use floor division") {
    assertEquals(LogicalValues.instantFromMillis(-1L), Instant.parse("1969-12-31T23:59:59.999Z"))
    assertEquals(LogicalValues.instantFromMicros(-1L), Instant.parse("1969-12-31T23:59:59.999999Z"))
    assertEquals(LogicalValues.instantFromNanos(-1L), Instant.parse("1969-12-31T23:59:59.999999999Z"))
    assertEquals(LogicalValues.localDateTimeFromMillis(-1L), LocalDateTime.parse("1969-12-31T23:59:59.999"))
    assertEquals(LogicalValues.localDateTimeFromMicros(-1L), LocalDateTime.parse("1969-12-31T23:59:59.999999"))
    assertEquals(LogicalValues.localDateTimeFromNanos(-1L), LocalDateTime.parse("1969-12-31T23:59:59.999999999"))
    assertEquals(LogicalValues.instantFromMillis(Long.MinValue), Instant.ofEpochMilli(Long.MinValue))
    assertEquals(LogicalValues.instantFromMillis(Long.MaxValue), Instant.ofEpochMilli(Long.MaxValue))
  }

  test("timestamp writers reject exactly one unit beyond either wire boundary") {
    instantMappings.zip(List(1000000L, 1000L, 1L)).foreach { (mapping, quantum) =>
      val (fromLong, write, _) = mapping
      intercept[IllegalArgumentException](write(fromLong(Long.MaxValue).plusNanos(quantum), new BinaryOutput()))
      intercept[IllegalArgumentException](write(fromLong(Long.MinValue).minusNanos(quantum), new BinaryOutput()))
    }
    localMappings.zip(List(1000000L, 1000L, 1L)).foreach { (mapping, quantum) =>
      val (fromLong, write, _) = mapping
      intercept[IllegalArgumentException](write(fromLong(Long.MaxValue).plusNanos(quantum), new BinaryOutput()))
      intercept[IllegalArgumentException](write(fromLong(Long.MinValue).minusNanos(quantum), new BinaryOutput()))
    }
  }

  test("local timestamp conversion uses no system timezone") {
    val local = LocalDateTime.parse("2000-01-01T12:00:00")
    val in = input(out => LogicalValues.writeLocalTimestampMillis(local, out))
    assertEquals(in.readLong(), 946728000000L)
    assertEquals(LogicalValues.localDateTimeFromMillis(946728000000L), local)
    assertEquals(LogicalValues.instantFromMillis(946728000000L), local.toInstant(ZoneOffset.UTC))
  }

  test("timestamp writers reject excess precision and range before emitting bytes") {
    val nanos = Instant.ofEpochSecond(-1L, 999999999L)
    val localNanos = LocalDateTime.ofInstant(nanos, ZoneOffset.UTC)
    val failures: List[AvroOutput => Unit] = List(
      out => LogicalValues.writeTimestampMillis(nanos, out),
      out => LogicalValues.writeTimestampMicros(nanos, out),
      out => LogicalValues.writeLocalTimestampMillis(localNanos, out),
      out => LogicalValues.writeLocalTimestampMicros(localNanos, out),
      out => LogicalValues.writeTimestampMillis(Instant.ofEpochSecond(Instant.MAX.getEpochSecond), out),
      out => LogicalValues.writeTimestampMicros(Instant.MIN, out),
      out => LogicalValues.writeTimestampNanos(Instant.MAX, out),
      out => LogicalValues.writeLocalTimestampMillis(LocalDateTime.MAX.withNano(0), out),
      out => LogicalValues.writeLocalTimestampMicros(LocalDateTime.MIN, out),
      out => LogicalValues.writeLocalTimestampNanos(LocalDateTime.MAX, out)
    )
    failures.foreach { write =>
      val out = new BinaryOutput()
      out.writeInt(123)
      val before = out.toByteArray.toList
      intercept[IllegalArgumentException](write(out))
      assertEquals(out.toByteArray.toList, before)
    }
  }

  test("UUID strings require canonical grouping and accept uppercase hex") {
    val text = "00112233-4455-6677-8899-aabbccddeeff"
    val uuid = UUID.fromString(text)
    assertEquals(LogicalValues.uuidFromString(text.toUpperCase(java.util.Locale.ROOT)), uuid)
    val in = input(out => LogicalValues.writeUuid(uuid, out))
    assertEquals(in.readString(), text)
    assertEquals(LogicalValues.readUuid(input(_.writeString(text))), uuid)
    List("1-1-1-1-1", "00112233445566778899aabbccddeeff", "00112233-4455-6677-8899-aabbccddeezz", "").foreach { value =>
      intercept[AvroDecodingException](LogicalValues.uuidFromString(value))
    }
  }

  test("fixed UUID follows the RFC big-endian byte layout") {
    val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val expected = List(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
      0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff).map(_.toByte)
    val bytes = encoded(out => LogicalValues.writeFixedUuid(uuid, out))
    assertEquals(bytes.toList, expected)
    val in = new BinaryInput(bytes)
    assertEquals(LogicalValues.readFixedUuid(in), uuid)
    in.requireEnd()
    intercept[AvroDecodingException](LogicalValues.uuidFromFixed(Bytes.empty))
  }

  test("decimal uses signed big-endian unscaled integers and sign-extends fixed output") {
    List(BigDecimal("123.45") -> List(0x30, 0x39), BigDecimal("-123.45") -> List(0xcf, 0xc7)).foreach { (value, expected) =>
      val bytes = encoded(out => LogicalValues.writeDecimal(value, out, 6, 2))
      assertEquals(bytes.map(_ & 0xff).toList, 4 :: expected)
      assertEquals(LogicalValues.readDecimal(new BinaryInput(bytes), 6, 2), value)
      val fixed = encoded(out => LogicalValues.writeFixedDecimal(value, out, 4, 6, 2))
      val padding = if value.signum < 0 then List(0xff, 0xff) else List(0, 0)
      assertEquals(fixed.map(_ & 0xff).toList, padding ++ expected)
      assertEquals(LogicalValues.readFixedDecimal(new BinaryInput(fixed), 4, 6, 2), value)
    }
    List(127 -> List(0x7f), 128 -> List(0, 0x80), -128 -> List(0x80), -129 -> List(0xff, 0x7f)).foreach { (value, expected) =>
      val in = input(out => LogicalValues.writeDecimal(BigDecimal(value), out, 4, 0))
      assertEquals(in.readBytes().toArray.map(_ & 0xff).toList, expected)
    }
  }

  test("decimal precision exceeds the default decimal128 context without rounding") {
    val value = BigDecimal.exact("12345678901234567890123456789012345678901234567890.12345678")
    val bytes = encoded(out => LogicalValues.writeDecimal(value, out, 60, 8))
    val result = LogicalValues.readDecimal(new BinaryInput(bytes), 60, 8)
    assertEquals(result.bigDecimal, value.bigDecimal)
    assertEquals(result.scale, 8)
  }

  test("decimal rescaling is exact and precision and fixed capacity are enforced") {
    List("1.2", "1.2000").foreach { string =>
      val in = input(out => LogicalValues.writeDecimal(BigDecimal(string), out, 3, 2))
      val value = LogicalValues.readDecimal(in, 3, 2)
      assertEquals(value.bigDecimal.toPlainString, "1.20")
    }
    val failures: List[AvroOutput => Unit] = List(
      out => LogicalValues.writeDecimal(BigDecimal("1.234"), out, 4, 2),
      out => LogicalValues.writeDecimal(BigDecimal("123.45"), out, 4, 2),
      out => LogicalValues.writeFixedDecimal(BigDecimal(128), out, 1, 3, 0),
      out => LogicalValues.writeFixedDecimal(BigDecimal(-129), out, 1, 3, 0),
      out => LogicalValues.writeFixedDecimal(BigDecimal(1), out, 0, 1, 0),
      out => LogicalValues.writeDecimal(BigDecimal(1), out, 0, 0),
      out => LogicalValues.writeDecimal(BigDecimal(1), out, 1, 2)
    )
    failures.foreach { write =>
      val out = new BinaryOutput()
      intercept[IllegalArgumentException](write(out))
      assertEquals(out.size, 0)
    }
    intercept[AvroDecodingException](LogicalValues.decimalFromBytes(Bytes.empty, 3, 0))
    intercept[AvroDecodingException](LogicalValues.decimalFromBytes(Bytes.fromArray(Array[Byte](0x30, 0x39)), 4, 2))
  }

  test("duration is three unsigned little-endian integers without calendar normalization") {
    val value = AvroDuration(0x12345678L, 0x80000000L, 0xffffffffL)
    val bytes = encoded(out => LogicalValues.writeDuration(value, out))
    assertEquals(bytes.map(_ & 0xff).toList,
      List(0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0x80, 0xff, 0xff, 0xff, 0xff))
    val in = new BinaryInput(bytes)
    assertEquals(LogicalValues.readDuration(in), value)
    in.requireEnd()
    assertEquals(LogicalValues.durationFromBytes(Bytes.fromArray(new Array[Byte](12))), AvroDuration(0, 0, 0))
    intercept[IllegalArgumentException](AvroDuration(-1L, 0, 0))
    intercept[IllegalArgumentException](AvroDuration(0, 0x100000000L, 0))
    intercept[IllegalArgumentException](AvroDuration(0, 0, 0x100000000L))
    intercept[AvroDecodingException](LogicalValues.durationFromBytes(Bytes.fromArray(new Array[Byte](11))))
  }
