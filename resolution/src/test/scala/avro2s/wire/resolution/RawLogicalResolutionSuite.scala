package avro2s.wire.resolution

import avro2s.wire.runtime.*
import java.time.{LocalDate, LocalTime}
import munit.FunSuite

final class RawLogicalResolutionSuite extends RawLogicalResolutionChecks(CodecExecution.Direct)
final class StackSafeRawLogicalResolutionSuite extends RawLogicalResolutionChecks(CodecExecution.StackSafe)

abstract class RawLogicalResolutionChecks(executionMode: CodecExecution) extends FunSuite:
  private def codec[A](json: String, raw: Set[String] = Set.empty,
      named: Map[String, AvroCodec[?]] = Map.empty)(build: Array[Any] => A): AvroCodec[A] =
    new AvroCodec[A]:
      override def execution: CodecExecution = executionMode
      override def schemaJson: String = json
      override def rawLogicalTypes: Set[String] = raw
      override def read(in: AvroInput): A = throw new UnsupportedOperationException("Resolution must construct the reader model")
      override def write(value: A, out: AvroOutput): Unit = throw new UnsupportedOperationException()
      override def construct(values: Array[Any]): A = build(values)
      override def namedCodec(name: String): AvroCodec[?] = named.getOrElse(name, super.namedCodec(name))

  private def record(fields: String, name: String = "R"): String =
    s"""{"type":"record","name":"$name","fields":[$fields]}"""

  private def binary(write: BinaryOutput => Unit): Array[Byte] =
    val out = new BinaryOutput()
    write(out)
    out.toByteArray

  private final case class FixedValue(value: Bytes)
  private final case class RawCase(logical: String, writer: String, reader: String,
      value: Any, defaultJson: String, write: BinaryOutput => Unit, fixedName: Option[String] = None):
    def expected: Any = if fixedName.nonEmpty then FixedValue(value.asInstanceOf[Bytes]) else value
    def readerCodec: AvroCodec[Any] = codec[Any](reader, Set(logical)) { values =>
      if fixedName.nonEmpty then FixedValue(values(0).asInstanceOf[Bytes]) else values(0)
    }

  private def intCase(logical: String): RawCase =
    RawCase(logical, "\"int\"", s"""{"type":"int","logicalType":"$logical"}""",
      -1, "-1", _.writeInt(-1))

  private def longCase(logical: String): RawCase =
    RawCase(logical, "\"long\"", s"""{"type":"long","logicalType":"$logical"}""",
      -1L, "-1", _.writeLong(-1L))

  private def fixedCase(logical: String, size: Int, properties: String = ""): RawCase =
    val name = "Fixed" + logical.replace("-", "")
    val writer = s"""{"type":"fixed","name":"$name","size":$size}"""
    val reader = s"""{"type":"fixed","name":"$name","size":$size,"logicalType":"$logical"$properties}"""
    val value = Bytes.fromArray(Array.fill[Byte](size)(97))
    RawCase(logical, writer, reader, value, "\"" + "a" * size + "\"", _.writeFixed(value), Some(name))

  private val rawCases = Vector(intCase("date"), intCase("time-millis")) ++
    Vector("time-micros", "timestamp-millis", "timestamp-micros", "timestamp-nanos",
      "local-timestamp-millis", "local-timestamp-micros", "local-timestamp-nanos").map(longCase) ++
    Vector(
      RawCase("uuid", "\"string\"", """{"type":"string","logicalType":"uuid"}""",
        "raw UUID text", "\"raw UUID text\"", _.writeString("raw UUID text")),
      RawCase("decimal", "\"bytes\"", """{"type":"bytes","logicalType":"decimal","precision":2,"scale":1}""",
        Bytes.fromArray(Array[Byte](97)), "\"a\"", _.writeBytes(Bytes.fromArray(Array[Byte](97)))),
      RawCase("big-decimal", "\"bytes\"", """{"type":"bytes","logicalType":"big-decimal"}""",
        Bytes.empty, "\"\"", _.writeBytes(Bytes.empty)),
      fixedCase("uuid", 16), fixedCase("duration", 12), fixedCase("decimal", 2, """, "precision":2,"scale":1""")
    )

  test("every raw logical mapping resolves to its storage type, with fixed model construction") {
    rawCases.foreach { example =>
      val actual = ResolvingReader(example.writer, example.readerCodec).decode(binary(example.write))
      assertEquals(actual, example.expected, example.reader)
      assertEquals(actual.getClass, example.expected.getClass, example.reader)
    }
  }

  test("every raw logical mapping also applies to reader defaults") {
    rawCases.foreach { example =>
      val reader = record(s"""{"name":"value","type":${example.reader},"default":${example.defaultJson}}""")
      val named = example.fixedName.map(_ -> example.readerCodec).toMap
      val raw = if example.fixedName.isEmpty then Set(example.logical) else Set.empty[String]
      val target = codec(reader, raw, named)(_(0))
      assertEquals(ResolvingReader(record(""), target).decode(Array.emptyByteArray), example.expected, example.reader)
    }
  }

  test("nested named records own raw conversion for fields, containers, unions, and defaults") {
    val date = """{"type":"int","logicalType":"date"}"""
    val fields = s"""{"name":"date","type":$date},
      {"name":"array","type":{"type":"array","items":$date}},
      {"name":"map","type":{"type":"map","values":$date}},
      {"name":"union","type":["null",$date]}"""
    val defaults = s"""{"name":"added","type":$date,"default":4},
      {"name":"addedArray","type":{"type":"array","items":$date},"default":[5]},
      {"name":"addedMap","type":{"type":"map","values":$date},"default":{"day":6}},
      {"name":"addedUnion","type":["null",$date],"default":7}"""
    val childWriter = record(fields, "original.Child")
    val childReader = record(s"$fields,$defaults", "original.Child")
    val writer = record(s"""{"name":"date","type":$date},{"name":"child","type":$childWriter}""")
    val reader = record(s"""{"name":"date","type":$date},{"name":"child","type":$childReader}""")
    val bytes = binary { out =>
      out.writeInt(0)
      out.writeInt(0)
      out.writeArrayStart(1)
      out.writeInt(1)
      out.writeArrayEnd()
      out.writeMapStart(1)
      out.writeString("day")
      out.writeInt(2)
      out.writeMapEnd()
      out.writeIndex(1)
      out.writeInt(3)
    }
    for rootRaw <- Vector(false, true) do
      val child = codec(childReader, if rootRaw then Set.empty else Set("date"))(_.toVector)
      val target = codec(reader, if rootRaw then Set("date") else Set.empty, Map("original.Child" -> child))(_.toVector)
      def childDay(value: Int): Any = if rootRaw then LocalDate.ofEpochDay(value) else value
      val expected = Vector[Any](
        if rootRaw then 0 else LocalDate.ofEpochDay(0),
        Vector[Any](childDay(0), Vector(childDay(1)), Map("day" -> childDay(2)), Some(childDay(3)),
          childDay(4), Vector(childDay(5)), Map("day" -> childDay(6)), Some(childDay(7)))
      )
      assertEquals(ResolvingReader(writer, target).decode(bytes), expected)
  }

  test("fixed codecs retain their own conversion mode for reads and defaults") {
    val fixed = """{"type":"fixed","name":"original.Id","size":16,"logicalType":"uuid"}"""
    val writer = record(s"""{"name":"id","type":$fixed}""")
    val reader = record(s"""{"name":"id","type":$fixed},{"name":"added","type":"original.Id","default":"abcdefghijklmnop"}""")
    val value = Bytes.fromArray("abcdefghijklmnop".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    for fixedRaw <- Vector(false, true) do
      val child = codec(fixed, if fixedRaw then Set("uuid") else Set.empty)(_(0))
      val target = codec(reader, if fixedRaw then Set.empty else Set("uuid"), Map("original.Id" -> child))(_.toVector)
      val expected: Any = if fixedRaw then value else LogicalValues.uuidFromFixed(value)
      assertEquals(ResolvingReader(writer, target).decode(value.toArray), Vector(expected, expected))
  }

  test("raw metadata does not load codecs for unused named reader alternatives") {
    val reader = """[{"type":"int","logicalType":"date"},{"type":"record","name":"Unused","fields":[]}]"""
    // An attempted named lookup throws because this codec deliberately has no named codecs.
    val target = codec(reader, Set("date"))(_(0))
    assertEquals(ResolvingReader("\"int\"", target).decode(binary(_.writeInt(7))), 7)
  }

  test("mixed raw and converted time unions use only the wrappers needed by their Scala types") {
    val millis = """{"type":"int","logicalType":"time-millis"}"""
    val micros = """{"type":"long","logicalType":"time-micros"}"""
    val time = LocalTime.ofSecondOfDay(1)
    val modes = Vector(
      (Set.empty[String], TimeMillis(time), TimeMicros(time)),
      (Set("time-millis"), 1000, time),
      (Set("time-micros"), time, 1000000L),
      (Set("time-millis", "time-micros"), 1000, 1000000L)
    )
    for (raw, expectedMillis, expectedMicros) <- modes; nullable <- Vector(false, true) do
      val reader = if nullable then s"[\"null\",$millis,$micros]" else s"[$millis,$micros]"
      val target = codec(reader, raw)(_(0))
      def wrap(value: Any): Any = if nullable then Some(value) else value
      assertEquals(ResolvingReader("\"int\"", target).decode(binary(_.writeInt(1000))), wrap(expectedMillis))
      assertEquals(ResolvingReader("\"long\"", target).decode(binary(_.writeLong(1000000L))), wrap(expectedMicros))
      if nullable then assertEquals(ResolvingReader("\"null\"", target).decode(Array.emptyByteArray), None)
  }

  test("mixed time union defaults agree with wire values for either first branch") {
    val millis = """{"type":"int","logicalType":"time-millis"}"""
    val micros = """{"type":"long","logicalType":"time-micros"}"""
    for raw <- Vector(Set.empty[String], Set("time-millis"), Set("time-micros"), Set("time-millis", "time-micros")) do
      for (branches, wire, write, default) <- Vector[(String, String, BinaryOutput => Unit, Long)](
          (s"[$millis,$micros]", "\"int\"", _.writeInt(1000), 1000L),
          (s"[$micros,$millis]", "\"long\"", _.writeLong(1000000L), 1000000L)) do
        val expected = ResolvingReader(wire, codec(branches, raw)(_(0))).decode(binary(write))
        val reader = record(s"""{"name":"time","type":$branches,"default":$default}""")
        assertEquals(ResolvingReader(record(""), codec(reader, raw)(_(0))).decode(Array.emptyByteArray), expected)
  }

  test("raw decimal representations still enforce precision and scale schema compatibility") {
    for fixed <- Vector(false, true); (precision, scale) <- Vector((9, 2), (8, 3)) do
      val storage = if fixed then "\"type\":\"fixed\",\"name\":\"Decimal\",\"size\":4" else "\"type\":\"bytes\""
      val writer = s"""{$storage,"logicalType":"decimal","precision":8,"scale":2}"""
      val reader = s"""{$storage,"logicalType":"decimal","precision":$precision,"scale":$scale}"""
      val bytes = Bytes.fromArray(Array[Byte](0, 0, 0, 1))
      val encoded = if fixed then bytes.toArray else binary(_.writeBytes(bytes))
      intercept[SchemaResolutionException] {
        ResolvingReader(writer, codec(reader, Set("decimal"))(_(0))).decode(encoded)
      }
  }
