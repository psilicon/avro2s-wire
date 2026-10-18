package avro2s.wire.compiler

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object Main:
  private val usage = "Usage: avro2s-wire [--decimal-type scala|java] [--namespace-map from=to] [--logical-type name=raw|converted] [--generate-stack-safe-codecs] [--root-type-name package.Name] <schema.avsc | schema-directory> <output-directory>"

  def main(args: Array[String]): Unit =
    val (input, output, config) = parseArguments(args.toList)
    val paths = SchemaCompiler.generate(input, output, config)
    println(s"Generated ${paths.size} Scala source files")

  private def parseArguments(args: List[String]): (Path, Path, GeneratorConfig) =
    val positional = Vector.newBuilder[String]
    var decimalType: Option[DecimalType] = None
    var generateStackSafeCodecs = false
    var rootTypeName: Option[String] = None
    val namespaceMappings = mutable.LinkedHashMap.empty[String, String]
    val logicalTypes = mutable.LinkedHashMap.empty[LogicalType, LogicalTypeMode]
    var remaining = args
    while remaining.nonEmpty do remaining match
      case "--root-type-name" :: value :: tail =>
        if rootTypeName.nonEmpty then throw GenerationException(s"--root-type-name may only be specified once. $usage")
        rootTypeName = Some(value)
        remaining = tail
      case "--root-type-name" :: Nil =>
        throw GenerationException(s"Missing --root-type-name value; expected package.Name. $usage")
      case "--generate-stack-safe-codecs" :: tail =>
        if generateStackSafeCodecs then
          throw GenerationException(s"--generate-stack-safe-codecs may only be specified once. $usage")
        generateStackSafeCodecs = true
        remaining = tail
      case "--decimal-type" :: value :: tail =>
        if decimalType.nonEmpty then throw GenerationException(s"--decimal-type may only be specified once. $usage")
        decimalType = Some(value match
          case "scala" => DecimalType.Scala
          case "java" => DecimalType.Java
          case _ => throw GenerationException(s"Invalid --decimal-type '$value'; expected scala or java. $usage")
        )
        remaining = tail
      case "--decimal-type" :: Nil =>
        throw GenerationException(s"Missing --decimal-type value; expected scala or java. $usage")
      case "--namespace-map" :: value :: tail =>
        val (from, to) = assignment("--namespace-map", value)
        if namespaceMappings.contains(from) then
          throw GenerationException(s"Duplicate --namespace-map for '$from'. $usage")
        namespaceMappings(from) = to
        remaining = tail
      case "--logical-type" :: value :: tail =>
        val (name, mode) = assignment("--logical-type", value)
        val logicalType = LogicalType.fromAvroName(name).getOrElse {
          throw GenerationException(s"Unknown logical type '$name'. $usage")
        }
        if logicalTypes.contains(logicalType) then
          throw GenerationException(s"Duplicate --logical-type for '$name'. $usage")
        logicalTypes(logicalType) = mode match
          case "raw" => LogicalTypeMode.Raw
          case "converted" => LogicalTypeMode.Converted
          case _ => throw GenerationException(s"Invalid logical type mode '$mode'; expected raw or converted. $usage")
        remaining = tail
      case (option @ ("--namespace-map" | "--logical-type")) :: Nil =>
        throw GenerationException(s"Missing $option value; expected name=value. $usage")
      case option :: _ if option.startsWith("--") =>
        throw GenerationException(s"Unknown option '$option'. $usage")
      case path :: tail =>
        positional += path
        remaining = tail
      case Nil => ()
    positional.result() match
      case Vector(input, output) =>
        val config = GeneratorConfig(decimalType.getOrElse(DecimalType.Scala), namespaceMappings.toMap,
          logicalTypes.toMap, generateStackSafeCodecs, rootTypeName)
        config.validate()
        (Path.of(input), Path.of(output), config)
      case _ => throw GenerationException(usage)

  private def assignment(option: String, value: String): (String, String) =
    val separator = value.indexOf('=')
    if separator < 0 || separator != value.lastIndexOf('=') then
      throw GenerationException(s"Invalid $option '$value'; expected name=value. $usage")
    (value.substring(0, separator), value.substring(separator + 1))

object SchemaCompiler:
  /** Validate every input before writing. Existing identical outputs keep their timestamps.
    * Unnamed unions default to the file basename in the common namespace of
    * their named branches (or the default package), with namespace mappings
    * applied. An explicit rootTypeName overrides exactly one union input.
    */
  def generate(input: Path, outputDirectory: Path, config: GeneratorConfig = GeneratorConfig()): Vector[Path] =
    config.validate()
    val inputs =
      if Files.isDirectory(input) then
        val stream = Files.walk(input)
        try stream.iterator.asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".avsc"))
          .toVector.sortBy(_.toString)
        finally stream.close()
      else if Files.isRegularFile(input) && input.toString.endsWith(".avsc") then Vector(input)
      else throw GenerationException(s"Input is not an .avsc file or schema directory: $input")

    if inputs.isEmpty then throw GenerationException(s"No .avsc schemas found in $input")
    val parsed = parse(inputs)
    if config.rootTypeName.nonEmpty && parsed.count(_._2.getType == Schema.Type.UNION) != 1 then
      throw GenerationException("--root-type-name / rootTypeName requires exactly one root union in the input; generate multiple unions separately to name each explicitly")
    val generated = parsed.flatMap { (path, schema) =>
      val rootName = if schema.getType == Schema.Type.UNION then
        config.rootTypeName.orElse(Some(inferredRootName(path, schema, config)))
      else None
      CodeGenerator.generate(schema, config.copy(rootTypeName = rootName))
    }
      .groupBy(_.relativePath).toVector.sortBy(_._1).map { (path, sources) =>
        if sources.map(_.content).distinct.size != 1 then
          throw GenerationException(s"Conflicting schema definitions generate the same Scala source: $path")
        sources.head
      }
    ScalaNames.validateTypePaths(generated.map(_.relativePath.stripSuffix(".scala").replace('/', '.')))
    val output = outputDirectory.toAbsolutePath.normalize
    generated.map { source =>
      val target = output.resolve(source.relativePath).normalize
      if !target.startsWith(output) then throw GenerationException(s"Unsafe generated path: ${source.relativePath}")
      Files.createDirectories(target.getParent)
      if !Files.exists(target) || Files.readString(target, UTF_8) != source.content then
        Files.writeString(target, source.content, UTF_8)
      target
    }

  private def inferredRootName(path: Path, schema: Schema, config: GeneratorConfig): String =
    val namespaces = schema.getTypes.asScala.toVector
      .filter(branch => Set(Schema.Type.RECORD, Schema.Type.ENUM, Schema.Type.FIXED)(branch.getType))
      .map(branch => Option(branch.getNamespace).getOrElse("").split("\\.").toVector.filter(_.nonEmpty))
    val common = namespaces.reduceOption { (left, right) =>
      left.zip(right).takeWhile((a, b) => a == b).map(_._1)
    }.getOrElse(Vector.empty)
    val name = (common :+ path.getFileName.toString.stripSuffix(".avsc")).mkString(".")
    val mapped = config.mappedFullName(name)
    try ScalaNames.validateFullName(mapped, config.generateStackSafeCodecs)
    catch case error: GenerationException =>
      throw GenerationException(s"Cannot infer a Scala root-union name from $path: ${error.getMessage}; specify --root-type-name / rootTypeName")
    mapped

  private def parse(inputs: Vector[Path]): Vector[(Path, Schema)] =
    val known = mutable.LinkedHashMap.empty[String, Schema]
    val roots = mutable.ArrayBuffer.empty[(Path, Schema)]
    var remaining = inputs
    // A fresh parser per attempt avoids leaking a partially parsed named type on failure.
    // Retrying unresolved files supports named references across separately stored schemas.
    while remaining.nonEmpty do
      val failures = mutable.ArrayBuffer.empty[(Path, String)]
      var progressed = false
      remaining.foreach { path =>
        try
          val parser = new Schema.Parser().setValidateDefaults(true)
          parser.addTypes(known.values.toVector.asJava)
          val root = parser.parse(Files.readString(path, UTF_8))
          parser.getTypes.asScala.foreach { (name, schema) => known(name) = schema }
          roots += ((path, root))
          progressed = true
        catch
          case NonFatal(error) => failures += ((path, Option(error.getMessage).getOrElse(error.getClass.getName)))
      }
      if !progressed then
        throw GenerationException(failures.map { (path, message) => s"$path: $message" }.mkString("Could not parse Avro schemas:\n", "\n", ""))
      remaining = failures.map(_._1).toVector
    roots.toVector
