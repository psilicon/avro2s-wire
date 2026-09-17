package avrogen.resolution

import avrogen.runtime.*
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Compiles a writer/reader schema pair once and constructs reader models directly.
 * Retain and reuse this instance. Schema JSON is preserved exactly, including
 * defaults and aliases; parsing-canonical fingerprints are not resolution keys.
 * Reading does not create GenericRecords or re-encode a datum into another buffer.
 * The compiled plan is immutable after construction; each read owns its slots.
 * Reader unions prefer an exact type/name (including reader aliases) before
 * considering promotions, matching Java Avro 1.12.1's branch-selection policy.
 * This deliberately differs from a literal first-promotable-branch reading of
 * the specification and keeps metadata changes from changing Scala branch types.
 * Decode limits bound wire reads; reader-schema defaults are trusted configuration
 * and are not included in those wire-byte/item budgets.
 */
final class ResolvingReader[A](val writerSchemaJson: String, val readerCodec: AvroCodec[A]):
  private val plan: AvroInput => A =
    if writerSchemaJson == readerCodec.schemaJson then readerCodec.read
    else
      val writer = SchemaModel.parse(writerSchemaJson)
      val reader = SchemaModel.parse(readerCodec.schemaJson)
      val compiled = new ResolutionCompiler(reader, readerCodec).compile(writer, reader, "root")
      in => compiled.read(in).asInstanceOf[A]

  /** Uses the supplied input's validation and resource-limit policy. */
  def read(in: AvroInput): A = plan(in)

  /** Reads exactly one datum with the native input's bounds, UTF-8, and depth checks. */
  def decode(bytes: Array[Byte], limits: DecodeLimits = DecodeLimits.default): A =
    val in = new BinaryInput(bytes, limits)
    val result = read(in)
    in.requireEnd()
    result

object ResolvingReader:
  def apply[A](writerSchemaJson: String, readerCodec: AvroCodec[A]): ResolvingReader[A] =
    new ResolvingReader(writerSchemaJson, readerCodec)

private[resolution] trait ReadPlan:
  def read(in: AvroInput): Any

private[resolution] final class ResolutionCompiler(root: SchemaModel.Node, rootCodec: AvroCodec[?]):
  import SchemaModel.*

  private final class Deferred extends ReadPlan:
    var target: ReadPlan = null
    override def read(in: AvroInput): Any = target.read(in)

  private val pairs = mutable.HashMap.empty[(Node, Node), Deferred]
  private val skips = mutable.HashMap.empty[Node, Deferred]
  private val codecs = mutable.HashMap.empty[String, AvroCodec[?]]
  private val defaultStack = mutable.HashSet.empty[(Node, JsonNode)]

  private def action(f: AvroInput => Any): ReadPlan = new ReadPlan:
    override def read(in: AvroInput): Any = f(in)

  private def mismatch(writer: Node, reader: Node, path: String, detail: String = ""): ReadPlan =
    action(_ => throw SchemaResolutionException(
      s"$path: cannot resolve ${writer.label} to ${reader.label}${if detail.isEmpty then "" else s": $detail"}"
    ))

  private def malformed(message: String): Nothing = throw AvroDecodingException(message)
  private def invalid(message: String): Nothing = throw SchemaResolutionException(message)

  private def codec(schema: Node): AvroCodec[?] =
    codecs.getOrElseUpdate(schema.name,
      if schema eq root then rootCodec else rootCodec.namedCodec(schema.name))

  private def sameName(writer: Node, reader: Node): Boolean =
    writer.name == reader.name || reader.aliases(writer.name)

  private def decimalCompatible(writer: Node, reader: Node): Boolean =
    (writer.logical, reader.logical) match
      case (Some(a), Some(b)) if a.name == "decimal" && b.name == "decimal" =>
        a.precision == b.precision && a.scale == b.scale
      case _ => true

  private def matches(writer: Node, reader: Node): Boolean =
    if !decimalCompatible(writer, reader) then false
    else if writer.kind == reader.kind then
      writer.kind match
        case "record" | "enum" => sameName(writer, reader)
        case "fixed" => sameName(writer, reader) && writer.size == reader.size
        case _ => true
    else (writer.kind, reader.kind) match
      case ("int", "long" | "float" | "double") => true
      case ("long", "float" | "double") => true
      case ("float", "double") => true
      case ("string", "bytes") | ("bytes", "string") => true
      case _ => false

  def compile(writer: Node, reader: Node, path: String): ReadPlan =
    pairs.get((writer, reader)) match
      case Some(existing) => existing
      case None =>
        val deferred = new Deferred()
        pairs((writer, reader)) = deferred
        deferred.target = build(writer, reader, path)
        deferred

  private def build(writer: Node, reader: Node, path: String): ReadPlan =
    if writer.kind == "union" then
      val branches = writer.branches.zipWithIndex.map { (branch, index) =>
        compile(branch, reader, s"$path.writerUnion[$index]")
      }.toArray
      action { in =>
        val index = in.readIndex()
        if index < 0 || index >= branches.length then malformed(s"$path: invalid writer union index $index")
        branches(index).read(in)
      }
    else if reader.kind == "union" then
      // Preserve the exact writer branch where available. Java Avro uses this
      // policy rather than selecting an earlier numeric/string promotion.
      val exact = reader.branches.indexWhere(branch => writer.kind == branch.kind && matches(writer, branch))
      val index = if exact >= 0 then exact else reader.branches.indexWhere(matches(writer, _))
      if index < 0 then mismatch(writer, reader, path, "no matching reader union branch")
      else
        val branch = reader.branches(index)
        val inner = compile(writer, branch, s"$path.readerUnion[$index]")
        val wrap = unionWrapper(reader, branch)
        action(in => wrap(inner.read(in)))
    else if !matches(writer, reader) then mismatch(writer, reader, path)
    else reader.kind match
      case "record" => record(writer, reader, path)
      case "enum" =>
        val target = codec(reader)
        val fallback = reader.enumDefault.map(reader.symbols.indexOf).getOrElse(-1)
        val ordinals = writer.symbols.map { symbol =>
          val index = reader.symbols.indexOf(symbol)
          if index >= 0 then index else fallback
        }.toArray
        action { in =>
          val index = in.readEnum()
          if index < 0 || index >= ordinals.length then malformed(s"$path: invalid writer enum ordinal $index")
          val mapped = ordinals(index)
          if mapped < 0 then invalid(s"$path: reader enum ${reader.name} has no symbol '${writer.symbols(index)}' or default")
          target.construct(Array[Any](mapped))
        }
      case "fixed" =>
        val target = codec(reader)
        val convert = logicalConversion(reader)
        action(in => target.construct(Array[Any](convert(in.readFixed(writer.size)))))
      case "array" =>
        val element = compile(writer.element, reader.element, s"$path[]")
        action { in =>
          val result = Vector.newBuilder[Any]
          var count = in.readArrayStart()
          while count != 0 do
            if count < 0 then malformed(s"$path: negative array block count")
            var left = count
            while left > 0 do
              result += element.read(in)
              left -= 1
            count = in.arrayNext()
          result.result()
        }
      case "map" =>
        val element = compile(writer.element, reader.element, s"$path{}")
        action { in =>
          val result = Map.newBuilder[String, Any]
          var count = in.readMapStart()
          while count != 0 do
            if count < 0 then malformed(s"$path: negative map block count")
            var left = count
            while left > 0 do
              val key = in.readString()
              result += key -> element.read(in)
              left -= 1
            count = in.mapNext()
          result.result()
        }
      case _ =>
        val raw = primitive(writer.kind, reader.kind)
        val convert = logicalConversion(reader)
        action(in => convert(raw(in)))

  private def record(writer: Node, reader: Node, path: String): ReadPlan =
    val assigned = mutable.HashSet.empty[Int]
    val fields = writer.fields.map { written =>
      val candidates = reader.fields.zipWithIndex.filter { (expected, _) =>
        expected.name == written.name || expected.aliases(written.name)
      }
      if candidates.size > 1 then invalid(s"$path: ambiguous reader field aliases for '${written.name}'")
      candidates.headOption match
        case Some((expected, index)) =>
          if !assigned.add(index) then invalid(s"$path: multiple writer fields map to '${expected.name}'")
          index -> compile(written.schema, expected.schema, s"$path.${expected.name}")
        case None => -1 -> skip(written.schema, s"$path.${written.name}")
    }.toArray
    val missing = reader.fields.zipWithIndex.filterNot { (_, index) => assigned(index) }
    val required = missing.find(_._1.default.isEmpty)
    if required.nonEmpty then return mismatch(writer, reader, path, s"missing required reader field '${required.get._1.name}'")
    val defaults = missing.map { (field, index) =>
      index -> defaultValue(field.schema, field.default.get, s"$path.${field.name}.default")
    }.toArray
    val target = codec(reader)
    action { in =>
      in.enterRecord()
      try
        val values = new Array[Any](reader.fields.size)
        var index = 0
        while index < fields.length do
          val (slot, field) = fields(index)
          val value = field.read(in)
          if slot >= 0 then values(slot) = value
          index += 1
        index = 0
        while index < defaults.length do
          val (slot, value) = defaults(index)
          values(slot) = value()
          index += 1
        target.construct(values)
      finally in.leaveRecord()
    }

  private def primitive(writer: String, reader: String): AvroInput => Any =
    (writer, reader) match
      case ("null", "null") => in => { in.readNull(); null }
      case ("boolean", "boolean") => _.readBoolean()
      case ("int", "int") => _.readInt()
      case ("int", "long") => in => in.readInt().toLong
      case ("int", "float") => in => in.readInt().toFloat
      case ("int", "double") => in => in.readInt().toDouble
      case ("long", "long") => _.readLong()
      case ("long", "float") => in => in.readLong().toFloat
      case ("long", "double") => in => in.readLong().toDouble
      case ("float", "float") => _.readFloat()
      case ("float", "double") => in => in.readFloat().toDouble
      case ("double", "double") => _.readDouble()
      case ("string", "string") => _.readString()
      case ("bytes", "bytes") => _.readBytes()
      case ("string", "bytes") => _.readStringAsBytes()
      case ("bytes", "string") => _.readBytesAsString()
      case _ => invalid(s"Unsupported primitive promotion $writer -> $reader")

  private def logicalConversion(schema: Node): Any => Any = schema.logical match
    case None => identity
    case Some(logical) => logical.name match
      case "date" => value => LogicalValues.dateFromDays(value.asInstanceOf[Int])
      case "time-millis" => value => LogicalValues.timeFromMillis(value.asInstanceOf[Int])
      case "time-micros" => value => LogicalValues.timeFromMicros(value.asInstanceOf[Long])
      case "timestamp-millis" => value => LogicalValues.instantFromMillis(value.asInstanceOf[Long])
      case "timestamp-micros" => value => LogicalValues.instantFromMicros(value.asInstanceOf[Long])
      case "timestamp-nanos" => value => LogicalValues.instantFromNanos(value.asInstanceOf[Long])
      case "local-timestamp-millis" => value => LogicalValues.localDateTimeFromMillis(value.asInstanceOf[Long])
      case "local-timestamp-micros" => value => LogicalValues.localDateTimeFromMicros(value.asInstanceOf[Long])
      case "local-timestamp-nanos" => value => LogicalValues.localDateTimeFromNanos(value.asInstanceOf[Long])
      case "uuid" if schema.kind == "fixed" => value => LogicalValues.uuidFromFixed(value.asInstanceOf[Bytes])
      case "uuid" => value => LogicalValues.uuidFromString(value.asInstanceOf[String])
      case "decimal" => value => LogicalValues.decimalFromBytes(value.asInstanceOf[Bytes], logical.precision, logical.scale)
      case "duration" => value => LogicalValues.durationFromBytes(value.asInstanceOf[Bytes])
      case other => invalid(s"Unsupported logical type '$other'")

  private def unionWrapper(union: Node, branch: Node): Any => Any =
    val logicalNames = union.branches.flatMap(_.logical.map(_.name)).toSet
    val tagTime = logicalNames("time-millis") && logicalNames("time-micros")
    val wrapValue: Any => Any =
      if tagTime && branch.logical.exists(_.name == "time-millis") then
        value => TimeMillis(value.asInstanceOf[java.time.LocalTime])
      else if tagTime && branch.logical.exists(_.name == "time-micros") then
        value => TimeMicros(value.asInstanceOf[java.time.LocalTime])
      else identity
    if union.branches.size > 1 && union.branches.exists(_.kind == "null") then
      if branch.kind == "null" then _ => None else value => Some(wrapValue(value))
    else wrapValue

  private def skip(schema: Node, path: String): ReadPlan =
    skips.get(schema) match
      case Some(existing) => existing
      case None =>
        val deferred = new Deferred()
        skips(schema) = deferred
        deferred.target = schema.kind match
          case "record" =>
            val fields = schema.fields.map(field => skip(field.schema, s"$path.${field.name}")).toArray
            action { in =>
              in.enterRecord()
              try
                fields.foreach(_.read(in))
                ()
              finally in.leaveRecord()
            }
          case "union" =>
            val branches = schema.branches.map(skip(_, path)).toArray
            action { in =>
              val index = in.readIndex()
              if index < 0 || index >= branches.length then malformed(s"$path: invalid skipped union index $index")
              branches(index).read(in)
              ()
            }
          case "array" | "map" =>
            val element = skip(schema.element, path)
            val isMap = schema.kind == "map"
            action { in =>
              var count = if isMap then in.readMapStart() else in.readArrayStart()
              while count != 0 do
                if count < 0 then malformed(s"$path: negative skipped collection count")
                var left = count
                while left > 0 do
                  if isMap then in.skipString()
                  element.read(in)
                  left -= 1
                count = if isMap then in.mapNext() else in.arrayNext()
              ()
            }
          case "string" => action(in => in.skipString())
          case "bytes" => action(in => in.skipBytes())
          case "fixed" => action(in => in.skipFixed(schema.size))
          case "enum" => action { in =>
            val ordinal = in.readEnum()
            if ordinal < 0 || ordinal >= schema.symbols.size then malformed(s"$path: invalid skipped enum ordinal $ordinal")
            ()
          }
          case primitiveKind => action(primitive(primitiveKind, primitiveKind))
        deferred

  private def defaultValue(schema: Node, json: JsonNode, path: String): () => Any =
    val key = (schema, json)
    if defaultStack.size >= 256 then invalid(s"$path: default nesting exceeds 256 levels")
    if !defaultStack.add(key) then invalid(s"$path: recursive default does not terminate")
    try compileDefault(schema, json, path)
    finally defaultStack.remove(key)

  private def compileDefault(schema: Node, json: JsonNode, path: String): () => Any =
    def bad(): Nothing = invalid(s"$path: invalid default for ${schema.label}: $json")
    def constant(value: Any): () => Any = () => value
    def bytes(): Bytes =
      if !json.isTextual then bad()
      val value = json.textValue()
      if value.exists(_.toInt > 255) then bad()
      Bytes.fromArray(value.map(_.toByte).toArray)
    val raw: () => Any = schema.kind match
      case "null" => if json.isNull then constant(null) else bad()
      case "boolean" => if json.isBoolean then constant(json.booleanValue()) else bad()
      case "int" => if json.isIntegralNumber && json.canConvertToInt then constant(json.intValue()) else bad()
      case "long" => if json.isIntegralNumber && json.canConvertToLong then constant(json.longValue()) else bad()
      case "float" => if json.isNumber then constant(json.floatValue()) else bad()
      case "double" => if json.isNumber then constant(json.doubleValue()) else bad()
      case "string" => if json.isTextual then constant(json.textValue()) else bad()
      case "bytes" => constant(bytes())
      case "fixed" =>
        val value = bytes()
        if value.size != schema.size then bad()
        val underlying = logicalConversion(schema)(value)
        val target = codec(schema)
        return () => target.construct(Array[Any](underlying))
      case "enum" =>
        if !json.isTextual then bad()
        val ordinal = schema.symbols.indexOf(json.textValue())
        if ordinal < 0 then bad()
        val target = codec(schema)
        return () => target.construct(Array[Any](ordinal))
      case "record" =>
        if !json.isObject then bad()
        val fields = schema.fields.map { field =>
          val value = Option(json.get(field.name)).orElse(field.default)
            .getOrElse(invalid(s"$path: missing default record field '${field.name}'"))
          defaultValue(field.schema, value, s"$path.${field.name}")
        }.toArray
        val target = codec(schema)
        return () => target.construct(fields.map(_()))
      case "array" =>
        if !json.isArray then bad()
        val values = json.elements().asScala.map(defaultValue(schema.element, _, s"$path[]")).toVector
        return () => values.map(_())
      case "map" =>
        if !json.isObject then bad()
        val values = json.properties().iterator().asScala.map { entry =>
          entry.getKey -> defaultValue(schema.element, entry.getValue, s"$path.${entry.getKey}")
        }.toVector
        return () => values.iterator.map { (key, value) => key -> value() }.toMap
      case "union" =>
        val iterator = schema.branches.iterator
        while iterator.hasNext do
          val branch = iterator.next()
          val candidate = try Some(defaultValue(branch, json, path))
          catch case _: SchemaResolutionException => None
          candidate match
            case Some(value) =>
              val wrap = unionWrapper(schema, branch)
              return () => wrap(value())
            case None => ()
        bad()
      case _ => bad()
    val converted = logicalConversion(schema)(raw())
    constant(converted)
