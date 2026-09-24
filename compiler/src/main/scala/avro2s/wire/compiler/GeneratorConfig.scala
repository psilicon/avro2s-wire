package avro2s.wire.compiler

/** The generated value type for decimal and big-decimal logical values. */
enum DecimalType:
  case Scala, Java

/** Supported Avro logical annotations, independent of their physical storage type. */
enum LogicalType(val avroName: String):
  case Date extends LogicalType("date")
  case TimeMillis extends LogicalType("time-millis")
  case TimeMicros extends LogicalType("time-micros")
  case TimestampMillis extends LogicalType("timestamp-millis")
  case TimestampMicros extends LogicalType("timestamp-micros")
  case TimestampNanos extends LogicalType("timestamp-nanos")
  case LocalTimestampMillis extends LogicalType("local-timestamp-millis")
  case LocalTimestampMicros extends LogicalType("local-timestamp-micros")
  case LocalTimestampNanos extends LogicalType("local-timestamp-nanos")
  case Uuid extends LogicalType("uuid")
  case Duration extends LogicalType("duration")
  case Decimal extends LogicalType("decimal")
  case BigDecimal extends LogicalType("big-decimal")

object LogicalType:
  def fromAvroName(name: String): Option[LogicalType] = values.find(_.avroName == name)

/** Converted uses domain values; Raw uses the Avro physical representation. */
enum LogicalTypeMode:
  case Raw, Converted

/** Generation options apply consistently to every reachable named schema.
  *
  * Namespace mappings match complete namespace components, using the longest
  * matching source prefix. An empty source matches only the default namespace;
  * an empty target removes the matched prefix. Missing logical type modes use
  * Converted. Neither option changes the original Avro schema metadata.
  */
final case class GeneratorConfig(
    decimalType: DecimalType = DecimalType.Scala,
    namespaceMappings: Map[String, String] = Map.empty,
    logicalTypes: Map[LogicalType, LogicalTypeMode] = Map.empty
):
  /** Validate options even when no schemas use the configured namespaces or logical types. */
  def validate(): Unit =
    namespaceMappings.toVector.sortBy(_._1).foreach { (source, target) =>
      if source.nonEmpty && !source.split("\\.", -1).forall(_.matches("[A-Za-z_][A-Za-z0-9_]*")) then
        throw GenerationException(s"Invalid source namespace '$source' in namespace mapping")
      if target.nonEmpty then
        try target.split("\\.", -1).foreach(ScalaNames.validateIdentifier)
        catch case error: GenerationException =>
          throw GenerationException(s"Invalid target namespace '$target' in namespace mapping: ${error.getMessage}")
    }

  private[compiler] def mappedFullName(fullName: String): String =
    val separator = fullName.lastIndexOf('.')
    val namespace = if separator < 0 then "" else fullName.substring(0, separator)
    val localName = fullName.substring(separator + 1)
    val mapping = namespaceMappings.iterator.filter { (source, _) =>
      if source.isEmpty then namespace.isEmpty
      else namespace == source || namespace.startsWith(source + ".")
    }.toVector.sortBy { (source, _) => (-source.length, source) }.headOption
    val mappedNamespace = mapping.fold(namespace) { (source, target) =>
      val suffix = namespace.substring(source.length).stripPrefix(".")
      Vector(target, suffix).filter(_.nonEmpty).mkString(".")
    }
    if mappedNamespace.isEmpty then localName else s"$mappedNamespace.$localName"

  private[compiler] def isRaw(logicalType: LogicalType): Boolean =
    logicalTypes.getOrElse(logicalType, LogicalTypeMode.Converted) == LogicalTypeMode.Raw

  private[compiler] def rawLogicalTypes: Vector[String] =
    logicalTypes.iterator.collect { case (logicalType, LogicalTypeMode.Raw) => logicalType.avroName }.toVector.sorted
