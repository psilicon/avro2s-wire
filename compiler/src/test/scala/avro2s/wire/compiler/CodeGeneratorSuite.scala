package avro2s.wire.compiler

import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import scala.jdk.CollectionConverters.*

class CodeGeneratorSuite extends munit.FunSuite:
  private def generate(json: String, config: GeneratorConfig = GeneratorConfig()): Vector[GeneratedSource] =
    CodeGenerator.generate(new Schema.Parser().parse(json), config)

  test("recursive references terminate and generate direct field codecs") {
    val json = """{"type":"record","name":"Node","namespace":"example","fields":[{"name":"value","type":"long"},{"name":"next","type":["null","Node"],"default":null}]}"""
    val sources = generate(json)
    assertEquals(sources.map(_.relativePath), Vector("example/Node.scala"))
    val content = sources.head.content
    assert(content.contains("next: _root_.scala.Option[_root_.example.Node]"))
    assert(content.contains("_root_.example.Node.codec.read(in)"))
    assert(content.contains("out.writeLong(value.value)"))
    assert(content.contains("in.enterRecord()"))
    assert(content.contains("finally in.leaveRecord()"))
    assert(content.contains("lazy val stackSafeCodec:"))
    assert(!content.contains("given stackSafeCodec"))
    assert(content.contains("_root_.example.Node.stackSafeCodec.readStep(in)"))
    assert(content.contains("_root_.example.Node.stackSafeCodec.writeStep("))
    assert(content.contains("case \"example.Node\" => _root_.example.Node.stackSafeCodec"))
    assert(!content.contains("org.apache.avro"))
    assert(!content.contains("GenericRecord"))
    assertEquals(generate(json), sources)
  }

  test("nullable unions preserve each writer branch order") {
    val sources = generate("""{"type":"record","name":"Options","fields":[{"name":"first","type":["null","long"]},{"name":"last","type":["long","null"]}]}""")
    val content = sources.head.content
    assert(content.contains("case 0 => in.readNull(); _root_.scala.None"))
    assert(content.contains("case 1 => in.readNull(); _root_.scala.None"))
    assert(content.contains("if value.first.isEmpty then {\n        out.writeIndex(0)"))
    assert(content.contains("if value.last.isEmpty then {\n        out.writeIndex(1)"))
    assert(content.contains("Invalid nullable union index"))
  }

  test("only physical int and long arrays select whole-array output hooks") {
    val source = generate("""{"type":"record","name":"BulkArrays","fields":[
      {"name":"ints","type":{"type":"array","items":"int"}},
      {"name":"longs","type":{"type":"array","items":"long"}},
      {"name":"optional","type":["null",{"type":"array","items":"int"}]},
      {"name":"nested","type":{"type":"map","values":{"type":"array","items":"long"}}},
      {"name":"dates","type":{"type":"array","items":{"type":"int","logicalType":"date"}}},
      {"name":"nullableItems","type":{"type":"array","items":["null","int"]}},
      {"name":"floats","type":{"type":"array","items":"float"}}
    ]}""").head.content
    assert(source.contains("out.writeIntArray(value.ints)"))
    assert(source.contains("out.writeLongArray(value.longs)"))
    // Both paths retain the physical primitive-array hooks.
    assertEquals("out.writeIntArray\\(".r.findAllIn(source).size, 4)
    assertEquals("out.writeLongArray\\(".r.findAllIn(source).size, 4)
    assert(source.contains("LogicalValues.writeDate("))
    assert(source.contains("out.writeFloat("))
  }

  test("stack-safe children in arrays maps and unions share deferred execution") {
    val sources = generate("""{"type":"record","name":"Tree","namespace":"example","fields":[
      {"name":"children","type":{"type":"array","items":"Tree"}},
      {"name":"named","type":{"type":"map","values":"Tree"}},
      {"name":"choice","type":["string","Tree"]}
    ]}""")
    val source = sources.head.content
    val safe = source.substring(source.indexOf("\n  lazy val stackSafeCodec:"))
    assert(safe.contains("StackSafe.readArray[_root_.example.Tree](in)"))
    assert(safe.contains("StackSafe.readMap[_root_.example.Tree](in)"))
    assert(safe.contains("StackSafe.writeArray[_root_.example.Tree](out, value.children)"))
    assert(safe.contains("StackSafe.writeMap[_root_.example.Tree](out, value.named)"))
    assert(safe.contains("Step.defer { _root_.example.Tree.stackSafeCodec.readStep(in) }"))
    assert(!safe.contains(".codec.read("))
    assert(!safe.contains(".codec.write("))
    assert(!safe.contains(".stackSafeCodec.read("))
    assert(!safe.contains(".stackSafeCodec.write("))
  }

  test("stack-safe collection unions retain their exact declared result type") {
    val source = generate("""{"type":"record","name":"Choice","fields":[{"name":"value","type":[
      {"type":"array","items":"int"}, {"type":"map","values":"string"}
    ]}]}""").head.content
    assert(source.contains("Step.defer[_root_.scala.collection.immutable.Vector[_root_.scala.Int] | _root_.scala.collection.immutable.Map[_root_.java.lang.String, _root_.java.lang.String]]"))
  }

  test("nested named schemas are emitted once in stable path order") {
    val sources = generate("""{"type":"record","name":"Z","namespace":"example","fields":[{"name":"child","type":{"type":"record","name":"A","fields":[{"name":"parent","type":["null","Z"]}]}},{"name":"other","type":"A"}]}""")
    assertEquals(sources.map(_.relativePath), Vector("example/A.scala", "example/Z.scala"))
    assert(sources.head.content.contains("_root_.example.Z.codec.read(in)"))
  }

  test("invalid and unknown logical types fail with useful field locations") {
    val logical = intercept[GenerationException] {
      generate("""{"type":"record","name":"R","fields":[{"name":"at","type":{"type":"int","logicalType":"timestamp-millis"}}]}""")
    }
    assert(logical.getMessage.contains("R.at: invalid logical type 'timestamp-millis'"))
    val unknown = intercept[GenerationException] {
      generate("""{"type":"fixed","name":"Unknown","size":16,"logicalType":"custom-type"}""")
    }
    assert(unknown.getMessage.contains("custom-type"))
  }

  test("decimal configuration applies to bytes, named fixed wrappers, and nested logical values") {
    val json = """{"type":"record","name":"Decimals","namespace":"example","fields":[
      {"name":"amount","type":{"type":"bytes","logicalType":"decimal","precision":18,"scale":4}},
      {"name":"variable","type":{"type":"bytes","logicalType":"big-decimal"}},
      {"name":"fixed","type":{"type":"fixed","name":"Money","size":8,"logicalType":"decimal","precision":18,"scale":4}},
      {"name":"optional","type":["null",{"type":"bytes","logicalType":"big-decimal"}]},
      {"name":"array","type":{"type":"array","items":{"type":"bytes","logicalType":"big-decimal"}}},
      {"name":"map","type":{"type":"map","values":{"type":"bytes","logicalType":"decimal","precision":9,"scale":2}}},
      {"name":"choice","type":["string",{"type":"bytes","logicalType":"big-decimal"},"Money"]}
    ]}"""
    assertEquals(generate(json), generate(json, GeneratorConfig(DecimalType.Scala)))
    for decimalType <- DecimalType.values do
      val sources = generate(json, GeneratorConfig(decimalType))
      val record = sources.find(_.relativePath == "example/Decimals.scala").get.content
      val fixed = sources.find(_.relativePath == "example/Money.scala").get.content
      val valueType = if decimalType == DecimalType.Java then "_root_.java.math.BigDecimal" else "_root_.scala.BigDecimal"
      val prefix = if decimalType == DecimalType.Java then "Java" else ""
      assert(record.contains(s"amount: $valueType"))
      assert(record.contains(s"variable: $valueType"))
      assert(record.contains(s"optional: _root_.scala.Option[$valueType]"))
      assert(record.contains(s"array: _root_.scala.collection.immutable.Vector[$valueType]"))
      assert(record.contains(s"map: _root_.scala.collection.immutable.Map[_root_.java.lang.String, $valueType]"))
      assert(record.contains(s"choice: _root_.java.lang.String | $valueType | _root_.example.Money"))
      assert(record.contains(s"LogicalValues.read${prefix}Decimal(in, 18, 4)"))
      assert(record.contains(s"LogicalValues.write${prefix}Decimal(value.amount, out, 18, 4)"))
      assert(record.contains(s"LogicalValues.read${prefix}BigDecimal(in)"))
      assert(record.contains(s"LogicalValues.write${prefix}BigDecimal(value.variable, out)"))
      assert(record.contains(s": $valueType =>"))
      assert(record.contains("case _ => throw new _root_.java.lang.IllegalArgumentException"))
      assert(fixed.contains(s"final case class Money(value: $valueType)"))
      assert(fixed.contains(s"LogicalValues.read${prefix}FixedDecimal(in, 8, 18, 4)"))
      assert(fixed.contains(s"LogicalValues.write${prefix}FixedDecimal(value.value, out, 8, 18, 4)"))
      assertEquals(record.contains("DecimalRepresentation.Java"), decimalType == DecimalType.Java)
      assertEquals(fixed.contains("DecimalRepresentation.Java"), decimalType == DecimalType.Java)
  }

  test("big-decimal validates bytes storage and retains unconstrained schema metadata") {
    for physical <- Vector("int", "long", "string", "fixed") do
      val extra = if physical == "fixed" then "\"name\":\"InvalidDecimal\",\"size\":16," else ""
      val error = intercept[GenerationException] {
        generate(s"""{"type":"record","name":"R","fields":[{"name":"amount","type":{"type":"$physical",${extra}"logicalType":"big-decimal"}}]}""")
      }
      assert(error.getMessage.contains("R.amount: invalid logical type 'big-decimal'"))
    val schema = new Schema.Parser().parse("""{"type":"record","name":"R","fields":[{"name":"amount","type":{"type":"bytes","logicalType":"big-decimal","precision":2,"scale":7}}]}""")
    val source = CodeGenerator.generate(schema).head.content
    assert(source.contains("LogicalValues.readBigDecimal(in)"))
    assert(source.contains(ScalaNames.literal(schema.toString)))
  }

  test("general unions retain Scala union types and tag only colliding logical representations") {
    val source = generate("""{"type":"record","name":"R","fields":[{"name":"value","type":["string","int"]},{"name":"optional","type":["long","null","string"]},{"name":"single","type":["int"]}]}""").head.content
    assert(source.contains("value: _root_.java.lang.String | _root_.scala.Int"))
    assert(source.contains("optional: _root_.scala.Option[_root_.scala.Long | _root_.java.lang.String]"))
    assert(source.contains("single: _root_.scala.Int"))
    assert(!source.contains("sealed trait"))
    val collision = generate("""{"type":"record","name":"R","fields":[{"name":"time","type":[{"type":"int","logicalType":"time-millis"},{"type":"long","logicalType":"time-micros"}]}]}""").head.content
    assert(collision.contains("time: _root_.avro2s.wire.runtime.TimeMillis | _root_.avro2s.wire.runtime.TimeMicros"))
    assert(collision.contains("new _root_.avro2s.wire.runtime.TimeMillis("))
    val plain = generate("""{"type":"record","name":"R","fields":[{"name":"time","type":{"type":"int","logicalType":"time-millis"}}]}""").head.content
    assert(plain.contains("time: _root_.java.time.LocalTime"))
  }

  test("logical fixed types remain nominal to distinguish union branches") {
    val sources = generate("""{"type":"record","name":"R","fields":[{"name":"id","type":[{"type":"string","logicalType":"uuid"},{"type":"fixed","name":"BinaryUuid","size":16,"logicalType":"uuid"}]}]}""")
    val record = sources.find(_.relativePath == "R.scala").get.content
    val fixed = sources.find(_.relativePath == "BinaryUuid.scala").get.content
    assert(record.contains("_root_.java.util.UUID | BinaryUuid"))
    assert(fixed.contains("final case class BinaryUuid(value: _root_.java.util.UUID)"))
    assert(fixed.contains("LogicalValues.readFixedUuid(in)"))
    assert(fixed.contains("LogicalValues.writeFixedUuid(value.value, out)"))
  }

  test("named codec factories include reachable definitions without inaccessible parents") {
    val sources = generate("""{"type":"record","name":"Parent","fields":[{"name":"child","type":{"type":"record","name":"Child","namespace":"p","fields":[]}}]}""")
    val parent = sources.find(_.relativePath == "Parent.scala").get.content
    val child = sources.find(_.relativePath == "p/Child.scala").get.content
    assert(parent.contains("case \"Parent\" => Parent.codec"))
    assert(parent.contains("case \"p.Child\" => _root_.p.Child.codec"))
    assert(!child.contains("Parent.codec"))
    assert(parent.contains("override def construct(values:"))
  }

  test("keywords are escaped and inherited members are renamed without collisions") {
    val source = generate("""{"type":"record","name":"String","namespace":"example.type","fields":[{"name":"type","type":"string"},{"name":"copy","type":"int"},{"name":"avro_field_copy","type":"int"},{"name":"productArity","type":"int"}]}""").head.content
    assert(source.contains("package example.`type`"))
    assert(source.contains("`type`: _root_.java.lang.String"))
    assert(source.contains("avro_field_copy_: _root_.scala.Int"))
    assert(source.contains("avro_field_copy: _root_.scala.Int"))
    assert(source.contains("avro_field_productArity: _root_.scala.Int"))
    assert(source.contains("out.writeInt(value.avro_field_copy_)"))
  }

  test("enum companion and inherited names are mapped deterministically") {
    val source = generate("""{"type":"enum","name":"E","symbols":["codec","values","valueOf","fromOrdinal","ordinal","type","avro_symbol_codec"]}""").head.content
    assert(source.contains("case avro_symbol_codec_"))
    assert(source.contains("case avro_symbol_values"))
    assert(source.contains("case avro_symbol_valueOf"))
    assert(source.contains("case avro_symbol_fromOrdinal"))
    assert(source.contains("case avro_symbol_ordinal"))
    assert(source.contains("case `type`"))
    assert(source.contains("case 0 => E.avro_symbol_codec_"))
    assert(source.contains("case 6 => E.avro_symbol_codec"))
  }

  test("the additional codec name is reserved for enum symbols") {
    val source = generate("""{"type":"enum","name":"E","symbols":["stackSafeCodec","avro_symbol_stackSafeCodec"]}""").head.content
    assert(source.contains("case avro_symbol_stackSafeCodec_"))
    assert(source.contains("case 0 => E.avro_symbol_stackSafeCodec_"))
    assert(source.contains("case 1 => E.avro_symbol_stackSafeCodec"))
    assert(source.contains("lazy val stackSafeCodec:"))
  }

  test("unsafe Scala wildcard identifiers are rejected explicitly") {
    val error = intercept[GenerationException] {
      generate("""{"type":"record","name":"_","fields":[]}""")
    }
    assert(error.getMessage.contains("cannot safely be represented"))
  }

  test("non-named root contracts and empty enums are rejected explicitly") {
    val root = intercept[GenerationException] {
      generate("""{"type":"array","items":{"type":"record","name":"Item","fields":[]}}""")
    }
    assert(root.getMessage.contains("root schema must be a named"))
    val enumeration = intercept[GenerationException] {
      generate("""{"type":"enum","name":"Empty","symbols":[]}""")
    }
    assert(enumeration.getMessage.contains("empty enums"))
  }

  test("large schema metadata is split into safe JVM string constants") {
    val text = "x" * 70000
    val expression = ScalaNames.stringExpression(text)
    assert(expression.startsWith("new _root_.java.lang.StringBuilder(70000)"))
    val chunks = "\\.append\\(\"(x+)\"\\)".r.findAllMatchIn(expression).map(_.group(1)).toVector
    assertEquals(chunks.size, 9)
    assert(chunks.forall(_.length <= 8000))
    assertEquals(chunks.mkString, text)
  }

  test("default-package references use local names and ambiguous names are rejected") {
    val source = generate("""{"type":"record","name":"PlainRecord","fields":[{"name":"next","type":["null","PlainRecord"]}]}""").head.content
    assert(source.contains("Option[PlainRecord]"))
    assert(source.contains("PlainRecord.codec.read(in)"))
    assert(!source.contains("_root_.PlainRecord"))
    for name <- Vector("value", "decimalRepresentation") do
      val ambiguous = intercept[GenerationException] {
        generate(s"""{"type":"record","name":"$name","fields":[]}""")
      }
      assert(ambiguous.getMessage.contains("give this type an Avro namespace"))
  }

  test("named packages cannot refer to default-package definitions") {
    val error = intercept[GenerationException] {
      generate("""{"type":"record","name":"Parent","namespace":"p","fields":[{"name":"child","type":{"type":"record","name":"Child","namespace":"","fields":[]}}]}""")
    }
    assert(error.getMessage.contains("p.Parent.child: Scala cannot reference default-package type 'Child'"))
  }

  test("schema emoji crossing a string chunk boundary survive UTF-8 source writing") {
    def record(doc: String): Schema =
      val schema = Schema.createRecord("Emoji", doc, null, false)
      schema.setFields(java.util.Collections.emptyList[Schema.Field]())
      schema
    val prefixLength = record("MARKER").toString.indexOf("MARKER")
    val schema = record("x" * (7999 - prefixLength) + "\ud83d\ude00" + "tail")
    val json = schema.toString
    assert(Character.isHighSurrogate(json.charAt(7999)))
    assert(Character.isLowSurrogate(json.charAt(8000)))
    val expression = ScalaNames.stringExpression(json)
    // Scan literals linearly: a repeated regex alternation over a long string
    // can overflow the Java regex engine's stack before testing the generator.
    val literals = Vector.newBuilder[String]
    var offset = expression.indexOf(".append(")
    while offset >= 0 do
      val start = offset + ".append(".length
      var end = start + 1
      var escaped = false
      while end < expression.length && (expression.charAt(end) != '"' || escaped) do
        if escaped then escaped = false
        else escaped = expression.charAt(end) == '\\'
        end += 1
      literals += expression.substring(start, end + 1)
      offset = expression.indexOf(".append(", end + 1)
    val reconstructed = literals.result().map { literal =>
      new Schema.Parser().parse(s"""{"type":"record","name":"Part","doc":$literal,"fields":[]}""").getDoc
    }.mkString
    assertEquals(reconstructed, json)
    withDirectory { directory =>
      val source = CodeGenerator.generate(schema).head.content
      val file = directory.resolve("Emoji.scala")
      Files.writeString(file, source)
      assertEquals(Files.readString(file), source)
      assert(source.contains("\\ud83d"))
      assert(source.contains("\\ude00"))
    }
  }

  test("schema metadata is retained and Scala string literals are escaped") {
    val json = """{"type":"record","name":"R","doc":"a quote: \" and a slash: \\","aliases":["OldR"],"custom":"hello\nworld","fields":[{"name":"value","type":"string","default":"x"}]}"""
    val schema = new Schema.Parser().parse(json)
    val source = CodeGenerator.generate(schema).head.content
    assert(source.contains(ScalaNames.literal(schema.toString)))
    assertEquals(ScalaNames.literal("\"\\\n\r\t\b\f"), "\"\\\"\\\\\\n\\r\\t\\b\\f\"")
    assert(source.contains("OldR"))
    assert(source.contains("default"))
  }

  test("directory compilation resolves separately stored types and preserves unchanged files") {
    withDirectory { directory =>
      val inputs = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(inputs.resolve("a-parent.avsc"), """{"type":"record","name":"Parent","namespace":"example","fields":[{"name":"child","type":"Child"}]}""")
      Files.writeString(inputs.resolve("z-child.avsc"), """{"type":"record","name":"Child","namespace":"example","fields":[{"name":"value","type":"int"}]}""")
      val output = directory.resolve("generated")
      val paths = SchemaCompiler.generate(inputs, output)
      assertEquals(paths.map(output.relativize(_).toString), Vector("example/Child.scala", "example/Parent.scala"))
      val timestamps = paths.map(Files.getLastModifiedTime(_))
      assertEquals(SchemaCompiler.generate(inputs, output), paths)
      assertEquals(paths.map(Files.getLastModifiedTime(_)), timestamps)
    }
  }

  test("directory compilation and the CLI forward decimal configuration") {
    withDirectory { directory =>
      val input = directory.resolve("decimal.avsc")
      Files.writeString(input, """{"type":"record","name":"DecimalRecord","fields":[{"name":"value","type":{"type":"bytes","logicalType":"big-decimal"}}]}""")
      val output = directory.resolve("api")
      val generated = SchemaCompiler.generate(input, output, GeneratorConfig(DecimalType.Java))
      assert(Files.readString(generated.head).contains("value: _root_.java.math.BigDecimal"))
      for (options, expectedType, index) <- Vector(
        (Vector.empty[String], "_root_.scala.BigDecimal", 0),
        (Vector("--decimal-type", "scala"), "_root_.scala.BigDecimal", 1),
        (Vector("--decimal-type", "java"), "_root_.java.math.BigDecimal", 2)
      ) do
        val target = directory.resolve(s"cli-$index")
        Main.main((options ++ Vector(input.toString, target.toString)).toArray)
        assert(Files.readString(target.resolve("DecimalRecord.scala")).contains(s"value: $expectedType"))
      val trailing = directory.resolve("trailing-option")
      Main.main(Array(input.toString, trailing.toString, "--decimal-type", "java"))
      assert(Files.readString(trailing.resolve("DecimalRecord.scala")).contains("value: _root_.java.math.BigDecimal"))
    }
  }

  test("invalid CLI options fail before reading inputs or creating output") {
    val invalid = intercept[GenerationException](Main.main(Array("--decimal-type", "kotlin", "missing.avsc", "unused")))
    assert(invalid.getMessage.contains("Invalid --decimal-type 'kotlin'; expected scala or java"))
    val missing = intercept[GenerationException](Main.main(Array("missing.avsc", "unused", "--decimal-type")))
    assert(missing.getMessage.contains("Missing --decimal-type value"))
    val duplicate = intercept[GenerationException](Main.main(Array("--decimal-type", "java", "--decimal-type", "scala", "missing.avsc", "unused")))
    assert(duplicate.getMessage.contains("may only be specified once"))
    val unknown = intercept[GenerationException](Main.main(Array("--unknown", "missing.avsc", "unused")))
    assert(unknown.getMessage.contains("Unknown option '--unknown'"))
    val positional = intercept[GenerationException](Main.main(Array("missing.avsc")))
    assert(positional.getMessage.contains("Usage: avro2s-wire"))
  }

  test("all schemas are validated before any output is written") {
    withDirectory { directory =>
      val inputs = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(inputs.resolve("good.avsc"), """{"type":"record","name":"Good","fields":[]}""")
      Files.writeString(inputs.resolve("unsupported.avsc"), """{"type":"record","name":"Unsupported","fields":[{"name":"value","type":{"type":"bytes","logicalType":"unknown-logical-type"}}]}""")
      val output = directory.resolve("generated")
      intercept[GenerationException](SchemaCompiler.generate(inputs, output))
      assert(!Files.exists(output))
    }
  }

  private def withDirectory(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("avro2s-wire-compiler-test-")
    try body(directory)
    finally
      val stream = Files.walk(directory)
      try stream.iterator.asScala.toVector.reverse.foreach(Files.delete)
      finally stream.close()
