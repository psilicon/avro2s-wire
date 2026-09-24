package avro2s.wire.compiler

import org.apache.avro.Schema

class GeneratorOptionsSuite extends munit.FunSuite:
  private def generate(json: String, config: GeneratorConfig = GeneratorConfig()): Vector[GeneratedSource] =
    CodeGenerator.generate(new Schema.Parser().parse(json), config)

  private def record(value: String, name: String = "example.Value"): String =
    s"""{"type":"record","name":"$name","fields":[{"name":"value","type":$value}]}"""

  private val allRaw = GeneratorConfig(logicalTypes = LogicalType.values.map(_ -> LogicalTypeMode.Raw).toMap)
  private val allConverted = GeneratorConfig(logicalTypes = LogicalType.values.map(_ -> LogicalTypeMode.Converted).toMap)

  test("logical type API names enumerate every supported annotation") {
    assertEquals(LogicalType.values.map(_.avroName).toSet, Set(
      "date", "time-millis", "time-micros", "timestamp-millis", "timestamp-micros", "timestamp-nanos",
      "local-timestamp-millis", "local-timestamp-micros", "local-timestamp-nanos", "uuid", "duration", "decimal", "big-decimal"
    ))
    for logical <- LogicalType.values do assertEquals(LogicalType.fromAvroName(logical.avroName), Some(logical))
    assertEquals(LogicalType.fromAvroName("unknown"), None)
  }

  test("namespace mappings match namespace components and the longest prefix") {
    val config = GeneratorConfig(namespaceMappings = Map(
      "avro" -> "scala", "avro.deep" -> "target", "avro.deep.Record" -> "wrong", "" -> "defaults"
    ))
    assertEquals(config.mappedFullName("avro.Record"), "scala.Record")
    assertEquals(config.mappedFullName("avro.deep.Record"), "target.Record")
    assertEquals(config.mappedFullName("avro.deep.child.Record"), "target.child.Record")
    assertEquals(config.mappedFullName("avro.deeper.Record"), "scala.deeper.Record")
    assertEquals(config.mappedFullName("avroish.Record"), "avroish.Record")
    assertEquals(config.mappedFullName("Record"), "defaults.Record")
    assertEquals(config.mappedFullName("other.Record"), "other.Record")
    val reverse = config.copy(namespaceMappings = config.namespaceMappings.toVector.reverse.toMap)
    assertEquals(reverse.mappedFullName("avro.deep.child.Record"), "target.child.Record")
    assertEquals(GeneratorConfig(namespaceMappings = Map("avro" -> "")).mappedFullName("avro.deep.Record"), "deep.Record")
    assertEquals(GeneratorConfig(namespaceMappings = Map("avro" -> "")).mappedFullName("avro.Record"), "Record")
  }

  test("mapping rewrites declarations paths and recursive references while preserving Avro metadata") {
    val schema = new Schema.Parser().parse("""{"type":"record","name":"Parent","namespace":"avro.deep","aliases":["OldParent"],"fields":[
      {"name":"self","type":["null","Parent"]},
      {"name":"child","type":{"type":"record","name":"Child","fields":[{"name":"parent","type":"Parent"}]}},
      {"name":"children","type":{"type":"array","items":"Child"}},
      {"name":"lookup","type":{"type":"map","values":"Child"}},
      {"name":"choice","type":["string","Child"]},
      {"name":"code","type":{"type":"enum","name":"Code","symbols":["A"]}},
      {"name":"bytes","type":{"type":"fixed","name":"Data","size":2}}
    ]}""")
    val before = schema.toString
    val sources = CodeGenerator.generate(schema, GeneratorConfig(namespaceMappings = Map("avro" -> "fallback", "avro.deep" -> "scala.type")))
    assertEquals(sources.map(_.relativePath), Vector("scala/type/Child.scala", "scala/type/Code.scala", "scala/type/Data.scala", "scala/type/Parent.scala"))
    val parent = sources.find(_.relativePath.endsWith("/Parent.scala")).get.content
    val child = sources.find(_.relativePath.endsWith("/Child.scala")).get.content
    assert(parent.contains("package scala.`type`"))
    assert(parent.contains("self: _root_.scala.Option[_root_.scala.`type`.Parent]"))
    assert(parent.contains("child: _root_.scala.`type`.Child"))
    assert(parent.contains("Vector[_root_.scala.`type`.Child]"))
    assert(parent.contains("Map[_root_.java.lang.String, _root_.scala.`type`.Child]"))
    assert(parent.contains("_root_.java.lang.String | _root_.scala.`type`.Child"))
    assert(parent.contains("_root_.scala.`type`.Child.codec.read(in)"))
    assert(parent.contains("_root_.scala.`type`.Child.codec.write(value.child, out)"))
    assert(child.contains("_root_.scala.`type`.Parent.codec.read(in)"))
    assert(parent.contains(ScalaNames.literal(before)))
    assertEquals(schema.toString, before)
    for original <- Vector("Parent", "Child", "Code", "Data") do
      assert(parent.contains(s"case \"avro.deep.$original\" => _root_.scala.`type`.$original.codec"))
    assert(!parent.contains("case \"scala.type.Parent\""))
  }

  test("empty source mappings move only default-package types") {
    val json = """{"type":"record","name":"Parent","fields":[{"name":"child","type":{"type":"record","name":"Child","namespace":"nested","fields":[]}}]}"""
    val sources = generate(json, GeneratorConfig(namespaceMappings = Map("" -> "moved")))
    assertEquals(sources.map(_.relativePath).toSet, Set("moved/Parent.scala", "nested/Child.scala"))
    val parent = sources.find(_.relativePath == "moved/Parent.scala").get.content
    assert(parent.contains("child: _root_.nested.Child"))
    assert(parent.contains("case \"Parent\" => _root_.moved.Parent.codec"))
    assert(!sources.find(_.relativePath == "nested/Child.scala").get.content.contains("moved.Parent.codec"))
  }

  test("reference accessibility is validated after mapping and can be repaired by mapping") {
    val schema = """{"type":"record","name":"Parent","namespace":"source","fields":[{"name":"child","type":{"type":"record","name":"Child","namespace":"other","fields":[]}}]}"""
    val error = intercept[GenerationException] {
      generate(schema, GeneratorConfig(namespaceMappings = Map("other" -> "")))
    }
    assert(error.getMessage.contains("source.Parent.child: Scala cannot reference default-package type 'Child'"))
    val both = generate(schema, GeneratorConfig(namespaceMappings = Map("source" -> "", "other" -> "")))
    assertEquals(both.map(_.relativePath).toSet, Set("Parent.scala", "Child.scala"))
    assert(both.find(_.relativePath == "Parent.scala").get.content.contains("child: Child"))
    val originalDefault = schema.replace("\"namespace\":\"other\"", "\"namespace\":\"\"")
    val repaired = generate(originalDefault, GeneratorConfig(namespaceMappings = Map("" -> "safe")))
    assert(repaired.find(_.relativePath == "source/Parent.scala").get.content.contains("child: _root_.safe.Child"))
  }

  test("mapped Scala names are validated including stripped default-package names") {
    for mapping <- Vector(Map("unused..source" -> "valid"), Map("valid" -> "bad/path"), Map("valid" -> "bad..target"), Map("valid" -> "_root_.target"), Map("valid" -> "_")) do
      intercept[GenerationException] { GeneratorConfig(namespaceMappings = mapping).validate() }
    val error = intercept[GenerationException] {
      generate(record("\"int\"", "original.value"), GeneratorConfig(namespaceMappings = Map("original" -> "")))
    }
    assert(error.getMessage.contains("Default-package type 'value' conflicts"))
    val repaired = generate(record("\"int\"", "_.Value"), GeneratorConfig(namespaceMappings = Map("_" -> "safe")))
    assertEquals(repaired.head.relativePath, "safe/Value.scala")
  }

  test("mapped collisions include unmapped names and report deterministic original identities") {
    val json = """{"type":"record","name":"Root","namespace":"example","fields":[
      {"name":"first","type":{"type":"record","name":"Item","namespace":"source","fields":[]}},
      {"name":"second","type":{"type":"record","name":"Item","namespace":"target","fields":[]}},
      {"name":"third","type":{"type":"record","name":"Item","namespace":"extra","fields":[]}}
    ]}"""
    def message(config: GeneratorConfig): String = intercept[GenerationException] { generate(json, config) }.getMessage
    val config = GeneratorConfig(namespaceMappings = Map("source" -> "target", "extra" -> "target"))
    val error = message(config)
    assertEquals(error, message(config.copy(namespaceMappings = config.namespaceMappings.toVector.reverse.toMap)))
    assert(error.contains("'target.Item' from 'extra.Item', 'source.Item', 'target.Item'"))
  }

  test("raw logical primitives expose physical values and direct binary operations") {
    val cases = Vector(
      (LogicalType.Date, "int", "_root_.scala.Int", "Int", ""),
      (LogicalType.TimeMillis, "int", "_root_.scala.Int", "Int", ""),
      (LogicalType.TimeMicros, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.TimestampMillis, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.TimestampMicros, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.TimestampNanos, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.LocalTimestampMillis, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.LocalTimestampMicros, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.LocalTimestampNanos, "long", "_root_.scala.Long", "Long", ""),
      (LogicalType.Uuid, "string", "_root_.java.lang.String", "String", ""),
      (LogicalType.Decimal, "bytes", "_root_.avro2s.wire.runtime.Bytes", "Bytes", ",\"precision\":9,\"scale\":2"),
      (LogicalType.BigDecimal, "bytes", "_root_.avro2s.wire.runtime.Bytes", "Bytes", "")
    )
    for (logical, physical, valueType, method, extra) <- cases do
      val json = record(s"""{"type":"$physical","logicalType":"${logical.avroName}"$extra}""")
      val source = generate(json, GeneratorConfig(logicalTypes = Map(logical -> LogicalTypeMode.Raw))).head.content
      assert(source.contains(s"value: $valueType"), logical.toString)
      assert(source.contains(s"in.read$method()"), logical.toString)
      assert(source.contains(s"out.write$method(value.value)"), logical.toString)
      assert(!source.contains("LogicalValues."), logical.toString)
      assert(source.contains(s"_root_.scala.collection.immutable.Set(\"${logical.avroName}\")"))
      assertEquals(generate(json), generate(json, allConverted))
  }

  test("mapped types cannot become another generated type's package") {
    val json = """{"type":"record","name":"Root","namespace":"source","fields":[
      {"name":"first","type":{"type":"record","name":"A","fields":[]}},
      {"name":"second","type":{"type":"record","name":"Child","namespace":"other","fields":[]}}
    ]}"""
    for mappings <- Vector(Map("source" -> "target", "other" -> "target.A"), Map("other" -> "source.A")) do
      val error = intercept[GenerationException] { generate(json, GeneratorConfig(namespaceMappings = mappings)) }
      assert(error.getMessage.contains("is both a generated type and a package required by"))
      assert(error.getMessage.contains("A.Child"))
  }

  test("type package collisions are component-based and deterministic without rejecting the empty package") {
    val names = Vector("p.A", "p.A.B", "p.A.B.Child", "p.Able.Child", "p.A")
    def message(values: Vector[String]): String =
      intercept[GenerationException] { ScalaNames.validateTypePaths(values) }.getMessage
    assertEquals(message(names), message(names.reverse))
    assertEquals(message(names), "Scala type/package path collisions: " +
      "'p.A' is both a generated type and a package required by 'p.A.B'; " +
      "'p.A' is both a generated type and a package required by 'p.A.B.Child'; " +
      "'p.A.B' is both a generated type and a package required by 'p.A.B.Child'")
    ScalaNames.validateTypePaths(Vector("p.A", "p.Able.Child", "A", "A.Child", "scala", "scala.custom.Child"))
    val sources = generate("""{"type":"record","name":"original.A","fields":[
      {"name":"child","type":{"type":"record","name":"Child","namespace":"A","fields":[]}}
    ]}""", GeneratorConfig(namespaceMappings = Map("original" -> "")))
    assertEquals(sources.map(_.relativePath).toSet, Set("A.scala", "A/Child.scala"))
    assert(sources.find(_.relativePath == "A.scala").get.content.contains("child: _root_.A.Child"))
  }

  test("raw fixed logical types retain nominal byte wrappers and exact size checks") {
    for (logical, size, extra) <- Vector((LogicalType.Uuid, 16, ""), (LogicalType.Duration, 12, ""), (LogicalType.Decimal, 8, ",\"precision\":18,\"scale\":4")) do
      val json = s"""{"type":"fixed","name":"example.FixedValue","size":$size,"logicalType":"${logical.avroName}"$extra}"""
      val raw = generate(json, allRaw).head.content
      assert(raw.contains("final case class FixedValue(value: _root_.avro2s.wire.runtime.Bytes)"))
      assert(raw.contains(s"require(value.size == $size,"))
      assert(raw.contains(s"new _root_.example.FixedValue(in.readFixed($size))"))
      assert(raw.contains("out.writeFixed(value.value)"))
      assert(!raw.contains("LogicalValues."))
      assertEquals(generate(json), generate(json, allConverted))
  }

  test("raw modes propagate through nullable arrays maps and nominal fixed unions") {
    val json = """{"type":"record","name":"example.Nested","fields":[
      {"name":"optional","type":["null",{"type":"int","logicalType":"date"}]},
      {"name":"dates","type":{"type":"array","items":{"type":"int","logicalType":"date"}}},
      {"name":"timestamps","type":{"type":"map","values":{"type":"long","logicalType":"timestamp-nanos"}}},
      {"name":"id","type":[{"type":"string","logicalType":"uuid"},{"type":"fixed","name":"Id","size":16,"logicalType":"uuid"}]}
    ]}"""
    val sources = generate(json, allRaw.copy(namespaceMappings = Map("example" -> "mapped")))
    val source = sources.find(_.relativePath == "mapped/Nested.scala").get.content
    assert(source.contains("optional: _root_.scala.Option[_root_.scala.Int]"))
    assert(source.contains("dates: _root_.scala.collection.immutable.Vector[_root_.scala.Int]"))
    assert(source.contains("timestamps: _root_.scala.collection.immutable.Map[_root_.java.lang.String, _root_.scala.Long]"))
    assert(source.contains("id: _root_.java.lang.String | _root_.mapped.Id"))
    assert(source.contains("out.writeIntArray(value.dates)"))
    assert(source.contains("case \"example.Id\" => _root_.mapped.Id.codec"))
    assert(!source.contains("LogicalValues."))
  }

  test("mixed raw and converted time unions tag only two converted LocalTime branches") {
    val json = record("""[{"type":"int","logicalType":"time-millis"},{"type":"long","logicalType":"time-micros"}]""")
    for millis <- LogicalTypeMode.values; micros <- LogicalTypeMode.values do
      val source = generate(json, GeneratorConfig(logicalTypes = Map(LogicalType.TimeMillis -> millis, LogicalType.TimeMicros -> micros))).head.content
      val bothConverted = millis == LogicalTypeMode.Converted && micros == LogicalTypeMode.Converted
      assertEquals(source.contains("new _root_.avro2s.wire.runtime.TimeMillis("), bothConverted)
      assertEquals(source.contains("new _root_.avro2s.wire.runtime.TimeMicros("), bothConverted)
      val left = if millis == LogicalTypeMode.Raw then "_root_.scala.Int" else if bothConverted then "_root_.avro2s.wire.runtime.TimeMillis" else "_root_.java.time.LocalTime"
      val right = if micros == LogicalTypeMode.Raw then "_root_.scala.Long" else if bothConverted then "_root_.avro2s.wire.runtime.TimeMicros" else "_root_.java.time.LocalTime"
      assert(source.contains(s"value: $left | $right"))
  }

  test("raw policies still reject invalid and unknown logical annotations") {
    val invalid = Vector(
      """{"type":"long","logicalType":"date"}""",
      """{"type":"bytes","logicalType":"decimal","precision":0,"scale":0}""",
      """{"type":"bytes","logicalType":"decimal","precision":2,"scale":3}""",
      """{"type":"fixed","name":"Bad","size":1,"logicalType":"decimal","precision":10}""",
      """{"type":"fixed","name":"Bad","size":4,"logicalType":"duration"}""",
      """{"type":"fixed","name":"Bad","size":4,"logicalType":"uuid"}""",
      """{"type":"string","logicalType":"big-decimal"}""",
      """{"type":"int","logicalType":"unknown"}""",
      """{"type":"int","logicalType":42}"""
    )
    for value <- invalid do
      val error = intercept[GenerationException] { generate(record(value), allRaw) }
      assert(error.getMessage.contains("example.Value.value:"), error.getMessage)
  }

  test("logical metadata is deterministic absent for converted options and combined with Java decimal metadata") {
    val json = record("\"int\"")
    val default = generate(json)
    assertEquals(generate(json, allConverted), default)
    assert(!default.head.content.contains("rawLogicalTypes"))
    val config = GeneratorConfig(DecimalType.Java, logicalTypes = Map(LogicalType.Uuid -> LogicalTypeMode.Raw, LogicalType.Date -> LogicalTypeMode.Raw, LogicalType.Decimal -> LogicalTypeMode.Converted))
    val source = generate(json, config).head.content
    assert(source.contains("override val rawLogicalTypes: _root_.scala.collection.immutable.Set[_root_.java.lang.String] = _root_.scala.collection.immutable.Set(\"date\", \"uuid\")"))
    assert(source.contains("DecimalRepresentation.Java"))
    assertEquals(generate(json, config), generate(json, config.copy(logicalTypes = config.logicalTypes.toVector.reverse.toMap)))
  }
