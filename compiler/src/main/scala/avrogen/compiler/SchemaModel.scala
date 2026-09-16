package avrogen.compiler

import org.apache.avro.Schema
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A generation error identifies a schema feature that cannot yet be represented safely. */
final class GenerationException(message: String) extends IllegalArgumentException(message)

private[compiler] object SchemaModel:
  enum Value:
    case Primitive(kind: Schema.Type)
    case Named(fullName: String)
    case ArrayOf(element: Value)
    case MapOf(element: Value)
    case Optional(element: Value, nullIndex: Int, valueIndex: Int)

  final case class Field(avroName: String, scalaName: String, value: Value)

  enum Definition:
    case Record(fullName: String, fields: Vector[Field], json: String)
    case Enumeration(fullName: String, symbols: Vector[String], json: String)
    case Fixed(fullName: String, size: Int, json: String)

    def name: String = this match
      case Record(name, _, _)      => name
      case Enumeration(name, _, _) => name
      case Fixed(name, _, _)       => name

  /** Named references terminate traversal, including mutually recursive records. */
  def definitions(schema: Schema): Vector[Definition] =
    if !Set(Schema.Type.RECORD, Schema.Type.ENUM, Schema.Type.FIXED)(schema.getType) then
      throw GenerationException(s"The root schema must be a named record, enum, or fixed type; found ${schema.getType}")
    val seen = mutable.Set.empty[String]
    val result = mutable.ArrayBuffer.empty[Definition]

    def visit(schema: Schema, location: String): Value =
      if schema.getObjectProp("logicalType") != null then
        throw GenerationException(s"$location: logical type '${schema.getObjectProp("logicalType")}' is not supported yet")
      schema.getType match
        case Schema.Type.RECORD | Schema.Type.ENUM | Schema.Type.FIXED =>
          val name = schema.getFullName
          ScalaNames.validateFullName(name)
          if seen.add(name) then
            val definition = schema.getType match
              case Schema.Type.RECORD =>
                val sourceFields = schema.getFields.asScala.toVector
                val fieldNames = ScalaNames.memberNames(sourceFields.map(_.name), ScalaNames.recordMembers, "field")
                val fields = sourceFields.zip(fieldNames).map { (field, scalaName) =>
                  Field(field.name, scalaName, visit(field.schema, s"$name.${field.name}"))
                }
                Definition.Record(name, fields, schema.toString)
              case Schema.Type.ENUM =>
                val symbols = schema.getEnumSymbols.asScala.toVector
                if symbols.isEmpty then throw GenerationException(s"$name: empty enums cannot be represented as a Scala enum")
                symbols.foreach(ScalaNames.validateIdentifier)
                Definition.Enumeration(name, symbols, schema.toString)
              case Schema.Type.FIXED => Definition.Fixed(name, schema.getFixedSize, schema.toString)
              case other => throw GenerationException(s"Unexpected named schema: $other")
            result += definition
          Value.Named(name)
        case Schema.Type.ARRAY => Value.ArrayOf(visit(schema.getElementType, s"$location[]"))
        case Schema.Type.MAP   => Value.MapOf(visit(schema.getValueType, s"$location{}"))
        case Schema.Type.UNION =>
          val branches = schema.getTypes.asScala.toVector
          val nullIndex = branches.indexWhere(_.getType == Schema.Type.NULL)
          if branches.size != 2 || nullIndex < 0 then
            throw GenerationException(s"$location: only two-branch nullable unions are supported; found ${schema.toString}")
          // Validate null metadata too: unknown logical types must not silently disappear.
          visit(branches(nullIndex), location)
          val valueIndex = 1 - nullIndex
          Value.Optional(visit(branches(valueIndex), location), nullIndex, valueIndex)
        case primitive => Value.Primitive(primitive)

    visit(schema, "root")
    val definitions = result.toVector.sortBy(_.name)
    if definitions.isEmpty then throw GenerationException("The schema must contain at least one named record, enum, or fixed type")
    def validateReference(value: Value, owner: String, location: String): Unit = value match
      case Value.Named(name) if owner.contains('.') && !name.contains('.') =>
        throw GenerationException(s"$location: Scala cannot reference default-package type '$name' from named package '${owner.substring(0, owner.lastIndexOf('.'))}'")
      case Value.ArrayOf(element) => validateReference(element, owner, location)
      case Value.MapOf(element) => validateReference(element, owner, location)
      case Value.Optional(element, _, _) => validateReference(element, owner, location)
      case _ => ()
    definitions.foreach {
      case Definition.Record(name, fields, _) =>
        fields.foreach(field => validateReference(field.value, name, s"$name.${field.avroName}"))
      case _ => ()
    }
    definitions

private[compiler] object ScalaNames:
  private val keywords = Set(
    "abstract", "as", "case", "catch", "class", "def", "derives", "do", "else", "end", "enum",
    "export", "extends", "false", "final", "finally", "for", "forSome", "given", "if", "implicit",
    "import", "infix", "inline", "lazy", "match", "new", "null", "object", "opaque", "open",
    "override", "package", "private", "protected", "return", "sealed", "super", "then", "this",
    "throw", "trait", "transparent", "true", "try", "type", "using", "val", "var", "while", "with", "yield"
  )
  val recordMembers: Set[String] = Set(
    "copy", "canEqual", "equals", "hashCode", "toString", "productArity", "productElement",
    "productElementName", "productElementNames", "productIterator", "productPrefix", "getClass",
    "notify", "notifyAll", "wait", "clone", "finalize", "synchronized", "asInstanceOf", "isInstanceOf", "eq", "ne"
  )
  val enumMembers: Set[String] = recordMembers ++ Set(
    "values", "valueOf", "fromOrdinal", "ordinal", "codec", "schemaJson", "readResolve"
  )

  def validateIdentifier(name: String): Unit =
    if !name.matches("[A-Za-z_][A-Za-z0-9_]*") || name == "_" || name == "_root_" then
      throw GenerationException(s"Avro identifier '$name' cannot safely be represented as a Scala identifier")

  private val defaultPackageMembers = enumMembers ++ Set("in", "out", "value", "index", "read", "write", "apply", "unapply")

  def validateFullName(name: String): Unit =
    name.split("\\.", -1).foreach(validateIdentifier)
    if !name.contains('.') && (defaultPackageMembers(name) || name.matches("(?:field|builder|remaining|key|item|iterator|entry)[0-9]+")) then
      throw GenerationException(s"Default-package type '$name' conflicts with a generated Scala member; give this type an Avro namespace")

  def escaped(name: String): String =
    validateIdentifier(name)
    if keywords(name) then s"`$name`" else name

  def qualified(name: String): String =
    val scalaName = name.split("\\.").map(escaped).mkString(".")
    if name.contains('.') then "_root_." + scalaName else scalaName

  /** Reserve original spellings before renaming so adding a collision is deterministic. */
  def memberNames(names: Vector[String], reserved: Set[String], kind: String): Vector[String] =
    names.foreach(validateIdentifier)
    val occupied = mutable.Set.from(names ++ reserved)
    names.map { name =>
      if reserved(name) then
        var candidate = s"avro_${kind}_$name"
        while occupied(candidate) do candidate += "_"
        occupied += candidate
        candidate
      else name
    }

  def literal(value: String): String =
    val out = new StringBuilder("\"")
    value.foreach {
      case '"'  => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case '\b' => out.append("\\b")
      case '\f' => out.append("\\f")
      case c if c < ' ' || c == '\u2028' || c == '\u2029' || Character.isSurrogate(c) => out.append(f"\\u${c.toInt}%04x")
      case c => out.append(c)
    }
    out.append('"').toString

  /** Keep individual JVM constants well below the modified UTF-8 65535-byte limit. */
  def stringExpression(value: String): String =
    if value.length <= 8000 then literal(value)
    else value.grouped(8000).map(part => s".append(${literal(part)})")
      .mkString(s"new _root_.java.lang.StringBuilder(${value.length})", "", ".toString")
