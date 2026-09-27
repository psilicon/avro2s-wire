package avro2s.wire.benchmarks.suite

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import _root_.avro2s.wire.runtime.{AvroCodec, CodecExecution}
import org.apache.avro.{Conversions, Schema}
import org.apache.avro.data.TimeConversions
import org.apache.avro.generic.{GenericData, IndexedRecord}
import org.apache.avro.specific.{SpecificData, SpecificRecordBase}
import scala.jdk.CollectionConverters.*

/** Catalogue parameters describe inputs, independently of the implementation being measured. */
final case class SuiteCase(
    id: String,
    model: String,
    kind: String,
    node: JsonNode,
    avro2s: Boolean,
    operations: Vector[String]
):
  def int(name: String): Int =
    require(node.has(name), s"$id has no parameter $name")
    node.get(name).asInt()
  def int(name: String, default: Int): Int = if node.has(name) then int(name) else default
  def text(name: String): String =
    require(node.has(name), s"$id has no parameter $name")
    node.get(name).asText()
  def text(name: String, default: String): String = if node.has(name) then text(name) else default

object SuiteCatalog:
  private lazy val document = SuiteSupport.json("/suite/catalog.json")
  lazy val corpusSize: Int = document.get("corpusSize").asInt()
  lazy val seed: Long = document.get("seed").asLong()
  lazy val all: Vector[SuiteCase] =
    document.get("cases").elements().asScala.map { node =>
      SuiteCase(node.get("id").asText(), node.get("model").asText(), node.get("kind").asText(),
        node, node.path("avro2s").asBoolean(false),
        node.get("operations").elements().asScala.map(_.asText()).toVector)
    }.toVector

  def byId(id: String): SuiteCase =
    all.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"Unknown suite case $id"))

/** Setup and verification helpers; none of these traverse models in a timed benchmark. */
object SuiteSupport:
  private val mapper = new ObjectMapper()
  val namespace = "avro2s.wire.benchmarks.suite"
  val wireEngines = Vector("wire", "wire-stack-safe", "wire-java", "wire-java-stack-safe")
  val engineOrder = Vector("wire", "java-specific", "java-generic", "java-custom", "avro2s") ++ wireEngines.tail

  def isWire(engine: String): Boolean = wireEngines.contains(engine)

  /** Codec selection occurs outside measurement; stack-safe variants must never fall back to standard execution. */
  private[suite] def wireCodec(c: SuiteCase, engine: String): AvroCodec[Any] =
    require(isWire(engine), s"Not a Wire engine: $engine")
    val stackSafe = engine.endsWith("-stack-safe")
    val codec = if stackSafe then
      val companionClass = Class.forName(s"$namespace.wire.${c.model}$$")
      val companion = companionClass.getField("MODULE$").get(null)
      companionClass.getMethod("stackSafeCodec").invoke(companion).asInstanceOf[AvroCodec[Any]]
    else
      val codecClass = Class.forName(s"$namespace.wire.${c.model}$$codec$$")
      codecClass.getField("MODULE$").get(null).asInstanceOf[AvroCodec[Any]]
    val expected = if stackSafe then CodecExecution.StackSafe else CodecExecution.Standard
    require(codec.execution == expected, s"$engine selected ${codec.execution}, expected $expected")
    codec

  def resource(path: String): String =
    val stream = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw new IllegalArgumentException(s"Missing suite resource $path"))
    try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  def json(path: String): JsonNode = mapper.readTree(resource(path))
  def readerSchema(c: SuiteCase): Schema = new Schema.Parser().parse(resource(s"/suite/schemas/${c.model}.avsc"))
  def writerSchema(c: SuiteCase): Schema =
    if c.kind == "evolution" then new Schema.Parser().parse(resource(s"/suite/writers/${c.model}.avsc"))
    else readerSchema(c)

  private lazy val capabilities: Map[String, JsonNode] =
    json("/suite/capabilities.json").get("cases").elements().asScala
      .map(n => n.get("id").asText() -> n.get("engines")).toMap

  def operationsFor(c: SuiteCase, engine: String): Vector[String] =
    val engines = capabilities.getOrElse(c.id,
      throw new IllegalArgumentException(s"No generated capability declaration for ${c.id}"))
    // Additional Wire execution/backends use the same generated models and operations.
    // Keep the pinned external-baseline manifest unchanged.
    val capabilityEngine = if isWire(engine) then "wire" else engine
    val capability = Option(engines.get(capabilityEngine)).getOrElse(
      throw new IllegalArgumentException(s"No generated capability declaration for ${c.id}/$engine"))
    if capability.path("supported").asBoolean(false) then
      capability.get("operations").elements().asScala.map(_.asText()).toVector
    else Vector.empty

  def enginesFor(c: SuiteCase, operation: String = "decode"): Vector[String] =
    engineOrder.filter(engine => operationsFor(c, engine).contains(operation))

  def unsupportedReason(c: SuiteCase, engine: String): Option[String] =
    val capabilityEngine = if isWire(engine) then "wire" else engine
    Option(capabilities(c.id).path(capabilityEngine).get("reason")).map(_.asText())

  /** All logical cases use their domain values in Java as well as Wire. */
  def conversions[A <: GenericData](data: A): A =
    data.addLogicalTypeConversion(new Conversions.UUIDConversion())
    data.addLogicalTypeConversion(new Conversions.DecimalConversion())
    data.addLogicalTypeConversion(new Conversions.BigDecimalConversion())
    data.addLogicalTypeConversion(new Conversions.DurationConversion())
    data.addLogicalTypeConversion(new TimeConversions.DateConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimeMillisConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimeMicrosConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimestampMillisConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimestampNanosConversion())
    data.addLogicalTypeConversion(new TimeConversions.LocalTimestampMillisConversion())
    data.addLogicalTypeConversion(new TimeConversions.LocalTimestampMicrosConversion())
    data.addLogicalTypeConversion(new TimeConversions.LocalTimestampNanosConversion())
    data

  def genericData(): GenericData = conversions(new GenericData()).setFastReaderEnabled(true)

  def specificData(custom: Boolean): SpecificData =
    val data = conversions(new SpecificData(getClass.getClassLoader))
    data.setCustomCoders(custom)
    // Avro's fast reader bypasses customDecode; ordinary readers keep their default fast path.
    data.setFastReaderEnabled(!custom)
    data

  def baselineSchema(engine: String, model: String): Schema =
    val family = if engine == "avro2s" then "avro2s" else "javaavro"
    Class.forName(s"$namespace.$family.$model").getDeclaredConstructor().newInstance()
      .asInstanceOf[SpecificRecordBase].getSchema

  def relocated(schema: Schema, family: String): Schema =
    new Schema.Parser().parse(schema.toString.replace(s"$namespace.wire", s"$namespace.$family"))

  def stringSchema(original: Schema): Schema =
    val result = new Schema.Parser().parse(original.toString)
    val seen = new java.util.IdentityHashMap[Schema, java.lang.Boolean]()
    def visit(schema: Schema): Unit =
      if seen.put(schema, java.lang.Boolean.TRUE) == null then schema.getType match
        case Schema.Type.STRING => GenericData.setStringType(schema, GenericData.StringType.String)
        case Schema.Type.MAP =>
          GenericData.setStringType(schema, GenericData.StringType.String)
          visit(schema.getValueType)
        case Schema.Type.ARRAY => visit(schema.getElementType)
        case Schema.Type.RECORD => schema.getFields.asScala.foreach(f => visit(f.schema()))
        case Schema.Type.UNION => schema.getTypes.asScala.foreach(visit)
        case _ => ()
    visit(result)
    result

  def requireStrings(value: Any, schema: Schema, data: GenericData): Unit =
    if value != null && schema.getLogicalType == null then schema.getType match
      case Schema.Type.STRING =>
        require(value.isInstanceOf[String], s"Expected String, got ${value.getClass.getName}")
      case Schema.Type.RECORD =>
        val record = value.asInstanceOf[IndexedRecord]
        schema.getFields.asScala.foreach(f => requireStrings(record.get(f.pos()), f.schema(), data))
      case Schema.Type.ARRAY =>
        value.asInstanceOf[java.util.Collection[?]].asScala.foreach(v => requireStrings(v, schema.getElementType, data))
      case Schema.Type.MAP =>
        value.asInstanceOf[java.util.Map[?, ?]].asScala.foreach { (key, v) =>
          require(key.isInstanceOf[String], s"Expected String map key, got ${key.getClass.getName}")
          requireStrings(v, schema.getValueType, data)
        }
      case Schema.Type.UNION => requireStrings(value, schema.getTypes.get(data.resolveUnion(schema, value)), data)
      case _ => ()
