package avrogen.properties

import java.math.BigInteger
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Coverage of values actually traversed, independently of schema metadata labels. */
object Coverage:
  private val primitiveAndNamedTypes = Set("null", "boolean", "int", "long", "float", "double", "bytes", "string", "enum", "fixed", "record", "array", "map")
  private val logicalStorage = Set(
    "date:int", "time-millis:int", "time-micros:long", "timestamp-millis:long", "timestamp-micros:long",
    "timestamp-nanos:long", "local-timestamp-millis:long", "local-timestamp-micros:long", "local-timestamp-nanos:long",
    "uuid:string", "uuid:fixed", "decimal:bytes", "decimal:fixed", "duration:fixed"
  )
  private val actualMatrix = (for
    atom <- SchemaCases.atoms
    context <- SchemaCases.contexts
    if atom != "null" || !context.startsWith("null-")
  yield s"observed:$atom:$context").toSet

  /** Required labels are reached by the deterministic mandatory corpus, without random luck. */
  val required: Set[String] = actualMatrix ++
    primitiveAndNamedTypes.map(t => s"value:type:$t") ++
    primitiveAndNamedTypes.map(t => s"value:union:general:$t") ++
    logicalStorage.map(t => s"value:logical:$t") ++
    (for kind <- Set("int", "long"); edge <- Set("zero", "negative", "positive", "minimum", "maximum", "one-byte-negative-edge", "one-byte-positive-edge", "two-byte-positive-start", "two-byte-negative-start") yield s"value:$kind:$edge") ++
    (for kind <- Set("float", "double"); family <- Set("positive-zero", "negative-zero", "finite", "subnormal", "positive-infinity", "negative-infinity", "nan", "noncanonical-nan") yield s"value:$kind:$family") ++
    (for kind <- Set("array", "map", "bytes"); state <- Set("empty", "nonempty") yield s"value:$kind:$state") ++
    (for order <- Set("null-first", "null-last"); branch <- Set("null", "value") yield s"value:union:$order:$branch") ++
    (for storage <- Set("bytes", "fixed"); sign <- Set("zero", "positive", "negative") yield s"value:decimal:$storage:$sign") ++
    (for logical <- Set("date", "timestamp-millis", "timestamp-micros", "timestamp-nanos", "local-timestamp-millis", "local-timestamp-micros", "local-timestamp-nanos"); sign <- Set("zero", "positive", "negative") yield s"value:$logical:$sign") ++
    (for logical <- Set("time-millis", "time-micros"); edge <- Set("midnight", "last-unit") yield s"value:$logical:$edge") ++
    (for storage <- Set("string", "fixed"); state <- Set("zero", "nonzero") yield s"value:uuid:$storage:$state") ++
    Set(
      "value:boolean:false", "value:boolean:true", "value:string:empty", "value:string:ascii", "value:string:ascii-control",
      "value:string:ascii-long", "value:string:two-byte", "value:string:three-byte", "value:string:four-byte", "value:string:combining-mark",
      "value:decimal:more-than-34-digits", "value:decimal:nonzero-scale", "value:duration:zero", "value:duration:nonzero",
      "value:enum:first-symbol", "value:enum:last-symbol", "value:fixed:zero-bytes", "value:fixed:nonzero-bytes",
      "value:record:empty", "value:record:nonempty", "value:recursive-record", "value:collection:nested"
    )

  private def rawBytes(value: AnyRef): Array[Byte] = value match
    case fixed: GenericData.Fixed => fixed.bytes()
    case buffer: ByteBuffer =>
      val copy = buffer.duplicate()
      val result = new Array[Byte](copy.remaining())
      copy.get(result)
      result

  private def atomName(schema: Schema): Option[String] =
    Option(schema.getProp("logicalType")) match
      case Some("uuid") => Some(s"uuid-${schema.getType.getName}")
      case Some("decimal") => Some(s"decimal-${schema.getType.getName}")
      case Some(other) => Some(other)
      case None => schema.getType match
        case Schema.Type.RECORD | Schema.Type.ARRAY | Schema.Type.MAP | Schema.Type.UNION => None
        case other => Some(other.getName)

  def labels(c: SchemaCase): Set[String] =
    val found = mutable.Set.empty[String]
    def mark(label: String): Unit = { found += s"value:$label"; () }
    def sign(number: Long): String = if number == 0 then "zero" else if number < 0 then "negative" else "positive"
    def integer(kind: String, value: Long, minimum: Long, maximum: Long): Unit =
      mark(s"$kind:${sign(value)}")
      if value == minimum then mark(s"$kind:minimum")
      if value == maximum then mark(s"$kind:maximum")
      if value == -64 then mark(s"$kind:one-byte-negative-edge")
      if value == 63 then mark(s"$kind:one-byte-positive-edge")
      if value == 64 then mark(s"$kind:two-byte-positive-start")
      if value == -65 then mark(s"$kind:two-byte-negative-start")
    def text(value: String): Unit =
      if value.isEmpty then mark("string:empty")
      if value.forall(_ < 128) then
        mark("string:ascii")
        if value.length >= 128 then mark("string:ascii-long")
        if value.exists(c => c < 32 || c == 127) then mark("string:ascii-control")
      value.codePoints().iterator().asScala.foreach { point =>
        if point >= 0x80 && point < 0x800 then mark("string:two-byte")
        else if point >= 0x800 && point <= 0xffff then mark("string:three-byte")
        else if point > 0xffff then mark("string:four-byte")
        if Set(Character.NON_SPACING_MARK.toInt, Character.COMBINING_SPACING_MARK.toInt, Character.ENCLOSING_MARK.toInt)(Character.getType(point)) then
          mark("string:combining-mark")
      }
    def visit(schema: Schema, value: AnyRef, context: Vector[String], recordStack: Set[String]): Unit =
      val kind = schema.getType.getName
      mark(s"type:$kind")
      val contextName = context match
        case Vector() => Some("direct")
        case Vector("array", "map") => Some("array-map")
        case Vector(single) if SchemaCases.contexts.contains(single) => Some(single)
        case _ => None
      for atom <- atomName(schema); path <- contextName do found += s"observed:$atom:$path"

      Option(schema.getProp("logicalType")).foreach { logical =>
        mark(s"logical:$logical:$kind")
        logical match
          case "decimal" =>
            val integer = new BigInteger(rawBytes(value))
            mark(s"decimal:$kind:${if integer.signum() == 0 then "zero" else if integer.signum() < 0 then "negative" else "positive"}")
            if integer.abs().toString.length > 34 then mark("decimal:more-than-34-digits")
            if schema.getObjectProp("scale").asInstanceOf[Number].intValue() > 0 then mark("decimal:nonzero-scale")
          case "uuid" =>
            val zero = if schema.getType == Schema.Type.STRING then value.toString == "00000000-0000-0000-0000-000000000000" else rawBytes(value).forall(_ == 0)
            mark(s"uuid:$kind:${if zero then "zero" else "nonzero"}")
          case "duration" => mark(s"duration:${if rawBytes(value).forall(_ == 0) then "zero" else "nonzero"}")
          case "time-millis" | "time-micros" =>
            val number = value.asInstanceOf[Number].longValue()
            if number == 0 then mark(s"$logical:midnight")
            if number == (if logical == "time-millis" then 86399999L else 86399999999L) then mark(s"$logical:last-unit")
          case other => mark(s"$other:${sign(value.asInstanceOf[Number].longValue())}")
      }

      schema.getType match
        case Schema.Type.NULL => ()
        case Schema.Type.BOOLEAN => mark(s"boolean:${value.toString}")
        case Schema.Type.INT => integer("int", value.asInstanceOf[Number].longValue(), Int.MinValue.toLong, Int.MaxValue.toLong)
        case Schema.Type.LONG => integer("long", value.asInstanceOf[Number].longValue(), Long.MinValue, Long.MaxValue)
        case Schema.Type.FLOAT | Schema.Type.DOUBLE =>
          val number = value.asInstanceOf[Number].doubleValue()
          val negativeZero = if schema.getType == Schema.Type.FLOAT then java.lang.Float.floatToRawIntBits(value.asInstanceOf[Number].floatValue()) == Int.MinValue else java.lang.Double.doubleToRawLongBits(number) == Long.MinValue
          if number.isNaN then
            mark(s"$kind:nan")
            val noncanonical = if schema.getType == Schema.Type.FLOAT then
              java.lang.Float.floatToRawIntBits(value.asInstanceOf[Number].floatValue()) != java.lang.Float.floatToIntBits(value.asInstanceOf[Number].floatValue())
            else java.lang.Double.doubleToRawLongBits(number) != java.lang.Double.doubleToLongBits(number)
            if noncanonical then mark(s"$kind:noncanonical-nan")
          else if number == Double.PositiveInfinity then mark(s"$kind:positive-infinity")
          else if number == Double.NegativeInfinity then mark(s"$kind:negative-infinity")
          else if number == 0 then mark(s"$kind:${if negativeZero then "negative-zero" else "positive-zero"}")
          else
            mark(s"$kind:finite")
            val smallestNormal = if schema.getType == Schema.Type.FLOAT then java.lang.Float.MIN_NORMAL.toDouble else java.lang.Double.MIN_NORMAL
            if math.abs(number) < smallestNormal then mark(s"$kind:subnormal")
        case Schema.Type.STRING => text(value.toString)
        case Schema.Type.BYTES => mark(s"bytes:${if rawBytes(value).isEmpty then "empty" else "nonempty"}")
        case Schema.Type.FIXED => mark(s"fixed:${if rawBytes(value).forall(_ == 0) then "zero-bytes" else "nonzero-bytes"}")
        case Schema.Type.ENUM =>
          if value.toString == schema.getEnumSymbols.get(0) then mark("enum:first-symbol")
          if value.toString == schema.getEnumSymbols.get(schema.getEnumSymbols.size() - 1) then mark("enum:last-symbol")
        case Schema.Type.RECORD =>
          mark(s"record:${if schema.getFields.isEmpty then "empty" else "nonempty"}")
          if recordStack(schema.getFullName) then mark("recursive-record")
          val nextContext = if recordStack.isEmpty then context else context :+ "record"
          schema.getFields.asScala.foreach { field =>
            visit(field.schema(), value.asInstanceOf[GenericData.Record].get(field.pos()), nextContext, recordStack + schema.getFullName)
          }
        case Schema.Type.ARRAY =>
          val items = value.asInstanceOf[java.util.Collection[AnyRef]]
          mark(s"array:${if items.isEmpty then "empty" else "nonempty"}")
          if context.exists(c => c == "array" || c == "map") then mark("collection:nested")
          items.asScala.foreach(v => visit(schema.getElementType, v, context :+ "array", recordStack))
        case Schema.Type.MAP =>
          val entries = value.asInstanceOf[java.util.Map[String, AnyRef]]
          mark(s"map:${if entries.isEmpty then "empty" else "nonempty"}")
          if context.exists(c => c == "array" || c == "map") then mark("collection:nested")
          entries.asScala.foreach { (key, v) =>
            text(key)
            visit(schema.getValueType, v, context :+ "map", recordStack)
          }
        case Schema.Type.UNION =>
          val branches = schema.getTypes.asScala.toVector
          val branch = branches(GenericData.get().resolveUnion(schema, value))
          val nullable = branches.size == 2 && branches.exists(_.getType == Schema.Type.NULL)
          val unionKind = if !nullable then "general" else if branches.head.getType == Schema.Type.NULL then "null-first" else "null-last"
          mark(s"union:$unionKind:${if nullable then (if value == null then "null" else "value") else branch.getType.getName}")
          if Set(Schema.Type.RECORD, Schema.Type.ENUM, Schema.Type.FIXED)(branch.getType) then
            mark(s"union:$unionKind:${branch.getType.getName}:${branch.getFullName}")
          mark(s"union-context:${if context.isEmpty then "direct" else context.mkString("/")}:$unionKind:${branch.getType.getName}")
          visit(branch, value, context :+ unionKind, recordStack)
    c.values.foreach(visit(c.schema, _, Vector.empty, Set.empty))
    found.toSet
