package avro2s.wire.benchmarks.suite

import _root_.avro2s.wire.runtime.{AvroCodec, BinaryOutput}
import _root_.avro2s.wire.resolution.ResolvingReader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter}
import org.apache.avro.io.{DatumReader, DatumWriter, DecoderFactory, EncoderFactory}
import org.apache.avro.message.{RawMessageDecoder, RawMessageEncoder}
import org.apache.avro.specific.{SpecificData, SpecificDatumReader, SpecificDatumWriter, SpecificRecordBase}
import scala.jdk.CollectionConverters.*

/** One implementation and one operation per benchmark state. Inputs are prepared outside timing.
  * Both usages return independent encoded storage and fresh models with Strings. The default
  * fresh usage preserves the original adapter. Reuse uses Wire's caller-owned BinaryOutput and
  * Avro's official raw-message helpers, returning their natural Array[Byte]/ByteBuffer results.
  * Avro's raw-message decoder accepts trailing bytes; Wire and the fresh adapter reject them.
  */
final class SuiteWorkload(
    val caseDef: SuiteCase,
    val engine: String,
    private val payloads: Array[Array[Byte]],
    val operation: String = "both",
    val usage: String = "fresh"
):
  import SuiteSupport.*
  require(payloads.nonEmpty, "Corpus must not be empty")
  require(Set("encode", "decode", "both").contains(operation), s"Unknown operation $operation")
  require(Set("fresh", "reuse").contains(usage), s"Unknown usage $usage")
  private val requestedOperations = if operation == "both" then Vector("encode", "decode") else Vector(operation)
  requestedOperations.foreach { requested =>
    require(caseDef.operations.contains(requested), s"${caseDef.id} does not support $requested")
    require(enginesFor(caseDef, requested).contains(engine),
      s"$engine does not support ${caseDef.id}/$requested: ${unsupportedReason(caseDef, engine).getOrElse("unsupported capability")}")
  }

  private val canonicalReaderSchema = SuiteSupport.readerSchema(caseDef)
  private val canonicalWriterSchema = SuiteSupport.writerSchema(caseDef)

  // Construction branches only here: another implementation's codecs/models are not prepared.
  private val implementation: Implementation = engine match
    case "wire" => new WireImplementation
    case "java-generic" => new GenericImplementation
    case "java-specific" | "java-custom" | "avro2s" => new SpecificImplementation
    case _ => throw new IllegalArgumentException(s"Unknown engine $engine")

  val readerSchema: Schema = implementation.readerSchema
  val writerSchema: Schema = implementation.writerSchema
  val corpusSize: Int = payloads.length
  private val inputs: Array[Any] =
    if operation != "decode" && caseDef.operations.contains("encode") then
      payloads.map { bytes =>
        val result = implementation.decode(bytes)
        implementation.checkModel(result)
        result
      }
    else Array.empty[Any]

  // Warm the selected reader's resolver/fast-reader caches and check its public representation.
  // JMH warmup subsequently establishes the compilation state used for measurement.
  implementation.checkModel(implementation.decode(payloads(0)))

  private var cursor = 0
  private def nextIndex(): Int =
    val index = cursor
    cursor = if index + 1 == corpusSize then 0 else index + 1
    index

  def encode(): AnyRef = implementation.encode(inputs(nextIndex()))
  def decode(): Any = implementation.decode(payloads(nextIndex()))
  def encodeAt(index: Int): AnyRef = implementation.encode(inputs(index))
  def decodeBytes(bytes: Array[Byte]): Any = implementation.decode(bytes)
  def inputAt(index: Int): Any = inputs(index)
  def payloadAt(index: Int): Array[Byte] = payloads(index)
  def checkModel(value: Any): Unit = implementation.checkModel(value)

  /** Untimed proof that the separately labelled custom engine invokes generated custom methods. */
  def verifyCustomDispatch(): (Int, Int, Int, Int) =
    require(engine == "java-custom" && caseDef.operations.contains("encode"))
    def exercise(enabled: Boolean): (Int, Int) =
      val data = specificData(enabled)
      val source = inputAt(0).asInstanceOf[SpecificRecordBase]
      val writeProbe = GeneratedProbes.create(caseDef.model)
      readerSchema.getFields.asScala.foreach(f => writeProbe.put(f.pos(), source.get(f.pos())))
      val readProbe = GeneratedProbes.create(caseDef.model)
      val result = if usage == "reuse" then
        val encoder = new RawMessageEncoder[SpecificRecordBase](data, readerSchema)
        val decoder = new RawMessageDecoder[SpecificRecordBase](data, readerSchema)
        decoder.decode(encoder.encode(writeProbe), readProbe)
      else
        val bytes = new ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
        new SpecificDatumWriter[SpecificRecordBase](readerSchema, data).write(writeProbe, encoder)
        encoder.flush()
        val decoder = DecoderFactory.get().binaryDecoder(bytes.toByteArray, null)
        val decoded = new SpecificDatumReader[SpecificRecordBase](readerSchema, readerSchema, data).read(readProbe, decoder)
        require(decoder.isEnd, "Custom dispatch probe left trailing data")
        decoded
      require(result eq readProbe)
      requireStrings(result, readerSchema, data)
      (writeProbe.encodeCalls, readProbe.decodeCalls)
    val standard = exercise(false)
    val custom = exercise(true)
    val result = (standard._1, standard._2, custom._1, custom._2)
    require(result == (0, 0, 1, 1), s"${caseDef.id} custom dispatch mismatch: $result")
    result

  /** Evolution has no encode measurement, but still proves genuine custom-reader dispatch. */
  def verifyCustomDecodeDispatch(): (Int, Int) =
    require(engine == "java-custom")
    def exercise(enabled: Boolean): Int =
      val data = specificData(enabled)
      val probe = GeneratedProbes.create(caseDef.model)
      val result = if usage == "reuse" then
        new RawMessageDecoder[SpecificRecordBase](data, writerSchema, readerSchema).decode(payloadAt(0), probe)
      else
        val decoder = DecoderFactory.get().binaryDecoder(payloadAt(0), null)
        val decoded = new SpecificDatumReader[SpecificRecordBase](writerSchema, readerSchema, data).read(probe, decoder)
        require(decoder.isEnd, "Custom evolution probe left trailing data")
        decoded
      require(result eq probe)
      requireStrings(result, readerSchema, data)
      probe.decodeCalls
    val result = (exercise(false), exercise(true))
    require(result == (0, 1), s"${caseDef.id} custom decode dispatch mismatch: $result")
    result

  private trait Implementation:
    def readerSchema: Schema
    def writerSchema: Schema
    def encode(value: Any): AnyRef
    def decode(bytes: Array[Byte]): Any
    def checkModel(value: Any): Unit

  private final class WireImplementation extends Implementation:
    private val codecClass = Class.forName(s"$namespace.wire.${caseDef.model}$$codec$$")
    private val codec = codecClass.getField("MODULE$").get(null).asInstanceOf[AvroCodec[Any]]
    private val output = if usage == "reuse" then new BinaryOutput() else null
    val readerSchema = canonicalReaderSchema
    val writerSchema = canonicalWriterSchema
    private val read: Array[Byte] => Any =
      if caseDef.kind == "evolution" then
        val resolved = ResolvingReader(writerSchema.toString, codec)
        bytes => resolved.decode(bytes)
      else bytes => codec.decode(bytes)
    def encode(value: Any): Array[Byte] =
      if usage == "reuse" then
        output.reset()
        codec.write(value, output)
        output.toByteArray
      else codec.encode(value)
    def decode(bytes: Array[Byte]): Any = read(bytes)
    def checkModel(value: Any): Unit = () // Generated Wire field types already require String.

  private abstract class JavaImplementation extends Implementation:
    def data: GenericData
    def writer: DatumWriter[Any]
    def reader: DatumReader[Any]
    def messageEncoder: RawMessageEncoder[Any]
    def messageDecoder: RawMessageDecoder[Any]
    final def encode(value: Any): AnyRef =
      if usage == "reuse" then messageEncoder.encode(value)
      else
        val output = new ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(output, null)
        writer.write(value, encoder)
        encoder.flush()
        output.toByteArray
    final def decode(bytes: Array[Byte]): Any =
      if usage == "reuse" then messageDecoder.decode(bytes)
      else
        val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
        val result = reader.read(null, decoder)
        require(decoder.isEnd, "Decoder left trailing bytes")
        result
    def checkModel(value: Any): Unit = requireStrings(value, this.readerSchema, data)

  private final class GenericImplementation extends JavaImplementation:
    val data = genericData()
    val readerSchema = stringSchema(canonicalReaderSchema)
    val writerSchema = if caseDef.kind == "evolution" then canonicalWriterSchema else readerSchema
    val writer: DatumWriter[Any] = if usage == "fresh" then new GenericDatumWriter[Any](readerSchema, data) else null
    val reader: DatumReader[Any] = if usage == "fresh" then new GenericDatumReader[Any](writerSchema, readerSchema, data) else null
    val messageEncoder = if usage == "reuse" then new RawMessageEncoder[Any](data, readerSchema) else null
    val messageDecoder = if usage == "reuse" then new RawMessageDecoder[Any](data, writerSchema, readerSchema) else null

  private final class SpecificImplementation extends JavaImplementation:
    val data: SpecificData = specificData(engine == "java-custom")
    val readerSchema = stringSchema(baselineSchema(engine, caseDef.model))
    val writerSchema =
      if caseDef.kind == "evolution" then relocated(canonicalWriterSchema, "javaavro")
      else readerSchema
    val writer: DatumWriter[Any] = if usage == "fresh" then new SpecificDatumWriter[Any](readerSchema, data) else null
    val reader: DatumReader[Any] = if usage == "fresh" then new SpecificDatumReader[Any](writerSchema, readerSchema, data) else null
    val messageEncoder = if usage == "reuse" then new RawMessageEncoder[Any](data, readerSchema) else null
    val messageDecoder = if usage == "reuse" then new RawMessageDecoder[Any](data, writerSchema, readerSchema) else null
    override def checkModel(value: Any): Unit =
      val family = if engine == "avro2s" then "avro2s" else "javaavro"
      require(value.getClass.getName == s"$namespace.$family.${caseDef.model}",
        s"$engine did not create the generated ${caseDef.model} model")
      super.checkModel(value)

object SuiteWorkload:
  def prepared(caseId: String, engine: String, operation: String = "both", usage: String = "fresh"): SuiteWorkload =
    val c = SuiteCatalog.byId(caseId)
    new SuiteWorkload(c, engine, SuiteCorpus.payloads(c), operation, usage)

  /** Verification only: JMH consumes each library's natural result without this conversion. */
  def encodedBytes(value: AnyRef): Array[Byte] = value match
    case bytes: Array[Byte] => bytes
    case buffer: ByteBuffer =>
      val copy = buffer.duplicate()
      val bytes = new Array[Byte](copy.remaining())
      copy.get(bytes)
      bytes
    case other => throw new IllegalArgumentException(s"Unexpected encoded result ${other.getClass}")
