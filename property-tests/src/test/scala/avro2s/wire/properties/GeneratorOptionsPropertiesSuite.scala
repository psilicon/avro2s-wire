package avro2s.wire.properties

import avro2s.wire.compiler.{CodeGenerator, DecimalType, GeneratorConfig, LogicalType, LogicalTypeMode}
import avro2s.wire.resolution.ResolvingReader
import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericFixed, IndexedRecord}
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Option expectations use Java's schema graph and physical datums, never compiler internals. */
final class GeneratorOptionsPropertiesSuite extends munit.FunSuite:
  override val munitTimeout = 10.minutes
  private lazy val target =
    val root = Path.of(sys.props("avro2s.wire.property.target"), "generator-options")
    Files.createDirectories(root)
    Files.createTempDirectory(root, "campaign-")
  private val known = Set("date", "time-millis", "time-micros", "timestamp-millis", "timestamp-micros", "timestamp-nanos",
    "local-timestamp-millis", "local-timestamp-micros", "local-timestamp-nanos", "uuid", "decimal", "big-decimal", "duration")
  private val halfRaw = Set("date", "time-millis", "timestamp-micros", "local-timestamp-nanos", "uuid", "decimal", "duration")
  private val mappings = Map("avro2s.wire.propertymatrix" -> "options.matrix", "option.source" -> "options.models",
    "option.source.deep" -> "options.precise", "" -> "options.unnamed", "option.strip" -> "")
  private def config(mode: DecimalType, raw: Set[String], names: Map[String, String] = mappings): GeneratorConfig =
    GeneratorConfig(mode, namespaceMappings = names,
      logicalTypes = LogicalType.values.map(t => t -> (if raw(t.avroName) then LogicalTypeMode.Raw else LogicalTypeMode.Converted)).toMap)
  private def rawTypes(config: GeneratorConfig): Set[String] =
    config.logicalTypes.collect { case (logical, LogicalTypeMode.Raw) => logical.avroName }.toSet
  private def options(config: GeneratorConfig): NativeValues.Options =
    NativeValues.Options(config.decimalType == DecimalType.Java, config.namespaceMappings, rawTypes(config))
  private def field(name: String, schema: Schema): Schema.Field = new Schema.Field(name, schema, null, null.asInstanceOf[AnyRef])
  private def record(name: String, namespace: String, fields: Vector[Schema.Field]): Schema =
    val result = Schema.createRecord(name, null, namespace, false)
    result.setFields(fields.asJava)
    result
  private def datum(schema: Schema, values: AnyRef*): GenericData.Record =
    val result = new GenericData.Record(schema)
    values.zipWithIndex.foreach((value, i) => result.put(i, value))
    result

  private def namedSchemas(root: Schema): Vector[Schema] =
    val seen = mutable.Set.empty[String]
    val result = mutable.ArrayBuffer.empty[Schema]
    def visit(schema: Schema): Unit = schema.getType match
      case Schema.Type.RECORD | Schema.Type.ENUM | Schema.Type.FIXED =>
        if seen.add(schema.getFullName) then
          result += schema
          if schema.getType == Schema.Type.RECORD then schema.getFields.asScala.foreach(f => visit(f.schema))
      case Schema.Type.UNION => schema.getTypes.asScala.foreach(visit)
      case Schema.Type.ARRAY => visit(schema.getElementType)
      case Schema.Type.MAP => visit(schema.getValueType)
      case _ => ()
    visit(root)
    result.toVector

  private def check(c: SchemaCase, code: CompiledCase, config: GeneratorConfig, exactWire: Boolean = false): Unit =
    code.variants.foreach(checkCodec(c, _, config, exactWire))

  private def checkCodec(c: SchemaCase, code: CompiledCase, config: GeneratorConfig, exactWire: Boolean): Unit =
    val raw = rawTypes(config)
    assertEquals(code.codec.schemaJson, c.schema.toString, "Namespace mapping must preserve the embedded original schema")
    assertEquals(code.codec.rawLogicalTypes, raw)
    namedSchemas(c.schema).foreach { schema =>
      val named = code.codec.namedCodec(schema.getFullName)
      assertEquals(new Schema.Parser().parse(named.schemaJson).getFullName, schema.getFullName)
      assertEquals(named.rawLogicalTypes, raw, s"Named codec ${schema.getFullName} lost configuration")
      assertEquals(named.execution, code.codec.execution, s"Named codec ${schema.getFullName} lost its execution mode")
    }
    c.values.zip(code.values).foreach { (reference, native) =>
      val javaBytes = JavaOracle.encode(c.schema, reference)
      val actual = code.codec.decode(javaBytes)
      assert(JavaOracle.nativeEqual(actual, native), s"Wrong native representation in ${c.schema.getFullName}: $actual != $native")
      val bytes = code.codec.encode(native)
      assertEquals(JavaOracle.normalized(c.schema, JavaOracle.decode(c.schema, bytes), raw), JavaOracle.normalized(c.schema, reference, raw))
      assert(JavaOracle.nativeEqual(code.codec.decode(bytes), native))
      if exactWire then assertEquals(bytes.toVector, javaBytes.toVector, "Physical bytes or selected union index changed")
    }

  private def compiled[A](cases: Vector[SchemaCase], name: String, settings: GeneratorConfig)(body: Vector[CompiledCase] => A): A =
    val directory = target.resolve(name)
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("configuration.txt"), settings.toString + "\n")
    val code = CompiledCases.compile(cases, directory, settings, checkModelTypes = true)
    try body(code.cases)
    finally code.close()

  /** The existing 15 logical/storage atoms occur in each of the six collection/union contexts. */
  private lazy val matrix = SchemaCases.contexts.map { context =>
    val cells = SchemaCases.mandatoryCases.filter { c =>
      Option(c.schema.getProp("propertyCase")).exists { label =>
        val parts = label.split(":")
        parts.length == 3 && parts(0) == "matrix" && parts(2) == context &&
          !Set("null", "boolean", "int", "long", "float", "double", "bytes", "string", "enum", "fixed")(parts(1))
      }
    }
    assertEquals(cells.size, 15)
    val root = record("Context_" + context.replace('-', '_'), "option.source.matrix", cells.map { c =>
      field(c.schema.getProp("propertyCase").split(":")(1).replace('-', '_'), c.schema.getField("payload").schema)
    })
    val values = Vector.tabulate(12) { index =>
      datum(root, cells.map(c => c.values(index).asInstanceOf[IndexedRecord].get(0).asInstanceOf[AnyRef])*)
    }
    SchemaCase(root, values)
  }

  test("configured models compile across every logical storage/context, raw selection, and decimal mode") {
    assertEquals(LogicalType.values.map(_.avroName).toSet, known)
    for
      decimal <- Vector(DecimalType.Scala, DecimalType.Java)
      (raw, index) <- Vector(Set.empty[String], known, halfRaw, known -- halfRaw).zipWithIndex
    do
      val settings = config(decimal, raw)
      val unions = SchemaCases.mandatoryCases.filter(c =>
        Set("composite:NamedLogicalBranches", "composite:AmbiguousTimes").contains(c.schema.getProp("propertyCase")))
      val cases = matrix ++ unions
      compiled(cases, s"matrix-$decimal-$index", settings) { codes =>
        cases.zip(codes).foreach((c, code) => check(c, code, settings, exactWire = !c.schema.getName.contains("map")))
      }
    Files.writeString(target.resolve("coverage.txt"),
      (for context <- SchemaCases.contexts; logical <- known.toVector.sorted; mode <- Vector("Scala", "Java"); raw <- Vector("raw", "converted")
        yield s"$logical:$context:$mode:$raw").mkString("\n") + "\n")
  }

  private lazy val namespaceCases: Vector[SchemaCase] =
    val root = new Schema.Parser().parse("""{"type":"record","name":"Root","namespace":"option.source","fields":[
      {"name":"node","type":{"type":"record","name":"Node","namespace":"option.source.deep.branch","fields":[
        {"name":"id","type":{"type":"int","logicalType":"date"}},
        {"name":"next","type":["null","option.source.deep.branch.Node"]}]}},
      {"name":"state","type":{"type":"enum","name":"State","namespace":"option.source.deep","symbols":["A","B"]}},
      {"name":"token","type":{"type":"fixed","name":"Token","namespace":"option.source.deep","size":16,"logicalType":"uuid"}},
      {"name":"boundary","type":{"type":"record","name":"Boundary","namespace":"option.sourceish","fields":[{"name":"id","type":"long"}]}},
      {"name":"unnamed","type":{"type":"record","name":"Unqualified","namespace":"","fields":[{"name":"id","type":"int"}]}}
    ]}""")
    val node = root.getField("node").schema
    val state = root.getField("state").schema
    val token = root.getField("token").schema
    val values = Vector.tabulate(4) { i =>
      datum(root, datum(node, Int.box(i), datum(node, Int.box(-i), null)), new GenericData.EnumSymbol(state, if i % 2 == 0 then "A" else "B"),
        new GenericData.Fixed(token, Array.tabulate[Byte](16)(j => (i + j * 17).toByte)),
        datum(root.getField("boundary").schema, Long.box(i)), datum(root.getField("unnamed").schema, Int.box(i)))
    }
    val stripped = new Schema.Parser().parse("""{"type":"record","name":"Stripped","namespace":"option.strip","fields":[
      {"name":"value","type":{"type":"fixed","name":"RawFixed","size":2}},
      {"name":"state","type":{"type":"enum","name":"RawEnum","symbols":["A"]}},
      {"name":"child","type":{"type":"record","name":"Child","namespace":"option.strip.child","fields":[{"name":"id","type":"int"}]}}
    ]}""")
    val strippedValue = datum(stripped, new GenericData.Fixed(stripped.getField("value").schema, Array[Byte](0, -1)),
      new GenericData.EnumSymbol(stripped.getField("state").schema, "A"), datum(stripped.getField("child").schema, Int.box(17)))
    Vector(SchemaCase(root, values), SchemaCase(stripped, Vector(strippedValue)))

  test("namespace prefix matching maps nested, recursive, enum, fixed and default-package names without changing Avro identities") {
    val settings = config(DecimalType.Scala, halfRaw)
    val oracle = options(settings)
    val expectedNames = Set("options.models.Root", "options.precise.branch.Node", "options.precise.State", "options.precise.Token",
      "option.sourceish.Boundary", "options.unnamed.Unqualified", "Stripped", "RawFixed", "RawEnum", "child.Child")
    assertEquals(namespaceCases.flatMap(c => namedSchemas(c.schema).map(NativeValues.mappedName(_, oracle))).toSet, expectedNames)
    compiled(namespaceCases, "namespaces", settings) { codes =>
      namespaceCases.zip(codes).foreach((c, code) => check(c, code, settings, exactWire = true))
      assertEquals(codes.head.values.head.getClass.getName, "options.models.Root")
      assertEquals(codes(1).values.head.getClass.getName, "Stripped")
    }
  }

  private def byteDefault(value: AnyRef): String =
    val bytes = value match
      case fixed: GenericFixed => fixed.bytes()
      case original: ByteBuffer =>
        val buffer = original.duplicate()
        val result = new Array[Byte](buffer.remaining())
        buffer.get(result)
        result
    bytes.iterator.map(byte => (byte & 255).toChar).mkString

  private lazy val defaultEvolution: EvolutionCase =
    val direct = matrix.head
    val inner = record("Nested", "option.source.deep", Vector(field("day", direct.schema.getField("date").schema)))
    val nestedDefault = new Schema.Field("nested", inner, null, Map("day" -> Int.box(-7)).asJava)
    val added = direct.schema.getFields.asScala.zipWithIndex.map { (f, index) =>
      val physical = direct.values((index + 1) % direct.values.size).asInstanceOf[IndexedRecord].get(f.pos).asInstanceOf[AnyRef]
      val default = if f.schema.getType == Schema.Type.BYTES || f.schema.getType == Schema.Type.FIXED then byteDefault(physical) else physical
      new Schema.Field(f.name, f.schema, null, default)
    }.toVector
    val writer = record("BeforeDefaults", "option.source", Vector(field("discarded", Schema.create(Schema.Type.STRING)), field("id", Schema.create(Schema.Type.INT))))
    val renamed = field("renamedId", Schema.create(Schema.Type.LONG))
    renamed.addAlias("id")
    val reader = record("AfterDefaults", "option.source", (added :+ nestedDefault :+ renamed).reverse)
    reader.addAlias(writer.getFullName)
    EvolutionCase(writer, reader, Vector.tabulate(4)(i => datum(writer, s"discarded-$i", Int.box(i))), Set("record-alias", "field-alias", "defaults", "reorder-fields"))

  private def checkEvolution(c: EvolutionCase, expected: SchemaCase, code: CompiledCase, settings: GeneratorConfig): Unit =
    code.variants.foreach(checkEvolutionCodec(c, expected, _, settings))

  private def checkEvolutionCodec(c: EvolutionCase, expected: SchemaCase, code: CompiledCase, settings: GeneratorConfig): Unit =
    check(expected, code, settings)
    val resolver = new ResolvingReader(c.writer.toString, code.codec)
    c.values.zip(code.values).foreach { (physical, native) =>
      val bytes = JavaOracle.encode(c.writer, physical)
      val resolved = resolver.decode(bytes)
      assert(JavaOracle.nativeEqual(resolved, native), s"Reader model/default representation changed: $resolved != $native")
      assertEquals(JavaOracle.normalized(c.reader, JavaOracle.decode(c.reader, code.codec.encode(resolved)), rawTypes(settings)),
        JavaOracle.normalized(c.reader, EvolutionOracle.resolve(c.writer, c.reader, bytes), rawTypes(settings)))
    }

  test("mapped reader models preserve aliases, reordering, nested named data and raw or converted logical defaults") {
    val rules = Set("union-named-aliases", "recursive-record", "logical-fixed-alias", "enum-reorder")
    val cases = EvolutionCases.mandatory.filter(c => c.labels.exists(l => rules(l.stripPrefix("rule:"))) && c.labels("context:direct")) :+ defaultEvolution
    val expected = cases.map(EvolutionOracle.expected)
    for decimal <- Vector(DecimalType.Scala, DecimalType.Java); (raw, index) <- Vector(Set.empty[String], known, halfRaw, known -- halfRaw).zipWithIndex do
      val settings = config(decimal, raw, mappings ++ Map("avro2s.wire.propertyevolution" -> "options.evolution", "avro2s.wire.propertyrandom" -> "options.carried"))
      compiled(expected, s"evolution-$decimal-$index", settings) { codes =>
        cases.zip(expected).zip(codes).foreach { case ((c, ref), code) => checkEvolution(c, ref, code, settings) }
      }
  }

  private lazy val timeEvolution: EvolutionCase =
    val millis = """{"type":"int","logicalType":"time-millis"}"""
    val micros = """{"type":"long","logicalType":"time-micros"}"""
    val reader = new Schema.Parser().parse(s"""{"type":"record","name":"Times","namespace":"option.source","fields":[
      {"name":"payload","type":["null",$millis,$micros]},
      {"name":"defaultMillis","type":[$millis,$micros,"null"],"default":7},
      {"name":"defaultMicros","type":[$micros,"null",$millis],"default":7001},
      {"name":"defaultNull","type":["null",$micros,$millis],"default":null},
      {"name":"singleMillis","type":["null",$millis],"default":null},
      {"name":"singleMicros","type":[$micros,"null"],"default":9001}
    ]}""")
    val writer = new Schema.Parser().parse(s"""{"type":"record","name":"Times","namespace":"option.source","fields":[
      {"name":"payload","type":[$micros,"null",$millis]}
    ]}""")
    EvolutionCase(writer, reader, Vector(null, Int.box(0), Long.box(0), Int.box(12345), Long.box(12345001)).map(v => datum(writer, v)), Set("time-unions"))

  test("time union tags depend on both converted branches through nullable values and reader defaults") {
    val c = timeEvolution
    val expected = EvolutionOracle.expected(c)
    val combinations = Vector(Set.empty[String], Set("time-millis"), Set("time-micros"), Set("time-millis", "time-micros"))
    combinations.zipWithIndex.foreach { (raw, index) =>
      val settings = config(DecimalType.Scala, raw)
      compiled(Vector(expected), s"times-$index", settings) { codes =>
        checkEvolution(c, expected, codes.head, settings)
        check(expected, codes.head, settings, exactWire = true)
      }
    }
  }

  test("raw bytes retain noncanonical decimal encodings and raw primitives bypass logical conversion") {
    val root = new Schema.Parser().parse("""{"type":"record","name":"Physical","namespace":"option.source","fields":[
      {"name":"decimal","type":{"type":"bytes","logicalType":"decimal","precision":9,"scale":2}},
      {"name":"variable","type":{"type":"bytes","logicalType":"big-decimal"}},
      {"name":"uuid","type":{"type":"string","logicalType":"uuid"}},
      {"name":"millis","type":{"type":"int","logicalType":"time-millis"}},
      {"name":"micros","type":{"type":"long","logicalType":"time-micros"}}
    ]}""")
    val values = Vector(Array[Byte](0, 0, 1), Array[Byte](-1, -1, -2), Array.emptyByteArray).map { raw =>
      datum(root, ByteBuffer.wrap(raw), ByteBuffer.wrap(Array[Byte](-1, 0, 1)), "physical-not-a-uuid", Int.box(Int.MinValue), Long.box(Long.MaxValue))
    }
    val c = SchemaCase(root, values)
    val settings = config(DecimalType.Java, known)
    compiled(Vector(c), "raw-physical", settings)(codes => check(c, codes.head, settings, exactWire = true))
  }

  test("explicit Converted entries emit exactly the default source in either decimal mode") {
    for decimal <- Vector(DecimalType.Scala, DecimalType.Java); names <- Vector(Map.empty[String, String], mappings) do
      // The mixed named/default-package graph needs its mapping to be representable in Scala.
      val cases = matrix ++ (if names.isEmpty then namespaceCases.tail else namespaceCases)
      val default = GeneratorConfig(decimal, namespaceMappings = names)
      val converted = config(decimal, Set.empty, names)
      cases.foreach(c => assertEquals(CodeGenerator.generate(c.schema, converted), CodeGenerator.generate(c.schema, default)))
  }
