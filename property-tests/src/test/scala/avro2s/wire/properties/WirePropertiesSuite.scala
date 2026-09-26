package avro2s.wire.properties

import avro2s.wire.runtime.{AvroCodec, AvroDecodingException, BinaryInput, BinaryOutput, Bytes, DecodeLimits}
import java.nio.file.{Files, Path}
import java.util.Properties
import org.scalacheck.{Gen, Shrink}
import org.scalacheck.rng.Seed
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class WirePropertiesSuite extends munit.FunSuite:
  import WireLayouts.*
  override val munitTimeout = 10.minutes
  private val seed = sys.env.get("AVRO2S_WIRE_TEST_SEED").fold(20260917L)(_.toLong)
  private val randomCount = sys.env.get("AVRO2S_WIRE_WIRE_CASES").fold(32)(_.toInt)
  require(randomCount >= 0 && randomCount <= 256, "AVRO2S_WIRE_WIRE_CASES must be between 0 and 256")
  private val replayDirectory = sys.env.get("AVRO2S_WIRE_WIRE_REPLAY").map(Path.of(_))
  private lazy val target =
    val root = Path.of(sys.props("avro2s.wire.property.target"), "wire-properties")
    Files.createDirectories(root)
    Files.createTempDirectory(root, s"seed-$seed-")
  private case class Scenario(operation: String, layout: Layout, layoutSeed: Long, path: String = "$", argument: Long = 0L, actions: Option[List[Int]] = None):
    def mutation: Mutation = Mutation(operation, path, argument)
  private case class StateFailure(actions: List[Int], underlying: Throwable)
    extends RuntimeException(s"State sequence failed; reduced operations=$actions", underlying)
  private lazy val replayScenario: Option[Scenario] = replayDirectory.map { directory =>
    val properties = new Properties()
    val stream = Files.newInputStream(directory.resolve("wire.properties"))
    try properties.load(stream) finally stream.close()
    val actions = Option(properties.getProperty("actions")).map { value =>
      if value.isEmpty then List.empty[Int] else value.split(",").map(_.toInt).toList
    }
    Scenario(properties.getProperty("operation"), Layout.valueOf(properties.getProperty("layout")),
      properties.getProperty("layoutSeed").toLong, properties.getProperty("path"), properties.getProperty("argument").toLong, actions)
  }
  private lazy val cases: Vector[SchemaCase] = replayDirectory match
    case Some(directory) => Vector(FailureReplay.load(directory.resolve("base")))
    case None =>
      val direct = Vector("null", "boolean", "int", "long", "float", "double", "bytes", "string", "enum", "fixed",
        "date", "time-millis", "uuid-string", "decimal-bytes", "decimal-fixed", "duration").map(a => s"matrix:$a:direct")
      val required = (direct ++ Vector("matrix:null:array", "matrix:null:map", "matrix:int:array", "matrix:string:array-map",
        "composite:AllBranches", "composite:CollectionBranches", "composite:Recursive")).toSet
      val mandatory = SchemaCases.mandatoryCases.filter(c => SchemaCases.labels(c).exists(required)).map(c => c.copy(values = c.values.take(4)))
      require(mandatory.size == required.size, "Wire campaign lost a required schema shape")
      val random = Gen.listOfN(randomCount, SchemaCases.randomCase(2, 4)).apply(Gen.Parameters.default, Seed(seed ^ 0x57495245L)).get
      mandatory ++ random

  private var opened = Vector.empty[CompiledCases]
  private lazy val compiled: Vector[(SchemaCase, CompiledCase)] =
    val groups = cases.grouped(24).zipWithIndex.map { (group, index) =>
      val result = CompiledCases.compile(group, target.resolve(s"compiled-$index"))
      opened :+= result
      group.zip(result.cases).flatMap((c, code) => code.variants.map(c -> _))
    }.toVector.flatten
    println(s"avro2s-wire wire properties: seed=$seed, ${cases.size} schemas, ${groups.size} codec variants, ${groups.map(_._1.values.size).sum} value checks; artifacts: $target")
    groups
  override def afterAll(): Unit = opened.foreach(_.close())

  private def image(c: SchemaCase, scenario: Scenario): Image = WireLayouts.encode(c.schema, c.values.head, scenario.layout, scenario.layoutSeed)
  private def isRejected(codec: AvroCodec[Any], bytes: Array[Byte], limits: DecodeLimits = DecodeLimits.default): Boolean =
    try { codec.decode(bytes, limits); false }
    catch case _: AvroDecodingException => true

  private def save(c: SchemaCase, scenario: Scenario, directory: Path, details: String): Unit =
    FailureReplay.save(c, directory.resolve("base"), s"seed=$seed\n$details\n")
    val properties = new Properties()
    properties.setProperty("operation", scenario.operation)
    properties.setProperty("layout", scenario.layout.toString)
    properties.setProperty("layoutSeed", scenario.layoutSeed.toString)
    properties.setProperty("path", scenario.path)
    properties.setProperty("argument", scenario.argument.toString)
    scenario.actions.foreach(actions => properties.setProperty("actions", actions.mkString(",")))
    val output = Files.newOutputStream(directory.resolve("wire.properties"))
    try properties.store(output, "avro2s-wire structured wire-property replay") finally output.close()
    if scenario.operation != "state" then
      val base = image(c, scenario)
      val bytes = if scenario.operation == "valid" || scenario.operation.startsWith("limit:") then base.bytes
        else WireLayouts.mutate(base, scenario.mutation)
      Files.write(directory.resolve("wire.bin"), bytes)

  private def checked(c: SchemaCase, scenario: Scenario)(body: => Unit): Unit =
    try body
    catch case NonFatal(error) =>
      val directory = Files.createTempDirectory(target, "failure-")
      save(c, scenario, directory.resolve("original"), error.toString)
      // Joint schema/value shrinking retains the same grammar mutation. A removed
      // target site is discarded instead of mutating a different field by accident.
      val minimize = scenario.operation != "valid" && !scenario.operation.startsWith("limit:") && scenario.operation != "state"
      var attempt = 0
      val reduced = if minimize then FailureReplay.minimize(c, 6) { candidate =>
        try
          val mutated = WireLayouts.mutate(image(candidate, scenario), scenario.mutation)
          attempt += 1
          val result = CompiledCases.compile(Vector(candidate), directory.resolve(s"shrink-$attempt"))
          try result.cases.head.codecs.exists(codec => !isRejected(codec, mutated))
          finally result.close()
        catch case NonFatal(_) => false
      }._1 else c
      val reducedScenario = error match
        case failure: StateFailure => scenario.copy(actions = Some(failure.actions))
        case _ => scenario
      save(reduced, reducedScenario, directory.resolve("minimal"), s"$error\nshrinkCompilations=$attempt")
      fail(s"Wire property failed: $scenario; seed=$seed; replay with AVRO2S_WIRE_WIRE_REPLAY=${directory.resolve("minimal")} sbt 'propertyTests/testOnly avro2s.wire.properties.WirePropertiesSuite'", error)

  private def valid(c: SchemaCase, code: CompiledCase, scenario: Scenario): Unit =
    val bytes = image(c, scenario).bytes
    val expected = JavaOracle.normalized(c.schema, c.values.head)
    assertEquals(JavaOracle.normalized(c.schema, JavaOracle.decode(c.schema, bytes)), expected)
    val native = code.codec.decode(bytes)
    assert(JavaOracle.nativeEqual(native, code.values.head))
    assertEquals(JavaOracle.normalized(c.schema, JavaOracle.decode(c.schema, code.codec.encode(native))), expected)

  private def single(c: SchemaCase, code: CompiledCase, index: Int): (SchemaCase, CompiledCase) =
    (c.copy(values = Vector(c.values(index))), code.copy(values = Vector(code.values(index))))

  test("generated codecs and Java accept varied positive, sized-negative and mixed collection blocks") {
    val observed = mutable.Set.empty[String]
    if replayScenario.exists(_.operation == "valid") then
      compiled.foreach { (c, code) =>
        checked(c, replayScenario.get)(valid(c, code, replayScenario.get))
      }
    else if replayScenario.isEmpty then
      compiled.zipWithIndex.foreach { case ((c, code), caseIndex) =>
        c.values.indices.foreach { valueIndex =>
          val (one, oneCode) = single(c, code, valueIndex)
          Layout.values.foreach { layout =>
            val scenario = Scenario("valid", layout, seed + caseIndex * 1009L + valueIndex)
            observed ++= image(one, scenario).labels
            checked(one, scenario)(valid(one, oneCode, scenario))
          }
        }
      }
      val required = Set("array:positive", "array:negative", "map:positive", "map:negative", "array:multiple-blocks", "map:multiple-blocks",
        "array:empty", "map:empty", "array:zero-byte-items", "zero-byte:null")
      assertEquals(required -- observed, Set.empty[String], "Required wire layouts were not exercised")
      Files.writeString(target.resolve("layout-coverage.txt"), observed.toVector.sorted.mkString("\n") + "\n")
  }

  test("structured invalid encodings, proper prefixes and trailing bytes are rejected by generated codecs") {
    val observed = mutable.Set.empty[String]
    def check(c: SchemaCase, code: CompiledCase, scenario: Scenario): Unit = checked(c, scenario) {
      val base = image(c, scenario)
      assert(isRejected(code.codec, WireLayouts.mutate(base, scenario.mutation)), s"Accepted known-invalid encoding: $scenario")
    }
    if replayScenario.exists(s => s.operation != "valid" && s.operation != "state" && !s.operation.startsWith("limit:")) then
      compiled.foreach((c, code) => check(c, code, replayScenario.get))
    else if replayScenario.isEmpty then
      compiled.zipWithIndex.foreach { case ((c, code), caseIndex) =>
        c.values.indices.foreach { valueIndex =>
          val (one, oneCode) = single(c, code, valueIndex)
          val layoutSeed = seed + caseIndex * 1009L + valueIndex
          Vector(Layout.Positive, Layout.SizedNegative).foreach { layout =>
            val base = WireLayouts.encode(one.schema, one.values.head, layout, layoutSeed)
            val mutations = WireLayouts.mutations(base)
              .filter(m => if layout == Layout.SizedNegative then m.kind.startsWith("block-size-") else true)
              .groupBy(_.kind).toVector.sortBy(_._1).flatMap(_._2.take(2))
            mutations.foreach { mutation =>
              observed += mutation.kind
              check(one, oneCode, Scenario(mutation.kind, layout, layoutSeed, mutation.path, mutation.argument))
            }
            val prefixes = if base.bytes.length <= 64 then base.bytes.indices.toVector
              else
                val random = Gen.listOfN(24, Gen.chooseNum(0, base.bytes.length - 1)).apply(Gen.Parameters.default, Seed(layoutSeed)).get
                (Vector(0, 1, base.bytes.length - 1) ++ random).distinct
            prefixes.foreach(cut => check(one, oneCode, Scenario("truncate", layout, layoutSeed, argument = cut)))
            check(one, oneCode, Scenario("trailing", layout, layoutSeed, argument = 0x7f))
          }
        }
      }
      val required = Set("invalid-boolean", "int-overflow", "long-overflow", "negative-length", "oversize-length", "invalid-utf8",
        "negative-enum-index", "enum-index-out-of-range", "negative-union-index", "union-index-out-of-range", "collection-count-min", "block-size-short", "block-size-long")
      assertEquals(required -- observed, Set.empty[String], "Required corruption classes were not exercised")
      Files.writeString(target.resolve("mutation-coverage.txt"), observed.toVector.sorted.mkString("\n") + "\n")
  }

  private def exactLimits(c: SchemaCase, bytes: Array[Byte]): DecodeLimits =
    val resources = WireLayouts.resources(c.schema, c.values.head)
    DecodeLimits(Some(bytes.length), Some(resources.stringBytes), Some(resources.bytesLength),
      Some(resources.collectionItems), Some(resources.nestingDepth))

  private def lower(limits: DecodeLimits, dimension: String): DecodeLimits = dimension match
    case "input" => limits.copy(maxInputBytes = limits.maxInputBytes.map(_ - 1))
    case "string" => limits.copy(maxStringBytes = limits.maxStringBytes.map(_ - 1))
    case "bytes" => limits.copy(maxBytesLength = limits.maxBytesLength.map(_ - 1))
    case "items" => limits.copy(maxCollectionItems = limits.maxCollectionItems.map(_ - 1))
    case "depth" => limits.copy(maxNestingDepth = limits.maxNestingDepth.map(_ - 1))

  test("generated codecs enforce exact input, length, cumulative item and nesting budgets") {
    val observed = mutable.Set.empty[String]
    def check(c: SchemaCase, code: CompiledCase, scenario: Scenario): Unit = checked(c, scenario) {
      val bytes = image(c, scenario).bytes
      val limits = exactLimits(c, bytes)
      assert(JavaOracle.nativeEqual(code.codec.decode(bytes, limits), code.values.head), s"Exact limits should admit the datum: $limits")
      if scenario.operation != "limit:exact" then
        assert(isRejected(code.codec, bytes, lower(limits, scenario.operation.stripPrefix("limit:"))))
    }
    if replayScenario.exists(_.operation.startsWith("limit:")) then compiled.foreach((c, code) => check(c, code, replayScenario.get))
    else if replayScenario.isEmpty then
      compiled.zipWithIndex.foreach { case ((c, code), caseIndex) =>
        c.values.indices.foreach { valueIndex =>
          val (one, oneCode) = single(c, code, valueIndex)
          val scenario = Scenario("limit:exact", Layout.SizedNegative, seed + caseIndex * 1009L + valueIndex)
          val limits = exactLimits(one, image(one, scenario).bytes)
          check(one, oneCode, scenario)
          val dimensions = Vector("input" -> limits.maxInputBytes.get.toLong, "string" -> limits.maxStringBytes.get.toLong,
            "bytes" -> limits.maxBytesLength.get.toLong, "items" -> limits.maxCollectionItems.get, "depth" -> limits.maxNestingDepth.get.toLong)
          dimensions.filter(_._2 > 0).foreach { (dimension, _) =>
            observed += dimension
            check(one, oneCode, scenario.copy(operation = s"limit:$dimension"))
          }
        }
      }
      assertEquals(observed.toSet, Set("input", "string", "bytes", "items", "depth"))
  }

  private def ownedBytes(value: Any): Vector[Bytes] = value match
    case bytes: Bytes => Vector(bytes)
    case map: Map[?, ?] => map.valuesIterator.toVector.flatMap(ownedBytes)
    case sequence: Seq[?] => sequence.toVector.flatMap(ownedBytes)
    case product: Product => product.productIterator.toVector.flatMap(ownedBytes)
    case _ => Vector.empty

  private def runActions(code: CompiledCase, actions: List[Int], capacity: Int): Unit =
    val output = new BinaryOutput(capacity)
    var expected = Vector.empty[Any]
    val snapshots = mutable.ArrayBuffer.empty[(Array[Byte], Vector[Any])]
    def verify(bytes: Array[Byte], values: Vector[Any]): Unit =
      val input = new BinaryInput(bytes)
      values.foreach(value => assert(JavaOracle.nativeEqual(code.codec.read(input), value)))
      input.requireEnd()
    actions.foreach {
      case -1 => output.reset(); expected = Vector.empty
      case -2 =>
        val copy = output.toByteArray
        snapshots += ((copy, expected))
        val disposable = output.toByteArray
        java.util.Arrays.fill(disposable, 0x55.toByte)
        assertEquals(output.toByteArray.toVector, copy.toVector)
      case index =>
        val value = code.values(index)
        code.codec.write(value, output)
        expected :+= value
    }
    verify(output.toByteArray, expected)
    snapshots.foreach((bytes, values) => verify(bytes, values))

  test("generated operation sequences preserve output snapshots, reset state and decoded byte ownership") {
    def check(c: SchemaCase, code: CompiledCase, scenario: Scenario): Unit = checked(c, scenario) {
      code.values.foreach { expected =>
        val input = code.codec.encode(expected)
        val decoded = code.codec.decode(input)
        java.util.Arrays.fill(input, 0x55.toByte)
        assert(JavaOracle.nativeEqual(decoded, expected), "Decoded values retained mutable input storage")
        ownedBytes(decoded).foreach { value =>
          val exposed = value.toArray
          java.util.Arrays.fill(exposed, 0x33.toByte)
        }
        assert(JavaOracle.nativeEqual(decoded, expected), "Bytes.toArray exposed mutable decoded storage")
      }
      val complete = scenario.actions.getOrElse {
        val generated = Gen.listOfN(32, Gen.chooseNum(-2, code.values.size - 1)).apply(Gen.Parameters.default, Seed(scenario.layoutSeed)).get
        List(0, -2, 0, -1, code.values.size - 1, -2) ++ generated
      }
      try runActions(code, complete, scenario.argument.toInt)
      catch case NonFatal(error) =>
        var minimal = complete
        var budget = 64
        var searching = true
        while searching && budget > 0 do
          searching = false
          val shrinks = Shrink.shrink(minimal).filter(_.forall(i => i >= -2 && i < code.values.size)).iterator
          while shrinks.hasNext && !searching && budget > 0 do
            val candidate = shrinks.next()
            budget -= 1
            try runActions(code, candidate, scenario.argument.toInt)
            catch case NonFatal(_) => minimal = candidate; searching = true
        throw StateFailure(minimal, error)
    }
    if replayScenario.exists(_.operation == "state") then compiled.foreach((c, code) => check(c, code, replayScenario.get))
    else if replayScenario.isEmpty then
      compiled.zipWithIndex.foreach { case ((c, code), index) =>
        Vector(0, 1, 7, 64).foreach(capacity => check(c, code, Scenario("state", Layout.Positive, seed + index * 1009L + capacity, argument = capacity)))
      }
  }
