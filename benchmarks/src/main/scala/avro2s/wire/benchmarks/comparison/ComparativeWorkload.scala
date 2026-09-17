package avro2s.wire.benchmarks.comparison

import _root_.avro2s.wire.runtime.{AvroCodec, BinaryInput, BinaryOutput, Bytes}
import _root_.avro2s.wire.interop.{JavaAvroInput, JavaAvroOutput}
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.*
import org.apache.avro.{Conversions, Schema}
import org.apache.avro.data.TimeConversions
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericFixed, GenericRecord, IndexedRecord}
import org.apache.avro.io.{Decoder, DecoderFactory, EncoderFactory}
import org.apache.avro.specific.{SpecificData, SpecificDatumReader, SpecificDatumWriter, SpecificRecordBase}
import scala.jdk.CollectionConverters.*

/** Complete implementations with natural public models, never a model-conversion microbenchmark.
  * Readers allocate fresh decoder/model; writers reuse buffers without a final byte-array copy.
  * Harness reflection, fixture conversions, schema parsing and interoperability checks run during
  * setup/tests. Runtime validation and any library-internal constructor reflection remain timed.
  */
final class ComparativeWorkload[A](
    val codec: AvroCodec[A],
    val expected: A,
    makeGeneric: Schema => GenericRecord,
    val supportsAvro2s: Boolean = true,
    val supportsCustom: Boolean = true
):
  import ComparativeWorkload.*
  val schema: Schema = new Schema.Parser().parse(codec.schemaJson)
  private val genericData = conversions(new GenericData()).setFastReaderEnabled(true)
  private val standardData = specificData(custom = false)
  private val customData = specificData(custom = true)
  private val scalaData = specificData(custom = false)
  val javaSchema: Schema = baselineSchema("javaavro", schema.getName)
  private val scalaSchema = if supportsAvro2s then baselineSchema("avro2s", schema.getName) else null
  private val genericValue = makeGeneric(schema)
  private val referenceBytes =
    val out = new JavaOutput
    new GenericDatumWriter[GenericRecord](schema, genericData).write(genericValue, out.encoder)
    out.finish()
    out.bytes.toByteArray
  // Raw field layouts match across the relocated namespaces. Populate genuine
  // generated models outside timing using each model's own schema, without
  // treating the deliberately different record names as schema evolution.
  private def baselineValue(targetSchema: Schema, data: SpecificData): SpecificRecordBase =
    val in = DecoderFactory.get().binaryDecoder(referenceBytes, null)
    val value = new SpecificDatumReader[SpecificRecordBase](targetSchema, targetSchema, data).read(null, in)
    require(in.isEnd)
    value
  private val javaValue = baselineValue(javaSchema, standardData)
  private val scalaValue = if supportsAvro2s then baselineValue(scalaSchema, scalaData) else null
  // Start all writers from Strings, including map keys; Java readers still return
  // their natural Utf8/CharSequence representation. No writer gets pre-encoded UTF-8.
  strings(genericValue, schema, genericData)
  strings(javaValue, javaSchema, standardData)
  requireStrings(genericValue, schema, genericData)
  requireStrings(javaValue, javaSchema, standardData)
  if supportsAvro2s then requireStrings(scalaValue, scalaSchema, scalaData)

  private val nativeOutput = new BinaryOutput()
  private val primitives = new JavaOutput
  private val generic = new JavaOutput
  private val specific = new JavaOutput
  private val custom = new JavaOutput
  private val scalaSpecific = new JavaOutput
  private val primitiveOutput = new JavaAvroOutput(primitives.encoder)
  private val genericWriter = new GenericDatumWriter[GenericRecord](schema, genericData)
  private val genericReader = new GenericDatumReader[GenericRecord](schema, schema, genericData)
  private val specificWriter = new SpecificDatumWriter[SpecificRecordBase](javaSchema, standardData)
  private val specificReader = new SpecificDatumReader[SpecificRecordBase](javaSchema, javaSchema, standardData)
  // Separate reader schemas preserve the original Utf8 baselines. These variants
  // explicitly request String materialization from Avro's normal readers.
  private lazy val stringGenericReader =
    val readerSchema = stringSchema(schema)
    new GenericDatumReader[GenericRecord](schema, readerSchema, genericData)
  private lazy val stringSpecificReader =
    val readerSchema = stringSchema(javaSchema)
    new SpecificDatumReader[SpecificRecordBase](javaSchema, readerSchema, standardData)
  private val customWriter = new SpecificDatumWriter[SpecificRecordBase](javaSchema, customData)
  private val customReader = new SpecificDatumReader[SpecificRecordBase](javaSchema, javaSchema, customData)
  private val scalaWriter = if supportsAvro2s then new SpecificDatumWriter[SpecificRecordBase](scalaSchema, scalaData) else null
  private val scalaReader = if supportsAvro2s then new SpecificDatumReader[SpecificRecordBase](scalaSchema, scalaSchema, scalaData) else null
  private val canonical = normalize(expected, schema)
  require(normalize(genericValue, schema) == canonical, "Independent fixture values disagree")
  require(normalize(javaValue, schema) == canonical, "Java fixture values disagree")
  if supportsAvro2s then require(normalize(scalaValue, schema) == canonical, "Avro2s fixture values disagree")
  private val payload: Array[Byte] =
    javaGenericWrite()
    generic.bytes.toByteArray

  def nativeWrite(): Int =
    nativeOutput.reset()
    codec.write(expected, nativeOutput)
    nativeOutput.size
  def javaPrimitivesWrite(): Int =
    primitives.bytes.reset()
    codec.write(expected, primitiveOutput)
    primitives.finish()
  def javaGenericWrite(): Int =
    generic.bytes.reset()
    genericWriter.write(genericValue, generic.encoder)
    generic.finish()
  def javaSpecificWrite(): Int =
    specific.bytes.reset()
    specificWriter.write(javaValue, specific.encoder)
    specific.finish()
  def javaCustomWrite(): Int =
    require(supportsCustom, "This schema has no generated Java custom coder")
    custom.bytes.reset()
    customWriter.write(javaValue, custom.encoder)
    custom.finish()
  def avro2sWrite(): Int =
    require(supportsAvro2s, "This schema has no equivalent avro2s logical model")
    scalaSpecific.bytes.reset()
    scalaWriter.write(scalaValue, scalaSpecific.encoder)
    scalaSpecific.finish()

  def nativeRead(): A = codec.read(new BinaryInput(payload))
  def javaPrimitivesRead(): A = codec.read(new JavaAvroInput(DecoderFactory.get().binaryDecoder(payload, null)))
  def javaGenericRead(): GenericRecord = genericReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))
  def javaSpecificRead(): SpecificRecordBase = specificReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))
  def javaGenericStringRead(): GenericRecord = stringGenericReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))
  def javaSpecificStringRead(): SpecificRecordBase = stringSpecificReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))
  def verifyStringReaders(): Unit =
    val generic = javaGenericStringRead()
    val specific = javaSpecificStringRead()
    requireStrings(generic, schema, genericData)
    requireStrings(specific, javaSchema, standardData)
    require(normalize(generic, schema) == canonical)
    require(normalize(specific, schema) == canonical)
  def javaCustomRead(): SpecificRecordBase =
    require(supportsCustom, "This schema has no generated Java custom coder")
    customReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))
  def avro2sRead(): SpecificRecordBase =
    require(supportsAvro2s, "This schema has no equivalent avro2s logical model")
    scalaReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))

  def encodedByEveryWriter(): Vector[(String, Array[Byte])] =
    nativeWrite(); javaPrimitivesWrite(); javaGenericWrite(); javaSpecificWrite()
    val ordinary = Vector("native" -> nativeOutput.toByteArray, "javaPrimitives" -> primitives.bytes.toByteArray,
      "javaGeneric" -> generic.bytes.toByteArray, "javaSpecific" -> specific.bytes.toByteArray)
    ordinary ++ (if supportsCustom then { javaCustomWrite(); Vector("javaCustom" -> custom.bytes.toByteArray) } else Vector.empty) ++
      (if supportsAvro2s then { avro2sWrite(); Vector("avro2s" -> scalaSpecific.bytes.toByteArray) } else Vector.empty)

  def decodedByEveryReader(bytes: Array[Byte]): Vector[(String, Any)] =
    def read(name: String)(f: Decoder => Any): (String, Any) =
      val in = DecoderFactory.get().binaryDecoder(bytes, null)
      val result = f(in)
      require(in.isEnd, s"$name left trailing data")
      name -> normalize(result, schema)
    Vector("native" -> normalize(codec.decode(bytes), schema),
      read("javaPrimitives")(in => codec.read(new JavaAvroInput(in))),
      read("javaGeneric")(in => genericReader.read(null, in)),
      read("javaSpecific")(in => specificReader.read(null, in))) ++
      (if supportsCustom then Vector(read("javaCustom")(in => customReader.read(null, in))) else Vector.empty) ++
      (if supportsAvro2s then Vector(read("avro2s")(in => scalaReader.read(null, in))) else Vector.empty)

  def verifyInteroperability(): Unit =
    encodedByEveryWriter().foreach { (writer, bytes) =>
      decodedByEveryReader(bytes).foreach { (reader, value) =>
        require(value == canonical, s"${schema.getName}: $writer -> $reader mismatch: $value != $canonical")
      }
    }
    require(standardData.isFastReaderEnabled() && scalaData.isFastReaderEnabled() && genericData.isFastReaderEnabled())
    require(!customData.isFastReaderEnabled())
    if supportsCustom then verifyCustomDispatch()

  /** Uses counters only on untimed probe subclasses of the genuine Java output. */
  def verifyCustomDispatch(): (Int, Int, Int, Int) =
    require(supportsCustom)
    def exercise(enabled: Boolean): (Int, Int) =
      val data = specificData(enabled)
      val writeProbe = ComparisonCustomProbes.create(schema.getName)
      javaSchema.getFields.asScala.foreach(f => writeProbe.put(f.pos(), javaValue.get(f.pos())))
      val out = new JavaOutput
      new SpecificDatumWriter[SpecificRecordBase](javaSchema, data).write(writeProbe, out.encoder)
      out.finish()
      val readProbe = ComparisonCustomProbes.create(schema.getName)
      val in = DecoderFactory.get().binaryDecoder(out.bytes.toByteArray, null)
      val result = new SpecificDatumReader[SpecificRecordBase](javaSchema, javaSchema, data).read(readProbe, in)
      require(result eq readProbe)
      require(in.isEnd && normalize(result, schema) == canonical)
      (writeProbe.encodeCalls, readProbe.decodeCalls)
    val standard = exercise(false)
    val custom = exercise(true)
    val counts = (standard._1, standard._2, custom._1, custom._2)
    require(counts == (0, 0, 1, 1), s"Actual custom-coder dispatch mismatch: $counts")
    counts

object ComparativeWorkload:
  private def stringSchema(original: Schema): Schema =
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

  private final class JavaOutput:
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    def finish(): Int = { encoder.flush(); bytes.size() }

  private def conversions[A <: GenericData](data: A): A =
    data.addLogicalTypeConversion(new Conversions.UUIDConversion())
    data.addLogicalTypeConversion(new Conversions.DecimalConversion())
    data.addLogicalTypeConversion(new TimeConversions.DateConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimeMillisConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimeMicrosConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimestampMillisConversion())
    data.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion())
    data.addLogicalTypeConversion(new TimeConversions.LocalTimestampMillisConversion())
    data.addLogicalTypeConversion(new TimeConversions.LocalTimestampMicrosConversion())
    data

  private def specificData(custom: Boolean): SpecificData =
    val data = conversions(new SpecificData(getClass.getClassLoader))
    data.setCustomCoders(custom)
    data.setFastReaderEnabled(!custom)
    data

  private def baselineSchema(engine: String, name: String): Schema =
    Class.forName(s"avro2s.wire.benchmarks.comparison.$engine.$name").getDeclaredConstructor().newInstance()
      .asInstanceOf[SpecificRecordBase].getSchema

  private def strings(value: Any, schema: Schema, data: GenericData): Any =
    if value == null || schema.getLogicalType != null then value
    else schema.getType match
      case Schema.Type.STRING => value.toString
      case Schema.Type.RECORD =>
        val record = value.asInstanceOf[IndexedRecord]
        schema.getFields.asScala.foreach(f => record.put(f.pos(), strings(record.get(f.pos()), f.schema(), data)))
        record
      case Schema.Type.ARRAY =>
        val result = new java.util.ArrayList[Any]()
        value.asInstanceOf[java.util.Collection[?]].asScala.foreach(v => result.add(strings(v, schema.getElementType, data)))
        result
      case Schema.Type.MAP =>
        val result = new java.util.LinkedHashMap[String, Any]()
        value.asInstanceOf[java.util.Map[?, ?]].asScala.foreach((key, v) => result.put(key.toString, strings(v, schema.getValueType, data)))
        result
      case Schema.Type.UNION => strings(value, schema.getTypes.get(data.resolveUnion(schema, value)), data)
      case _ => value

  private def requireStrings(value: Any, schema: Schema, data: GenericData): Unit =
    if value != null && schema.getLogicalType == null then schema.getType match
      case Schema.Type.STRING => require(value.isInstanceOf[String], "Writer starts with pre-encoded text")
      case Schema.Type.RECORD =>
        val record = value.asInstanceOf[IndexedRecord]
        schema.getFields.asScala.foreach(f => requireStrings(record.get(f.pos()), f.schema(), data))
      case Schema.Type.ARRAY =>
        value.asInstanceOf[java.util.Collection[?]].asScala.foreach(v => requireStrings(v, schema.getElementType, data))
      case Schema.Type.MAP =>
        value.asInstanceOf[java.util.Map[?, ?]].asScala.foreach { (key, v) =>
          require(key.isInstanceOf[String], "Writer starts with pre-encoded map key")
          requireStrings(v, schema.getValueType, data)
        }
      case Schema.Type.UNION => requireStrings(value, schema.getTypes.get(data.resolveUnion(schema, value)), data)
      case _ => ()

  /** Compare semantic fields and exact numeric/union identities without codec round trips. */
  def normalize(value: Any, schema: Schema): Any =
    if schema.getType == Schema.Type.UNION then
      val actual = value match
        case option: Option[?] => option.getOrElse(null)
        case other => other
      val branches = schema.getTypes.asScala
      val index = branches.indexWhere(branch => matches(actual, branch))
      require(index >= 0, s"No union branch for $actual")
      (index, normalize(actual, branches(index)))
    else if Option(schema.getLogicalType).exists(_.getName == "decimal") then
      def decimal(v: Any): java.math.BigDecimal = v match
        case n: java.math.BigDecimal => n
        case n: BigDecimal => n.bigDecimal
        case wrapper: Product => decimal(wrapper.productElement(0))
        case other => throw new IllegalArgumentException(s"Expected decimal model, got $other")
      decimal(value)
    else schema.getType match
      case Schema.Type.RECORD =>
        schema.getFields.asScala.map { field =>
          val item = value match
            case record: IndexedRecord => record.get(field.pos())
            case record: Product => record.productElement(field.pos())
          field.name() -> normalize(item, field.schema())
        }.toVector
      case Schema.Type.ARRAY =>
        val items = value match
          case collection: java.util.Collection[?] => collection.asScala.toVector
          case collection: Iterable[?] => collection.toVector
        items.map(normalize(_, schema.getElementType))
      case Schema.Type.MAP =>
        val items = value match
          case mapping: java.util.Map[?, ?] => mapping.asScala.toVector
          case mapping: scala.collection.Map[?, ?] => mapping.toVector
        items.map((key, item) => key.toString -> normalize(item, schema.getValueType)).toMap
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
          case wrapper: Product => bytes(wrapper.productElement(0))
        bytes(value)
      case Schema.Type.ENUM => value.toString
      case Schema.Type.STRING if schema.getLogicalType == null => value.toString
      case Schema.Type.FLOAT => ("float", java.lang.Float.floatToRawIntBits(value.asInstanceOf[Float]))
      case Schema.Type.DOUBLE => ("double", java.lang.Double.doubleToRawLongBits(value.asInstanceOf[Double]))
      case Schema.Type.INT if schema.getLogicalType == null => ("int", value.asInstanceOf[Int])
      case Schema.Type.LONG if schema.getLogicalType == null => ("long", value.asInstanceOf[Long])
      case _ => value

  private def matches(value: Any, schema: Schema): Boolean = schema.getType match
    case Schema.Type.NULL => value == null
    case Schema.Type.INT => value.isInstanceOf[Int]
    case Schema.Type.LONG => value.isInstanceOf[Long]
    case Schema.Type.FLOAT => value.isInstanceOf[Float]
    case Schema.Type.DOUBLE => value.isInstanceOf[Double]
    case Schema.Type.BOOLEAN => value.isInstanceOf[Boolean]
    case Schema.Type.STRING => value.isInstanceOf[CharSequence]
    case Schema.Type.RECORD => value match
      case record: IndexedRecord => record.getSchema.getName == schema.getName
      case record: Product => record.productPrefix == schema.getName
      case _ => false
    case _ => false
