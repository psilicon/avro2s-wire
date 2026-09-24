package avro2s.wire.resolution

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.{DeserializationFeature, JsonNode, ObjectMapper}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** An invalid schema or unsupported resolution request. */
final class SchemaResolutionException(message: String) extends IllegalArgumentException(message)

private[resolution] object SchemaModel:
  final case class Logical(name: String, precision: Int = 0, scale: Int = 0)
  final case class Field(name: String, schema: Node, aliases: Set[String], default: Option[JsonNode])

  // Identity equality is intentional: graphs can contain recursive records.
  final class Node(val kind: String, val name: String = "", val aliases: Set[String] = Set.empty):
    var fields: Vector[Field] = Vector.empty
    var branches: Vector[Node] = Vector.empty
    var element: Node = null
    var symbols: Vector[String] = Vector.empty
    var enumDefault: Option[String] = None
    var size: Int = 0
    var logical: Option[Logical] = None
    def label: String = if name.isEmpty then kind else name

  private val primitives = Set("null", "boolean", "int", "long", "float", "double", "string", "bytes")
  private val mapper = new ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

  def parse(json: String): Node =
    val tree = try mapper.readTree(json)
    catch case error: Exception => throw SchemaResolutionException(s"Invalid schema JSON: ${error.getMessage}")
    if tree == null then fail("Empty schema JSON")
    new Parser().parse(tree, "", 0)

  private def fail(message: String): Nothing = throw SchemaResolutionException(message)

  private final class Parser:
    private val names = mutable.HashMap.empty[String, Node]

    private def text(node: JsonNode, field: String): String =
      val value = node.get(field)
      if value == null || !value.isTextual then fail(s"Schema property '$field' must be a string")
      value.textValue()

    private def strings(node: JsonNode, field: String): Vector[String] =
      val value = node.get(field)
      if value == null then Vector.empty
      else
        if !value.isArray then fail(s"Schema property '$field' must be an array")
        value.elements().asScala.map { entry =>
          if !entry.isTextual then fail(s"Schema property '$field' must contain strings")
          entry.textValue()
        }.toVector

    private def identifier(value: String): Unit =
      if !value.matches("[A-Za-z_][A-Za-z0-9_]*") then fail(s"Invalid Avro identifier '$value'")

    private def fullName(raw: String, namespace: String): String =
      if raw.contains('.') || namespace.isEmpty then raw else s"$namespace.$raw"

    private def namespace(name: String): String =
      val dot = name.lastIndexOf('.')
      if dot < 0 then "" else name.substring(0, dot)

    private def integer(node: JsonNode, field: String, fallback: Option[Int] = None): Int =
      val value = node.get(field)
      if value == null then fallback.getOrElse(fail(s"Missing integer schema property '$field'"))
      else if value.isIntegralNumber && value.canConvertToInt then value.intValue()
      else fail(s"Schema property '$field' must be a 32-bit integer")

    def parse(json: JsonNode, enclosingNamespace: String, depth: Int): Node =
      if depth > 256 then fail("Schema nesting exceeds 256 levels")
      if json.isTextual then
        val kind = json.textValue()
        if primitives(kind) then new Node(kind)
        else names.getOrElse(fullName(kind, enclosingNamespace), fail(s"Unknown schema reference '$kind'"))
      else if json.isArray then
        val result = new Node("union")
        result.branches = json.elements().asScala.map(parse(_, enclosingNamespace, depth + 1)).toVector
        if result.branches.isEmpty then fail("A union must have at least one branch")
        if result.branches.exists(_.kind == "union") then fail("Unions cannot directly contain unions")
        val keys = result.branches.map(branch => if branch.name.nonEmpty then branch.name else branch.kind)
        if keys.distinct.size != keys.size then fail("Union contains duplicate branch types")
        result
      else if json.isObject then
        val typeNode = json.get("type")
        if typeNode == null then fail("Schema object is missing 'type'")
        if !typeNode.isTextual then
          if json.has("logicalType") then fail("A logical type requires a primitive or fixed schema")
          parse(typeNode, enclosingNamespace, depth + 1)
        else
          val kind = typeNode.textValue()
          val result = kind match
            case "record" | "enum" | "fixed" =>
              val rawName = text(json, "name")
              val explicitNamespace = Option(json.get("namespace")).map { value =>
                if !value.isTextual then fail("Schema namespace must be a string")
                value.textValue()
              }.getOrElse(enclosingNamespace)
              val name = fullName(rawName, explicitNamespace)
              name.split("\\.", -1).foreach(identifier)
              if names.contains(name) then fail(s"Duplicate named schema '$name'")
              val ns = namespace(name)
              val aliases = strings(json, "aliases").map(fullName(_, ns)).toSet
              val named = new Node(kind, name, aliases)
              names(name) = named // Register before fields so self references terminate.
              kind match
                case "record" =>
                  val fields = json.get("fields")
                  if fields == null || !fields.isArray then fail(s"Record '$name' requires a fields array")
                  named.fields = fields.elements().asScala.map { field =>
                    if !field.isObject then fail(s"Invalid field in '$name'")
                    val fieldName = text(field, "name")
                    identifier(fieldName)
                    val fieldSchema = field.get("type")
                    if fieldSchema == null then fail(s"Field '$name.$fieldName' requires a type")
                    Field(fieldName, parse(fieldSchema, ns, depth + 1), strings(field, "aliases").toSet,
                      Option(field.get("default")))
                  }.toVector
                  if named.fields.map(_.name).distinct.size != named.fields.size then
                    fail(s"Duplicate field in record '$name'")
                case "enum" =>
                  if !json.has("symbols") then fail(s"Enum '$name' requires symbols")
                  named.symbols = strings(json, "symbols")
                  named.symbols.foreach(identifier)
                  if named.symbols.distinct.size != named.symbols.size then fail(s"Duplicate enum symbol in '$name'")
                  named.enumDefault = Option(json.get("default")).map { value =>
                    if !value.isTextual || !named.symbols.contains(value.textValue()) then
                      fail(s"Invalid enum default in '$name'")
                    value.textValue()
                  }
                case "fixed" =>
                  named.size = integer(json, "size")
                  if named.size < 0 then fail(s"Negative fixed size in '$name'")
                case _ => ()
              named
            case "array" | "map" =>
              val key = if kind == "array" then "items" else "values"
              val element = json.get(key)
              if element == null then fail(s"$kind schema requires '$key'")
              val container = new Node(kind)
              container.element = parse(element, enclosingNamespace, depth + 1)
              container
            case primitive if primitives(primitive) => new Node(primitive)
            case reference =>
              if json.has("logicalType") then fail("Logical annotations on named references are unsupported")
              names.getOrElse(fullName(reference, enclosingNamespace), fail(s"Unknown schema reference '$reference'"))
          if json.has("logicalType") then result.logical = Some(logical(json, result))
          result
      else fail("A schema must be a JSON string, object, or union array")

    private def logical(json: JsonNode, schema: Node): Logical =
      val name = text(json, "logicalType")
      def requireKind(kinds: Set[String]): Unit =
        if !kinds(schema.kind) then fail(s"Logical type '$name' is not valid on ${schema.kind}")
      name match
        case "date" | "time-millis" => requireKind(Set("int"))
        case "time-micros" | "timestamp-millis" | "timestamp-micros" | "timestamp-nanos" |
            "local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos" =>
          requireKind(Set("long"))
        case "uuid" =>
          requireKind(Set("string", "fixed"))
          if schema.kind == "fixed" && schema.size != 16 then fail("Fixed UUID must have size 16")
        case "duration" =>
          requireKind(Set("fixed"))
          if schema.size != 12 then fail("Duration must have fixed size 12")
        case "big-decimal" => requireKind(Set("bytes"))
        case "decimal" =>
          requireKind(Set("bytes", "fixed"))
          val precision = integer(json, "precision")
          val scale = integer(json, "scale", Some(0))
          if precision <= 0 || scale < 0 || scale > precision then fail("Invalid decimal precision or scale")
          if schema.kind == "fixed" then
            // floor(log10(2^(8*n-1)-1)); computing the bound avoids huge BigInts.
            val maximum = math.floor((8.0 * schema.size - 1.0) * math.log10(2.0)).toLong
            if schema.size == 0 || precision.toLong > maximum then fail("Decimal precision exceeds fixed capacity")
          return Logical(name, precision, scale)
        case other => fail(s"Unsupported logical type '$other'")
      Logical(name)
