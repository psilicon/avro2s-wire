package avro2s.wire.properties

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.{Conversions, LogicalTypes, Schema}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, IndexedRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

/** Java owns the reference data and wire encoding; no avro2s-wire adapters are used. */
object JavaOracle:
  private val bigDecimals = new Conversions.BigDecimalConversion()

  def bigDecimalBytes(schema: Schema, value: java.math.BigDecimal): ByteBuffer =
    bigDecimals.toBytes(value, schema, LogicalTypes.bigDecimal())

  def bigDecimalValue(schema: Schema, value: AnyRef): java.math.BigDecimal =
    bigDecimals.fromBytes(value.asInstanceOf[ByteBuffer].duplicate(), schema, LogicalTypes.bigDecimal())

  def encode(schema: Schema, value: AnyRef): Array[Byte] =
    val stream = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(stream, null)
    new GenericDatumWriter[AnyRef](schema).write(value, encoder)
    encoder.flush()
    stream.toByteArray

  def decode(schema: Schema, bytes: Array[Byte]): AnyRef =
    val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
    val value = new GenericDatumReader[AnyRef](schema).read(null, decoder)
    require(decoder.isEnd, "Java reader left trailing bytes")
    value

  private def byteVector(value: AnyRef): Vector[Byte] = value match
    case v: ByteBuffer =>
      val buffer = v.duplicate()
      Vector.fill(buffer.remaining())(buffer.get())
    case v: GenericData.Fixed => v.bytes().toVector

  /** Includes the selected union branch, named identity, and floating-point bits.
    * Collection block layout and map order are deliberately not part of datum equality.
    */
  def normalized(schema: Schema, value: AnyRef, rawLogicalTypes: Set[String] = Set.empty): Any =
    if schema.getProp("logicalType") == "decimal" && !rawLogicalTypes("decimal") then
      ("decimal", new java.math.BigInteger(byteVector(value).toArray), schema.getObjectProp("scale"))
    else if schema.getProp("logicalType") == "big-decimal" && !rawLogicalTypes("big-decimal") then
      val decimal = bigDecimalValue(schema, value)
      ("big-decimal", decimal.unscaledValue(), decimal.scale())
    else schema.getType match
      case Schema.Type.NULL => ()
      case Schema.Type.UNION =>
        val branch = GenericData.get().resolveUnion(schema, value)
        (branch, normalized(schema.getTypes.get(branch), value, rawLogicalTypes))
      case Schema.Type.RECORD =>
        val record = value.asInstanceOf[IndexedRecord]
        (schema.getFullName, schema.getFields.asScala.map(f => normalized(f.schema(), record.get(f.pos()).asInstanceOf[AnyRef], rawLogicalTypes)).toVector)
      case Schema.Type.ARRAY => value.asInstanceOf[java.util.Collection[AnyRef]].asScala.map(normalized(schema.getElementType, _, rawLogicalTypes)).toVector
      case Schema.Type.MAP => value.asInstanceOf[java.util.Map[CharSequence, AnyRef]].asScala.iterator.map((k, v) => k.toString -> normalized(schema.getValueType, v, rawLogicalTypes)).toMap
      case Schema.Type.ENUM => (schema.getFullName, value.toString)
      case Schema.Type.FIXED => (schema.getFullName, byteVector(value))
      case Schema.Type.BYTES => byteVector(value)
      case Schema.Type.STRING => value.toString
      case Schema.Type.FLOAT => java.lang.Float.floatToRawIntBits(value.asInstanceOf[java.lang.Float].floatValue())
      case Schema.Type.DOUBLE => java.lang.Double.doubleToRawLongBits(value.asInstanceOf[java.lang.Double].doubleValue())
      case _ => value

  /** Scala case-class equality alone misses signed zero and cannot compare NaNs reliably. */
  def nativeEqual(left: Any, right: Any): Boolean = (left, right) match
    case (a: java.math.BigDecimal, b: java.math.BigDecimal) => a.equals(b)
    case (a: scala.math.BigDecimal, b: scala.math.BigDecimal) => a.bigDecimal.equals(b.bigDecimal)
    case (a: Float, b: Float) => java.lang.Float.floatToRawIntBits(a) == java.lang.Float.floatToRawIntBits(b)
    case (a: Double, b: Double) => java.lang.Double.doubleToRawLongBits(a) == java.lang.Double.doubleToRawLongBits(b)
    case (a: Map[?, ?], b: Map[?, ?]) =>
      val aa = a.asInstanceOf[Map[Any, Any]]
      val bb = b.asInstanceOf[Map[Any, Any]]
      aa.keySet == bb.keySet && aa.forall((k, v) => nativeEqual(v, bb(k)))
    case (a: Seq[?], b: Seq[?]) => a.size == b.size && a.iterator.zip(b.iterator).forall((x, y) => nativeEqual(x, y))
    case (a: Product, b: Product) =>
      a.getClass == b.getClass && a.productArity == b.productArity && a.productIterator.zip(b.productIterator).forall((x, y) => nativeEqual(x, y))
    case _ =>
      // Scala universal equality treats 1, 1L and 1.0 as equal. A union's
      // runtime representation is part of its identity, even at equal values.
      if left == null || right == null then left == null && right == null
      else left.getClass == right.getClass && left == right
