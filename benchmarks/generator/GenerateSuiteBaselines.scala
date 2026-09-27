package avro2s.wire.benchmarks.generator

import avro2s.generator.{CodeGenerator, CodeWriter, EnumType, GeneratorConfig}
import avro2s.language.ScalaVersion
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import org.apache.avro.{Conversions, Schema}
import org.apache.avro.compiler.specific.SpecificCompiler
import org.apache.avro.generic.GenericData.StringType
import scala.jdk.CollectionConverters.*

/** Reproducible generation of the comparison models; never edits generated baseline code. */
object GenerateSuiteBaselines:
  private val ExpectedCommit = "861c816b63643edfaf012d2b84f1bc0ea8b4ce99"

  def main(args: Array[String]): Unit =
    require(args.isEmpty, "Paths are supplied through AVRO2S_WIRE_SUITE_ROOT")
    val root = Path.of(sys.env("AVRO2S_WIRE_SUITE_ROOT")).toAbsolutePath.normalize
    val commit = sys.env("AVRO2S_WIRE_SUITE_COMMIT")
    require(commit == ExpectedCommit, s"Expected avro2s $ExpectedCommit, found $commit")
    val avroVersion = classOf[Schema].getPackage.getImplementationVersion
    require(avroVersion == "1.12.1", s"Expected Apache Avro 1.12.1, found $avroVersion")
    val mapper = new ObjectMapper()
    val cataloguePath = root.resolve("benchmarks/src/main/resources/suite/catalog.json")
    val catalogue = mapper.readTree(cataloguePath.toFile)
    val cases = catalogue.get("cases").elements().asScala.toVector
    val models = cases.map(_.get("model").asText()).distinct.sorted
    val avro2sModels = cases.filter(_.get("avro2s").asBoolean()).map(_.get("model").asText()).toSet
    val config = GeneratorConfig(
      targetScalaVersion = ScalaVersion.Scala_3,
      logicalTypesEnabled = true,
      enumType = EnumType.ScalaEnum
    )
    val generatedRoots = Vector.newBuilder[(String, Boolean)]

    def save(relativePath: String, text: String): Path =
      val path = root.resolve(relativePath)
      Files.createDirectories(path.getParent)
      Files.writeString(path, text, UTF_8)

    def relocated(tree: ObjectNode, namespace: String): Schema =
      val copy = tree.deepCopy()
      copy.put("namespace", namespace)
      new Schema.Parser().setValidateDefaults(true).parse(mapper.writeValueAsString(copy))

    models.foreach { model =>
      val schemaPath = root.resolve(s"benchmarks/src/main/resources/suite/schemas/$model.avsc")
      val tree = mapper.readTree(schemaPath.toFile).asInstanceOf[ObjectNode]
      val javaSchema = relocated(tree, "avro2s.wire.benchmarks.suite.javaavro")
      val compiler = new SpecificCompiler(javaSchema)
      compiler.setOutputCharacterEncoding("UTF-8")
      compiler.setStringType(StringType.String)
      compiler.setEnableDecimalLogicalType(true)
      // These are Apache Avro's own conversions, absent from SpecificCompiler's defaults.
      compiler.addCustomConversion(classOf[Conversions.DurationConversion])
      compiler.addCustomConversion(classOf[Conversions.BigDecimalConversion])
      compiler.compileToDestination(null, root.resolve("benchmarks/src/main/java").toFile)
      val generatedRoot = root.resolve(s"benchmarks/src/main/java/avro2s/wire/benchmarks/suite/javaavro/$model.java")
      val source = Files.readString(generatedRoot, UTF_8)
      val logical = Option(javaSchema.getField("value")).flatMap(field => Option(field.schema().getLogicalType))
      val declaredLogical = tree.path("fields").path(0).path("type").path("logicalType").asText("")
      require(declaredLogical.isEmpty || logical.exists(_.getName == declaredLogical), s"$model lost its $declaredLogical annotation")
      logical.foreach { annotation =>
        val expectedType = annotation.getName match
          case "date" => "java.time.LocalDate"
          case "time-millis" | "time-micros" => "java.time.LocalTime"
          case "timestamp-millis" | "timestamp-micros" | "timestamp-nanos" => "java.time.Instant"
          case "local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos" => "java.time.LocalDateTime"
          case "uuid" => "java.util.UUID"
          case "duration" => "org.apache.avro.util.TimePeriod"
          case "decimal" | "big-decimal" => "java.math.BigDecimal"
          case other => throw new IllegalArgumentException(s"Unverified logical representation: $other")
        require(source.contains(s"private $expectedType value;"), s"$model did not generate the required $expectedType domain value")
      }
      if model == "TextValue" then
        require(source.contains("private java.lang.String value;"), "Java string generation must produce String")
      val custom = "hasCustomCoders\\(\\)\\s*\\{\\s*return true;".r.findFirstIn(source).nonEmpty
      generatedRoots += model -> custom
      if avro2sModels(model) then
        val scalaSchema = relocated(tree, "avro2s.wire.benchmarks.suite.avro2s")
        val sources = CodeGenerator.generateCode(List(scalaSchema), config)
        sources.foreach { source =>
          val language = if source.path.endsWith(".java") then "java" else "scala"
          CodeWriter.writeToDirectory(root.resolve(s"benchmarks/src/main/$language").toString)(List(source))
        }
    }

    val customByModel = generatedRoots.result().toMap
    val customModels = models.filter(customByModel)
    val probeSource = """// Generated setup/test probes; genuine baseline records remain unmodified.
package avro2s.wire.benchmarks.suite

import org.apache.avro.io.{Encoder, ResolvingDecoder}
import org.apache.avro.specific.SpecificRecordBase

trait DispatchCounts:
  var encodeCalls = 0
  var decodeCalls = 0

object GeneratedProbes:
  def create(model: String): SpecificRecordBase & DispatchCounts = model match
""" + customModels.map(model => s"    case \"$model\" => new Counting$model\n").mkString +
      "    case other => throw new IllegalArgumentException(s\"No generated custom coder for $other\")\n\n" +
      customModels.map { model => s"""private final class Counting$model extends javaavro.$model with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

""" }.mkString
    save("benchmarks/src/main/scala/avro2s/wire/benchmarks/suite/GeneratedProbes.scala", probeSource)

    val manifest = mapper.createObjectNode()
    manifest.put("version", 1)
    val base = manifest.putObject("avro2s")
    base.put("commit", commit)
    base.put("scalaVersion", "3.3.6")
    base.put("targetScalaVersion", "Scala_3")
    base.put("logicalTypesEnabled", true)
    base.put("enumType", "ScalaEnum")
    val java = manifest.putObject("apacheAvro")
    java.put("version", avroVersion)
    java.put("stringType", "String")
    java.put("enableDecimalLogicalType", true)
    java.put("outputCharacterEncoding", "UTF-8")
    java.putArray("additionalOfficialConversions")
      .add("org.apache.avro.Conversions.DurationConversion")
      .add("org.apache.avro.Conversions.BigDecimalConversion")
    val capabilities = mapper.createObjectNode()
    capabilities.put("version", 1)
    val supportedCases = capabilities.putArray("cases")
    cases.foreach { benchmark =>
      val id = benchmark.get("id").asText()
      val model = benchmark.get("model").asText()
      val item = supportedCases.addObject()
      item.put("id", id)
      item.put("model", model)
      val engines = item.putObject("engines")
      val caseOperations = benchmark.get("operations").elements().asScala.map(_.asText()).toVector
      Vector("wire", "java-specific", "java-generic", "java-custom", "avro2s").foreach { engine =>
        val entry = engines.putObject(engine)
        val excluded =
          if engine == "java-custom" && !customByModel(model) then
            Some("Apache Avro SpecificCompiler did not generate custom coders for this model")
          else if engine == "avro2s" && !benchmark.get("avro2s").asBoolean() then
            Some("Outside the approved avro2s comparison subset")
          else None
        val invalidNanosecondEncode = Set("L06", "L09")(id) && Set("java-specific", "java-generic")(engine)
        val operations =
          if excluded.nonEmpty then Vector.empty
          else if invalidNanosecondEncode then caseOperations.filter(_ != "encode")
          else caseOperations
        entry.put("supported", operations.nonEmpty)
        val supportedOperations = entry.putArray("operations")
        operations.foreach(supportedOperations.add)
        excluded.foreach(reason => entry.put("reason", reason))
        if invalidNanosecondEncode then
          entry.put("reason", "Apache Avro 1.12.1 fractional pre-epoch nanosecond encoding shifts by 999 ms")
      }
    }
    manifest.set[JsonNode]("cases", supportedCases.deepCopy())
    save("benchmarks/src/main/resources/suite/capabilities.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(capabilities) + "\n")

    def sourceFiles(relative: String): Vector[Path] =
      val directory = root.resolve(relative)
      if !Files.exists(directory) then Vector.empty
      else
        val stream = Files.walk(directory)
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector
        finally stream.close()

    val inputs = sourceFiles("benchmarks/src/main/resources/suite") ++
      sourceFiles("benchmarks/src/main/java/avro2s/wire/benchmarks/suite/javaavro") ++
      sourceFiles("benchmarks/src/main/scala/avro2s/wire/benchmarks/suite/avro2s") ++
      sourceFiles("benchmarks/src/main/java/avro2s/wire/benchmarks/suite/avro2s") ++
      Vector(root.resolve("benchmarks/src/main/scala/avro2s/wire/benchmarks/suite/GeneratedProbes.scala"),
        root.resolve("benchmarks/generator/GenerateSuiteBaselines.scala"),
        root.resolve("scripts/regenerate-suite-baselines.sh"))
    val hashes = manifest.putArray("sources")
    inputs.distinct.sortBy(_.toString).foreach { path =>
      val entry = hashes.addObject()
      entry.put("path", root.relativize(path).toString)
      val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(b => f"${b & 0xff}%02x").mkString
      entry.put("sha256", hash)
    }
    save("benchmarks/generator/suite-baselines.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n")
    println(s"Generated ${models.size} Java root models, ${avro2sModels.size} avro2s root models; ${customModels.size} Java roots have generated custom coders")
