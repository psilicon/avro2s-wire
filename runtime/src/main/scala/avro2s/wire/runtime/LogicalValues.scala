package avro2s.wire.runtime

import java.math.{BigDecimal as JavaDecimal, BigInteger, RoundingMode}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.util.UUID

/** Preserve branch identity when a union contains both Avro time precisions. */
final case class TimeMillis(value: LocalTime)
final case class TimeMicros(value: LocalTime)

/** Avro duration components are independent unsigned 32-bit quantities.
  * Months and days deliberately are not converted into a fixed elapsed time.
  */
final case class AvroDuration(months: Long, days: Long, millis: Long):
  require(months >= 0L && months <= 0xffffffffL, "Duration months must fit unsigned 32 bits")
  require(days >= 0L && days <= 0xffffffffL, "Duration days must fit unsigned 32 bits")
  require(millis >= 0L && millis <= 0xffffffffL, "Duration millis must fit unsigned 32 bits")

/** Semantic mappings for Avro logical values, independent of Apache Avro.
  *
  * Writes reject overflow and loss of precision: sub-millisecond/microsecond
  * times are not silently truncated, and decimals are rescaled without rounding.
  * Invalid application values/configuration throw IllegalArgumentException;
  * malformed decoded values throw AvroDecodingException.
  */
object LogicalValues:
  private val NanosPerSecond = 1000000000L
  private val NanosPerMilli = 1000000L
  private val NanosPerMicro = 1000L

  private def invalid(message: String): Nothing = throw new AvroDecodingException(message)

  def dateFromDays(value: Int): LocalDate = LocalDate.ofEpochDay(value.toLong)
  def timeFromMillis(value: Int): LocalTime =
    if value < 0 || value >= 86400000 then invalid(s"Invalid time-millis value: $value")
    LocalTime.ofNanoOfDay(value.toLong * NanosPerMilli)
  def timeFromMicros(value: Long): LocalTime =
    if value < 0L || value >= 86400000000L then invalid(s"Invalid time-micros value: $value")
    LocalTime.ofNanoOfDay(value * NanosPerMicro)

  def instantFromMillis(value: Long): Instant = instant(value, 1000L, NanosPerMilli)
  def instantFromMicros(value: Long): Instant = instant(value, 1000000L, NanosPerMicro)
  def instantFromNanos(value: Long): Instant = instant(value, NanosPerSecond, 1L)
  def localDateTimeFromMillis(value: Long): LocalDateTime = local(value, 1000L, NanosPerMilli)
  def localDateTimeFromMicros(value: Long): LocalDateTime = local(value, 1000000L, NanosPerMicro)
  def localDateTimeFromNanos(value: Long): LocalDateTime = local(value, NanosPerSecond, 1L)

  private def instant(value: Long, units: Long, nanosPerUnit: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(value, units), Math.floorMod(value, units) * nanosPerUnit)

  private def local(value: Long, units: Long, nanosPerUnit: Long): LocalDateTime =
    LocalDateTime.ofEpochSecond(Math.floorDiv(value, units),
      (Math.floorMod(value, units) * nanosPerUnit).toInt, ZoneOffset.UTC)

  private def exactEpoch(seconds: Long, nanos: Int, units: Long, nanosPerUnit: Long, kind: String): Long =
    require(nanos % nanosPerUnit == 0L, s"$kind cannot represent sub-unit nanoseconds")
    val fraction = nanos.toLong / nanosPerUnit
    try
      // floor-divided negative timestamps can have a product below Long.MinValue
      // even when adding their positive fractional part brings them into range.
      if seconds < 0L then Math.addExact(Math.multiplyExact(seconds + 1L, units), fraction - units)
      else Math.addExact(Math.multiplyExact(seconds, units), fraction)
    catch
      case _: ArithmeticException => throw new IllegalArgumentException(s"$kind exceeds the Avro long range")

  def readDate(in: AvroInput): LocalDate = dateFromDays(in.readInt())
  def writeDate(value: LocalDate, out: AvroOutput): Unit =
    val days = value.toEpochDay
    require(days >= Int.MinValue.toLong && days <= Int.MaxValue.toLong, "date exceeds the Avro int range")
    out.writeInt(days.toInt)

  def readTimeMillis(in: AvroInput): LocalTime = timeFromMillis(in.readInt())
  def writeTimeMillis(value: LocalTime, out: AvroOutput): Unit =
    val nanos = value.toNanoOfDay
    require(nanos % NanosPerMilli == 0L, "time-millis cannot represent sub-millisecond nanoseconds")
    out.writeInt((nanos / NanosPerMilli).toInt)

  def readTimeMicros(in: AvroInput): LocalTime = timeFromMicros(in.readLong())
  def writeTimeMicros(value: LocalTime, out: AvroOutput): Unit =
    val nanos = value.toNanoOfDay
    require(nanos % NanosPerMicro == 0L, "time-micros cannot represent sub-microsecond nanoseconds")
    out.writeLong(nanos / NanosPerMicro)

  def readTimestampMillis(in: AvroInput): Instant = instantFromMillis(in.readLong())
  def writeTimestampMillis(value: Instant, out: AvroOutput): Unit =
    out.writeLong(exactEpoch(value.getEpochSecond, value.getNano, 1000L, NanosPerMilli, "timestamp-millis"))
  def readTimestampMicros(in: AvroInput): Instant = instantFromMicros(in.readLong())
  def writeTimestampMicros(value: Instant, out: AvroOutput): Unit =
    out.writeLong(exactEpoch(value.getEpochSecond, value.getNano, 1000000L, NanosPerMicro, "timestamp-micros"))
  def readTimestampNanos(in: AvroInput): Instant = instantFromNanos(in.readLong())
  def writeTimestampNanos(value: Instant, out: AvroOutput): Unit =
    out.writeLong(exactEpoch(value.getEpochSecond, value.getNano, NanosPerSecond, 1L, "timestamp-nanos"))

  def readLocalTimestampMillis(in: AvroInput): LocalDateTime = localDateTimeFromMillis(in.readLong())
  def writeLocalTimestampMillis(value: LocalDateTime, out: AvroOutput): Unit =
    out.writeLong(exactEpoch(value.toEpochSecond(ZoneOffset.UTC), value.getNano, 1000L, NanosPerMilli, "local-timestamp-millis"))
  def readLocalTimestampMicros(in: AvroInput): LocalDateTime = localDateTimeFromMicros(in.readLong())
  def writeLocalTimestampMicros(value: LocalDateTime, out: AvroOutput): Unit =
    out.writeLong(exactEpoch(value.toEpochSecond(ZoneOffset.UTC), value.getNano, 1000000L, NanosPerMicro, "local-timestamp-micros"))
  def readLocalTimestampNanos(in: AvroInput): LocalDateTime = localDateTimeFromNanos(in.readLong())
  def writeLocalTimestampNanos(value: LocalDateTime, out: AvroOutput): Unit =
    out.writeLong(exactEpoch(value.toEpochSecond(ZoneOffset.UTC), value.getNano, NanosPerSecond, 1L, "local-timestamp-nanos"))

  def uuidFromString(value: String): UUID =
    if value == null || value.length != 36 then invalid("UUID must have the canonical 8-4-4-4-12 hexadecimal form")
    if value.charAt(8) != '-' || value.charAt(13) != '-' || value.charAt(18) != '-' || value.charAt(23) != '-' then
      invalid("Invalid UUID string")
    // Validate and accumulate each ASCII hex digit once. UUID.fromString is
    // intentionally more permissive, so validating then calling it would parse
    // the same canonical representation twice.
    val most = (uuidHex(value, 0, 8) << 32) | (uuidHex(value, 9, 13) << 16) | uuidHex(value, 14, 18)
    val least = (uuidHex(value, 19, 23) << 48) | uuidHex(value, 24, 36)
    new UUID(most, least)

  private def uuidHex(value: String, start: Int, end: Int): Long =
    var result = 0L
    var index = start
    while index < end do
      val ch = value.charAt(index).toInt
      val digit = if ch >= '0' && ch <= '9' then ch - '0'
        else
          val lower = ch | 0x20
          if lower >= 'a' && lower <= 'f' then lower - 'a' + 10
          else invalid("Invalid UUID string")
      result = (result << 4) | digit.toLong
      index += 1
    result

  def uuidFromFixed(value: Bytes): UUID =
    if value.size != 16 then invalid("A fixed UUID requires exactly 16 bytes")
    val bytes = value.unsafeArray
    new UUID(bigEndianLong(bytes, 0), bigEndianLong(bytes, 8))

  private def bigEndianLong(bytes: Array[Byte], offset: Int): Long =
    var result = 0L
    var index = 0
    while index < 8 do
      result = (result << 8) | (bytes(offset + index) & 0xffL)
      index += 1
    result

  private def putBigEndianLong(bytes: Array[Byte], offset: Int, value: Long): Unit =
    var index = 0
    while index < 8 do
      bytes(offset + index) = (value >>> (56 - index * 8)).toByte
      index += 1

  def readUuid(in: AvroInput): UUID = uuidFromString(in.readString())
  def writeUuid(value: UUID, out: AvroOutput): Unit = out.writeString(value.toString)
  def readFixedUuid(in: AvroInput): UUID = uuidFromFixed(in.readFixed(16))
  def writeFixedUuid(value: UUID, out: AvroOutput): Unit =
    val bytes = new Array[Byte](16)
    putBigEndianLong(bytes, 0, value.getMostSignificantBits)
    putBigEndianLong(bytes, 8, value.getLeastSignificantBits)
    out.writeFixed(Bytes.unsafeWrap(bytes))

  private def decimalParameters(precision: Int, scale: Int): Unit =
    require(precision > 0, "Decimal precision must be positive")
    require(scale >= 0 && scale <= precision, "Decimal scale must be between zero and precision")

  def decimalFromBytes(value: Bytes, precision: Int, scale: Int): BigDecimal =
    BigDecimal.exact(javaDecimalFromBytes(value, precision, scale))

  def javaDecimalFromBytes(value: Bytes, precision: Int, scale: Int): JavaDecimal =
    decimalParameters(precision, scale)
    if value.size == 0 then invalid("Decimal bytes must contain a two's-complement integer")
    val decimal = new JavaDecimal(new BigInteger(value.unsafeArray), scale)
    if decimal.precision() > precision then invalid(s"Decimal exceeds precision $precision")
    decimal

  private def decimalBytes(value: JavaDecimal, precision: Int, scale: Int): Array[Byte] =
    decimalParameters(precision, scale)
    val decimal =
      try value.setScale(scale, RoundingMode.UNNECESSARY)
      catch
        case _: ArithmeticException => throw new IllegalArgumentException(s"Decimal cannot be represented exactly at scale $scale")
    require(decimal.precision() <= precision, s"Decimal exceeds precision $precision")
    decimal.unscaledValue().toByteArray

  def readDecimal(in: AvroInput, precision: Int, scale: Int): BigDecimal =
    decimalFromBytes(in.readBytes(), precision, scale)
  def writeDecimal(value: BigDecimal, out: AvroOutput, precision: Int, scale: Int): Unit =
    writeJavaDecimal(value.bigDecimal, out, precision, scale)

  def readJavaDecimal(in: AvroInput, precision: Int, scale: Int): JavaDecimal =
    javaDecimalFromBytes(in.readBytes(), precision, scale)
  def writeJavaDecimal(value: JavaDecimal, out: AvroOutput, precision: Int, scale: Int): Unit =
    out.writeBytes(Bytes.unsafeWrap(decimalBytes(value, precision, scale)))

  def readFixedDecimal(in: AvroInput, size: Int, precision: Int, scale: Int): BigDecimal =
    decimalFromBytes(in.readFixed(size), precision, scale)
  def writeFixedDecimal(value: BigDecimal, out: AvroOutput, size: Int, precision: Int, scale: Int): Unit =
    writeJavaFixedDecimal(value.bigDecimal, out, size, precision, scale)

  def readJavaFixedDecimal(in: AvroInput, size: Int, precision: Int, scale: Int): JavaDecimal =
    javaDecimalFromBytes(in.readFixed(size), precision, scale)
  def writeJavaFixedDecimal(value: JavaDecimal, out: AvroOutput, size: Int, precision: Int, scale: Int): Unit =
    require(size > 0, "A fixed decimal requires a positive size")
    val encoded = decimalBytes(value, precision, scale)
    require(encoded.length <= size, s"Decimal does not fit fixed size $size")
    val bytes = new Array[Byte](size)
    if encoded(0) < 0 then java.util.Arrays.fill(bytes, 0xff.toByte)
    System.arraycopy(encoded, 0, bytes, size - encoded.length, encoded.length)
    out.writeFixed(Bytes.unsafeWrap(bytes))

  /** Avro big-decimal stores bytes(unscaled integer), then int(scale), inside
    * the schema's bytes value. Unlike decimal, its scale belongs to each value.
    */
  def bigDecimalFromBytes(value: Bytes): BigDecimal =
    BigDecimal.exact(javaBigDecimalFromBytes(value))

  def javaBigDecimalFromBytes(value: Bytes): JavaDecimal =
    // The enclosing input has already enforced its configured bytes limit.
    // Bound this nested decoder to the actual payload, including direct calls,
    // so an inner length can never allocate beyond the supplied value.
    val in = new BinaryInput(value.unsafeArray,
      DecodeLimits(maxInputBytes = value.size, maxBytesLength = value.size))
    val unscaled = in.readBytes()
    if unscaled.size == 0 then invalid("Big-decimal bytes must contain a two's-complement integer")
    val scale = in.readInt()
    in.requireEnd()
    new JavaDecimal(new BigInteger(unscaled.unsafeArray), scale)

  def readBigDecimal(in: AvroInput): BigDecimal = bigDecimalFromBytes(in.readBytes())
  def writeBigDecimal(value: BigDecimal, out: AvroOutput): Unit =
    writeJavaBigDecimal(value.bigDecimal, out)

  def readJavaBigDecimal(in: AvroInput): JavaDecimal = javaBigDecimalFromBytes(in.readBytes())
  def writeJavaBigDecimal(value: JavaDecimal, out: AvroOutput): Unit =
    val payload = new BinaryOutput()
    payload.writeBytes(Bytes.unsafeWrap(value.unscaledValue().toByteArray))
    payload.writeInt(value.scale())
    out.writeBytes(Bytes.unsafeWrap(payload.toByteArray))

  def durationFromBytes(value: Bytes): AvroDuration =
    if value.size != 12 then invalid("An Avro duration requires exactly 12 bytes")
    val bytes = value.unsafeArray
    AvroDuration(unsignedLittleEndian(bytes, 0), unsignedLittleEndian(bytes, 4), unsignedLittleEndian(bytes, 8))

  private def unsignedLittleEndian(bytes: Array[Byte], offset: Int): Long =
    (bytes(offset) & 0xffL) | ((bytes(offset + 1) & 0xffL) << 8) |
      ((bytes(offset + 2) & 0xffL) << 16) | ((bytes(offset + 3) & 0xffL) << 24)

  private def putUnsignedLittleEndian(bytes: Array[Byte], offset: Int, value: Long): Unit =
    var index = 0
    while index < 4 do
      bytes(offset + index) = (value >>> (index * 8)).toByte
      index += 1

  def readDuration(in: AvroInput): AvroDuration = durationFromBytes(in.readFixed(12))
  def writeDuration(value: AvroDuration, out: AvroOutput): Unit =
    val bytes = new Array[Byte](12)
    putUnsignedLittleEndian(bytes, 0, value.months)
    putUnsignedLittleEndian(bytes, 4, value.days)
    putUnsignedLittleEndian(bytes, 8, value.millis)
    out.writeFixed(Bytes.unsafeWrap(bytes))
