package avrogen.properties

import java.math.BigInteger
import java.nio.{ByteBuffer, ByteOrder}
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericFixed, IndexedRecord}
import scala.jdk.CollectionConverters.*

/**
 * Independent source-expression oracle for Java Avro's physical datum model.
 *
 * This deliberately does not call the generator's schema model, naming helpers,
 * or the native runtime's logical conversions. Generated expressions use only
 * public value constructors, the Scala collections API, and JDK conversions.
 */
object NativeValues:
  def expression(schema: Schema, value: AnyRef): String =
    schema.getType match
      case Schema.Type.UNION => unionExpression(schema, value)
      case Schema.Type.RECORD =>
        val record = value.asInstanceOf[IndexedRecord]
        val fields = schema.getFields.asScala.map { field =>
          expression(field.schema, record.get(field.pos))
        }
        s"new ${namedType(schema)}(${fields.mkString(", ")})"
      case Schema.Type.ENUM =>
        val ordinal = schema.getEnumOrdinal(value.toString)
        require(ordinal >= 0, s"Unknown enum symbol '$value' for ${schema.getFullName}")
        s"${namedType(schema)}.fromOrdinal($ordinal)"
      case Schema.Type.FIXED =>
        val inner = logicalName(schema) match
          case Some(name) => logicalExpression(schema, name, value)
          case None => bytesExpression(physicalBytes(value))
        s"new ${namedType(schema)}($inner)"
      case Schema.Type.ARRAY =>
        val items = value.asInstanceOf[java.util.Collection[?]].asScala.iterator
          .map(item => expression(schema.getElementType, item.asInstanceOf[AnyRef])).mkString(", ")
        s"_root_.scala.collection.immutable.Vector[${nativeType(schema.getElementType)}]($items)"
      case Schema.Type.MAP =>
        // Ordering is not significant in Avro maps. Stable source makes a failing
        // property case easy to compare and reproduce.
        val entries = value.asInstanceOf[java.util.Map[?, ?]].asScala.iterator
          .map((key, item) => (key.toString, item.asInstanceOf[AnyRef])).toVector.sortBy(_._1)
          .map { (key, item) =>
            s"(${stringLiteral(key)}, ${expression(schema.getValueType, item)})"
          }.mkString(", ")
        s"_root_.scala.collection.immutable.Map[_root_.java.lang.String, ${nativeType(schema.getValueType)}]($entries)"
      case primitive => logicalName(schema) match
        case Some(name) => logicalExpression(schema, name, value)
        case None => primitive match
          case Schema.Type.NULL =>
            require(value == null, "An Avro null datum must be null")
            "null"
          case Schema.Type.BOOLEAN => value.asInstanceOf[java.lang.Boolean].booleanValue.toString
          case Schema.Type.INT => s"(${intLiteral(value.asInstanceOf[java.lang.Integer].intValue)}: _root_.scala.Int)"
          case Schema.Type.LONG => longLiteral(value.asInstanceOf[java.lang.Long].longValue)
          case Schema.Type.FLOAT =>
            val bits = java.lang.Float.floatToRawIntBits(value.asInstanceOf[java.lang.Float].floatValue)
            s"_root_.java.lang.Float.intBitsToFloat(${intLiteral(bits)})"
          case Schema.Type.DOUBLE =>
            val bits = java.lang.Double.doubleToRawLongBits(value.asInstanceOf[java.lang.Double].doubleValue)
            s"_root_.java.lang.Double.longBitsToDouble(${longLiteral(bits)})"
          case Schema.Type.STRING => stringLiteral(value.asInstanceOf[CharSequence].toString)
          case Schema.Type.BYTES => bytesExpression(physicalBytes(value))
          case other => throw new IllegalArgumentException(s"Unsupported physical schema: $other")

  private def unionExpression(schema: Schema, value: AnyRef): String =
    val branches = schema.getTypes.asScala.toVector
    require(branches.nonEmpty, "An empty union has no values")
    val selected = branches(GenericData.get().resolveUnion(schema, value))
    val optional = branches.size > 1 && branches.exists(_.getType == Schema.Type.NULL)
    if selected.getType == Schema.Type.NULL then
      if optional then "_root_.scala.None" else "null"
    else
      val raw = expression(selected, value)
      val tagged = if ambiguousTimes(branches) then logicalName(selected) match
        case Some("time-millis") => s"new _root_.avrogen.runtime.TimeMillis($raw)"
        case Some("time-micros") => s"new _root_.avrogen.runtime.TimeMicros($raw)"
        case _ => raw
      else raw
      if optional then s"_root_.scala.Some($tagged)" else tagged

  private def logicalExpression(schema: Schema, logical: String, value: AnyRef): String =
    def numeric = value.asInstanceOf[java.lang.Number].longValue
    logical match
      case "date" => s"_root_.java.time.LocalDate.ofEpochDay(${longLiteral(numeric)})"
      case "time-millis" =>
        val nanos = Math.multiplyExact(numeric, 1000000L)
        s"_root_.java.time.LocalTime.ofNanoOfDay(${longLiteral(nanos)})"
      case "time-micros" =>
        val nanos = Math.multiplyExact(numeric, 1000L)
        s"_root_.java.time.LocalTime.ofNanoOfDay(${longLiteral(nanos)})"
      case "timestamp-millis" | "timestamp-micros" | "timestamp-nanos" =>
        instantExpression(logical.stripPrefix("timestamp-"), numeric)
      case "local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos" =>
        val instant = instantExpression(logical.stripPrefix("local-timestamp-"), numeric)
        s"_root_.java.time.LocalDateTime.ofInstant($instant, _root_.java.time.ZoneOffset.UTC)"
      case "uuid" if schema.getType == Schema.Type.STRING =>
        s"_root_.java.util.UUID.fromString(${stringLiteral(value.asInstanceOf[CharSequence].toString)})"
      case "uuid" if schema.getType == Schema.Type.FIXED =>
        val bytes = physicalBytes(value)
        require(bytes.length == 16, "A fixed UUID must have 16 bytes")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val most = buffer.getLong()
        val least = buffer.getLong()
        s"new _root_.java.util.UUID(${longLiteral(most)}, ${longLiteral(least)})"
      case "decimal" =>
        val bytes = physicalBytes(value)
        require(bytes.nonEmpty, "A decimal needs a two's-complement integer")
        val unscaled = new BigInteger(bytes)
        val scale = Option(schema.getObjectProp("scale")).fold(0)(_.asInstanceOf[java.lang.Number].intValue)
        // exact(java.math.BigDecimal) preserves values longer than Scala's
        // default 34-digit arithmetic context, including their original scale.
        s"_root_.scala.BigDecimal.exact(new _root_.java.math.BigDecimal(new _root_.java.math.BigInteger(${stringLiteral(unscaled.toString)}), $scale))"
      case "duration" =>
        val bytes = physicalBytes(value)
        require(bytes.length == 12, "An Avro duration must have 12 bytes")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val months = java.lang.Integer.toUnsignedLong(buffer.getInt())
        val days = java.lang.Integer.toUnsignedLong(buffer.getInt())
        val millis = java.lang.Integer.toUnsignedLong(buffer.getInt())
        s"new _root_.avrogen.runtime.AvroDuration(${longLiteral(months)}, ${longLiteral(days)}, ${longLiteral(millis)})"
      case other => throw new IllegalArgumentException(s"Unsupported logical type '$other' on ${schema.getType}")

  private def instantExpression(unit: String, value: Long): String = unit match
    case "millis" => s"_root_.java.time.Instant.ofEpochMilli(${longLiteral(value)})"
    case "micros" => s"_root_.java.time.Instant.EPOCH.plus(${longLiteral(value)}, _root_.java.time.temporal.ChronoUnit.MICROS)"
    case "nanos" => s"_root_.java.time.Instant.EPOCH.plus(${longLiteral(value)}, _root_.java.time.temporal.ChronoUnit.NANOS)"
    case other => throw new IllegalArgumentException(s"Unsupported timestamp unit '$other'")

  private def nativeType(schema: Schema): String = schema.getType match
    case Schema.Type.RECORD | Schema.Type.ENUM | Schema.Type.FIXED => namedType(schema)
    case Schema.Type.ARRAY => s"_root_.scala.collection.immutable.Vector[${nativeType(schema.getElementType)}]"
    case Schema.Type.MAP => s"_root_.scala.collection.immutable.Map[_root_.java.lang.String, ${nativeType(schema.getValueType)}]"
    case Schema.Type.UNION =>
      val branches = schema.getTypes.asScala.toVector
      val nonNull = branches.filterNot(_.getType == Schema.Type.NULL)
      val typeNames = nonNull.map { branch =>
        if ambiguousTimes(branches) then logicalName(branch) match
          case Some("time-millis") => "_root_.avrogen.runtime.TimeMillis"
          case Some("time-micros") => "_root_.avrogen.runtime.TimeMicros"
          case _ => nativeType(branch)
        else nativeType(branch)
      }
      if typeNames.isEmpty then "_root_.scala.Null"
      else if nonNull.size != branches.size then s"_root_.scala.Option[${typeNames.mkString(" | ")}]"
      else typeNames.mkString(" | ")
    case primitive => logicalName(schema) match
      case Some("date") => "_root_.java.time.LocalDate"
      case Some("time-millis" | "time-micros") => "_root_.java.time.LocalTime"
      case Some("timestamp-millis" | "timestamp-micros" | "timestamp-nanos") => "_root_.java.time.Instant"
      case Some("local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos") => "_root_.java.time.LocalDateTime"
      case Some("uuid") => "_root_.java.util.UUID"
      case Some("decimal") => "_root_.scala.BigDecimal"
      case Some(other) => throw new IllegalArgumentException(s"Unsupported logical type '$other'")
      case None => primitive match
        case Schema.Type.NULL => "_root_.scala.Null"
        case Schema.Type.BOOLEAN => "_root_.scala.Boolean"
        case Schema.Type.INT => "_root_.scala.Int"
        case Schema.Type.LONG => "_root_.scala.Long"
        case Schema.Type.FLOAT => "_root_.scala.Float"
        case Schema.Type.DOUBLE => "_root_.scala.Double"
        case Schema.Type.STRING => "_root_.java.lang.String"
        case Schema.Type.BYTES => "_root_.avrogen.runtime.Bytes"
        case other => throw new IllegalArgumentException(s"Unsupported schema type: $other")

  private def logicalName(schema: Schema): Option[String] = Option(schema.getProp("logicalType"))

  private def ambiguousTimes(branches: Vector[Schema]): Boolean =
    val names = branches.flatMap(logicalName).toSet
    names("time-millis") && names("time-micros")

  private def namedType(schema: Schema): String =
    val parts = schema.getFullName.split("\\.", -1)
    parts.foreach { part =>
      require(part.matches("[A-Za-z_][A-Za-z0-9_]*") && part != "_" && part != "_root_", s"Unsupported Scala name '$part'")
    }
    val name = parts.map(part => s"`$part`").mkString(".")
    if parts.length > 1 then "_root_." + name else name

  private def physicalBytes(value: AnyRef): Array[Byte] = value match
    case fixed: GenericFixed => fixed.bytes().clone()
    case original: ByteBuffer =>
      val buffer = original.duplicate()
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      bytes
    case other => throw new IllegalArgumentException(s"Expected GenericFixed or ByteBuffer, found ${Option(other).map(_.getClass.getName).getOrElse("null")}")

  private def bytesExpression(bytes: Array[Byte]): String =
    s"_root_.avrogen.runtime.Bytes.fromArray(_root_.scala.Array[_root_.scala.Byte](${bytes.iterator.map(_.toString).mkString(", ")}))"

  private def intLiteral(value: Int): String =
    if value == Int.MinValue then "_root_.scala.Int.MinValue" else value.toString

  private def longLiteral(value: Long): String =
    if value == Long.MinValue then "_root_.scala.Long.MinValue" else s"${value}L"

  /** ASCII-only source preserves control characters and individual UTF-16 units. */
  private def stringLiteral(value: String): String =
    val encoded = new StringBuilder
    encoded.append('"')
    value.foreach { character =>
      character match
        case '"' => encoded.append("\\\"")
        case '\\' => encoded.append("\\\\")
        case '\n' => encoded.append("\\n")
        case '\r' => encoded.append("\\r")
        case '\t' => encoded.append("\\t")
        case '\b' => encoded.append("\\b")
        case '\f' => encoded.append("\\f")
        case printable if printable >= ' ' && printable <= '~' => encoded.append(printable)
        case unit => encoded.append(f"\\u${unit.toInt}%04x")
    }
    encoded.append('"').toString
