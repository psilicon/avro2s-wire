package avrogen.properties

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.scalacheck.{Gen, Prop, Test}
import org.scalacheck.rng.Seed
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class GeneratedPropertiesSuite extends munit.FunSuite:
  override val munitTimeout = 10.minutes
  private val seed = sys.env.get("AVROGEN_TEST_SEED").fold(20260917L)(_.toLong)
  private def setting(name: String, default: Int, min: Int, max: Int): Int =
    val value = sys.env.get(name).fold(default)(_.toInt)
    require(value >= min && value <= max, s"$name must be in [$min, $max]")
    value
  private val count = setting("AVROGEN_TEST_CASES", 48, 0, 1000)
  private val depth = setting("AVROGEN_TEST_DEPTH", 3, 0, 5)
  private val valueCount = setting("AVROGEN_TEST_VALUES", 8, 1, 64)
  private lazy val target = Path.of(sys.props("avrogen.property.target"), "schema-properties")
  private lazy val cases: Vector[SchemaCase] = sys.env.get("AVROGEN_TEST_REPLAY") match
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
        val compiled = CompiledCases.compile(Vector(candidate), directory.resolve(s"shrink-$attempt"))
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
    fail(s"${original.getMessage}; seed=$seed; replay: AVROGEN_TEST_REPLAY=$replay sbt 'propertyTests/test'", original)

  test("generated Scala compiles and agrees with independent Java Avro in both directions") {
    Files.createDirectories(target)
    val run = Files.createTempDirectory(target, s"seed-$seed-")
    val labels = cases.flatMap(SchemaCases.labels).toSet
    val observed = cases.flatMap(Coverage.labels).toSet
    if !sys.env.contains("AVROGEN_TEST_REPLAY") then
      assertEquals(SchemaCases.matrixLabels -- labels, Set.empty[String], "Required type/context coverage missing")
      assert(Set("union:general", "union:null-first", "union:null-last", "schema:recursive").subsetOf(labels))
      val mandatoryObserved = SchemaCases.mandatoryCases.flatMap(Coverage.labels).toSet
      assertEquals(Coverage.required -- mandatoryObserved, Set.empty[String], "Required actual-value coverage missing from mandatory corpus")
    val report = s"seed=$seed\nschemas=${cases.size}\nvalues=${cases.map(_.values.size).sum}\nrequiredMatrixCells=${SchemaCases.matrixLabels.size}\nrequiredValueLabels=${Coverage.required.size}\n" + (labels ++ observed).toVector.sorted.mkString("\n") + "\n"
    Files.writeString(run.resolve("coverage.txt"), report, UTF_8)
    println(s"Avrogen properties: seed=$seed, ${cases.size} schemas, ${cases.map(_.values.size).sum} values; coverage: ${run.resolve("coverage.txt")}")
    // Bound compiler memory for larger developer-selected campaigns.
    cases.grouped(32).zipWithIndex.foreach { (batch, batchIndex) =>
      val compiled = try CompiledCases.compile(batch, run.resolve(s"batch-$batchIndex"))
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

  test("shrinking preserves an injected failure, simplifies schema and data, and replays") {
    val schema = new Schema.Parser().parse("""{"type":"record","name":"ShrinkProbe","namespace":"avrogen.propertymeta","fields":[{"name":"irrelevant","type":"string"},{"name":"bug","type":"int"}]}""")
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
    val compiled = CompiledCases.compile(Vector(replay), directory.resolve("compiled"))
    try
      check(replay, compiled.cases.head)
      val valid = compiled.cases.head
      val mutant = new avrogen.runtime.AvroCodec[Any]:
        def schemaJson: String = valid.codec.schemaJson
        def read(in: avrogen.runtime.AvroInput): Any = valid.codec.read(in)
        def write(value: Any, out: avrogen.runtime.AvroOutput): Unit = out.writeInt(0)
      val failure = intercept[PropertyFailure](check(replay, CompiledCase(mutant, valid.values)))
      assertEquals(failure.kind, "native-to-java")
    finally compiled.close()
  }

  test("the oracle detects decoder-only numeric union branch corruption") {
    assert(!JavaOracle.nativeEqual(1, 1L))
    assert(!JavaOracle.nativeEqual(1.0f, 1.0d))
    assert(!JavaOracle.nativeEqual(java.lang.Float.intBitsToFloat(0x7fc01234), Float.NaN))
    assert(!JavaOracle.nativeEqual(java.lang.Double.longBitsToDouble(0x7ff8000012345678L), Double.NaN))
    val schema = new Schema.Parser().parse("""{"type":"record","name":"NumericUnionProbe","namespace":"avrogen.propertymeta","fields":[{"name":"value","type":["int","long"]}]}""")
    val record = new GenericData.Record(schema)
    record.put("value", Int.box(7))
    val c = SchemaCase(schema, Vector(record))
    Files.createDirectories(target)
    val directory = Files.createTempDirectory(target, "decoder-mutation-")
    val compiled = CompiledCases.compile(Vector(c), directory)
    try
      val valid = compiled.cases.head
      check(c, valid)
      val mutant = new avrogen.runtime.AvroCodec[Any]:
        def schemaJson: String = valid.codec.schemaJson
        def read(in: avrogen.runtime.AvroInput): Any =
          valid.codec.read(in)
          valid.codec.construct(Array[Any](7L))
        def write(value: Any, out: avrogen.runtime.AvroOutput): Unit = valid.codec.write(value, out)
      val failure = intercept[PropertyFailure](check(c, CompiledCase(mutant, valid.values)))
      assertEquals(failure.kind, "java-to-native")
    finally compiled.close()
  }
