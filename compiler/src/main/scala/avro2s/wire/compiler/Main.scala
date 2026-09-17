package avro2s.wire.compiler

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object Main:
  def main(args: Array[String]): Unit =
    if args.length != 2 then
      throw GenerationException("Usage: avro2s-wire <schema.avsc | schema-directory> <output-directory>")
    val paths = SchemaCompiler.generate(Path.of(args(0)), Path.of(args(1)))
    println(s"Generated ${paths.size} Scala source files")

object SchemaCompiler:
  /** Validate every input before writing. Existing identical outputs keep their timestamps. */
  def generate(input: Path, outputDirectory: Path): Vector[Path] =
    val inputs =
      if Files.isDirectory(input) then
        val stream = Files.walk(input)
        try stream.iterator.asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".avsc"))
          .toVector.sortBy(_.toString)
        finally stream.close()
      else if Files.isRegularFile(input) && input.toString.endsWith(".avsc") then Vector(input)
      else throw GenerationException(s"Input is not an .avsc file or schema directory: $input")

    if inputs.isEmpty then throw GenerationException(s"No .avsc schemas found in $input")
    val generated = parse(inputs).flatMap(CodeGenerator.generate)
      .groupBy(_.relativePath).toVector.sortBy(_._1).map { (path, sources) =>
        if sources.map(_.content).distinct.size != 1 then
          throw GenerationException(s"Conflicting schema definitions generate the same Scala source: $path")
        sources.head
      }
    val output = outputDirectory.toAbsolutePath.normalize
    generated.map { source =>
      val target = output.resolve(source.relativePath).normalize
      if !target.startsWith(output) then throw GenerationException(s"Unsafe generated path: ${source.relativePath}")
      Files.createDirectories(target.getParent)
      if !Files.exists(target) || Files.readString(target, UTF_8) != source.content then
        Files.writeString(target, source.content, UTF_8)
      target
    }

  private def parse(inputs: Vector[Path]): Vector[Schema] =
    val known = mutable.LinkedHashMap.empty[String, Schema]
    val roots = mutable.ArrayBuffer.empty[Schema]
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
          roots += root
          progressed = true
        catch
          case NonFatal(error) => failures += ((path, Option(error.getMessage).getOrElse(error.getClass.getName)))
      }
      if !progressed then
        throw GenerationException(failures.map { (path, message) => s"$path: $message" }.mkString("Could not parse Avro schemas:\n", "\n", ""))
      remaining = failures.map(_._1).toVector
    roots.toVector
