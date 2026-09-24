package avro2s.wire.properties

import avro2s.wire.compiler.{DecimalType, GeneratorConfig}
import avro2s.wire.resolution.ResolvingReader
import java.math.{BigDecimal as JDecimal, BigInteger}
import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, IndexedRecord}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The model constructor oracle chooses Java/Scala values independently of code generation. */
final class DecimalConfigurationSuite extends munit.FunSuite:
  override val munitTimeout = 10.minutes
  private lazy val target = Path.of(sys.props("avro2s.wire.property.target"), "decimal-configuration")
  private val modes = Vector(DecimalType.Scala, DecimalType.Java)

  private def directory(prefix: String): Path =
    Files.createDirectories(target)
    Files.createTempDirectory(target, prefix)

  private def decimals(value: Any): Vector[Any] = value match
    case decimal: JDecimal => Vector(decimal)
    case decimal: scala.math.BigDecimal => Vector(decimal)
    case values: Map[?, ?] => values.valuesIterator.flatMap(decimals).toVector
    case values: Seq[?] => values.iterator.flatMap(decimals).toVector
    case value: Product => value.productIterator.flatMap(decimals).toVector
    case _ => Vector.empty

  private def checkModel(actual: Any, expected: Any, mode: DecimalType): Unit =
    assert(JavaOracle.nativeEqual(actual, expected), "Generated value changed its decimal type, unscaled integer, or scale")
    decimals(actual).foreach { value =>
      mode match
        case DecimalType.Java => assert(value.isInstanceOf[JDecimal], "A generated Java decimal was coerced to Scala")
        case DecimalType.Scala => assert(value.isInstanceOf[scala.math.BigDecimal], "A generated Scala decimal was coerced to Java")
    }

  private def checkWire(c: SchemaCase, compiled: CompiledCase, mode: DecimalType): Unit =
    c.values.zip(compiled.values).foreach { (reference, native) =>
      val bytes = compiled.codec.encode(native)
      assertEquals(JavaOracle.normalized(c.schema, JavaOracle.decode(c.schema, bytes)), JavaOracle.normalized(c.schema, reference))
      checkModel(compiled.codec.decode(JavaOracle.encode(c.schema, reference)), native, mode)
      checkModel(compiled.codec.decode(bytes), native, mode)
    }

  test("both decimal model types compile across storage and collection contexts and interoperate exactly with Java") {
    val cases = SchemaCases.mandatoryCases.filter { c =>
      Option(c.schema.getProp("propertyCase")).exists(label =>
        Vector("matrix:decimal-bytes:", "matrix:decimal-fixed:", "matrix:big-decimal:").exists(label.startsWith))
    }
    assertEquals(cases.size, 18)
    modes.foreach { mode =>
      val compiled = CompiledCases.compile(cases, directory(s"matrix-$mode-"), GeneratorConfig(mode))
      try cases.zip(compiled.cases).foreach((c, code) => checkWire(c, code, mode))
      finally compiled.close()
    }
  }

  private def raw(value: AnyRef): Array[Byte] = value match
    case fixed: GenericData.Fixed => fixed.bytes().clone()
    case original: ByteBuffer =>
      val buffer = original.duplicate()
      val result = new Array[Byte](buffer.remaining())
      buffer.get(result)
      result

  private def defaultBytes(value: AnyRef): String = raw(value).iterator.map(b => (b & 0xff).toChar).mkString

  private def field(name: String, schema: Schema): Schema.Field =
    new Schema.Field(name, schema, null, null.asInstanceOf[AnyRef])

  private def evolutionCase(): (SchemaCase, SchemaCase) =
    val reader = new Schema.Parser().parse("""{
      "type":"record","name":"DecimalEvolution","namespace":"avro2s.wire.decimalconfig","fields":[
        {"name":"variable","type":{"type":"bytes","logicalType":"big-decimal"}},
        {"name":"bounded","type":{"type":"bytes","logicalType":"decimal","precision":70,"scale":8}},
        {"name":"fixed","type":{"type":"fixed","name":"FixedDecimal","size":32,"logicalType":"decimal","precision":70,"scale":8}},
        {"name":"nested","type":{"type":"record","name":"Inner","fields":[
          {"name":"variable","type":{"type":"bytes","logicalType":"big-decimal"}},
          {"name":"bounded","type":{"type":"bytes","logicalType":"decimal","precision":70,"scale":8}}
        ]}}
      ]} """)
    val variable = reader.getField("variable").schema()
    val bounded = reader.getField("bounded").schema()
    val fixed = reader.getField("fixed").schema()
    val inner = reader.getField("nested").schema()
    val unscaled = new BigInteger("123456789012345678901234567890123456789012345678901234567890")
    val boundedValue = ByteBuffer.wrap(unscaled.toByteArray)
    val padded = Array.fill[Byte](fixed.getFixedSize)(0)
    val integerBytes = unscaled.toByteArray
    System.arraycopy(integerBytes, 0, padded, padded.length - integerBytes.length, integerBytes.length)
    val fixedValue = new GenericData.Fixed(fixed, padded)
    val variableDefault = JavaOracle.bigDecimalBytes(variable, new JDecimal(unscaled.negate(), -12))
    val nestedValue = new GenericData.Record(inner)
    nestedValue.put("variable", variableDefault)
    nestedValue.put("bounded", boundedValue)
    val fields = reader.getFields.asScala.map(f => field(f.name(), f.schema())).toVector ++ Vector(
      new Schema.Field("defaultVariable", variable, null, defaultBytes(variableDefault)),
      new Schema.Field("defaultBounded", bounded, null, defaultBytes(boundedValue)),
      new Schema.Field("defaultFixed", fixed, null, defaultBytes(fixedValue)),
      new Schema.Field("defaultNested", inner, null,
        Map("variable" -> defaultBytes(variableDefault), "bounded" -> defaultBytes(boundedValue)).asJava)
    )
    // Clone the root to add defaults without mutating an already-bound Avro record.
    val extended = Schema.createRecord(reader.getName, null, reader.getNamespace, false)
    extended.setFields(fields.asJava)
    val parsedReader = new Schema.Parser().setValidateDefaults(true).parse(extended.toString)
    val writer = Schema.createRecord(reader.getName, null, reader.getNamespace, false)
    writer.setFields((reader.getFields.asScala.reverse.map(f => field(f.name(), f.schema())).toVector :+
      field("discarded", Schema.create(Schema.Type.STRING))).asJava)
    val values = Vector(new JDecimal("1.0"), new JDecimal("1.00"), new JDecimal(unscaled, 19))
    val expected = values.map { decimal =>
      val value = new GenericData.Record(parsedReader)
      value.put("variable", JavaOracle.bigDecimalBytes(variable, decimal))
      value.put("bounded", boundedValue)
      value.put("fixed", fixedValue)
      value.put("nested", nestedValue)
      value.put("defaultVariable", variableDefault)
      value.put("defaultBounded", boundedValue)
      value.put("defaultFixed", fixedValue)
      value.put("defaultNested", nestedValue)
      value.asInstanceOf[AnyRef]
    }
    val written = expected.map { reference =>
      val record = new GenericData.Record(writer)
      reader.getFields.asScala.foreach(f => record.put(f.name(), reference.asInstanceOf[IndexedRecord].get(parsedReader.getField(f.name()).pos())))
      record.put("discarded", "writer-only field")
      record.asInstanceOf[AnyRef]
    }
    (SchemaCase(writer, written), SchemaCase(parsedReader, expected))

  test("resolution preserves decimal mode and exact values through reordered fields, named models, and defaults") {
    val (writer, reader) = evolutionCase()
    modes.foreach { mode =>
      val compiled = CompiledCases.compile(Vector(reader), directory(s"evolution-$mode-"), GeneratorConfig(mode))
      try
        val code = compiled.cases.head
        checkWire(reader, code, mode)
        val resolver = new ResolvingReader(writer.schema.toString, code.codec)
        writer.values.zip(code.values).foreach { (physical, expected) =>
          val decoded = resolver.decode(JavaOracle.encode(writer.schema, physical))
          checkModel(decoded, expected, mode)
          assertEquals(decimals(decoded).size, 10)
        }
        // A generated case class inherits the configured decimal's equality contract.
        assertEquals(code.values(0) == code.values(1), mode == DecimalType.Scala)
        assert(!JavaOracle.nativeEqual(code.values(0), code.values(1)), "The wire oracle must distinguish 1.0 from 1.00 in either mode")
      finally compiled.close()
    }
  }
