package avro2s.wire.resolution

import avro2s.wire.runtime.*
import munit.FunSuite
import org.apache.avro.Schema
import org.apache.avro.SchemaCompatibility
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.io.DecoderFactory

/** Table-driven coverage of writer/reader schema evolution across both directions. */
final class EvolutionMatrixSuite extends FunSuite:
  private def record(fields: String, name: String = "R", extra: String = ""): String =
    s"""{"type":"record","name":"$name","fields":[$fields]$extra}"""

  private def codec[A](json: String)(make: Array[Any] => A): AvroCodec[A] = new AvroCodec[A]:
    override val schemaJson: String = json
    override def read(in: AvroInput): A = throw new UnsupportedOperationException("Read-only matrix codec")
    override def write(value: A, out: AvroOutput): Unit = throw new UnsupportedOperationException("Read-only matrix codec")
    override def construct(values: Array[Any]): A = make(values)

  private def row(json: String): AvroCodec[Vector[Any]] = codec(json)(_.toVector)
  private def scalar(json: String): AvroCodec[Any] = codec(json)(_(0))

  private def binary(write: BinaryOutput => Unit): Array[Byte] =
    val out = new BinaryOutput()
    write(out)
    out.toByteArray

  private def schema(json: String): Schema = new Schema.Parser().parse(json)

  private def compatible(writer: String, reader: String): Boolean =
    SchemaCompatibility.checkReaderWriterCompatibility(schema(reader), schema(writer)).getType ==
      SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE

  private def javaRead(writer: String, reader: String, bytes: Array[Byte]): AnyRef =
    new GenericDatumReader[AnyRef](schema(writer), schema(reader))
      .read(null, DecoderFactory.get().binaryDecoder(bytes, null))

  private def normalizedJava(value: Any): Any = value match
    case text: CharSequence => text.toString
    case other => other

  private final case class FieldCase(label: String, writer: String, reader: String,
      write: BinaryOutput => Unit, expected: Vector[Any], compatible: Boolean)

  test("record field add, removal, defaults, nullability, aliases and order form a two-way matrix") {
    val base = record("""{"name":"a","type":"int"},{"name":"b","type":"string"}""")
    val addedDefault = record("""{"name":"a","type":"int"},{"name":"b","type":"string"},
      {"name":"c","type":"boolean","default":true}""")
    val addedNullableNoDefault = record("""{"name":"a","type":"int"},{"name":"b","type":"string"},
      {"name":"c","type":["null","string"]}""")
    val addedRequired = record("""{"name":"a","type":"int"},{"name":"b","type":"string"},
      {"name":"c","type":"boolean"}""")
    val removed = record("""{"name":"a","type":"int"}""")
    val reordered = record("""{"name":"b","type":"string"},{"name":"a","type":"int"}""")
    val renamedAlias = record("""{"name":"a","type":"int"},{"name":"renamed","aliases":["b"],"type":"string"}""")
    val renamedNoAlias = record("""{"name":"a","type":"int"},{"name":"renamed","type":"string"}""")
    val examples = Vector(
      FieldCase("reader adds field with default", base, addedDefault, out => { out.writeInt(7); out.writeString("x") }, Vector(7, "x", true), true),
      FieldCase("reader adds nullable field without default", base, addedNullableNoDefault, out => { out.writeInt(7); out.writeString("x") }, Vector.empty, false),
      FieldCase("reader adds required field without default", base, addedRequired, out => { out.writeInt(7); out.writeString("x") }, Vector.empty, false),
      FieldCase("writer adds required field and reader drops it", addedRequired, base, out => { out.writeInt(7); out.writeString("x"); out.writeBoolean(false) }, Vector(7, "x"), true),
      FieldCase("reader removes writer field", addedDefault, base, out => { out.writeInt(7); out.writeString("x"); out.writeBoolean(false) }, Vector(7, "x"), true),
      FieldCase("field order changes", base, reordered, out => { out.writeInt(7); out.writeString("x") }, Vector("x", 7), true),
      FieldCase("field alias renames", base, renamedAlias, out => { out.writeInt(7); out.writeString("x") }, Vector(7, "x"), true),
      FieldCase("field alias is reader directed", renamedAlias, base, out => { out.writeInt(7); out.writeString("x") }, Vector.empty, false),
      FieldCase("field rename without alias", base, renamedNoAlias, out => { out.writeInt(7); out.writeString("x") }, Vector.empty, false),
      FieldCase("unaliased field rename in writer also fails", renamedNoAlias, base, out => { out.writeInt(7); out.writeString("x") }, Vector.empty, false),
      FieldCase("reorder is symmetric", reordered, base, out => { out.writeString("x"); out.writeInt(7) }, Vector(7, "x"), true)
    )
    examples.foreach { example =>
      assertEquals(compatible(example.writer, example.reader), example.compatible, example.label)
      if example.compatible then
        val bytes = binary(example.write)
        val actual = ResolvingReader(example.writer, row(example.reader)).decode(bytes)
        assertEquals(actual, example.expected, example.label)
        val java = javaRead(example.writer, example.reader, bytes).asInstanceOf[GenericRecord]
        assertEquals((0 until java.getSchema.getFields.size).map(i => normalizedJava(java.get(i))).toVector,
          actual, example.label)
      else
        intercept[SchemaResolutionException] {
          ResolvingReader(example.writer, row(example.reader)).decode(binary(example.write))
        }
    }
    // Nullability alone does not provide a default when a reader adds a field.
    assert(!compatible(base, addedNullableNoDefault))
  }

  test("all Avro numeric promotions resolve to the reader type and every demotion is rejected") {
    final case class NumberCase(writerType: String, readerType: String, write: BinaryOutput => Unit,
        expected: Any)
    val promotions = Vector(
      NumberCase("int", "long", _.writeInt(11), 11L),
      NumberCase("int", "float", _.writeInt(11), 11.0f),
      NumberCase("int", "double", _.writeInt(11), 11.0d),
      NumberCase("long", "float", _.writeLong(11L), 11.0f),
      NumberCase("long", "double", _.writeLong(11L), 11.0d),
      NumberCase("float", "double", _.writeFloat(1.5f), 1.5d)
    )
    promotions.foreach { item =>
      val writer = s"\"${item.writerType}\""
      val reader = s"\"${item.readerType}\""
      assert(compatible(writer, reader), s"${item.writerType} -> ${item.readerType}")
      val actual = ResolvingReader(writer, scalar(reader)).decode(binary(item.write))
      assertEquals(actual, item.expected)
      assertEquals(actual.getClass, item.expected.getClass)
      assertEquals(javaRead(writer, reader, binary(item.write)).getClass, actual match
        case _: Long => classOf[java.lang.Long]
        case _: Float => classOf[java.lang.Float]
        case _: Double => classOf[java.lang.Double]
        case _ => classOf[java.lang.Integer])
    }
    val types = Vector("int", "long", "float", "double")
    val supported = Set("int->long", "int->float", "int->double", "long->float", "long->double", "float->double")
    for writerType <- types; readerType <- types if writerType != readerType do
      val key = s"$writerType->$readerType"
      if supported(key) then assert(compatible(s"\"$writerType\"", s"\"$readerType\""), key)
      else
        assert(!compatible(s"\"$writerType\"", s"\"$readerType\""), key)
        val wire = writerType match
          case "int" => binary(_.writeInt(1))
          case "long" => binary(_.writeLong(1L))
          case "float" => binary(_.writeFloat(1.0f))
          case _ => binary(_.writeDouble(1.0d))
        intercept[SchemaResolutionException] {
          ResolvingReader(s"\"$writerType\"", scalar(s"\"$readerType\"")).decode(wire)
        }
    assertEquals(promotions.size, 6)
  }

  test("record, field, enum and fixed aliases match names while unaliased names fail") {
    val recordWriter = record("""{"name":"value","type":"int"}""", "Old")
    val recordReader = record("""{"name":"value","type":"int"}""", "New", ""","aliases":["Old"]""")
    val recordNoAlias = record("""{"name":"value","type":"int"}""", "New")
    val rwBytes = binary(_.writeInt(4))
    assert(compatible(recordWriter, recordReader))
    assertEquals(ResolvingReader(recordWriter, row(recordReader)).decode(rwBytes), Vector(4))
    assert(!compatible(recordWriter, recordNoAlias))
    intercept[SchemaResolutionException](ResolvingReader(recordWriter, row(recordNoAlias)).decode(rwBytes))

    val enumWriter = """{"type":"enum","name":"OldEnum","symbols":["A","B"]}"""
    val enumReader = """{"type":"enum","name":"NewEnum","aliases":["OldEnum"],"symbols":["A","B"]}"""
    val enumNoAlias = """{"type":"enum","name":"NewEnum","symbols":["A","B"]}"""
    assert(compatible(enumWriter, enumReader))
    assertEquals(ResolvingReader(enumWriter, scalar(enumReader)).decode(binary(_.writeEnum(1))), 1)
    assert(!compatible(enumWriter, enumNoAlias))
    intercept[SchemaResolutionException](ResolvingReader(enumWriter, scalar(enumNoAlias)).decode(binary(_.writeEnum(0))))

    val fixedWriter = """{"type":"fixed","name":"OldFixed","size":2}"""
    val fixedReader = """{"type":"fixed","name":"NewFixed","aliases":["OldFixed"],"size":2}"""
    val fixedNoAlias = """{"type":"fixed","name":"NewFixed","size":2}"""
    val fixedBytes = Array[Byte](1, 2)
    assert(compatible(fixedWriter, fixedReader))
    assertEquals(ResolvingReader(fixedWriter, scalar(fixedReader)).decode(fixedBytes), Bytes.fromArray(fixedBytes))
    assert(!compatible(fixedWriter, fixedNoAlias))
    intercept[SchemaResolutionException](ResolvingReader(fixedWriter, scalar(fixedNoAlias)).decode(fixedBytes))
  }

  test("enum symbol additions, removals, reordering and fallback are datum dependent") {
    val old = """{"type":"enum","name":"Choice","symbols":["A","B"]}"""
    val reorderedAndAdded = """{"type":"enum","name":"Choice","symbols":["B","A","C"]}"""
    val reduced = """{"type":"enum","name":"Choice","symbols":["A"]}"""
    val fallback = """{"type":"enum","name":"Choice","symbols":["A"],"default":"A"}"""
    assert(!compatible(old, reduced))
    assert(compatible(old, fallback))
    for (symbol, index) <- Vector("A" -> 0, "B" -> 1) do
      assertEquals(ResolvingReader(old, scalar(reorderedAndAdded)).decode(binary(_.writeEnum(index))), if symbol == "A" then 1 else 0)
      assertEquals(javaRead(old, reorderedAndAdded, binary(_.writeEnum(index))).toString, symbol)
      if symbol == "A" then assertEquals(ResolvingReader(old, scalar(reduced)).decode(binary(_.writeEnum(index))), 0)
      else intercept[SchemaResolutionException](ResolvingReader(old, scalar(reduced)).decode(binary(_.writeEnum(index))))
      assertEquals(ResolvingReader(old, scalar(fallback)).decode(binary(_.writeEnum(index))), 0)
    val addedWriter = """{"type":"enum","name":"Choice","symbols":["A","B","C"]}"""
    val oldReaderFallback = """{"type":"enum","name":"Choice","symbols":["A","B"],"default":"B"}"""
    val oldReaderNoFallback = """{"type":"enum","name":"Choice","symbols":["A","B"]}"""
    assert(compatible(addedWriter, oldReaderFallback))
    assert(!compatible(addedWriter, oldReaderNoFallback))
    assertEquals(ResolvingReader(addedWriter, scalar(oldReaderFallback)).decode(binary(_.writeEnum(2))), 1)
    intercept[SchemaResolutionException](ResolvingReader(addedWriter, scalar(oldReaderNoFallback)).decode(binary(_.writeEnum(2))))
  }

  test("writer union additions, removals, reorders and reader branch priority resolve selected data") {
    val writer = """["null","int","string"]"""
    val reader = """["string","long","null"]"""
    assert(compatible(writer, reader))
    val readerCodec = scalar(reader)
    val cases = Vector[(Int, BinaryOutput => Unit, Any)](
      (0, _.writeNull(), None),
      (1, _.writeInt(12), Some(12L)),
      (2, _.writeString("v"), Some("v"))
    )
    cases.foreach { (index, write, expected) =>
      val bytes = binary { out => out.writeIndex(index); write(out) }
      val actual = ResolvingReader(writer, readerCodec).decode(bytes)
      assertEquals(actual, expected, s"writer branch $index")
      if index != 0 then assert(actual.isInstanceOf[Some[?]], s"reader union wrapper for branch $index")
      assert(javaRead(writer, reader, bytes) != null || index == 0)
    }
    val removedReader = """["null","long"]"""
    assert(!compatible(writer, removedReader))
    val removedBranch = binary { out => out.writeIndex(2); out.writeString("unreadable") }
    intercept[SchemaResolutionException](ResolvingReader(writer, scalar(removedReader)).decode(removedBranch))
    // Whole schemas are incompatible, but actual success/failure still depends on the selected writer branch.
    assertEquals(ResolvingReader(writer, scalar(removedReader)).decode(binary { out => out.writeIndex(1); out.writeInt(9) }), Some(9L))

    val promotedWriter = "[\"int\",\"long\"]"
    val orderedReader = "[\"long\",\"float\"]"
    val reverseReader = "[\"float\",\"long\"]"
    val intDatum = binary { out => out.writeIndex(0); out.writeInt(3) }
    assert(compatible(promotedWriter, orderedReader))
    assertEquals(ResolvingReader(promotedWriter, scalar(orderedReader)).decode(intDatum), 3L)
    assertEquals(ResolvingReader(promotedWriter, scalar(reverseReader)).decode(intDatum), 3.0f)
    assert(javaRead(promotedWriter, orderedReader, intDatum).isInstanceOf[java.lang.Long])
    assert(javaRead(promotedWriter, reverseReader, intDatum).isInstanceOf[java.lang.Float])
  }

  test("container evolution carries promotions through direct, array and map values") {
    val contexts = Vector(
      ("direct", "\"int\"", "\"long\"", (out: BinaryOutput) => out.writeInt(5)),
      ("array", "{\"type\":\"array\",\"items\":\"int\"}", "{\"type\":\"array\",\"items\":\"long\"}",
        (out: BinaryOutput) => { out.writeArrayStart(2); out.writeInt(5); out.writeInt(6); out.writeArrayEnd() }),
      ("map", "{\"type\":\"map\",\"values\":\"int\"}", "{\"type\":\"map\",\"values\":\"long\"}",
        (out: BinaryOutput) => { out.writeMapStart(1); out.writeString("k"); out.writeInt(5); out.writeMapEnd() })
    )
    contexts.foreach { (label, writer, reader, write) =>
      assert(compatible(writer, reader), label)
      val result = ResolvingReader(writer, scalar(reader)).decode(binary(write))
      label match
        case "direct" => assertEquals(result, 5L)
        case "array" => assertEquals(result, Vector(5L, 6L))
        case _ => assertEquals(result, Map("k" -> 5L))
      assert(compatible(reader, writer) == false, s"demotion is rejected in $label")
    }
  }

  test("adjacent record-name aliases can preserve history while losing the oldest name") {
    val v1 = record("""{"name":"n","type":"int"}""", "A")
    val v2 = record("""{"name":"n","type":"int"}""", "B", ""","aliases":["A"]""")
    val v3 = record("""{"name":"n","type":"int"}""", "C", ""","aliases":["B"]""")
    assert(compatible(v1, v2), "V2 reader accepts V1 writer")
    assert(compatible(v2, v3), "V3 reader accepts V2 writer")
    assert(!compatible(v1, v3), "V3 does not retain V1 alias")
    val bytes = binary(_.writeInt(17))
    assertEquals(ResolvingReader(v1, row(v2)).decode(bytes), Vector(17))
    assertEquals(ResolvingReader(v2, row(v3)).decode(bytes), Vector(17))
    intercept[SchemaResolutionException](ResolvingReader(v1, row(v3)).decode(bytes))
    assertEquals(javaRead(v1, v2, bytes).asInstanceOf[GenericRecord].get("n"), java.lang.Integer.valueOf(17))
  }
