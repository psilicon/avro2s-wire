package avro2s.wire.properties

import avro2s.wire.compiler.{CodeGenerator, DecimalType, GeneratorConfig}
import avro2s.wire.runtime.AvroCodec
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable

final case class CompiledCase(codec: AvroCodec[Any], values: Vector[Any])

final class CompiledCases private (val cases: Vector[CompiledCase], loader: URLClassLoader) extends AutoCloseable:
  override def close(): Unit = loader.close()

object CompiledCases:
  private def qualified(name: String): String = "_root_." + name.split('.').map(n => s"`$n`").mkString(".")

  /** This is the actual pinned Scala compiler, not a source-string snapshot assertion. */
  def compile(cases: Vector[SchemaCase], directory: Path, config: GeneratorConfig = GeneratorConfig()): CompiledCases =
    val sources = directory.resolve("sources")
    val classes = directory.resolve("classes")
    Files.createDirectories(sources)
    Files.createDirectories(classes)
    val files = mutable.LinkedHashMap.empty[String, String]
    cases.zipWithIndex.foreach { (c, index) =>
      CodeGenerator.generate(c.schema, config).foreach { source =>
        files.get(source.relativePath).foreach(previous => require(previous == source.content, s"Conflicting generated source: ${source.relativePath}"))
        files(source.relativePath) = source.content
      }
      val expressions = c.values.map(v => NativeValues.expression(c.schema, v, javaDecimals = config.decimalType == DecimalType.Java))
      // Separate methods keep large campaigns below the JVM method-size limit.
      val valueMethods = expressions.zipWithIndex.map((expr, i) => s"  private def value$i: Any = $expr").mkString("\n")
      files(s"probes/Probe$index.scala") = s"""package avro2s.wire.propertyprobes
object Probe$index:
  def codec: _root_.avro2s.wire.runtime.AvroCodec[Any] = ${qualified(c.schema.getFullName)}.codec.asInstanceOf[_root_.avro2s.wire.runtime.AvroCodec[Any]]
$valueMethods
  def values: Vector[Any] = Vector(${expressions.indices.map(i => s"value$i").mkString(", ")})
"""
    }
    val paths = files.toVector.map { (name, contents) =>
      val path = sources.resolve(name)
      Files.createDirectories(path.getParent)
      Files.writeString(path, contents, UTF_8)
      path.toString
    }
    val classpath = sys.props.getOrElse("avro2s.wire.generated.classpath", sys.error("Run through sbt propertyTests/test to supply the runtime-only compilation classpath"))
    val arguments = Vector("-classpath", classpath, "-d", classes.toString, "-color:never", "-deprecation", "-feature", "-unchecked") ++ paths
    val reporter = new dotty.tools.dotc.Driver().process(arguments.toArray)
    require(!reporter.hasErrors, s"Generated Scala did not compile; sources retained at $sources")
    val loader = new URLClassLoader(Array(classes.toUri.toURL), getClass.getClassLoader)
    try
      val compiled = cases.indices.map { index =>
        val clazz = loader.loadClass(s"avro2s.wire.propertyprobes.Probe$index$$")
        val module = clazz.getField("MODULE$").get(null)
        CompiledCase(clazz.getMethod("codec").invoke(module).asInstanceOf[AvroCodec[Any]],
          clazz.getMethod("values").invoke(module).asInstanceOf[Vector[Any]])
      }.toVector
      new CompiledCases(compiled, loader)
    catch
      case error: Throwable => loader.close(); throw error
