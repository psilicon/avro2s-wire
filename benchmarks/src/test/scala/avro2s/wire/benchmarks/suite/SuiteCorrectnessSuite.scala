package avro2s.wire.benchmarks.suite

import java.nio.charset.StandardCharsets.UTF_8
import org.apache.avro.Schema
import scala.jdk.CollectionConverters.*
import SuiteWorkload.encodedBytes

class SuiteCorrectnessSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "minutes")

  test("approved catalogue is complete and avro2s subset is exact") {
    assertEquals(SuiteCatalog.all.size, 61)
    assertEquals(SuiteCatalog.all.map(_.id).distinct.size, 61)
    assertEquals(SuiteCatalog.all.map(_.operations.size).sum, 119)
    val subset = Set("P01", "P03", "P06", "P09", "P10", "T01", "T02", "T11",
      "C01", "C02", "C03", "C04", "U01", "U02", "U03", "U04", "U05")
    assertEquals(SuiteCatalog.all.filter(_.avro2s).map(_.id).toSet, subset)
    assertEquals(SuiteCorpus.size, 256)
  }

  for c <- SuiteCatalog.all do
    test(s"${c.id}: all public models, every input, and cross-engine encoding agree") {
      val payloads = SuiteCorpus.payloads(c)
      val readers = SuiteSupport.enginesFor(c, "decode").map(engine => new SuiteWorkload(c, engine, payloads, "decode"))
      val schema = SuiteSupport.readerSchema(c)
      for index <- payloads.indices do
        val expected = SuiteVerification.normalize(SuiteCorpus.expected(c, index), schema)
        readers.foreach { reader =>
          val result = reader.decodeBytes(payloads(index))
          reader.checkModel(result)
          assertEquals(SuiteVerification.normalize(result, schema), expected, s"${reader.engine} input $index")
        }
      if c.operations.contains("encode") then
        SuiteSupport.enginesFor(c, "encode").foreach { engine =>
          val writer = new SuiteWorkload(c, engine, payloads, "encode")
          for index <- payloads.indices do
            val encoded = encodedBytes(writer.encodeAt(index))
            val expected = SuiteVerification.normalize(SuiteCorpus.expected(c, index), schema)
            // All values pass an independent Java generic oracle; sampled boundaries also
            // exercise every writer/reader pairing without retaining every engine's corpus.
            val oracle = readers.find(_.engine == "java-generic").get
            assertEquals(SuiteVerification.normalize(oracle.decodeBytes(encoded), schema), expected, s"$engine encode $index")
            if index < 4 || index == 127 || index == 255 then
              readers.foreach { reader =>
                assertEquals(SuiteVerification.normalize(reader.decodeBytes(encoded), schema), expected,
                  s"$engine -> ${reader.engine} input $index")
              }
          val first = encodedBytes(writer.encodeAt(0))
          val snapshot = first.clone()
          val second = encodedBytes(writer.encodeAt(0))
          assert(!(first eq second), s"$engine reused returned array")
          assert(java.util.Arrays.equals(first, snapshot))
          second(0) = (second(0) ^ 0xff).toByte
          assert(java.util.Arrays.equals(first, snapshot), s"$engine output arrays alias")
          // Exercise cursor wraparound and repeated use of the same public input objects.
          for offset <- 0 until payloads.length * 2 do
            val index = offset % payloads.length
            val encoded = encodedBytes(writer.encode())
            if index < 4 || index == 255 then
              val expected = SuiteVerification.normalize(SuiteCorpus.expected(c, index), schema)
              val oracle = readers.find(_.engine == "java-generic").get
              assertEquals(SuiteVerification.normalize(oracle.decodeBytes(encoded), schema), expected,
                s"$engine changed input during corpus rotation $offset")
          if engine == "java-custom" then assertEquals(writer.verifyCustomDispatch(), (0, 0, 1, 1))
        }
      readers.foreach { reader =>
        val first = reader.decodeBytes(payloads(0)).asInstanceOf[AnyRef]
        val second = reader.decodeBytes(payloads(0)).asInstanceOf[AnyRef]
        assert(!(first eq second), s"${reader.engine} reused result record")
        intercept[Exception](reader.decodeBytes(payloads(0) ++ Array[Byte](0)))
        intercept[Exception](reader.decodeBytes(payloads(0).dropRight(1)))
      }
    }

  test("primitive and collection integers have the promised exact widths") {
    for c <- SuiteCatalog.all.filter(c => Set("int", "long")(c.kind)) do
      SuiteCorpus.payloads(c).foreach(bytes => assertEquals(bytes.length, c.int("width"), c.id))
    for widthBits <- Vector((3, 32), (5, 64)); index <- 0 until 4096 do
      val (width, bits) = widthBits
      val value = SuiteCorpus.integer(width, bits, index)
      var encoded = (value << 1) ^ (value >> 63)
      var count = 1
      while (encoded & ~0x7fL) != 0L do
        count += 1
        encoded >>>= 7
      assertEquals(count, width)
  }

  test("each string family has the exact UTF-8 size and expected character range") {
    for c <- SuiteCatalog.all.filter(_.kind == "string"); index <- Vector(0, 127, 255) do
      val text = SuiteCorpus.text(c.id, index)
      assertEquals(text.getBytes(UTF_8).length, c.int("utf8Bytes"), c.id)
      val (low, high, width) = c.text("stringFamily") match
        case "ascii" => (0, 127, 1)
        case "latin1" => (128, 255, 2)
        case "bmp2" => (256, 2047, 2)
        case "bmp3" => (2048, 65535, 3)
        case "supplementary" => (65536, 0x10ffff, 4)
      assertEquals(text.codePointCount(0, text.length), c.int("utf8Bytes") / width)
      assert(text.codePoints().allMatch(cp => cp >= low && cp <= high))
  }

  test("flat and nested controls have identical payloads and exact record counts") {
    for (flatId, nestedId, count) <- Vector(("R01", "R02", 4), ("R03", "R04", 16)) do
      val flat = SuiteCorpus.payloads(SuiteCatalog.byId(flatId))
      val nested = SuiteCorpus.payloads(SuiteCatalog.byId(nestedId))
      flat.indices.foreach(i => assert(java.util.Arrays.equals(flat(i), nested(i))))
      def records(s: Schema): Int = if s.getType == Schema.Type.RECORD then
        1 + s.getFields.asScala.map(f => records(f.schema())).sum
      else 0
      assertEquals(records(SuiteSupport.readerSchema(SuiteCatalog.byId(nestedId))), count)
  }

  test("avro2s enum corpus contains actual Scala enums") {
    val workload = SuiteWorkload.prepared("P10", "avro2s", "encode")
    val value = workload.inputAt(0).asInstanceOf[Product].productElement(0)
    assert(value.isInstanceOf[scala.reflect.Enum], s"Unexpected enum model: ${value.getClass}")
  }

  test("known Avro nanosecond encoding bug is explicit and does not remove valid decode comparisons") {
    val instant = java.time.Instant.ofEpochSecond(-1L, 123456789L)
    val schema = SuiteSupport.readerSchema(SuiteCatalog.byId("L06")).getField("value").schema()
    val conversion = new org.apache.avro.data.TimeConversions.TimestampNanosConversion()
    val expected = -876543211L
    val official = conversion.toLong(instant, schema, schema.getLogicalType).longValue()
    assertEquals(official - expected, 999000000L, "Revisit the capability exclusion if the pinned Avro bug changes")
    for id <- Vector("L06", "L09") do
      val c = SuiteCatalog.byId(id)
      assertEquals(SuiteSupport.enginesFor(c, "encode"), Vector("wire"))
      assert(SuiteSupport.enginesFor(c, "decode").contains("java-specific"))
      assert(SuiteSupport.enginesFor(c, "decode").contains("java-generic"))
      intercept[IllegalArgumentException](SuiteWorkload.prepared(id, "java-specific", "encode"))
  }

  test("decoded binary fields own their contents") {
    for id <- Vector("B01", "B04"); engine <- SuiteSupport.enginesFor(SuiteCatalog.byId(id)) do
      val c = SuiteCatalog.byId(id)
      val workload = SuiteWorkload.prepared(id, engine, "decode")
      val input = workload.payloadAt(0).clone()
      val decoded = workload.decodeBytes(input)
      val before = SuiteVerification.normalize(decoded, SuiteSupport.readerSchema(c))
      java.util.Arrays.fill(input, 0.toByte)
      assertEquals(SuiteVerification.normalize(decoded, SuiteSupport.readerSchema(c)), before, s"$id $engine aliases input")
  }
