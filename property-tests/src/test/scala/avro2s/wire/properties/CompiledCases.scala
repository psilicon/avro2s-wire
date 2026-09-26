package avro2s.wire.properties

import avro2s.wire.compiler.{CodeGenerator, DecimalType, GeneratorConfig, LogicalTypeMode}
import avro2s.wire.runtime.{AvroCodec, CodecExecution}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

final case class CompiledCase(codec: AvroCodec[Any], values: Vector[Any], alternatives: Vector[AvroCodec[Any]] = Vector.empty):
  def codecs: Vector[AvroCodec[Any]] = codec +: alternatives
  def variants: Vector[CompiledCase] = codecs.map(selected => copy(codec = selected, alternatives = Vector.empty))

final class CompiledCases private (val cases: Vector[CompiledCase], loader: URLClassLoader) extends AutoCloseable:
  override def close(): Unit = loader.close()

object CompiledCases:
  private def typeChecks(schema: Schema, options: NativeValues.Options): String =
    val seen = mutable.Set.empty[String]
    val methods = mutable.ArrayBuffer.empty[String]
    def visit(current: Schema): Unit = current.getType match
      case Schema.Type.RECORD | Schema.Type.ENUM | Schema.Type.FIXED =>
        if seen.add(current.getFullName) then
          val fields = current.getType match
            case Schema.Type.RECORD => current.getFields.asScala.map(f => f.name -> NativeValues.nativeType(f.schema, options)).toVector
            case Schema.Type.FIXED => Vector("value" -> NativeValues.fixedValueType(current, options))
            case _ => Vector.empty
          if fields.nonEmpty then
            val checks = fields.zipWithIndex.map { case ((name, expected), i) =>
              s"    val observed$i = infer(value.`$name`)\n    val expected$i: Exact[$expected] = observed$i"
            }.mkString("\n")
            methods += s"  private def types${methods.size}(value: ${NativeValues.namedType(current, options)}): Unit =\n$checks\n    ()"
          if current.getType == Schema.Type.RECORD then current.getFields.asScala.foreach(f => visit(f.schema))
      case Schema.Type.ARRAY => visit(current.getElementType)
      case Schema.Type.MAP => visit(current.getValueType)
      case Schema.Type.UNION => current.getTypes.asScala.foreach(visit)
      case _ => ()
    visit(schema)
    // Invariance prevents an Int field from satisfying a Long assertion by widening.
    "  private final class Exact[A]\n  private def infer[A](value: A): Exact[A] = new Exact[A]\n" + methods.mkString("\n")

  /** This is the actual pinned Scala compiler, not a source-string snapshot assertion. */
  def compile(cases: Vector[SchemaCase], directory: Path, config: GeneratorConfig = GeneratorConfig(), checkModelTypes: Boolean = false): CompiledCases =
    val sources = directory.resolve("sources")
    val classes = directory.resolve("classes")
    Files.createDirectories(sources)
    Files.createDirectories(classes)
    val options = NativeValues.Options(config.decimalType == DecimalType.Java, config.namespaceMappings,
      config.logicalTypes.collect { case (logical, LogicalTypeMode.Raw) => logical.avroName }.toSet)
    val files = mutable.LinkedHashMap.empty[String, String]
    cases.zipWithIndex.foreach { (c, index) =>
      CodeGenerator.generate(c.schema, config).foreach { source =>
        files.get(source.relativePath).foreach(previous => require(previous == source.content, s"Conflicting generated source: ${source.relativePath}"))
        files(source.relativePath) = source.content
      }
      val expressions = c.values.map(v => NativeValues.expression(c.schema, v, options))
      // Separate methods keep large campaigns below the JVM method-size limit.
      val valueMethods = expressions.zipWithIndex.map((expr, i) => s"  private def value$i: Any = $expr").mkString("\n")
      val checks = if checkModelTypes then typeChecks(c.schema, options) else ""
      // Default-package probes can reference models whose namespace was stripped.
      files(s"probes/Probe$index.scala") = s"""object Avro2sWirePropertyProbe$index:
  def codec: _root_.avro2s.wire.runtime.AvroCodec[Any] = ${NativeValues.namedType(c.schema, options)}.codec.asInstanceOf[_root_.avro2s.wire.runtime.AvroCodec[Any]]
  def stackSafeCodec: _root_.avro2s.wire.runtime.AvroCodec[Any] = ${NativeValues.namedType(c.schema, options)}.stackSafeCodec.asInstanceOf[_root_.avro2s.wire.runtime.AvroCodec[Any]]
$valueMethods
$checks
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
        val clazz = loader.loadClass(s"Avro2sWirePropertyProbe$index$$")
        val module = clazz.getField("MODULE$").get(null)
        val direct = clazz.getMethod("codec").invoke(module).asInstanceOf[AvroCodec[Any]]
        val stackSafe = clazz.getMethod("stackSafeCodec").invoke(module).asInstanceOf[AvroCodec[Any]]
        require(direct.execution == CodecExecution.Direct, "The implicit generated codec must remain direct")
        require(stackSafe.execution == CodecExecution.StackSafe, "The alternative codec must select stack-safe resolution")
        CompiledCase(direct, clazz.getMethod("values").invoke(module).asInstanceOf[Vector[Any]], Vector(stackSafe))
      }.toVector
      new CompiledCases(compiled, loader)
    catch
      case error: Throwable => loader.close(); throw error
