package avro2s.wire.benchmarks.suite

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.generic.GenericData
import org.apache.avro.io.{BufferedBinaryEncoder, DirectBinaryEncoder}
import SuiteWorkload.encodedBytes

/** Exercises each explicitly selectable Java IO configuration, rather than just legacy presets. */
class SuiteJavaConfigurationSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "minutes")

  private val javaEngines = Vector("java-specific", "java-generic", "java-custom", "wire-java", "wire-java-stack-safe", "avro2s")
  private val boundaries = Vector(0, 1, 127, 255)

  for c <- SuiteCatalog.all do
    test(s"${c.id}: factory matrix preserves values, ownership, fresh models and recovery") {
      val original = SuiteCorpus.payloads(c)
      val payloads = boundaries.map(original(_)).toArray
      val schema = SuiteSupport.readerSchema(c)
      def normalize(value: Any): Any = SuiteVerification.normalize(value, schema)
      def expected(index: Int): Any = normalize(SuiteCorpus.expected(c, boundaries(index)))
      val oracle = new SuiteWorkload(c, "wire", payloads, "decode")
      for engine <- javaEngines if SuiteSupport.operationsFor(c, engine).nonEmpty
          usage <- (if engine == "avro2s" then Vector("fresh") else Vector("fresh", "reuse"))
          encoder <- Vector("buffered", "unbuffered") do
        val canEncode = SuiteSupport.operationsFor(c, engine).contains("encode")
        val operation = if canEncode then "both" else "decode"
        val workload = new SuiteWorkload(c, engine, payloads, operation, usage, "reject", "factory", encoder)
        val clue = s"$engine/$usage/$encoder"
        assertEquals(workload.effectiveJavaApi, "factory", clue)
        assertEquals(workload.effectiveJavaEncoder, encoder, clue)
        val selected = workload.factoryEncoder(new ByteArrayOutputStream(), null)
        if encoder == "buffered" then assert(selected.isInstanceOf[BufferedBinaryEncoder], clue)
        else assert(selected.isInstanceOf[DirectBinaryEncoder], clue)
        assert(workload.factoryEncoder(new ByteArrayOutputStream(), selected) eq selected, clue)

        val input = payloads(0).clone()
        val retainedModel = workload.decodeBytes(input).asInstanceOf[AnyRef]
        java.util.Arrays.fill(input, 0.toByte)
        for index <- payloads.indices do
          val decoded = workload.decodeBytes(payloads(index))
          workload.checkModel(decoded)
          assertEquals(normalize(decoded), expected(index), clue)
          assert(!(retainedModel eq decoded.asInstanceOf[AnyRef]), s"$clue returned a reused model")
        assertEquals(normalize(retainedModel), expected(0), s"$clue retained model aliases input/storage")
        intercept[Exception](workload.decodeBytes(payloads(0).dropRight(1)))
        assertEquals(normalize(workload.decodeBytes(payloads(1))), expected(1), s"$clue truncated-call recovery")
        intercept[Exception](workload.decodeBytes(payloads(0) ++ Array[Byte](0)))
        assertEquals(normalize(workload.decodeBytes(payloads(1))), expected(1), s"$clue trailing-call recovery")

        if canEncode then
          val retained = workload.encodeAt(0).asInstanceOf[Array[Byte]]
          val snapshot = retained.clone()
          for index <- payloads.indices do
            val bytes = workload.encodeAt(index).asInstanceOf[Array[Byte]]
            assert(!(retained eq bytes), s"$clue reused returned array")
            assertEquals(normalize(oracle.decodeBytes(bytes)), expected(index), clue)
            java.util.Arrays.fill(bytes, 0.toByte)
          assertEquals(retained.toVector, snapshot.toVector, s"$clue outputs alias each other")
          for index <- 0 until payloads.length * 2 do
            assertEquals(normalize(oracle.decodeBytes(encodedBytes(workload.encode()))), expected(index % payloads.length), clue)
          if engine == "java-custom" then assertEquals(workload.verifyCustomDispatch(), (0, 0, 1, 1), clue)
        else if engine == "java-custom" then assertEquals(workload.verifyCustomDecodeDispatch(), (0, 1), clue)
    }

  test("explicit raw-message configuration retains its natural owned result and trailing-byte contract") {
    for engine <- Vector("java-specific", "java-generic", "java-custom") do
      val workload = SuiteWorkload.prepared("T01", engine, "both", "reuse", "reject", "raw-message", "unbuffered")
      assertEquals(workload.effectiveJavaApi, "raw-message")
      assertEquals(workload.effectiveJavaEncoder, "unbuffered")
      val first = workload.encodeAt(0).asInstanceOf[ByteBuffer]
      val snapshot = encodedBytes(first)
      val second = workload.encodeAt(1).asInstanceOf[ByteBuffer]
      second.put(0, 0.toByte)
      assertEquals(encodedBytes(first).toVector, snapshot.toVector)
      val firstModel = workload.decodeBytes(snapshot).asInstanceOf[AnyRef]
      val secondModel = workload.decodeBytes(snapshot ++ Array[Byte](0)).asInstanceOf[AnyRef]
      assert(!(firstModel eq secondModel))
      val schema = workload.readerSchema
      assertEquals(SuiteVerification.normalize(firstModel, schema), SuiteVerification.normalize(secondModel, schema))
      if engine == "java-custom" then assertEquals(workload.verifyCustomDispatch(), (0, 0, 1, 1))
  }

  test("factory reuse recovers after a partial failed write for both encoder kinds") {
    val c = SuiteCatalog.byId("C03")
    val payloads = SuiteCorpus.payloads(c).take(2)
    for engine <- javaEngines.filterNot(_ == "avro2s"); encoder <- Vector("buffered", "unbuffered") do
      val workload = new SuiteWorkload(c, engine, payloads, "both", "reuse", "reject", "factory", encoder)
      val nullKey = new java.util.LinkedHashMap[String, java.lang.Long]()
      nullKey.put(null, java.lang.Long.valueOf(1L))
      val malformed: Any = engine match
        case "wire-java" | "wire-java-stack-safe" => wire.LongMapValue(Map(null.asInstanceOf[String] -> 1L))
        case "java-generic" =>
          val value = new GenericData.Record(workload.readerSchema)
          value.put(0, nullKey)
          value
        case _ => new javaavro.LongMapValue(nullKey)
      intercept[Exception](workload.encodeValue(malformed))
      val expected = SuiteVerification.normalize(workload.inputAt(1), workload.readerSchema)
      val result = workload.decodeBytes(encodedBytes(workload.encodeAt(1)))
      assertEquals(SuiteVerification.normalize(result, workload.readerSchema), expected, s"$engine/$encoder")
  }

  test("invalid API, encoder and lifecycle combinations fail before measurement") {
    val invalid = Vector(
      ("wire", "fresh", "factory", "buffered"),
      ("wire-stack-safe", "reuse", "none", "unbuffered"),
      ("wire-java", "reuse", "raw-message", "unbuffered"),
      ("wire-java-stack-safe", "reuse", "raw-message", "unbuffered"),
      ("java-specific", "fresh", "raw-message", "unbuffered"),
      ("java-generic", "reuse", "raw-message", "buffered"),
      ("java-custom", "fresh", "none", "none"),
      ("java-specific", "fresh", "factory", "none"),
      ("java-specific", "fresh", "unknown", "buffered"),
      ("java-specific", "fresh", "factory", "unknown"),
      ("avro2s", "reuse", "factory", "buffered"),
      ("avro2s", "reuse", "raw-message", "unbuffered")
    )
    val c = SuiteCatalog.byId("P03")
    val payloads = SuiteCorpus.payloads(c).take(1)
    for (engine, usage, api, encoder) <- invalid do
      intercept[IllegalArgumentException](new SuiteWorkload(c, engine, payloads, "both", usage, "reject", api, encoder))
  }

  test("legacy Scala preparation resolves exactly the original API presets") {
    for usage <- Vector("fresh", "reuse") do
      val native = SuiteWorkload.prepared("P03", "wire", "both", usage)
      assertEquals((native.effectiveJavaApi, native.effectiveJavaEncoder), ("none", "none"))
      val backend = SuiteWorkload.prepared("P03", "wire-java", "both", usage)
      assertEquals((backend.effectiveJavaApi, backend.effectiveJavaEncoder), ("factory", "buffered"))
      val official = SuiteWorkload.prepared("P03", "java-specific", "both", usage)
      val expected = if usage == "fresh" then ("factory", "buffered") else ("raw-message", "unbuffered")
      assertEquals((official.effectiveJavaApi, official.effectiveJavaEncoder), expected)
  }
