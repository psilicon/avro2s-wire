package avro2s.wire.benchmarks.suite

import java.nio.ByteBuffer
import SuiteWorkload.encodedBytes

/** Check the actual public reuse APIs, including retained results and failed-call recovery. */
class SuiteReuseSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "minutes")

  private val diagnosticOperations = Set(
    "P03" -> "encode", "R01" -> "encode", "L12" -> "encode", "L16" -> "encode", "T01" -> "encode",
    "P02" -> "decode", "P10" -> "decode", "R02" -> "decode", "L12" -> "decode", "L08" -> "decode",
    "T11" -> "encode", "T11" -> "decode", "B03" -> "encode", "C02" -> "decode", "C04" -> "decode",
    "P06" -> "decode"
  )

  private def implementations(c: SuiteCase, operation: String): Vector[String] =
    SuiteSupport.enginesFor(c, operation).filterNot(_ == "avro2s")

  // Every supported case gets boundary coverage; all 256 inputs and cursor wraparound are
  // exercised for the selected diagnostic operations. Keep the independent oracle on the old,
  // fresh GenericDatumReader path, separate from RawMessageDecoder's reusable state.
  for c <- SuiteCatalog.all do
    test(s"${c.id}: reuse APIs preserve values, output ownership, and fresh models") {
      val schema = SuiteSupport.readerSchema(c)
      val payloads = SuiteCorpus.payloads(c)
      val oracle = new SuiteWorkload(c, "java-generic", payloads, "decode")
      def expected(index: Int): Any = SuiteVerification.normalize(SuiteCorpus.expected(c, index), schema)
      def normalized(value: Any): Any = SuiteVerification.normalize(value, schema)
      val boundaries = Vector(0, 1, 127, 255)

      for engine <- implementations(c, "decode") do
        val reader = new SuiteWorkload(c, engine, payloads, "decode", "reuse")
        val indices = if diagnosticOperations(c.id -> "decode") then payloads.indices.toVector else boundaries
        val first = reader.decodeBytes(payloads(0)).asInstanceOf[AnyRef]
        val before = normalized(first)
        for index <- indices do
          val value = reader.decodeBytes(payloads(index))
          reader.checkModel(value)
          assertEquals(normalized(value), expected(index), s"$engine decode $index")
          assert(!(first eq value.asInstanceOf[AnyRef]), s"$engine reused a returned model")
        if diagnosticOperations(c.id -> "decode") then
          for offset <- 0 until payloads.length * 2 do
            assertEquals(normalized(reader.decode()), expected(offset % payloads.length), s"$engine decode rotation $offset")
        assertEquals(normalized(first), before, s"$engine changed a previously decoded model")

        // A truncated call must not leave the retained decoder poisoned for the next message.
        intercept[Exception](reader.decodeBytes(payloads(0).dropRight(1)))
        assertEquals(normalized(reader.decodeBytes(payloads(1))), expected(1), s"$engine failed-call recovery")

        val withTrailing = payloads(0) ++ Array[Byte](0)
        if engine == "wire" then intercept[Exception](reader.decodeBytes(withTrailing))
        else assertEquals(normalized(reader.decodeBytes(withTrailing)), expected(0),
          "Official raw-message decode reads one datum and accepts trailing bytes")

        if engine == "java-custom" && c.kind == "evolution" then
          assertEquals(reader.verifyCustomDecodeDispatch(), (0, 1))

      if c.operations.contains("encode") then
        for engine <- implementations(c, "encode") do
          val writer = new SuiteWorkload(c, engine, payloads, "encode", "reuse")
          val indices = if diagnosticOperations(c.id -> "encode") then payloads.indices.toVector else boundaries
          val retained = writer.encodeAt(0)
          if engine == "wire" then assert(retained.isInstanceOf[Array[Byte]])
          else assert(retained.isInstanceOf[ByteBuffer], "JMH must consume the raw helper's natural output")
          val snapshot = encodedBytes(retained).clone()
          for index <- indices do
            val encoded = writer.encodeAt(index)
            assert(!(retained eq encoded), s"$engine reused its returned object")
            val bytes = encodedBytes(encoded)
            assertEquals(normalized(oracle.decodeBytes(bytes)), expected(index), s"$engine encode $index")
          if diagnosticOperations(c.id -> "encode") then
            for offset <- 0 until payloads.length * 2 do
              val encoded = encodedBytes(writer.encode())
              assertEquals(normalized(oracle.decodeBytes(encoded)), expected(offset % payloads.length),
                s"$engine encode rotation $offset")
          assert(java.util.Arrays.equals(encodedBytes(retained), snapshot), s"$engine overwrote a previous output")
          val later = writer.encodeAt(1)
          later match
            case bytes: Array[Byte] => bytes(0) = (bytes(0) ^ 0xff).toByte
            case buffer: ByteBuffer => buffer.put(0, (buffer.get(0) ^ 0xff).toByte)
            case other => fail(s"Unexpected encoded result ${other.getClass}")
          assert(java.util.Arrays.equals(encodedBytes(retained), snapshot), s"$engine outputs alias each other")
          if engine == "java-custom" then assertEquals(writer.verifyCustomDispatch(), (0, 0, 1, 1))
    }

  test("reuse decoders return owned binary fields after the input and scratch storage are reused") {
    for id <- Vector("B01", "B02", "B04"); engine <- implementations(SuiteCatalog.byId(id), "decode") do
      val c = SuiteCatalog.byId(id)
      val reader = SuiteWorkload.prepared(id, engine, "decode", "reuse")
      val input = reader.payloadAt(0).clone()
      val decoded = reader.decodeBytes(input)
      val snapshot = SuiteVerification.normalize(decoded, SuiteSupport.readerSchema(c))
      java.util.Arrays.fill(input, 0.toByte)
      reader.decodeBytes(reader.payloadAt(1))
      assertEquals(SuiteVerification.normalize(decoded, SuiteSupport.readerSchema(c)), snapshot, s"$id $engine aliases storage")
  }

  test("untimed ByteBuffer verification respects position and limit without changing either") {
    val buffer = ByteBuffer.wrap(Array[Byte](9, 1, 2, 8))
    buffer.position(1)
    buffer.limit(3)
    assertEquals(encodedBytes(buffer).toVector, Vector[Byte](1, 2))
    assertEquals(buffer.position(), 1)
    assertEquals(buffer.limit(), 3)
  }

  test("unknown usage is rejected instead of silently measuring the fresh path") {
    intercept[IllegalArgumentException](SuiteWorkload.prepared("P03", "wire", "encode", "unknown"))
  }


  test("Wire string policy reaches the measured encoder in fresh and reuse modes") {
    for usage <- Vector("fresh", "reuse"); id <- Vector("T01", "T11") do
      val rejecting = SuiteWorkload.prepared(id, "wire", "encode", usage)
      val replacing = SuiteWorkload.prepared(id, "wire", "encode", usage, "replace")
      val valid = rejecting.inputAt(0).asInstanceOf[wire.TextValue]
      val malformed = valid.copy(value = valid.value + "\ud800")
      intercept[IllegalArgumentException](rejecting.encodeValue(malformed))
      val encoded = replacing.encodeValue(malformed)
      val before = encodedBytes(encoded).clone()
      assertEquals(replacing.decodeBytes(before), valid.copy(value = valid.value + "?"))
      val next = replacing.encodeAt(1)
      assert(!(encoded eq next), s"$usage replacement output must remain independently owned")
      java.util.Arrays.fill(encodedBytes(next), 0.toByte)
      assertEquals(encodedBytes(encoded).toVector, before.toVector)
      // A failed strict write must not poison the retained output or change the policy.
      assertEquals(rejecting.decodeBytes(encodedBytes(rejecting.encodeAt(0))), valid)
      intercept[IllegalArgumentException](rejecting.encodeValue(malformed))
      assertEquals(encodedBytes(replacing.encodeAt(0)).toVector, encodedBytes(rejecting.encodeAt(0)).toVector)
  }

  test("Wire policy selection does not change Java's existing replacement strategy") {
    for usage <- Vector("fresh", "reuse"); policy <- Vector("reject", "replace") do
      val writer = SuiteWorkload.prepared("T01", "java-specific", "encode", usage, policy)
      val malformed = new javaavro.TextValue("A\ud800B")
      val encoded = encodedBytes(writer.encodeValue(malformed))
      val decoded = writer.decodeBytes(encoded).asInstanceOf[javaavro.TextValue]
      assertEquals(decoded.getValue, "A?B")
  }

  test("unknown Wire string policy cannot silently use the default") {
    for engine <- Vector("wire", "java-specific") do
      intercept[IllegalArgumentException](SuiteWorkload.prepared("T01", engine, "encode", "reuse", "unknown"))
  }
