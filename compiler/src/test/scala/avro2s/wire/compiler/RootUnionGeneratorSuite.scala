package avro2s.wire.compiler

import org.apache.avro.Schema

class RootUnionGeneratorSuite extends munit.FunSuite:
  private def generate(json: String, config: GeneratorConfig = GeneratorConfig(rootTypeName = Some("example.Event"))): Vector[GeneratedSource] =
    CodeGenerator.generate(new Schema.Parser().parse(json), config)

  private val records = """[
    {"type":"record","name":"example.Created","fields":[{"name":"id","type":"long"}]},
    {"type":"record","name":"example.Cancelled","fields":[{"name":"reason","type":"string"}]}
  ]"""

  test("root unions generate an alias and codec without an extra Avro record") {
    val sources = generate(records)
    assertEquals(sources.map(_.relativePath), Vector("example/Cancelled.scala", "example/Created.scala", "example/Event.scala"))
    val facade = sources.last.content
    assert(facade.contains("type Event = _root_.example.Created | _root_.example.Cancelled"))
    assert(facade.contains("given codec: _root_.avro2s.wire.runtime.AvroCodec[_root_.example.Event]"))
    assert(facade.contains("case 0 => _root_.example.Created.codec.read(in)"))
    assert(facade.contains("case 1 => _root_.example.Cancelled.codec.read(in)"))
    assert(facade.contains("case \"example.Created\" => _root_.example.Created.codec"))
    assert(!facade.contains("case \"example.Event\""))
    assert(!facade.contains("override def construct"))
    assert(!facade.contains("in.enterRecord()"))
    assert(!facade.contains("stackSafeCodec"))
    assert(facade.contains(ScalaNames.literal(new Schema.Parser().parse(records).toString)))
    assertEquals(sources, generate(records))
  }

  test("root unions work without any named definitions and retain nullable representations") {
    val examples = Vector(
      "[\"int\",\"string\"]" -> "_root_.scala.Int | _root_.java.lang.String",
      "[\"null\",\"long\"]" -> "_root_.scala.Option[_root_.scala.Long]",
      "[\"string\",\"null\",\"long\"]" -> "_root_.scala.Option[_root_.java.lang.String | _root_.scala.Long]",
      "[\"int\"]" -> "_root_.scala.Int",
      "[\"null\"]" -> "_root_.scala.Null"
    )
    for (json, expected) <- examples do
      val sources = generate(json)
      assertEquals(sources.size, 1)
      assert(sources.head.content.contains(s"type Event = $expected"), clues(json))
  }

  test("root recursive unions suspend child traversal in the same stack-safe driver") {
    val json = """["string",{"type":"record","name":"example.Node","fields":[
      {"name":"next","type":["null","example.Node"]}
    ]}]"""
    val baseline = GeneratorConfig(rootTypeName = Some("example.Event"))
    val sources = generate(json, baseline.copy(generateStackSafeCodecs = true))
    val facade = sources.find(_.relativePath == "example/Event.scala").get.content
    assert(facade.startsWith(generate(json, baseline).find(_.relativePath == "example/Event.scala").get.content))
    val safe = facade.substring(facade.indexOf("\n  lazy val stackSafeCodec:"))
    assert(safe.contains("Step.defer["))
    assert(safe.contains("_root_.example.Node.stackSafeCodec.readStep(in)"))
    assert(safe.contains("_root_.example.Node.stackSafeCodec.writeStep("))
    assert(safe.contains("case \"example.Node\" => _root_.example.Node.stackSafeCodec"))
    assert(!safe.contains(".codec.read("))
    assert(!safe.contains(".codec.write("))
    assert(!safe.contains(".stackSafeCodec.read("))
    assert(!safe.contains(".stackSafeCodec.write("))
    assert(!safe.contains("codecSelf"))
  }

  test("explicit facade names are Scala names while branch namespaces remain mapped") {
    val config = GeneratorConfig(rootTypeName = Some("example.Event"), namespaceMappings = Map("example" -> "models"))
    val sources = generate(records, config)
    val facade = sources.find(_.relativePath == "example/Event.scala").get.content
    assert(facade.contains("type Event = _root_.models.Created | _root_.models.Cancelled"))
    assert(facade.contains("case \"example.Created\" => _root_.models.Created.codec"))
    assertEquals(sources.map(_.relativePath).toSet, Set("example/Event.scala", "models/Created.scala", "models/Cancelled.scala"))
  }

  test("root logical representation metadata applies to both codecs") {
    val config = GeneratorConfig(rootTypeName = Some("example.Event"), decimalType = DecimalType.Java,
      logicalTypes = Map(LogicalType.Date -> LogicalTypeMode.Raw), generateStackSafeCodecs = true)
    val source = generate("""[{"type":"int","logicalType":"date"},{"type":"bytes","logicalType":"big-decimal"}]""", config).head.content
    assert(source.contains("type Event = _root_.scala.Int | _root_.java.math.BigDecimal"))
    assertEquals("DecimalRepresentation.Java".r.findAllIn(source).size, 2)
    assertEquals("override val rawLogicalTypes:".r.findAllIn(source).size, 2)
    assert(source.contains("LogicalValues.readJavaBigDecimal(in)"))
  }

  test("an Avro fullname equal to the Scala facade name remains a distinct mapped branch") {
    val json = """[{"type":"record","name":"example.Event","fields":[]},"int"]"""
    val config = GeneratorConfig(rootTypeName = Some("example.Event"), namespaceMappings = Map("example" -> "models"),
      generateStackSafeCodecs = true)
    val sources = generate(json, config)
    assertEquals(sources.map(_.relativePath).toSet, Set("example/Event.scala", "models/Event.scala"))
    val facade = sources.find(_.relativePath == "example/Event.scala").get.content
    assert(facade.contains("type Event = _root_.models.Event | _root_.scala.Int"))
    assert(facade.contains("case \"example.Event\" => _root_.models.Event.stackSafeCodec"))
    assert(facade.contains("_root_.models.Event.stackSafeCodec.readStep(in)"))
    assert(!facade.contains("codecSelf"))
  }

  test("root names reject missing invalid ignored colliding and inaccessible declarations") {
    val missing = intercept[GenerationException](generate("[\"int\",\"string\"]", GeneratorConfig()))
    assert(missing.getMessage.contains("rootTypeName"))
    for name <- Vector("", "bad..Name", "bad/path", "_root_.Name", "value") do
      intercept[GenerationException](generate(records, GeneratorConfig(rootTypeName = Some(name))))
    val ignored = intercept[GenerationException](generate("""{"type":"record","name":"Item","fields":[]}"""))
    assert(ignored.getMessage.contains("only to an unnamed root union"))
    val collision = intercept[GenerationException](generate(records, GeneratorConfig(rootTypeName = Some("example.Created"))))
    assert(collision.getMessage.contains("duplicate Scala types"))
    val path = intercept[GenerationException](generate(records, GeneratorConfig(rootTypeName = Some("example.Created.Event"))))
    assert(path.getMessage.contains("both a generated type and a package"))
    val inaccessible = intercept[GenerationException](generate("""[{"type":"record","name":"Plain","fields":[]},"int"]"""))
    assert(inaccessible.getMessage.contains("cannot reference default-package type 'Plain'"))
  }
