package avro2s.wire.benchmarks.suite

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.*
import org.apache.avro.{LogicalTypes, Schema}
import org.apache.avro.generic.{GenericFixed, IndexedRecord}
import org.apache.avro.util.TimePeriod
import scala.jdk.CollectionConverters.*

/** Agreement between codecs is insufficient if they all receive the wrong advertised workload. */
final class SuiteCorpusContractSuite extends munit.FunSuite:
  private def values(c: SuiteCase): Vector[Any] =
    val schema = SuiteSupport.readerSchema(c)
    Vector.tabulate(SuiteCatalog.corpusSize)(i => SuiteCorpus.record(c, i, schema).get("value"))

  test("corpus uses the manifest seed and size") {
    assertEquals(SuiteCorpus.seed, SuiteCatalog.seed)
    assertEquals(SuiteCorpus.size, SuiteCatalog.corpusSize)
  }

  test("primitive inputs include balanced booleans and enums, extrema, finite fractions and signed zero") {
    val booleans = values(SuiteCatalog.byId("P01"))
    assertEquals(booleans.count(_ == true), 128)
    assertEquals(booleans.count(_ == false), 128)
    val enums = values(SuiteCatalog.byId("P10")).map(_.toString).groupMapReduce(identity)(_ => 1)(_ + _)
    assertEquals(enums, (0 until 8).map(i => s"S$i" -> 32).toMap)
    val ints = values(SuiteCatalog.byId("P04"))
    assert(ints.contains(Int.MinValue) && ints.contains(Int.MaxValue))
    val longs = values(SuiteCatalog.byId("P07"))
    assert(longs.contains(Long.MinValue) && longs.contains(Long.MaxValue))
    for id <- Vector("P08", "P09") do
      val input = values(SuiteCatalog.byId(id)).map(_.asInstanceOf[Number].doubleValue())
      assert(input.forall(n => java.lang.Double.isFinite(n) && n >= -1024.0 && n <= 1024.0))
      assert(input.exists(n => n != math.floor(n)))
      val raw = input.map(java.lang.Double.doubleToRawLongBits)
      assert(raw.contains(java.lang.Double.doubleToRawLongBits(0.0)))
      assert(raw.contains(java.lang.Double.doubleToRawLongBits(-0.0)))
  }

  test("collection sizes, ASCII map keys and numeric representations match their catalogue entries") {
    for c <- SuiteCatalog.all.filter(c => c.kind == "array" || c.kind == "map") do
      values(c).foreach { value =>
        if c.kind == "array" then
          val entries = value.asInstanceOf[java.util.List[?]].asScala
          assertEquals(entries.size, c.int("size"))
          assert(entries.forall(_.isInstanceOf[java.lang.Integer]))
        else
          val entries = value.asInstanceOf[java.util.Map[String, ?]].asScala
          assertEquals(entries.size, c.int("size"))
          assert(entries.keys.forall(k => k.length == 8 && k.getBytes(UTF_8).length == 8 && k.forall(_ < 128)))
          assert(entries.values.forall(_.isInstanceOf[java.lang.Long]))
      }
  }

  test("binary inputs have the advertised exact lengths") {
    for c <- SuiteCatalog.all.filter(c => c.kind == "bytes" || c.kind == "fixed") do
      values(c).foreach { value =>
        val length = value match
          case b: ByteBuffer => b.remaining()
          case b: GenericFixed => b.bytes().length
        assertEquals(length, c.int("size"), c.id)
      }
  }

  test("each union case selects precisely its advertised branch") {
    for c <- SuiteCatalog.all.filter(c => c.kind == "union" || c.kind == "optional") do
      values(c).foreach { value =>
        c.text("branch") match
          case "null" => assertEquals(value, null)
          case "int" => assert(value.isInstanceOf[java.lang.Integer])
          case "string" =>
            assert(value.isInstanceOf[String])
            assertEquals(value.asInstanceOf[String].getBytes(UTF_8).length, c.int("utf8Bytes"))
          case "record" =>
            val record = value.asInstanceOf[IndexedRecord]
            assertEquals(record.getSchema.getName, "UnionRecord")
            assertEquals(record.getSchema.getFields.size(), 4)
            assert((0 until 4).forall(i => record.get(i).isInstanceOf[java.lang.Long]))
      }
  }

  test("logical inputs use domain values, stated ranges, declared precision and exact decimal scale") {
    val start = LocalDate.of(1960, 1, 1)
    val end = LocalDate.of(2031, 1, 1)
    val startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant
    val endInstant = end.atStartOfDay(ZoneOffset.UTC).toInstant
    for c <- SuiteCatalog.all.filter(_.kind == "logical") do
      val input = values(c)
      val logical = c.text("logicalType")
      input.foreach { value =>
        logical match
          case "date" =>
            val d = value.asInstanceOf[LocalDate]
            assert(!d.isBefore(start) && d.isBefore(end))
          case "time-millis" | "time-micros" =>
            val t = value.asInstanceOf[LocalTime]
            assertEquals(t.getNano % (if logical.endsWith("millis") then 1000000 else 1000), 0)
          case "timestamp-millis" | "timestamp-micros" | "timestamp-nanos" =>
            val t = value.asInstanceOf[Instant]
            assert(!t.isBefore(startInstant) && t.isBefore(endInstant))
            val divisor = if logical.endsWith("millis") then 1000000 else if logical.endsWith("micros") then 1000 else 1
            assertEquals(t.getNano % divisor, 0)
          case "local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos" =>
            val t = value.asInstanceOf[LocalDateTime]
            assert(!t.isBefore(start.atStartOfDay()) && t.isBefore(end.atStartOfDay()))
            val divisor = if logical.endsWith("millis") then 1000000 else if logical.endsWith("micros") then 1000 else 1
            assertEquals(t.getNano % divisor, 0)
          case "uuid" => assert(value.isInstanceOf[java.util.UUID])
          case "duration" =>
            val d = value.asInstanceOf[TimePeriod]
            assert(d.getMonths >= 0 && d.getMonths <= 24)
            assert(d.getDays >= 0 && d.getDays <= 31)
            assert(d.getMillis >= 0 && d.getMillis < 86400000L)
          case "decimal" | "big-decimal" =>
            val d = value.asInstanceOf[java.math.BigDecimal]
            assertEquals(d.precision(), c.int("precision"), c.id)
            assertEquals(d.scale(), c.int("scale"), c.id)
      }
      if logical == "decimal" || logical == "big-decimal" then
        assertEquals(input.count(_.asInstanceOf[java.math.BigDecimal].signum() < 0), 128)
      if logical == "date" then assert(input.exists(_.asInstanceOf[LocalDate].isBefore(LocalDate.ofEpochDay(0))))
      if logical.startsWith("timestamp-") then assert(input.exists(_.asInstanceOf[Instant].isBefore(Instant.EPOCH)))
  }

  test("evolution custom readers execute actual generated customDecode methods") {
    for c <- SuiteCatalog.all.filter(_.kind == "evolution") if SuiteSupport.enginesFor(c).contains("java-custom") do
      val workload = SuiteWorkload.prepared(c.id, "java-custom", "decode")
      assertEquals(workload.verifyCustomDecodeDispatch(), (0, 1), c.id)
  }

  test("semantic checks preserve numeric union identity, signed zero and decimal scale") {
    val union = new Schema.Parser().parse("[\"int\",\"long\"]")
    assert(SuiteVerification.normalize(7, union) != SuiteVerification.normalize(7L, union))
    val floating = Schema.create(Schema.Type.DOUBLE)
    assert(SuiteVerification.normalize(-0.0d, floating) != SuiteVerification.normalize(0.0d, floating))
    val decimal = LogicalTypes.decimal(8, 2).addToSchema(Schema.create(Schema.Type.BYTES))
    assert(SuiteVerification.normalize(new java.math.BigDecimal("1.00"), decimal) !=
      SuiteVerification.normalize(new java.math.BigDecimal("1.0"), decimal))
  }
