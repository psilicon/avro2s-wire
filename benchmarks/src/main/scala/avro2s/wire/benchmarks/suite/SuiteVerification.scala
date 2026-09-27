package avro2s.wire.benchmarks.suite

import _root_.avro2s.wire.runtime.{AvroDuration, Bytes}
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericFixed, IndexedRecord}
import org.apache.avro.util.TimePeriod
import scala.jdk.CollectionConverters.*

/** Untimed semantic checks preserve numeric kinds, signed zero, union branches and decimal scale. */
object SuiteVerification:
  def normalize(value: Any, schema: Schema): Any =
    if schema.getType == Schema.Type.UNION then
      val actual = value match
        case option: Option[?] => option.getOrElse(null)
        case other => other
      val branches = schema.getTypes.asScala
      val index = branches.indexWhere(matches(actual, _))
      require(index >= 0, s"No union branch for $actual")
      (index, normalize(actual, branches(index)))
    else if schema.getLogicalType != null then
      def unwrap(v: Any): Any = v match
        case duration: AvroDuration => duration
        case product: Product if product.productArity == 1 => unwrap(product.productElement(0))
        case other => other
      val actual = unwrap(value)
      schema.getLogicalType.getName match
        case "decimal" | "big-decimal" =>
          val decimal = actual match
            case d: java.math.BigDecimal => d
            case d: BigDecimal => d.bigDecimal
            case other => throw new IllegalArgumentException(s"Expected decimal domain value: $other")
          (decimal.unscaledValue(), decimal.scale())
        case "duration" => actual match
          case d: TimePeriod => (d.getMonths(), d.getDays(), d.getMillis())
          case d: AvroDuration => (d.months, d.days, d.millis)
          case other => throw new IllegalArgumentException(s"Expected duration domain value: $other")
        case "date" => actual.asInstanceOf[java.time.LocalDate]
        case "time-millis" | "time-micros" => actual.asInstanceOf[java.time.LocalTime]
        case "timestamp-millis" | "timestamp-micros" | "timestamp-nanos" => actual.asInstanceOf[java.time.Instant]
        case "local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos" => actual.asInstanceOf[java.time.LocalDateTime]
        case "uuid" => actual.asInstanceOf[java.util.UUID]
        case other => throw new IllegalArgumentException(s"Unsupported logical comparison: $other")
    else schema.getType match
      case Schema.Type.RECORD =>
        schema.getFields.asScala.map { field =>
          val item = value match
            case record: IndexedRecord => record.get(field.pos())
            case product: Product => product.productElement(field.pos())
            case other => throw new IllegalArgumentException(s"Expected record: $other")
          field.name() -> normalize(item, field.schema())
        }.toVector
      case Schema.Type.ARRAY =>
        val items = value match
          case a: java.util.Collection[?] => a.asScala.iterator
          case a: Iterable[?] => a.iterator
        items.map(normalize(_, schema.getElementType)).toVector
      case Schema.Type.MAP =>
        val items = value match
          case m: java.util.Map[?, ?] => m.asScala.iterator
          case m: scala.collection.Map[?, ?] => m.iterator
        items.map { (key, item) =>
          require(key.isInstanceOf[String], s"Map key is not a String: ${key.getClass}")
          key.toString -> normalize(item, schema.getValueType)
        }.toMap
      case Schema.Type.FIXED | Schema.Type.BYTES =>
        def bytes(v: Any): Vector[Byte] = v match
          case b: Bytes => b.toArray.toVector
          case b: Array[Byte] => b.toVector
          case b: GenericFixed => b.bytes().toVector
          case b: ByteBuffer =>
            val copy = b.duplicate()
            val result = new Array[Byte](copy.remaining())
            copy.get(result)
            result.toVector
          case wrapper: Product if wrapper.productArity == 1 => bytes(wrapper.productElement(0))
          case other => throw new IllegalArgumentException(s"Expected binary value: $other")
        bytes(value)
      case Schema.Type.ENUM => value.toString
      case Schema.Type.STRING =>
        require(value.isInstanceOf[String], s"Expected String: ${value.getClass}")
        value
      case Schema.Type.FLOAT => ("float", java.lang.Float.floatToRawIntBits(value.asInstanceOf[Float]))
      case Schema.Type.DOUBLE => ("double", java.lang.Double.doubleToRawLongBits(value.asInstanceOf[Double]))
      case Schema.Type.INT => ("int", value.asInstanceOf[Int])
      case Schema.Type.LONG => ("long", value.asInstanceOf[Long])
      case Schema.Type.BOOLEAN => value.asInstanceOf[Boolean]
      case Schema.Type.NULL => require(value == null); null
      case other => throw new IllegalArgumentException(s"Unsupported schema type $other")

  private def matches(value: Any, schema: Schema): Boolean = schema.getType match
    case Schema.Type.NULL => value == null
    case Schema.Type.INT => value.isInstanceOf[Int]
    case Schema.Type.LONG => value.isInstanceOf[Long]
    case Schema.Type.STRING => value.isInstanceOf[String]
    case Schema.Type.RECORD => value match
      case r: IndexedRecord => r.getSchema.getName == schema.getName
      case p: Product => p.productPrefix == schema.getName
      case _ => false
    case _ => false
