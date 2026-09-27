package avro2s.wire.benchmarks.suite

import _root_.avro2s.wire.runtime.{AvroInput, BinaryOutput, MalformedStringPolicy, WriterSettings}
import _root_.avro2s.wire.javabackend.{JavaAvroInput, JavaAvroOutput}
import _root_.avro2s.wire.resolution.ResolvingReader
import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter}
import org.apache.avro.io.{BinaryEncoder, DatumReader, DatumWriter, DecoderFactory, EncoderFactory}
import org.apache.avro.message.{RawMessageDecoder, RawMessageEncoder}
import org.apache.avro.specific.{SpecificData, SpecificDatumReader, SpecificDatumWriter, SpecificRecordBase}
import scala.jdk.CollectionConverters.*

/** One implementation and one operation per benchmark state. Inputs are prepared outside timing.
  * Both usages return independent encoded storage and fresh models with Strings. The default
  * fresh usage preserves the original adapter. Java API and encoder choices are explicit and
  * independent of factory IO lifetime. Raw-message helpers require reuse/unbuffered and return
  * owned ByteBuffers; factory encoders return owned arrays. Only raw decoding accepts trailing
  * bytes. The auto defaults exist for existing Scala callers; the runner passes resolved values.
  */
final class SuiteWorkload(
    val caseDef: SuiteCase,
    val engine: String,
    private val payloads: Array[Array[Byte]],
    val operation: String = "both",
    val usage: String = "fresh",
    val wireStringPolicy: String = "reject",
    javaApi: String = "auto",
    javaEncoder: String = "auto"
):
  import SuiteSupport.*
  require(payloads.nonEmpty, "Corpus must not be empty")
  require(Set("encode", "decode", "both").contains(operation), s"Unknown operation $operation")
  require(Set("fresh", "reuse").contains(usage), s"Unknown usage $usage")
  require(Set("reject", "replace").contains(wireStringPolicy), s"Unknown Wire string policy $wireStringPolicy")
  private val nativeWire = engine == "wire" || engine == "wire-stack-safe"
  private val javaBackedWire = engine == "wire-java" || engine == "wire-java-stack-safe"
  val effectiveJavaApi: String =
    if javaApi != "auto" then javaApi
    else if nativeWire then "none"
    else if javaBackedWire || usage == "fresh" then "factory"
    else "raw-message"
  val effectiveJavaEncoder: String =
    if javaEncoder != "auto" then javaEncoder
    else if nativeWire then "none"
    else if effectiveJavaApi == "raw-message" then "unbuffered"
    else "buffered"
  require(Set("none", "factory", "raw-message").contains(effectiveJavaApi), s"Unknown Java API $javaApi")
  require(Set("none", "buffered", "unbuffered").contains(effectiveJavaEncoder), s"Unknown Java encoder $javaEncoder")
  if nativeWire then
    require(effectiveJavaApi == "none" && effectiveJavaEncoder == "none", "Native Wire does not use Java API or encoder settings")
  else
    require(effectiveJavaApi != "none" && effectiveJavaEncoder != "none", "Java engines require a Java API and encoder")
    require(!javaBackedWire || effectiveJavaApi == "factory", "Wire's Java backend uses the factory API only")
    require(effectiveJavaApi != "raw-message" || (usage == "reuse" && effectiveJavaEncoder == "unbuffered"),
      "Raw-message API requires reuse usage and an unbuffered encoder")
  require(engine != "avro2s" || (usage == "fresh" && effectiveJavaApi == "factory"), "avro2s supports fresh factory usage only")
  private val requestedOperations = if operation == "both" then Vector("encode", "decode") else Vector(operation)
  requestedOperations.foreach { requested =>
    require(caseDef.operations.contains(requested), s"${caseDef.id} does not support $requested")
    require(enginesFor(caseDef, requested).contains(engine),
      s"$engine does not support ${caseDef.id}/$requested: ${unsupportedReason(caseDef, engine).getOrElse("unsupported capability")}")
  }

  private val rawMessageApi = effectiveJavaApi == "raw-message"
  private val selectedEncoderFactory = EncoderFactory.get()
  private val createFactoryEncoder: (OutputStream, BinaryEncoder) => BinaryEncoder =
    effectiveJavaEncoder match
      case "buffered" => selectedEncoderFactory.binaryEncoder
      case "unbuffered" => selectedEncoderFactory.directBinaryEncoder
      case _ => (_, _) => throw new IllegalStateException("Native Wire has no Java encoder")

  private val canonicalReaderSchema = SuiteSupport.readerSchema(caseDef)
  private val canonicalWriterSchema = SuiteSupport.writerSchema(caseDef)

  // Construction branches only here: another implementation's codecs/models are not prepared.
  private val implementation: Implementation = engine match
    case "wire" | "wire-stack-safe" => new WireImplementation
    case "wire-java" | "wire-java-stack-safe" => new WireJavaImplementation
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
  private[suite] def encodeValue(value: Any): AnyRef = implementation.encode(value)
  def inputAt(index: Int): Any = inputs(index)
  def payloadAt(index: Int): Array[Byte] = payloads(index)
  def checkModel(value: Any): Unit = implementation.checkModel(value)

  /** The actual factory selection, also exposed to untimed dispatch verification. */
  private[suite] def factoryEncoder(output: OutputStream, reuse: BinaryEncoder): BinaryEncoder =
    createFactoryEncoder(output, reuse)

  /** Untimed proof that the separately labelled custom engine invokes generated custom methods. */
  def verifyCustomDispatch(): (Int, Int, Int, Int) =
    require(engine == "java-custom" && caseDef.operations.contains("encode"))
    def exercise(enabled: Boolean): (Int, Int) =
      val data = specificData(enabled)
      val source = inputAt(0).asInstanceOf[SpecificRecordBase]
      val writeProbe = GeneratedProbes.create(caseDef.model)
      readerSchema.getFields.asScala.foreach(f => writeProbe.put(f.pos(), source.get(f.pos())))
      val readProbe = GeneratedProbes.create(caseDef.model)
      val result = if rawMessageApi then
        val encoder = new RawMessageEncoder[SpecificRecordBase](data, readerSchema)
        val decoder = new RawMessageDecoder[SpecificRecordBase](data, readerSchema)
        decoder.decode(encoder.encode(writeProbe), readProbe)
      else
        val bytes = new ByteArrayOutputStream()
        val previousEncoder = if usage == "reuse" then factoryEncoder(bytes, null) else null
        val encoder = factoryEncoder(bytes, previousEncoder)
        new SpecificDatumWriter[SpecificRecordBase](readerSchema, data).write(writeProbe, encoder)
        encoder.flush()
        val previousDecoder = if usage == "reuse" then DecoderFactory.get().binaryDecoder(Array.emptyByteArray, null) else null
        val decoder = DecoderFactory.get().binaryDecoder(bytes.toByteArray, previousDecoder)
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
      val result = if rawMessageApi then
        new RawMessageDecoder[SpecificRecordBase](data, writerSchema, readerSchema).decode(payloadAt(0), probe)
      else
        val previousDecoder = if usage == "reuse" then DecoderFactory.get().binaryDecoder(Array.emptyByteArray, null) else null
        val decoder = DecoderFactory.get().binaryDecoder(payloadAt(0), previousDecoder)
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
    private val codec = wireCodec(caseDef, engine)
    private val settings = WriterSettings(malformedStrings = wireStringPolicy match
      case "reject" => MalformedStringPolicy.Reject
      case "replace" => MalformedStringPolicy.Replace
    )
    private val output = if usage == "reuse" then new BinaryOutput(settings = settings) else null
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
      else codec.encode(value, settings)
    def decode(bytes: Array[Byte]): Any = read(bytes)
    def checkModel(value: Any): Unit = () // Generated Wire field types already require String.

  /** The same generated immutable models and codec traversal, using the public Java IO backend.
    * Its string policy is Java's replacement behavior, independent of the native writer setting.
    * Reuse reconfigures the selected official encoder/array decoder and retains the adapters.
    */
  private final class WireJavaImplementation extends Implementation:
    private val codec = wireCodec(caseDef, engine)
    val readerSchema = canonicalReaderSchema
    val writerSchema = canonicalWriterSchema
    private val read: AvroInput => Any =
      if caseDef.kind == "evolution" then ResolvingReader(writerSchema.toString, codec).read
      else codec.read
    private val decoderFactory = DecoderFactory.get()
    private val output = if usage == "reuse" then new ByteArrayOutputStream() else null
    private var encoder = if usage == "reuse" then factoryEncoder(output, null) else null
    private var avroOutput = if usage == "reuse" then new JavaAvroOutput(encoder) else null
    private var decoder = if usage == "reuse" then decoderFactory.binaryDecoder(Array.emptyByteArray, null) else null
    private var avroInput = if usage == "reuse" then new JavaAvroInput(decoder) else null

    def encode(value: Any): Array[Byte] =
      if usage == "reuse" then
        val configured = factoryEncoder(output, encoder)
        if !(configured eq encoder) then
          encoder = configured
          avroOutput = new JavaAvroOutput(encoder)
        // Reconfiguration may flush a prior failed write; discard it before the next datum.
        output.reset()
        codec.write(value, avroOutput)
        encoder.flush()
        output.toByteArray
      else
        val bytes = new ByteArrayOutputStream()
        val freshEncoder = factoryEncoder(bytes, null)
        codec.write(value, new JavaAvroOutput(freshEncoder))
        freshEncoder.flush()
        bytes.toByteArray

    def decode(bytes: Array[Byte]): Any =
      if usage == "reuse" then
        val configured = decoderFactory.binaryDecoder(bytes, decoder)
        if !(configured eq decoder) then
          decoder = configured
          avroInput = new JavaAvroInput(decoder)
        val value = read(avroInput)
        require(decoder.isEnd, "Decoder left trailing bytes")
        value
      else
        val freshDecoder = decoderFactory.binaryDecoder(bytes, null)
        val value = read(new JavaAvroInput(freshDecoder))
        require(freshDecoder.isEnd, "Decoder left trailing bytes")
        value

    def checkModel(value: Any): Unit = () // These are the same generated Wire model types.

  private abstract class JavaImplementation extends Implementation:
    def data: GenericData
    def writer: DatumWriter[Any]
    def reader: DatumReader[Any]
    def messageEncoder: RawMessageEncoder[Any]
    def messageDecoder: RawMessageDecoder[Any]
    private val output = if effectiveJavaApi == "factory" && usage == "reuse" then new ByteArrayOutputStream() else null
    private var encoder = if output != null then factoryEncoder(output, null) else null
    private var decoder = if output != null then DecoderFactory.get().binaryDecoder(Array.emptyByteArray, null) else null
    final def encode(value: Any): AnyRef =
      if rawMessageApi then messageEncoder.encode(value)
      else if usage == "reuse" then
        // Reconfiguration flushes any pending bytes from a failed write before reset discards them.
        encoder = factoryEncoder(output, encoder)
        output.reset()
        writer.write(value, encoder)
        encoder.flush()
        output.toByteArray
      else
        val output = new ByteArrayOutputStream()
        val encoder = factoryEncoder(output, null)
        writer.write(value, encoder)
        encoder.flush()
        output.toByteArray
    final def decode(bytes: Array[Byte]): Any =
      if rawMessageApi then messageDecoder.decode(bytes)
      else if usage == "reuse" then
        decoder = DecoderFactory.get().binaryDecoder(bytes, decoder)
        val result = reader.read(null, decoder)
        require(decoder.isEnd, "Decoder left trailing bytes")
        result
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
    val writer: DatumWriter[Any] = if effectiveJavaApi == "factory" then new GenericDatumWriter[Any](readerSchema, data) else null
    val reader: DatumReader[Any] = if effectiveJavaApi == "factory" then new GenericDatumReader[Any](writerSchema, readerSchema, data) else null
    val messageEncoder = if rawMessageApi then new RawMessageEncoder[Any](data, readerSchema) else null
    val messageDecoder = if rawMessageApi then new RawMessageDecoder[Any](data, writerSchema, readerSchema) else null

  private final class SpecificImplementation extends JavaImplementation:
    val data: SpecificData = specificData(engine == "java-custom")
    val readerSchema = stringSchema(baselineSchema(engine, caseDef.model))
    val writerSchema =
      if caseDef.kind == "evolution" then relocated(canonicalWriterSchema, "javaavro")
      else readerSchema
    val writer: DatumWriter[Any] = if effectiveJavaApi == "factory" then new SpecificDatumWriter[Any](readerSchema, data) else null
    val reader: DatumReader[Any] = if effectiveJavaApi == "factory" then new SpecificDatumReader[Any](writerSchema, readerSchema, data) else null
    val messageEncoder = if rawMessageApi then new RawMessageEncoder[Any](data, readerSchema) else null
    val messageDecoder = if rawMessageApi then new RawMessageDecoder[Any](data, writerSchema, readerSchema) else null
    override def checkModel(value: Any): Unit =
      val family = if engine == "avro2s" then "avro2s" else "javaavro"
      require(value.getClass.getName == s"$namespace.$family.${caseDef.model}",
        s"$engine did not create the generated ${caseDef.model} model")
      super.checkModel(value)

object SuiteWorkload:
  def prepared(caseId: String, engine: String, operation: String = "both", usage: String = "fresh",
      wireStringPolicy: String = "reject", javaApi: String = "auto", javaEncoder: String = "auto"): SuiteWorkload =
    val c = SuiteCatalog.byId(caseId)
    new SuiteWorkload(c, engine, SuiteCorpus.payloads(c), operation, usage, wireStringPolicy, javaApi, javaEncoder)

  /** Verification only: JMH consumes each library's natural result without this conversion. */
  def encodedBytes(value: AnyRef): Array[Byte] = value match
    case bytes: Array[Byte] => bytes
    case buffer: ByteBuffer =>
      val copy = buffer.duplicate()
      val bytes = new Array[Byte](copy.remaining())
      copy.get(bytes)
      bytes
    case other => throw new IllegalArgumentException(s"Unexpected encoded result ${other.getClass}")
