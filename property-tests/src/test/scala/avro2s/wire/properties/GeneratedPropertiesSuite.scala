package avro2s.wire.properties

import avro2s.wire.compiler.GeneratorConfig
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.scalacheck.{Gen, Prop, Test}
import org.scalacheck.rng.Seed
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class GeneratedPropertiesSuite extends munit.FunSuite:
  override val munitTimeout = 10.minutes
  private val generation = GeneratorConfig(generateStackSafeCodecs = true)
  private val seed = sys.env.get("AVRO2S_WIRE_TEST_SEED").fold(20260917L)(_.toLong)
  private def setting(name: String, default: Int, min: Int, max: Int): Int =
    val value = sys.env.get(name).fold(default)(_.toInt)
    require(value >= min && value <= max, s"$name must be in [$min, $max]")
    value
  private val count = setting("AVRO2S_WIRE_TEST_CASES", 48, 0, 1000)
  private val depth = setting("AVRO2S_WIRE_TEST_DEPTH", 3, 0, 5)
  private val valueCount = setting("AVRO2S_WIRE_TEST_VALUES", 8, 1, 64)
  private lazy val target = Path.of(sys.props("avro2s.wire.property.target"), "schema-properties")
  private lazy val cases: Vector[SchemaCase] = sys.env.get("AVRO2S_WIRE_TEST_REPLAY") match
    case Some(path) => Vector(FailureReplay.load(Path.of(path)))
    case None =>
      val random = Gen.listOfN(count, SchemaCases.randomCase(depth, valueCount))
        .apply(Gen.Parameters.default, Seed(seed)).getOrElse(sys.error("Schema generator unexpectedly discarded its input"))
      SchemaCases.mandatoryCases ++ random

  private case class PropertyFailure(kind: String, index: Int, underlying: Throwable)
    extends RuntimeException(s"$kind, value $index: ${underlying.getMessage}", underlying)

  private def phase(kind: String, index: Int)(body: => Unit): Unit =
    try body
    catch case NonFatal(error) => throw PropertyFailure(kind, index, error)

  private def check(c: SchemaCase, compiled: CompiledCase): Unit =
    compiled.variants.foreach(checkCodec(c, _))
    compiled.values.zipWithIndex.foreach { (value, index) =>
      phase("execution-wire-equivalence", index) {
        val directBytes = compiled.codec.encode(value).toVector
        compiled.alternatives.foreach(codec => assertEquals(codec.encode(value).toVector, directBytes))
      }
    }

  private def checkCodec(c: SchemaCase, compiled: CompiledCase): Unit =
    assertEquals(compiled.values.size, c.values.size, "Every reference datum must have an independently constructed Scala value")
    c.values.zip(compiled.values).zipWithIndex.foreach { case ((reference, native), i) =>
      phase("native-to-java", i) {
        val decoded = JavaOracle.decode(c.schema, compiled.codec.encode(native))
        assertEquals(JavaOracle.normalized(c.schema, decoded), JavaOracle.normalized(c.schema, reference))
      }
      phase("java-to-native", i) {
        val decoded = compiled.codec.decode(JavaOracle.encode(c.schema, reference))
        assert(JavaOracle.nativeEqual(decoded, native), s"Decoded Scala value differs: $decoded != $native")
      }
      phase("native-roundtrip", i) {
        assert(JavaOracle.nativeEqual(compiled.codec.decode(compiled.codec.encode(native)), native))
      }
    }

  private def diagnose(c: SchemaCase, original: PropertyFailure): Nothing =
    val directory = Files.createTempDirectory(target, "failure-")
    val first = c.copy(values = Vector(c.values(original.index)))
    FailureReplay.save(first, directory.resolve("original"), s"seed=$seed\n${original.getMessage}\n")
    var attempt = 0
    val (minimal, attempts) = FailureReplay.minimize(first, 32) { candidate =>
      attempt += 1
      try
        val compiled = CompiledCases.compile(Vector(candidate), directory.resolve(s"shrink-$attempt"), generation)
        try
          try { check(candidate, compiled.cases.head); false }
          catch case failed: PropertyFailure => failed.kind == original.kind
        finally compiled.close()
      catch case NonFatal(error) =>
        // An unrelated compiler/model-construction failure is not a smaller
        // reproduction of the original interoperability failure.
        Files.writeString(directory.resolve(s"rejected-shrink-$attempt.txt"), error.toString, UTF_8)
        false
    }
    val replay = directory.resolve("minimal")
    FailureReplay.save(minimal, replay, s"seed=$seed\nrandomSchemas=$count\ndepth=$depth\nvalues=$valueCount\nshrinkAttempts=$attempts\n${original.getMessage}\n")
    fail(s"${original.getMessage}; seed=$seed; replay: AVRO2S_WIRE_TEST_REPLAY=$replay sbt 'propertyTests/test'", original)

  test("generated Scala compiles and agrees with independent Java Avro in both directions") {
    Files.createDirectories(target)
    val run = Files.createTempDirectory(target, s"seed-$seed-")
    val labels = cases.flatMap(SchemaCases.labels).toSet
    val observed = cases.flatMap(Coverage.labels).toSet
    if !sys.env.contains("AVRO2S_WIRE_TEST_REPLAY") then
      assertEquals(SchemaCases.matrixLabels -- labels, Set.empty[String], "Required type/context coverage missing")
      assert(Set("union:general", "union:null-first", "union:null-last", "schema:recursive").subsetOf(labels))
      val mandatoryObserved = SchemaCases.mandatoryCases.flatMap(Coverage.labels).toSet
      assertEquals(Coverage.required -- mandatoryObserved, Set.empty[String], "Required actual-value coverage missing from mandatory corpus")
    val report = s"seed=$seed\nschemas=${cases.size}\nvalues=${cases.map(_.values.size).sum}\nrequiredMatrixCells=${SchemaCases.matrixLabels.size}\nrequiredValueLabels=${Coverage.required.size}\n" + (labels ++ observed).toVector.sorted.mkString("\n") + "\n"
    Files.writeString(run.resolve("coverage.txt"), report, UTF_8)
    println(s"avro2s-wire properties: seed=$seed, ${cases.size} schemas, ${cases.map(_.values.size).sum} values; coverage: ${run.resolve("coverage.txt")}")
    // Bound compiler memory for larger developer-selected campaigns.
    cases.grouped(32).zipWithIndex.foreach { (batch, batchIndex) =>
      val compiled = try CompiledCases.compile(batch, run.resolve(s"batch-$batchIndex"), generation)
      catch
        case NonFatal(error) =>
          // Compilation failures also retain exact independent input datums for replay.
          batch.zipWithIndex.foreach((c, i) => FailureReplay.save(c, run.resolve(s"compile-failure-$batchIndex-$i"), s"seed=$seed\n${error.toString}\n"))
          throw error
      try
        batch.zip(compiled.cases).foreach { (c, code) =>
          try check(c, code)
          catch case failure: PropertyFailure => diagnose(c, failure)
        }
      finally compiled.close()
    }
  }

  test("collection-only schema nesting uses the stack-safe driver without named child records") {
    val collectionCases = for
      levels <- Vector(6, 48)
      firstIsArray <- Vector(true, false)
    yield
      val (payloadSchema, payload) = (0 until levels).foldLeft((Schema.create(Schema.Type.INT), Int.box(10000).asInstanceOf[AnyRef])) {
        case ((element, value), level) =>
          if (level % 2 == 0) == firstIsArray then
            Schema.createArray(element) -> java.util.Collections.singletonList(value).asInstanceOf[AnyRef]
          else
            Schema.createMap(element) -> java.util.Collections.singletonMap(s"key-$level", value).asInstanceOf[AnyRef]
      }
      val schema = Schema.createRecord(s"CollectionDepth${levels}_${if firstIsArray then "Array" else "Map"}", null,
        "avro2s.wire.collectiondepth", false)
      schema.setFields(List(new Schema.Field("value", payloadSchema, null, null.asInstanceOf[AnyRef])).asJava)
      val datum = new GenericData.Record(schema)
      datum.put("value", payload)
      SchemaCase(schema, Vector(datum))
    Files.createDirectories(target)
    val compiled = CompiledCases.compile(collectionCases, Files.createTempDirectory(target, "collection-depth-"), generation)
    try collectionCases.zip(compiled.cases).foreach { (c, code) =>
      check(c, code)
      val codec = code.alternatives.head
      val expected = JavaOracle.encode(c.schema, c.values.head)
      val observed = new AtomicReference[(Array[Byte], Any)]()
      val failure = new AtomicReference[Throwable]()
      val operation = new Runnable:
        override def run(): Unit =
          try
            val decoded = codec.decode(expected)
            observed.set(codec.encode(decoded) -> decoded)
          catch case error: Throwable => failure.set(error)
      val thread = new Thread(null, operation, "wire-static-collection-depth", 256 * 1024L)
      thread.setDaemon(true)
      thread.start()
      thread.join(60000L)
      assert(!thread.isAlive, "Collection-only traversal did not finish within 60 seconds")
      Option(failure.get()).foreach(error => throw error)
      // The model's recursive equality is outside the codec's small-stack scope.
      assertEquals(observed.get()._1.toVector, expected.toVector)
      assert(JavaOracle.nativeEqual(observed.get()._2, code.values.head))
    }
    finally compiled.close()
  }

  test("default generation compiles and interoperates without any stack-safe companion members") {
    Files.createDirectories(target)
    val run = Files.createTempDirectory(target, "direct-only-")
    assert(!GeneratorConfig().generateStackSafeCodecs)
    SchemaCases.mandatoryCases.grouped(32).zipWithIndex.foreach { (batch, index) =>
      // Use the real default configuration, including the harness default. No
      // alternative getter is compiled, and the harness checks its absence.
      val compiled = CompiledCases.compile(batch, run.resolve(s"batch-$index"))
      try batch.zip(compiled.cases).foreach { (c, code) =>
        assertEquals(code.alternatives.size, 0)
        check(c, code)
        avro2s.wire.compiler.CodeGenerator.generate(c.schema).foreach { source =>
          assert(!source.content.contains("runtime.codegen."), source.relativePath)
        }
      }
      finally compiled.close()
    }
  }

  test("deeper generated schemas agree with Java across multiple reproducible seeds") {
    if !sys.env.contains("AVRO2S_WIRE_TEST_REPLAY") then
      Files.createDirectories(target)
      var observedDepth = 0
      for campaignSeed <- Vector(0L, 42L, 20260926L) do
        val batch = Gen.listOfN(16, SchemaCases.randomCase(5, 8, valueDepth = 8))
          .apply(Gen.Parameters.default, Seed(campaignSeed)).get.toVector
        val run = Files.createTempDirectory(target, s"deep-seed-$campaignSeed-")
        val actualDepth = batch.flatMap(c => c.values.map(v => WireLayouts.resources(c.schema, v).nestingDepth)).max
        observedDepth = math.max(observedDepth, actualDepth)
        Files.writeString(run.resolve("coverage.txt"), s"seed=$campaignSeed\nschemaDepthBudget=5\nvalueDepthBudget=8\nobservedNestingDepth=$actualDepth\n")
        // Save exact schemas and independent datums before compilation so
        // compiler failures and runtime failures are both replayable.
        batch.zipWithIndex.foreach((c, i) => FailureReplay.save(c, run.resolve(s"case-$i"), s"seed=$campaignSeed\ndepth=5\n"))
        val compiled = CompiledCases.compile(batch, run.resolve("compiled"), generation)
        try batch.zip(compiled.cases).zipWithIndex.foreach { case ((c, code), index) =>
          assertEquals(code.codecs.size, 2)
          try check(c, code)
          catch case NonFatal(error) => fail(s"Deep campaign seed=$campaignSeed; replay: ${run.resolve(s"case-$index")}", error)
        }
        finally compiled.close()
      assert(observedDepth >= 6, s"The deeper campaign must actually traverse nested values, observed $observedDepth")
      println(s"avro2s-wire deeper matching properties: seeds=0,42,20260926; 48 schemas, 384 values, both codecs; observed depth=$observedDepth")
  }

  test("mutually recursive generated companions compile and resolve in either generation mode") {
    val left = new Schema.Parser().parse("""{"type":"record","name":"Left","namespace":"flag.mutual","fields":[
      {"name":"next","type":["null",{"type":"record","name":"Right","fields":[
        {"name":"next","type":["Left","null"]},{"name":"label","type":"string"}]}]},
      {"name":"value","type":"int"}]}""")
    val right = left.getField("next").schema.getTypes.get(1)
    val tail = new GenericData.Record(left)
    tail.put("next", null)
    tail.put("value", Int.box(-19))
    val middle = new GenericData.Record(right)
    middle.put("next", tail)
    middle.put("label", "middle")
    val head = new GenericData.Record(left)
    head.put("next", middle)
    head.put("value", Int.box(73))
    val c = SchemaCase(left, Vector(tail, head))
    Files.createDirectories(target)
    for enabled <- Vector(false, true) do
      val config = GeneratorConfig(generateStackSafeCodecs = enabled)
      val compiled = CompiledCases.compile(Vector(c), Files.createTempDirectory(target, s"mutual-$enabled-"), config)
      try
        val code = compiled.cases.head
        assertEquals(code.codecs.size, if enabled then 2 else 1)
        check(c, code)
        code.codecs.foreach { codec =>
          val child = codec.namedCodec(right.getFullName)
          assertEquals(child.execution, codec.execution)
          assert(child.namedCodec(left.getFullName) eq codec)
          // A doc difference forces schema resolution instead of exact JSON dispatch.
          val writer = new Schema.Parser().parse(left.toString)
          writer.addProp("testMetadata", "force resolution")
          val reader = new avro2s.wire.resolution.ResolvingReader(writer.toString, codec)
          c.values.zip(code.values).foreach { (reference, expected) =>
            assert(JavaOracle.nativeEqual(reader.decode(JavaOracle.encode(writer, reference)), expected))
          }
        }
      finally compiled.close()
  }

  test("ScalaCheck-generated cases and joint shrinks remain valid independent Avro datums") {
    val property = Prop.forAllNoShrink(SchemaCases.randomCase(3, 4)) { c =>
      (Iterator(c) ++ SchemaCases.shrink(c).iterator.take(64)).forall { candidate =>
        val parsed = new Schema.Parser().setValidateDefaults(true).parse(candidate.schema.toString)
        candidate.values.forall { datum =>
          GenericData.get().validate(candidate.schema, datum) &&
            JavaOracle.normalized(parsed, JavaOracle.decode(parsed, JavaOracle.encode(candidate.schema, datum))) == JavaOracle.normalized(candidate.schema, datum)
        }
      }
    }
    val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(100).withInitialSeed(Seed(seed)), property)
    assert(result.passed, s"Generator/shrinker failure, seed=$seed: $result")
    // Mandatory logical/recursive schemas must also survive structural shrinking.
    SchemaCases.mandatoryCases.foreach { c =>
      SchemaCases.shrink(c.copy(values = c.values.take(1))).take(64).foreach { candidate =>
        candidate.values.foreach(v => assert(GenericData.get().validate(candidate.schema, v), s"Invalid mandatory shrink: ${candidate.schema}"))
      }
    }
  }

  test("random schema identities remain consistent across depths and value counts") {
    val sourceByPath = scala.collection.mutable.Map.empty[String, String]
    val schemaByName = scala.collection.mutable.Map.empty[String, String]
    val parameters = Gen.Parameters.default
    for
      initialSeed <- Vector(0L, 1L, 2L, 3L, 42L, 700000L, 20260917L)
      maxDepth <- Vector(0, 1, 3, 5)
    do
      def sample(values: Int): SchemaCase =
        SchemaCases.randomCase(maxDepth, values).apply(parameters, Seed(initialSeed)).get
      val one = sample(1)
      val many = sample(4)
      val replayed = sample(1)
      assertEquals(one.schema.toString, many.schema.toString, "Value count must not rename or change the generated schema")
      assertEquals(one.schema.toString, replayed.schema.toString, "Schema generation must replay deterministically")
      assertEquals(JavaOracle.normalized(one.schema, one.values.head), JavaOracle.normalized(replayed.schema, replayed.values.head))
      schemaByName.get(one.schema.getFullName).foreach { previous =>
        assertEquals(one.schema.toString, previous, "A generated name cannot identify different schemas at different depths")
      }
      schemaByName(one.schema.getFullName) = one.schema.toString
      // Check every emitted named type, including nested records/enums/fixed,
      // without adding another expensive dynamic-compilation batch.
      avro2s.wire.compiler.CodeGenerator.generate(one.schema).foreach { source =>
        sourceByPath.get(source.relativePath).foreach { previous =>
          assertEquals(source.content, previous, s"Conflicting source identity at ${source.relativePath}")
        }
        sourceByPath(source.relativePath) = source.content
      }
  }

  test("shrinking preserves an injected failure, simplifies schema and data, and replays") {
    val schema = new Schema.Parser().parse("""{"type":"record","name":"ShrinkProbe","namespace":"avro2s.wire.propertymeta","fields":[{"name":"irrelevant","type":"string"},{"name":"bug","type":"int"}]}""")
    val record = new GenericData.Record(schema)
    record.put("irrelevant", "irrelevant payload")
    record.put("bug", Int.box(1024))
    val original = SchemaCase(schema, Vector(record, record))
    def injectedFailure(c: SchemaCase): Boolean = Option(c.schema.getField("bug")).exists { field =>
      c.values.exists(_.asInstanceOf[GenericData.Record].get(field.pos()).asInstanceOf[Number].intValue() != 0)
    }
    val (minimal, attempts) = FailureReplay.minimize(original, 100)(injectedFailure)
    assert(attempts > 0)
    assert(injectedFailure(minimal))
    assertEquals(minimal.values.size, 1)
    assertEquals(minimal.schema.getFields.size(), 1)
    assertEquals(minimal.values.head.asInstanceOf[GenericData.Record].get("bug"), Int.box(1))
    Files.createDirectories(target)
    val directory = Files.createTempDirectory(target, "replay-check-")
    FailureReplay.save(minimal, directory, "Injected shrinker test; not a codec defect")
    val replay = FailureReplay.load(directory)
    assertEquals(replay.schema, minimal.schema)
    assertEquals(JavaOracle.normalized(replay.schema, replay.values.head), JavaOracle.normalized(minimal.schema, minimal.values.head))
    val compiled = CompiledCases.compile(Vector(replay), directory.resolve("compiled"), generation)
    try
      check(replay, compiled.cases.head)
      val valid = compiled.cases.head
      val mutant = new avro2s.wire.runtime.AvroCodec[Any]:
        def schemaJson: String = valid.codec.schemaJson
        def read(in: avro2s.wire.runtime.AvroInput): Any = valid.codec.read(in)
        def write(value: Any, out: avro2s.wire.runtime.AvroOutput): Unit = out.writeInt(0)
      val failure = intercept[PropertyFailure](check(replay, CompiledCase(mutant, valid.values)))
      assertEquals(failure.kind, "native-to-java")
    finally compiled.close()
  }

  test("the oracle detects decoder-only numeric union branch corruption") {
    assert(!JavaOracle.nativeEqual(1, 1L))
    assert(!JavaOracle.nativeEqual(1.0f, 1.0d))
    assert(!JavaOracle.nativeEqual(java.lang.Float.intBitsToFloat(0x7fc01234), Float.NaN))
    assert(!JavaOracle.nativeEqual(java.lang.Double.longBitsToDouble(0x7ff8000012345678L), Double.NaN))
    val schema = new Schema.Parser().parse("""{"type":"record","name":"NumericUnionProbe","namespace":"avro2s.wire.propertymeta","fields":[{"name":"value","type":["int","long"]}]}""")
    val record = new GenericData.Record(schema)
    record.put("value", Int.box(7))
    val c = SchemaCase(schema, Vector(record))
    Files.createDirectories(target)
    val directory = Files.createTempDirectory(target, "decoder-mutation-")
    val compiled = CompiledCases.compile(Vector(c), directory, generation)
    try
      val valid = compiled.cases.head
      check(c, valid)
      val mutant = new avro2s.wire.runtime.AvroCodec[Any]:
        def schemaJson: String = valid.codec.schemaJson
        def read(in: avro2s.wire.runtime.AvroInput): Any =
          valid.codec.read(in)
          valid.codec.construct(Array[Any](7L))
        def write(value: Any, out: avro2s.wire.runtime.AvroOutput): Unit = valid.codec.write(value, out)
      val failure = intercept[PropertyFailure](check(c, CompiledCase(mutant, valid.values)))
      assertEquals(failure.kind, "java-to-native")
    finally compiled.close()
  }
