package avro2s.wire.properties

import avro2s.wire.resolution.{ResolvingReader, SchemaResolutionException}
import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput, BinaryInput}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, IndexedRecord}
import org.scalacheck.{Gen, Prop, Test}
import org.scalacheck.rng.Seed
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class EvolutionPropertiesSuite extends munit.FunSuite:
  override val munitTimeout = 10.minutes
  private val seed = sys.env.get("AVRO2S_WIRE_TEST_SEED").fold(20260917L)(_.toLong)
  private def setting(name: String, default: Int, min: Int, max: Int): Int =
    val value = sys.env.get(name).fold(default)(_.toInt)
    require(value >= min && value <= max, s"$name must be in [$min, $max]")
    value
  private val count = setting("AVRO2S_WIRE_EVOLUTION_CASES", 32, 0, 1000)
  private val depth = setting("AVRO2S_WIRE_EVOLUTION_DEPTH", 1, 0, 3)
  private val valueCount = setting("AVRO2S_WIRE_EVOLUTION_VALUES", 4, 1, 64)
  private lazy val target = Path.of(sys.props("avro2s.wire.property.target"), "evolution-properties")
  private lazy val cases: Vector[EvolutionCase] = sys.env.get("AVRO2S_WIRE_EVOLUTION_REPLAY") match
    case Some(path) => Vector(EvolutionReplay.load(Path.of(path)))
    case None =>
      val random = Gen.listOfN(count, EvolutionCases.randomCase(depth, valueCount))
        .apply(Gen.Parameters.default, Seed(seed)).getOrElse(sys.error("Evolution generator unexpectedly discarded its input"))
      EvolutionCases.mandatory ++ random

  private case class PropertyFailure(kind: String, index: Int, underlying: Throwable)
    extends RuntimeException(s"$kind, value $index: ${underlying.getMessage}", underlying)
  private def phase[A](kind: String, index: Int)(body: => A): A =
    try body
    catch case NonFatal(error) => throw PropertyFailure(kind, index, error)

  private def check(c: EvolutionCase, expected: SchemaCase, compiled: CompiledCase): Unit =
    assertEquals(compiled.values.size, c.values.size)
    assertEquals(expected.values.size, c.values.size)
    val resolver = phase("plan", 0)(new ResolvingReader(c.writer.toString, compiled.codec))
    c.values.indices.foreach { index =>
      val bytes = JavaOracle.encode(c.writer, c.values(index))
      val actual = phase("resolve", index) {
        val resolved = resolver.decode(bytes)
        assert(JavaOracle.nativeEqual(resolved, compiled.values(index)),
          s"Native resolved Scala value differs from independently constructed reader value: $resolved != ${compiled.values(index)}")
        resolved
      }
      phase("reader-wire", index) {
        val physical = JavaOracle.decode(c.reader, compiled.codec.encode(actual))
        assertEquals(JavaOracle.normalized(c.reader, physical), JavaOracle.normalized(c.reader, expected.values(index)))
      }
      phase("reused-plan", index) {
        // A plan is retained across different data and reused on one concatenated
        // input; skipped fields must end at exactly the next record boundary.
        val in = new BinaryInput(bytes ++ bytes)
        assert(JavaOracle.nativeEqual(resolver.read(in), compiled.values(index)))
        assert(JavaOracle.nativeEqual(resolver.read(in), compiled.values(index)))
        in.requireEnd()
      }
    }

  private def diagnose(c: EvolutionCase, original: PropertyFailure): Nothing =
    val directory = Files.createTempDirectory(target, "failure-")
    val first = c.copy(values = Vector(c.values(original.index)))
    EvolutionReplay.save(first, directory.resolve("original"), s"seed=$seed\n${original.getMessage}\n")
    var attempt = 0
    val (minimal, attempts) = EvolutionReplay.minimize(first, 24) { candidate =>
      attempt += 1
      try
        // Shrinks must still resolve in Java. Unrelated schema/compiler failures
        // cannot become a reproduction of a runtime resolution defect.
        val expected = EvolutionOracle.expected(candidate)
        val compiled = CompiledCases.compile(Vector(expected), directory.resolve(s"shrink-$attempt"))
        try
          try { check(candidate, expected, compiled.cases.head); false }
          catch case failed: PropertyFailure => failed.kind == original.kind
        finally compiled.close()
      catch case NonFatal(error) =>
        Files.writeString(directory.resolve(s"rejected-shrink-$attempt.txt"), error.toString, UTF_8)
        false
    }
    val replay = directory.resolve("minimal")
    EvolutionReplay.save(minimal, replay,
      s"seed=$seed\nrandomPairs=$count\ndepth=$depth\nvalues=$valueCount\nshrinkAttempts=$attempts\n${original.getMessage}\n")
    fail(s"${original.getMessage}; seed=$seed; replay: AVRO2S_WIRE_EVOLUTION_REPLAY=$replay sbt 'propertyTests/testOnly avro2s.wire.properties.EvolutionPropertiesSuite'", original)

  /** Observe physical branch visits, not just the generator's intended operation labels. */
  private def selectedBranches(c: EvolutionCase): Set[String] =
    val rule = c.labels.find(_.startsWith("rule:")).getOrElse("rule:replay").stripPrefix("rule:")
    val field = Option(c.writer.getField("payload"))
    def visit(schema: Schema, value: AnyRef): Set[String] = schema.getType match
      case Schema.Type.UNION =>
        val index = GenericData.get().resolveUnion(schema, value)
        Set(s"selected:$rule:$index")
      case Schema.Type.ARRAY => value.asInstanceOf[java.util.Collection[AnyRef]].asScala.flatMap(visit(schema.getElementType, _)).toSet
      case Schema.Type.MAP => value.asInstanceOf[java.util.Map[?, AnyRef]].values.asScala.flatMap(visit(schema.getValueType, _)).toSet
      case Schema.Type.ENUM => Set(s"symbol:$rule:${value.toString}")
      case Schema.Type.RECORD if rule == "recursive-record" =>
        val node = value.asInstanceOf[IndexedRecord]
        if node.get(schema.getField("next").pos) != null then Set("observed:recursive-nonterminal") else Set.empty
      case _ => Set.empty
    field.toSet.flatMap(f => c.values.flatMap(v => visit(f.schema, v.asInstanceOf[IndexedRecord].get(f.pos).asInstanceOf[AnyRef])))

  private val requiredBranches = Set(
    "selected:union-reorder:0", "selected:union-reorder:1", "selected:union-reorder:2",
    "selected:union-promotion:0", "selected:union-promotion:1", "selected:union-promotion:2",
    "selected:union-metadata:0", "selected:union-metadata:1",
    "selected:union-times:0", "selected:union-times:1", "selected:union-times:2",
    "selected:union-named-aliases:0", "selected:union-named-aliases:1", "selected:union-named-aliases:2",
    "symbol:enum-default:B", "symbol:enum-reorder:A", "symbol:enum-reorder:B", "symbol:enum-reorder:C",
    "observed:recursive-nonterminal"
  )

  test("generated writer-reader pairs agree with Java resolution and independent Scala constructors") {
    Files.createDirectories(target)
    val run = Files.createTempDirectory(target, s"seed-$seed-")
    val observed = cases.flatMap(selectedBranches).toSet
    val labels = cases.flatMap(_.labels).toSet
    if !sys.env.contains("AVRO2S_WIRE_EVOLUTION_REPLAY") then
      assertEquals(EvolutionCases.required -- EvolutionCases.mandatory.flatMap(_.labels).toSet, Set.empty[String])
      assertEquals(requiredBranches -- EvolutionCases.mandatory.flatMap(selectedBranches).toSet, Set.empty[String],
        "Mandatory evolution corpus must actually select every required writer union branch")
    val report = s"seed=$seed\npairs=${cases.size}\nvalues=${cases.map(_.values.size).sum}\nrandomPairs=$count\ndepth=$depth\n" +
      (labels ++ observed).toVector.sorted.mkString("\n") + "\n"
    Files.writeString(run.resolve("coverage.txt"), report, UTF_8)
    println(s"avro2s-wire evolution properties: seed=$seed, ${cases.size} pairs, ${cases.map(_.values.size).sum} values; coverage: ${run.resolve("coverage.txt")}")
    cases.grouped(24).zipWithIndex.foreach { (batch, batchIndex) =>
      val expected = batch.zipWithIndex.map { (c, index) =>
        try EvolutionOracle.expected(c)
        catch case NonFatal(error) =>
          EvolutionReplay.save(c, run.resolve(s"oracle-failure-$batchIndex-$index"), s"seed=$seed\n${error.toString}\n")
          throw error
      }
      val compiled = try CompiledCases.compile(expected, run.resolve(s"batch-$batchIndex"))
      catch case NonFatal(error) =>
        batch.zipWithIndex.foreach((c, i) => EvolutionReplay.save(c, run.resolve(s"compile-failure-$batchIndex-$i"), s"seed=$seed\n${error.toString}\n"))
        throw error
      try batch.zip(expected).zip(compiled.cases).foreach { case ((c, reference), code) =>
        try check(c, reference, code)
        catch case failure: PropertyFailure => diagnose(c, failure)
      }
      finally compiled.close()
    }
  }

  test("random pair and value shrinks retain compatible Java resolution") {
    val property = Prop.forAllNoShrink(EvolutionCases.randomCase(1, 4)) { c =>
      (Iterator(c) ++ EvolutionCases.shrink(c).iterator.take(24)).forall { candidate =>
        val expected = EvolutionOracle.expected(candidate)
        expected.values.size == candidate.values.size
      }
    }
    val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(32).withInitialSeed(Seed(seed)), property)
    assert(result.passed, s"Evolution generator/shrinker failure, seed=$seed: $result")
    val generated = Gen.listOfN(128, EvolutionCases.randomCase(1, 1))
      .apply(Gen.Parameters.default, Seed(seed)).get
    generated.groupBy(_.reader.getFullName).values.foreach { shared =>
      assertEquals(shared.map(_.reader.toString).distinct.size, 1, "A generated name must identify one complete reader schema")
      assertEquals(shared.map(_.writer.toString).distinct.size, 1, "A generated name must identify one complete writer schema")
    }
    val bytesDefault = EvolutionCases.mandatory.find(_.labels("default:logical-decimal")).get
    val decimal = bytesDefault.reader.getFields.asScala.find(_.schema.getProp("logicalType") == "decimal").get
    val original = decimal.defaultVal.asInstanceOf[Array[Byte]].toVector
    EvolutionCases.shrink(bytesDefault).filter(_.reader.getField(decimal.name) != null).take(12).foreach { candidate =>
      assertEquals(candidate.reader.getField(decimal.name).defaultVal.asInstanceOf[Array[Byte]].toVector, original,
        "Pair projection must preserve Avro byte-string defaults without base64 re-encoding")
      EvolutionOracle.expected(candidate)
    }
  }

  test("pair shrinking preserves a failure predicate and replays both schemas and physical data") {
    val original = EvolutionCases.mandatory.find(c => c.labels("rule:int-long") && c.labels("context:direct")).get
    def injectedFailure(c: EvolutionCase): Boolean = Option(c.writer.getField("payload")).exists { field =>
      c.values.exists(v => v.asInstanceOf[IndexedRecord].get(field.pos).asInstanceOf[Number].intValue != 0)
    }
    val (minimal, attempts) = EvolutionReplay.minimize(original, 96) { candidate =>
      EvolutionOracle.expected(candidate)
      injectedFailure(candidate)
    }
    assert(attempts > 0)
    assert(injectedFailure(minimal))
    assertEquals(minimal.values.size, 1)
    assertEquals(minimal.writer.getFields.size, 1)
    assertEquals(minimal.reader.getFields.size, 1)
    Files.createDirectories(target)
    val directory = Files.createTempDirectory(target, "replay-check-")
    EvolutionReplay.save(minimal, directory, "Injected shrinking test; not a resolver defect")
    val replay = EvolutionReplay.load(directory)
    assertEquals(replay.writer, minimal.writer)
    assertEquals(replay.reader, minimal.reader)
    assertEquals(JavaOracle.normalized(replay.writer, replay.values.head), JavaOracle.normalized(minimal.writer, minimal.values.head))
    val expected = EvolutionOracle.expected(replay)
    val compiled = CompiledCases.compile(Vector(expected), directory.resolve("compiled"))
    try check(replay, expected, compiled.cases.head)
    finally compiled.close()
  }

  test("numeric branch corruption is detected and incompatible union branches fail only when selected") {
    val numeric = EvolutionCases.mandatory.find(c => c.labels("rule:union-exact-before-promotion") && c.labels("context:direct")).get
    val writer = new Schema.Parser().parse("""{"type":"record","name":"Conditional","namespace":"avro2s.wire.evolutionmeta","fields":[{"name":"value","type":["int","string"]}]}""")
    val reader = new Schema.Parser().parse("""{"type":"record","name":"Conditional","namespace":"avro2s.wire.evolutionmeta","fields":[{"name":"value","type":["long"]}]}""")
    val valid = new GenericData.Record(writer)
    valid.put("value", Int.box(7))
    val invalid = new GenericData.Record(writer)
    invalid.put("value", "no matching reader branch")
    val conditional = EvolutionCase(writer, reader, Vector(valid), Set("conditional-incompatible-branch"))
    val examples = Vector(numeric, conditional)
    val expected = examples.map(EvolutionOracle.expected)
    Files.createDirectories(target)
    val directory = Files.createTempDirectory(target, "oracle-mutations-")
    val compiled = CompiledCases.compile(expected, directory)
    try
      examples.zip(expected).zip(compiled.cases).foreach { case ((c, reference), code) => check(c, reference, code) }
      val code = compiled.cases.head
      val position = Option(numeric.reader.getField("payload")).getOrElse(numeric.reader.getField("changed")).pos
      val mutant = new AvroCodec[Any]:
        def schemaJson: String = code.codec.schemaJson
        def read(in: AvroInput): Any = code.codec.read(in)
        def write(value: Any, out: AvroOutput): Unit = code.codec.write(value, out)
        override def namedCodec(name: String): AvroCodec[?] = code.codec.namedCodec(name)
        override def construct(values: Array[Any]): Any =
          val modified = values.clone()
          modified(position) = modified(position).asInstanceOf[Int].toLong
          code.codec.construct(modified)
      val failure = intercept[PropertyFailure](check(numeric, expected.head, CompiledCase(mutant, code.values)))
      assertEquals(failure.kind, "resolve")
      val resolver = new ResolvingReader(writer.toString, compiled.cases(1).codec)
      val bytes = JavaOracle.encode(writer, invalid)
      intercept[org.apache.avro.AvroTypeException](EvolutionOracle.resolve(writer, reader, bytes))
      intercept[SchemaResolutionException](resolver.decode(bytes))
      // A failed branch must not poison the cached plan for subsequent valid data.
      assert(JavaOracle.nativeEqual(resolver.decode(JavaOracle.encode(writer, valid)), compiled.cases(1).values.head))
    finally compiled.close()
  }
