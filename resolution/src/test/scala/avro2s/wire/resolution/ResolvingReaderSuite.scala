package avro2s.wire.resolution

import avro2s.wire.runtime.*
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import munit.FunSuite
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.io.DecoderFactory

final class ResolvingReaderSuite extends ResolvingReaderChecks(CodecExecution.Direct)
final class StackSafeResolvingReaderSuite extends ResolvingReaderChecks(CodecExecution.StackSafe)

abstract class ResolvingReaderChecks(executionMode: CodecExecution) extends FunSuite:
  private def record(fields: String, name: String = "R", extra: String = ""): String =
    s"""{"type":"record","name":"$name","fields":[$fields]$extra}"""

  private def field(kind: String): String = record(s"""{"name":"value","type":$kind}""")

  private def codec[A](json: String, named: Map[String, AvroCodec[?]] = Map.empty)(make: Array[Any] => A): AvroCodec[A] =
    new AvroCodec[A]:
      override def execution: CodecExecution = executionMode
      override val schemaJson: String = json
      override def read(in: AvroInput): A = throw new UnsupportedOperationException("Matching-schema path not used in this test")
      override def write(value: A, out: AvroOutput): Unit = throw new UnsupportedOperationException("Read-only test codec")
      override def construct(values: Array[Any]): A = make(values)
      override def namedCodec(name: String): AvroCodec[?] = named.getOrElse(name, super.namedCodec(name))

  private def row(json: String, named: Map[String, AvroCodec[?]] = Map.empty): AvroCodec[Vector[Any]] =
    codec(json, named)(_.toVector)

  private def scalar(json: String): AvroCodec[Any] = codec(json)(_(0))

  private def binary(write: BinaryOutput => Unit): Array[Byte] =
    val out = new BinaryOutput()
    write(out)
    out.toByteArray

  private def javaRead(writer: String, reader: String, bytes: Array[Byte]): AnyRef =
    val w = new Schema.Parser().parse(writer)
    val r = new Schema.Parser().parse(reader)
    new GenericDatumReader[AnyRef](w, r).read(null, DecoderFactory.get().binaryDecoder(bytes, null))

  test("record and field aliases, reorder, promotion, skip, and default agree with Java Avro") {
    val writer = record("""{"name":"removed","type":{"type":"array","items":"int"}},
      {"name":"age","type":"int"},{"name":"oldName","type":"string"}""", "Old")
    val reader = record("""{"name":"name","aliases":["oldName"],"type":"string"},
      {"name":"active","type":"boolean","default":true},{"name":"age","type":"long"}""",
      "New", """, "aliases":["Old"]""")
    val bytes = binary { out =>
      out.writeArrayStart(2)
      out.writeInt(10000)
      out.writeInt(10001)
      out.writeArrayEnd()
      out.writeInt(23)
      out.writeString("Ada")
    }
    assertEquals(ResolvingReader(writer, row(reader)).decode(bytes), Vector[Any]("Ada", true, 23L))
    val javaValue = javaRead(writer, reader, bytes).asInstanceOf[GenericRecord]
    assertEquals(javaValue.get("name").toString, "Ada")
    assertEquals(javaValue.get("active"), java.lang.Boolean.TRUE)
    assertEquals(javaValue.get("age"), java.lang.Long.valueOf(23))
  }

  test("numeric promotions produce the reader's numeric representation") {
    val examples = Vector[(String, String, BinaryOutput => Unit, Any)](
      ("int", "long", _.writeInt(-10000), -10000L),
      ("int", "float", _.writeInt(123), 123.0f),
      ("int", "double", _.writeInt(123), 123.0d),
      ("long", "float", _.writeLong(123), 123.0f),
      ("long", "double", _.writeLong(123), 123.0d),
      ("float", "double", _.writeFloat(1.25f), 1.25d)
    )
    examples.foreach { (written, expected, write, value) =>
      val writer = field(s"\"$written\"")
      val reader = field(s"\"$expected\"")
      val bytes = binary(write)
      val actual = ResolvingReader(writer, row(reader)).decode(bytes).head
      assertEquals(actual, value)
      assertEquals(actual.getClass, value.getClass)
      assertEquals(javaRead(writer, reader, bytes).asInstanceOf[GenericRecord].get("value"), value)
    }
  }

  test("string and bytes promotions preserve UTF-8 and reject invalid promoted text") {
    val bytes = binary(_.writeString("λ😀"))
    val asBytes = ResolvingReader("\"string\"", scalar("\"bytes\"")).decode(bytes).asInstanceOf[Bytes]
    assertEquals(asBytes.toArray.toSeq, "λ😀".getBytes(StandardCharsets.UTF_8).toSeq)
    val asText = ResolvingReader("\"bytes\"", scalar("\"string\"")).decode(bytes)
    assertEquals(asText, "λ😀")
    val bad = binary(_.writeBytes(Bytes.fromArray(Array(0xc0.toByte, 0x80.toByte))))
    intercept[AvroDecodingException](ResolvingReader("\"bytes\"", scalar("\"string\"")).decode(bad))
  }

  test("incompatible writer union branches fail only when selected") {
    val reader = ResolvingReader("""["int","string"]""", scalar("\"long\""))
    assertEquals(reader.decode(binary { out => out.writeIndex(0); out.writeInt(7) }), 7L)
    intercept[SchemaResolutionException] {
      reader.decode(binary { out => out.writeIndex(1); out.writeString("incompatible") })
    }
    intercept[AvroDecodingException](reader.decode(binary(_.writeIndex(2))))
  }

  test("reader union prefers the exact branch before promotion, matching Java Avro") {
    val reader = """["long","int"]"""
    val bytes = binary(_.writeInt(7))
    val actual = ResolvingReader("\"int\"", scalar(reader)).decode(bytes)
    assert(actual.isInstanceOf[Int])
    assertEquals(actual, 7)
    assert(javaRead("\"int\"", reader, bytes).isInstanceOf[java.lang.Integer])
  }

  test("metadata-only schema changes preserve the selected writer union branch") {
    val writer = """["long","int"]"""
    val reader = """["long",{"type":"int","doc":"reader metadata"}]"""
    val bytes = binary { out => out.writeIndex(1); out.writeInt(7) }
    val direct = new AvroCodec[Any]:
      override def execution: CodecExecution = executionMode
      override val schemaJson = writer
      override def read(in: AvroInput): Any =
        if in.readIndex() == 0 then in.readLong() else in.readInt()
      override def write(value: Any, out: AvroOutput): Unit = throw new UnsupportedOperationException()
    val exact = ResolvingReader(writer, direct).decode(bytes)
    val withMetadata = ResolvingReader(writer, scalar(reader)).decode(bytes)
    assertEquals(withMetadata, exact)
    assertEquals(withMetadata.getClass, exact.getClass)
    assert(withMetadata.isInstanceOf[Int])
    assert(javaRead(writer, reader, bytes).isInstanceOf[java.lang.Integer])
  }

  test("logical time union metadata changes preserve the branch wrapper and units") {
    val writer = """[{"type":"long","logicalType":"time-micros"},{"type":"int","logicalType":"time-millis"}]"""
    val reader = """[{"type":"long","logicalType":"time-micros"},{"type":"int","logicalType":"time-millis","doc":"updated"}]"""
    val bytes = binary { out => out.writeIndex(1); out.writeInt(1000) }
    assertEquals(ResolvingReader(writer, scalar(reader)).decode(bytes), TimeMillis(java.time.LocalTime.ofSecondOfDay(1)))
  }

  test("reordered nullable general unions wrap reader branch values in Option") {
    val resolving = ResolvingReader("""["string","null","int"]""", scalar("""["null","long","string"]"""))
    assertEquals(resolving.decode(binary { out => out.writeIndex(0); out.writeString("value") }), Some("value"))
    assertEquals(resolving.decode(binary { out => out.writeIndex(1); out.writeNull() }), None)
    val promoted = resolving.decode(binary { out => out.writeIndex(2); out.writeInt(9) })
    assertEquals(promoted, Some(9L))
    assert(promoted.asInstanceOf[Option[Any]].get.isInstanceOf[Long])
    assertEquals(ResolvingReader("\"null\"", scalar("""["null"]""")).decode(Array.emptyByteArray), null)
  }

  test("enum symbols remap by name and reader enum defaults cover missing symbols") {
    val writer = """{"type":"enum","name":"Choice","symbols":["A","B","C"]}"""
    val reader = """{"type":"enum","name":"Choice","symbols":["B","A","D"],"default":"D"}"""
    val resolving = ResolvingReader(writer, scalar(reader))
    assertEquals(resolving.decode(binary(_.writeEnum(0))), 1)
    assertEquals(resolving.decode(binary(_.writeEnum(1))), 0)
    assertEquals(resolving.decode(binary(_.writeEnum(2))), 2)
    assertEquals(javaRead(writer, reader, binary(_.writeEnum(2))).toString, "D")
    intercept[AvroDecodingException](resolving.decode(binary(_.writeEnum(3))))
    val noDefault = """{"type":"enum","name":"Choice","symbols":["A"]}"""
    intercept[SchemaResolutionException](ResolvingReader(writer, scalar(noDefault)).decode(binary(_.writeEnum(2))))
  }

  test("fixed aliases preserve bytes while incompatible sizes fail") {
    val writer = """{"type":"fixed","name":"Old","size":2}"""
    val reader = """{"type":"fixed","name":"New","aliases":["Old"],"size":2}"""
    val value = Bytes.fromArray(Array[Byte](1, 2))
    assertEquals(ResolvingReader(writer, scalar(reader)).decode(value.toArray), value)
    val bad = """{"type":"fixed","name":"Old","size":3}"""
    intercept[SchemaResolutionException](ResolvingReader(writer, scalar(bad)).decode(value.toArray))
  }

  test("recursive records resolve through memoized plans and obey depth limits") {
    val writer = record("""{"name":"value","type":"int"},{"name":"next","type":["null","Node"]}""", "Node")
    val reader = record("""{"name":"next","type":["null","Node"]},{"name":"value","type":"long"},
      {"name":"label","type":"string","default":"new"}""", "Node")
    val bytes = binary { out =>
      out.writeInt(1)
      out.writeIndex(1)
      out.writeInt(2)
      out.writeIndex(0)
    }
    val resolving = ResolvingReader(writer, row(reader))
    assertEquals(resolving.decode(bytes), Vector[Any](Some(Vector[Any](None, 2L, "new")), 1L, "new"))
    intercept[AvroDecodingException](resolving.decode(bytes, DecodeLimits(maxNestingDepth = Some(1))))
    intercept[AvroDecodingException](resolving.decode(bytes.dropRight(1)))
  }

  test("skipping a recursive writer-only record does not require a model codec") {
    val writer = record("""{"name":"discard","type":{"type":"record","name":"Discard",
      "fields":[{"name":"payload","type":"bytes"},{"name":"next","type":["null","Discard"]}]}},
      {"name":"keep","type":"int"}""")
    val reader = record("""{"name":"keep","type":"int"}""")
    val bytes = binary { out =>
      out.writeBytes(Bytes.fromArray(Array[Byte](1, 2)))
      out.writeIndex(1)
      out.writeBytes(Bytes.empty)
      out.writeIndex(0)
      out.writeInt(42)
    }
    assertEquals(ResolvingReader(writer, row(reader)).decode(bytes), Vector[Any](42))
  }

  test("defaults preserve array, map, bytes, fixed, and non-first null union values") {
    val fixedJson = """{"type":"fixed","name":"F","size":2}"""
    val reader = record(s"""{"name":"array","type":{"type":"array","items":"int"},"default":[1,2]},
      {"name":"map","type":{"type":"map","values":"string"},"default":{"x":"value"}},
      {"name":"bytes","type":"bytes","default":"ÿ"},
      {"name":"fixed","type":$fixedJson,"default":"ab"},
      {"name":"option","type":["int","null"],"default":null}""")
    val resolving = ResolvingReader(record(""), row(reader, Map("F" -> scalar(fixedJson))))
    assertEquals(resolving.decode(Array.emptyByteArray), Vector[Any](
      Vector(1, 2), Map("x" -> "value"), Bytes.fromArray(Array(0xff.toByte)),
      Bytes.fromArray(Array[Byte](97, 98)), None))
  }

  test("default record construction receives fresh mutable slot arrays on every read") {
    val nested = """{"type":"record","name":"DefaultRecord","fields":[{"name":"value","type":"int"}]}"""
    val reader = record(s"""{"name":"nested","type":$nested,"default":{"value":7}}""")
    val nestedCodec = codec[Array[Any]](nested)(identity)
    val resolving = ResolvingReader(record(""), row(reader, Map("DefaultRecord" -> nestedCodec)))
    val first = resolving.decode(Array.emptyByteArray).head.asInstanceOf[Array[Any]]
    first(0) = 99
    val second = resolving.decode(Array.emptyByteArray).head.asInstanceOf[Array[Any]]
    assertEquals(second.toVector, Vector[Any](7))
    assert(first ne second)
  }

  test("reader defaults remain part of the resolution identity") {
    val writer = record("")
    def reader(default: Int): String = record(s"""{"name":"value","type":"int","default":$default}""")
    assertEquals(ResolvingReader(writer, row(reader(1))).decode(Array.emptyByteArray), Vector[Any](1))
    assertEquals(ResolvingReader(writer, row(reader(2))).decode(Array.emptyByteArray), Vector[Any](2))
  }

  test("primitive logical values and logical defaults use reader conversions") {
    val date = """{"type":"int","logicalType":"date"}"""
    assertEquals(ResolvingReader("\"int\"", scalar(date)).decode(binary(_.writeInt(-1))), LocalDate.of(1969, 12, 31))
    val reader = record(s"""{"name":"date","type":$date,"default":0}""")
    assertEquals(ResolvingReader(record(""), row(reader)).decode(Array.emptyByteArray), Vector[Any](LocalDate.ofEpochDay(0)))
  }

  test("decimal schema resolution requires equal precision and scale") {
    val writer = """{"type":"bytes","logicalType":"decimal","precision":8,"scale":2}"""
    val reader = """{"type":"bytes","logicalType":"decimal","precision":9,"scale":2}"""
    intercept[SchemaResolutionException] {
      ResolvingReader(writer, scalar(reader)).decode(binary(_.writeBytes(Bytes.fromArray(Array[Byte](1)))))
    }
  }

  test("missing required fields and ambiguous field aliases fail explicitly") {
    intercept[SchemaResolutionException] {
      ResolvingReader(record(""), row(field("\"int\""))).decode(Array.emptyByteArray)
    }
    val reader = record("""{"name":"a","type":"int","aliases":["old"]},
      {"name":"b","type":"int","aliases":["old"]}""")
    intercept[SchemaResolutionException] {
      ResolvingReader(record("""{"name":"old","type":"int"}"""), row(reader))
    }
  }

  test("invalid schema metadata and unknown logical annotations are rejected") {
    intercept[SchemaResolutionException] {
      ResolvingReader("\"int\" \"string\"", scalar("\"long\""))
    }
    intercept[SchemaResolutionException] {
      ResolvingReader("\"int\"", scalar("""{"type":"int","logicalType":"not-supported"}"""))
    }
    intercept[SchemaResolutionException] {
      ResolvingReader("""["int","int"]""", scalar("\"long\""))
    }
    intercept[SchemaResolutionException] {
      ResolvingReader("\"bytes\"", scalar("""{"type":"bytes","logicalType":"decimal","precision":2,"scale":3}"""))
    }
  }

  test("matching JSON uses the generated codec directly without factory hooks") {
    val direct = new AvroCodec[Int]:
      override def execution: CodecExecution = executionMode
      override val schemaJson = "\"int\""
      override def read(in: AvroInput): Int = in.readInt()
      override def write(value: Int, out: AvroOutput): Unit = out.writeInt(value)
    assertEquals(ResolvingReader(direct.schemaJson, direct).decode(binary(_.writeInt(42))), 42)
  }
