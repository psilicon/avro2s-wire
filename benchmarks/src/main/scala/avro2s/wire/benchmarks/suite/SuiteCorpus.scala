package avro2s.wire.benchmarks.suite

import java.io.ByteArrayOutputStream
import java.math.{BigDecimal as JavaDecimal, BigInteger}
import java.nio.ByteBuffer
import java.time.*
import java.util.{SplittableRandom, UUID}
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.TimePeriod
import scala.jdk.CollectionConverters.*

/** Independent, deterministic Avro datum inputs. No Wire encoder constructs the oracle corpus.
  * All generation, domain conversion, model construction and payload preparation is outside JMH timing.
  */
object SuiteCorpus:
  val size: Int = SuiteCatalog.corpusSize
  val seed: Long = SuiteCatalog.seed

  def payloads(c: SuiteCase): Array[Array[Byte]] =
    val schema = SuiteSupport.writerSchema(c)
    // Avro 1.12.1's toLong conversion shifts fractional pre-epoch nanos by 999ms.
    // Build these two oracle corpora from independently calculated physical longs;
    // the benchmark capability manifest excludes the affected Java encode paths.
    val nanosCase = c.id == "L06" || c.id == "L09"
    val data = if nanosCase then new GenericData() else SuiteSupport.genericData()
    val writer = new GenericDatumWriter[GenericRecord](schema, data)
    Array.tabulate(size) { index =>
      val out = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().binaryEncoder(out, null)
      val datum = record(c, index, schema)
      if nanosCase then
        val instant = datum.get("value") match
          case i: Instant => i
          case local: LocalDateTime => local.toInstant(ZoneOffset.UTC)
        datum.put("value", Math.addExact(Math.multiplyExact(instant.getEpochSecond, 1000000000L), instant.getNano.toLong))
      writer.write(datum, encoder)
      encoder.flush()
      out.toByteArray
    }

  def expected(c: SuiteCase, index: Int): GenericRecord =
    val schema = SuiteSupport.readerSchema(c)
    if c.id.startsWith("E") then
      val result = new GenericData.Record(schema)
      c.id match
        case "E01" => result.put("id", integer(5, 64, index))
        case "E02" =>
          result.put("id", integer(5, 64, index))
          result.put("enabled", true)
        case "E03" => result.put("value", integer(3, 32, index))
      result
    else record(c, index, schema)

  def record(c: SuiteCase, index: Int, schema: Schema): GenericRecord =
    require(index >= 0 && index < size)
    val result = new GenericData.Record(schema)
    if c.id.startsWith("R") then
      val count = if Set("R01", "R02")(c.id) then 4 else 16
      var fieldIndex = 0
      def structure(s: Schema): GenericRecord =
        val r = new GenericData.Record(s)
        s.getFields.asScala.foreach { field =>
          if field.schema().getType == Schema.Type.RECORD then r.put(field.pos(), structure(field.schema()))
          else
            r.put(field.pos(), integer(5, 64, index * count + fieldIndex))
            fieldIndex += 1
        }
        r
      structure(schema)
    else if c.id.startsWith("E") then
      c.id match
        case "E01" =>
          result.put("id", integer(5, 64, index))
          result.put("blob", ByteBuffer.wrap(binary(4096, index)))
        case "E02" => result.put("id", integer(5, 64, index))
        case "E03" => result.put("value", integer(3, 32, index).toInt)
      result
    else
      val field = schema.getField("value").schema()
      val value: Any = c.id match
        case "P01" => index % 2 == 0
        case "P02" => integer(1, 32, index).toInt
        case "P03" => integer(3, 32, index).toInt
        case "P04" => integer(5, 32, index).toInt
        case "P05" => integer(1, 64, index)
        case "P06" => integer(5, 64, index)
        case "P07" => integer(10, 64, index)
        case "P08" => floating(index).toFloat
        case "P09" => floating(index)
        case "P10" => new GenericData.EnumSymbol(field, field.getEnumSymbols.get(index % 8))
        case id if id.startsWith("T") => text(id, index)
        case "B01" => ByteBuffer.wrap(binary(32, index))
        case "B02" => ByteBuffer.wrap(binary(4096, index))
        case "B03" => ByteBuffer.wrap(binary(65536, index))
        case "B04" => new GenericData.Fixed(field, binary(16, index))
        case "C01" | "C02" =>
          val count = if c.id == "C01" then 8 else 1024
          val values = new java.util.ArrayList[Integer](count)
          (0 until count).foreach(i => values.add(integer(3, 32, index * count + i).toInt))
          values
        case "C03" | "C04" =>
          val count = if c.id == "C03" then 8 else 128
          val values = new java.util.LinkedHashMap[String, java.lang.Long]()
          (0 until count).foreach { i =>
            val key = f"k${index * 257 + i}%07x"
            values.put(key, integer(5, 64, index * count + i))
          }
          values
        case "U01" => null
        case "U02" | "U03" => integer(3, 32, index).toInt
        case "U04" => text("T01", index)
        case "U05" =>
          val branch = field.getTypes.asScala.find(_.getType == Schema.Type.RECORD).get
          val r = new GenericData.Record(branch)
          branch.getFields.asScala.foreach(f => r.put(f.pos(), integer(5, 64, index * 4 + f.pos())))
          r
        case id if id.startsWith("L") => logical(field, index)
        case other => throw new IllegalArgumentException(s"Unknown corpus case $other")
      result.put("value", value)
      result

  /** Signed values are selected by their exact zig-zag varint width, with both ends included.
    * Pairing x and -x-1 keeps positive and negative encoded widths identical.
    */
  def integer(width: Int, bits: Int, index: Int): Long =
    if width == 1 then (index % 128 - 64).toLong
    else
      val low = BigInt(1) << (7 * (width - 1) - 1)
      val high = (BigInt(1) << math.min(7 * width - 1, bits - 1)) - 1
      val magnitude = index / 2 match
        case 0 => low
        case 1 => high
        case _ =>
          val random = new SplittableRandom(seed ^ (index.toLong * 0x9e3779b97f4a7c15L))
          low + BigInt(random.nextLong()).abs % (high - low + 1)
      (if index % 2 == 0 then magnitude else -magnitude - 1).toLong

  private def floating(index: Int): Double = index match
    case 0 => 0.0
    case 1 => -0.0
    case 2 => -1024.0
    case 3 => 1024.0
    case _ => new SplittableRandom(seed + index).nextDouble(-1024.0, 1024.0)

  def text(id: String, index: Int): String =
    val number = id.drop(1).toInt
    val family = if number <= 10 then (number - 1) / 2 else number - 11
    val byteCount = if number >= 11 then 196608 else if number % 2 == 1 then 48 else 3072
    val (first, variants, width) = family match
      case 0 => (0x20, 95, 1)
      case 1 => (0xa0, 96, 2)
      case 2 => (0x370, 144, 2)
      case 3 => (0x4e00, 512, 3)
      case 4 => (0x1f600, 64, 4)
    val random = new SplittableRandom(seed ^ index.toLong)
    val builder = new java.lang.StringBuilder(byteCount / width)
    var position = 0
    while position < byteCount / width do
      builder.appendCodePoint(first + random.nextInt(variants))
      position += 1
    builder.toString

  private def binary(count: Int, index: Int): Array[Byte] =
    val random = new SplittableRandom(seed ^ index.toLong)
    Array.fill(count)(random.nextInt(256).toByte)

  private def logical(schema: Schema, index: Int): Any =
    val random = new SplittableRandom(seed + index)
    val firstDay = LocalDate.of(1960, 1, 1).toEpochDay
    val lastDay = LocalDate.of(2031, 1, 1).toEpochDay - 1
    val day = index match
      case 0 => firstDay
      case 1 => 0L
      case 2 => lastDay
      case _ => random.nextLong(firstDay, lastDay + 1)
    val seconds = day * 86400L + random.nextLong(86400L)
    def nanos(precision: Int): Int =
      val units = if precision == 3 then 1000 else if precision == 6 then 1000000 else 1000000000
      random.nextInt(units) * (1000000000 / units)
    val name = schema.getLogicalType.getName
    name match
      case "date" => LocalDate.ofEpochDay(day)
      case "time-millis" => LocalTime.ofNanoOfDay(random.nextLong(86400000L) * 1000000L)
      case "time-micros" => LocalTime.ofNanoOfDay(random.nextLong(86400000000L) * 1000L)
      case "timestamp-millis" => Instant.ofEpochSecond(seconds, nanos(3))
      case "timestamp-micros" => Instant.ofEpochSecond(seconds, nanos(6))
      case "timestamp-nanos" => Instant.ofEpochSecond(seconds, nanos(9))
      case "local-timestamp-millis" => LocalDateTime.ofEpochSecond(seconds, nanos(3), ZoneOffset.UTC)
      case "local-timestamp-micros" => LocalDateTime.ofEpochSecond(seconds, nanos(6), ZoneOffset.UTC)
      case "local-timestamp-nanos" => LocalDateTime.ofEpochSecond(seconds, nanos(9), ZoneOffset.UTC)
      case "uuid" => new UUID(random.nextLong(), random.nextLong())
      case "duration" => TimePeriod.of(random.nextInt(25).toLong, random.nextInt(32).toLong, random.nextLong(86400000L))
      case "decimal" | "big-decimal" =>
        val digits = if name == "big-decimal" then 50 else schema.getObjectProp("precision").toString.toInt
        val scale = if name == "big-decimal" then 6 else schema.getObjectProp("scale").toString.toInt
        val magnitude = new java.lang.StringBuilder(digits)
        magnitude.append(('1' + random.nextInt(9)).toChar)
        (1 until digits).foreach(_ => magnitude.append(('0' + random.nextInt(10)).toChar))
        val integer = new BigInteger((if index % 2 == 0 then "" else "-") + magnitude.toString)
        new JavaDecimal(integer, scale)
      case other => throw new IllegalArgumentException(s"Unsupported logical type $other")
