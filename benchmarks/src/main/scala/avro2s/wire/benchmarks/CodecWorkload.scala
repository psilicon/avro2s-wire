package avro2s.wire.benchmarks

import _root_.avro2s.wire.fixtures.performance.*
import _root_.avro2s.wire.interop.{JavaAvroInput, JavaAvroOutput}
import _root_.avro2s.wire.runtime.{AvroCodec, BinaryInput, BinaryOutput, Bytes}
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

/** Identical generated codec/model on both primitive engines. Reused writers omit
  * the final byte-array copy; encode includes new buffers and that owned copy.
  * All reads create a fresh input and model. Read uses an existing payload;
  * decode additionally checks for trailing data. There is no input/datum reuse.
  * Native validation remains enabled. Java primitive decoding has Java's normal
  * validation policy, so its performance is not a validation-equivalent baseline.
  */
final class CodecWorkload[A](
    val codec: AvroCodec[A],
    val expected: A,
    genericValue: Schema => GenericRecord
):
  private val schema = new Schema.Parser().parse(codec.schemaJson)
  private val oracleValue = genericValue(schema)
  private val oracleWriter = new GenericDatumWriter[GenericRecord](schema)
  private val oracleReader = new GenericDatumReader[GenericRecord](schema)
  private val nativeOutput = new BinaryOutput()
  private val javaBytes = new ByteArrayOutputStream()
  private val javaEncoder = EncoderFactory.get().binaryEncoder(javaBytes, null)
  private val javaOutput = new JavaAvroOutput(javaEncoder)
  val payload: Array[Byte] = codec.encode(expected)

  def nativeWrite(): Int =
    nativeOutput.reset()
    codec.write(expected, nativeOutput)
    nativeOutput.size

  def javaPrimitivesWrite(): Int =
    javaBytes.reset()
    codec.write(expected, javaOutput)
    javaEncoder.flush()
    javaBytes.size()

  def nativeRead(): A = codec.read(new BinaryInput(payload))
  def javaPrimitivesRead(): A =
    codec.read(new JavaAvroInput(DecoderFactory.get().binaryDecoder(payload, null)))

  def nativeEncode(): Array[Byte] = codec.encode(expected)
  def nativeDecode(): A = codec.decode(payload)

  def javaPrimitivesEncode(): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    codec.write(expected, new JavaAvroOutput(encoder))
    encoder.flush()
    bytes.toByteArray

  def javaPrimitivesDecode(): A =
    val decoder = DecoderFactory.get().binaryDecoder(payload, null)
    val result = codec.read(new JavaAvroInput(decoder))
    require(decoder.isEnd, "Trailing bytes")
    result

  /** Setup/tests only: independent Java datum writer, built from schema fields. */
  def oracleEncode(): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    oracleWriter.write(oracleValue, encoder)
    encoder.flush()
    bytes.toByteArray

  /** Owned snapshots used only outside timed operations. */
  def encodedByEveryWriter(): Vector[(String, Array[Byte])] =
    nativeWrite()
    javaPrimitivesWrite()
    Vector(
      "nativeWrite" -> nativeOutput.toByteArray,
      "javaPrimitivesWrite" -> javaBytes.toByteArray,
      "nativeEncode" -> nativeEncode(),
      "javaPrimitivesEncode" -> javaPrimitivesEncode(),
      "javaGeneric" -> oracleEncode()
    )

  def verifyInteroperability(): Unit =
    val normalizedExpected = CodecWorkloads.normalize(oracleValue, schema)
    encodedByEveryWriter().foreach { (writer, bytes) =>
      require(codec.decode(bytes) == expected, s"$writer -> native value mismatch")
      val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
      require(codec.read(new JavaAvroInput(decoder)) == expected, s"$writer -> Java primitives value mismatch")
      require(decoder.isEnd, s"$writer -> Java primitives left trailing data")
      val oracleDecoder = DecoderFactory.get().binaryDecoder(bytes, null)
      val oracleResult = oracleReader.read(null, oracleDecoder)
      require(oracleDecoder.isEnd, s"$writer -> Java generic left trailing data")
      require(CodecWorkloads.normalize(oracleResult, schema) == normalizedExpected,
        s"$writer -> independent Java generic value mismatch")
    }
    require(nativeRead() == expected && javaPrimitivesRead() == expected)
    require(nativeDecode() == expected && javaPrimitivesDecode() == expected)

object CodecWorkloads:
  val integerDistributions = Vector("one-byte", "medium", "wide", "mixed")
  val stringProfiles = Vector("empty", "ascii-short", "ascii-long", "multilingual-short",
    "multilingual-long", "emoji-short", "emoji-long", "latin1-short", "latin1-long",
    "question-short", "question-long", "replacement-short", "replacement-long")
  val integerCount = 1024

  def ints(distribution: String): Vector[Int] =
    val boundaries = (0 to 4).flatMap { width =>
      val shift = math.min(6 + width * 7, 30)
      val limit = 1 << shift
      Vector(limit - 1, -limit, limit, -limit - 1)
    }.toVector ++ Vector(Int.MinValue, Int.MaxValue)
    val random = new scala.util.Random(8675309L)
    Vector.tabulate(integerCount) { index => distribution match
      case "one-byte" => index % 128 - 64
      case "medium" => (10000 + index) * (if index % 2 == 0 then 1 else -1)
      case "wide" => if index % 2 == 0 then Int.MaxValue - index else Int.MinValue + index
      case "mixed" => boundaries(random.nextInt(boundaries.size))
      case other => throw new IllegalArgumentException(s"Unknown integer distribution: $other")
    }

  def longs(distribution: String): Vector[Long] =
    val boundaries = (0 to 8).flatMap { width =>
      val limit = 1L << (6 + width * 7)
      Vector(limit - 1, -limit, limit, -limit - 1)
    }.toVector ++ Vector(Long.MinValue, Long.MaxValue)
    val random = new scala.util.Random(8675309L)
    Vector.tabulate(integerCount) { index => distribution match
      case "one-byte" => (index % 128 - 64).toLong
      case "medium" => (1000000L + index) * (if index % 2 == 0 then 1L else -1L)
      case "wide" => if index % 2 == 0 then Long.MaxValue - index else Long.MinValue + index
      case "mixed" => boundaries(random.nextInt(boundaries.size))
      case other => throw new IllegalArgumentException(s"Unknown integer distribution: $other")
    }

  def integer(kind: String, distribution: String): CodecWorkload[?] = kind match
    case "int" =>
      val values = ints(distribution)
      new CodecWorkload(PerfInts.codec, PerfInts(values), schema =>
        record(schema, "values" -> values.map(Int.box).asJava))
    case "long" =>
      val values = longs(distribution)
      new CodecWorkload(PerfLongs.codec, PerfLongs(values), schema =>
        record(schema, "values" -> values.map(Long.box).asJava))
    case other => throw new IllegalArgumentException(s"Unknown integer kind: $other")

  /** Every nonempty short string has 32 Unicode code points; long has 4096.
    * ASCII = 1 byte/code point, multilingual = 2/3 bytes, emoji = 4 bytes.
    * Inputs and their UTF-8 encoding are deterministic and generated outside timing.
    */
  def text(profile: String): String =
    if profile == "empty" then ""
    else
      val (unit, codePoints) = profile match
        case "ascii-short" => ("Avro2026", 32)
        case "ascii-long" => ("Avro2026", 4096)
        case "multilingual-short" => ("λé漢字", 32)
        case "multilingual-long" => ("λé漢字", 4096)
        case "emoji-short" => ("😀🚀🎉🌍", 32)
        case "emoji-long" => ("😀🚀🎉🌍", 4096)
        case "latin1-short" => ("éñüç", 32)
        case "latin1-long" => ("éñüç", 4096)
        case "question-short" => ("Ready?Go", 32)
        case "question-long" => ("Ready?Go", 4096)
        case "replacement-short" => ("a\ufffdλ😀", 32)
        case "replacement-long" => ("a\ufffdλ😀", 4096)
        case other => throw new IllegalArgumentException(s"Unknown string profile: $other")
      unit.repeat(codePoints / unit.codePointCount(0, unit.length))

  def string(profile: String): CodecWorkload[PerfString] =
    val value = text(profile)
    new CodecWorkload(PerfString.codec, PerfString(value), schema => record(schema, "value" -> value))

  def bytes(size: Int): CodecWorkload[PerfBytes] =
    require(size >= 0)
    val value = Array.tabulate[Byte](size)(index => (index * 73 + 17).toByte)
    new CodecWorkload(PerfBytes.codec, PerfBytes(Bytes.fromArray(value)), schema =>
      record(schema, "value" -> ByteBuffer.wrap(value)))

  def collections(size: Int): CodecWorkload[PerfCollections] =
    require(size >= 0)
    val values = Vector.tabulate(size)(index => 10000 + index)
    val labels = Vector.tabulate(size)(index => s"key-$index" -> s"value-$index-λ").toMap
    val javaLabels = new java.util.LinkedHashMap[String, String]()
    labels.foreach((key, value) => javaLabels.put(key, value))
    new CodecWorkload(PerfCollections.codec, PerfCollections(values, labels), schema =>
      record(schema, "values" -> values.map(Int.box).asJava, "labels" -> javaLabels))

  /** A binary tree, four union branches at every node: null/int/string/record.
    * depth 0 = 1 node, depth 1 = 3 nodes, depth 4 = 31 nodes.
    */
  def nested(depth: Int): CodecWorkload[PerfNested] =
    require(depth >= 0 && depth <= 8)
    def value(level: Int, id: Long): PerfNested =
      PerfNested(id, Vector(None, Some(10000), Some(s"node-$id-λ"), Some(PerfLeaf(id + 10000))),
        if level == 0 then Vector.empty else Vector(value(level - 1, id * 2), value(level - 1, id * 2 + 1)))
    val expected = value(depth, 1L)
    def generic(node: PerfNested, schema: Schema): GenericRecord =
      val leafSchema = schema.getField("choices").schema().getElementType.getTypes.get(3)
      val choices: Vector[AnyRef] = Vector(null, Int.box(10000), s"node-${node.id}-λ",
        record(leafSchema, "value" -> Long.box(node.id + 10000)))
      record(schema, "id" -> Long.box(node.id), "choices" -> choices.asJava,
        "children" -> node.children.map(child => generic(child, schema)).asJava)
    new CodecWorkload(PerfNested.codec, expected, schema => generic(expected, schema))

  private def record(schema: Schema, fields: (String, AnyRef)*): GenericRecord =
    val result = new GenericData.Record(schema)
    fields.foreach((name, value) => result.put(name, value))
    result

  /** Preserve union branch identities and compare maps independent of iteration order. */
  private[benchmarks] def normalize(value: Any, schema: Schema): Any = schema.getType match
    case Schema.Type.RECORD =>
      val record = value.asInstanceOf[GenericRecord]
      schema.getFields.asScala.map(field => field.name() -> normalize(record.get(field.name()), field.schema())).toVector
    case Schema.Type.ARRAY =>
      value.asInstanceOf[java.util.Collection[?]].asScala.map(item => normalize(item, schema.getElementType)).toVector
    case Schema.Type.MAP =>
      value.asInstanceOf[java.util.Map[?, ?]].asScala.map((key, item) => key.toString -> normalize(item, schema.getValueType)).toMap
    case Schema.Type.UNION =>
      val index = GenericData.get().resolveUnion(schema, value)
      (index, normalize(value, schema.getTypes.get(index)))
    case Schema.Type.STRING | Schema.Type.ENUM => value.toString
    case Schema.Type.BYTES =>
      val buffer = value.asInstanceOf[ByteBuffer].duplicate()
      val bytes = new Array[Byte](buffer.remaining())
      buffer.get(bytes)
      bytes.toVector
    case Schema.Type.FIXED => value.asInstanceOf[GenericData.Fixed].bytes().toVector
    case _ => value
