package avro2s.wire.benchmarks

import _root_.avro2s.wire.benchmarks.avro2s.{Trade as ScalaTrade}
import _root_.avro2s.wire.benchmarks.javaavro.{Trade as JavaTrade}
import _root_.avro2s.wire.fixtures.Trade
import _root_.avro2s.wire.javabackend.{JavaAvroInput, JavaAvroOutput}
import _root_.avro2s.wire.runtime.{BinaryInput, BinaryOutput}
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{BinaryEncoder, DecoderFactory, Encoder, EncoderFactory, ResolvingDecoder}
import org.apache.avro.specific.{SpecificData, SpecificDatumReader, SpecificDatumWriter}
import org.openjdk.jmh.annotations.*
import scala.jdk.CollectionConverters.*

/**
 * Six implementations of the same raw Avro record layout. Every ordinary read
 * creates a fresh decoder and result; no datum or decoder reuse is hidden in a
 * baseline. Writers reuse buffers and encoders and return the byte count without
 * a final output copy. Fixture construction and schema parsing are outside timing.
 *
 * Native and Java-primitives paths produce the same immutable Scala model.
 * Avro2s produces its generated Scala model; Java specific/custom paths produce
 * genuine default generated Java records; generic produces GenericRecord.
 * Collection representations differ (Vector, List, and Java List). Default Java
 * strings decode as Utf8/CharSequence, Scala strings as String. All writer inputs
 * use the same String, with no pre-encoded Utf8 advantage. Native decoding keeps
 * UTF-8 validation and resource limits. These are complete implementation costs.
 * Standard Java, avro2s, and generic paths retain Avro 1.12.1's fast reader.
 * The custom path disables that reader so generated customDecode is exercised.
 *
 * Fixture namespaces differ to let all generated classes coexist. Cross-checks
 * use their identical raw field layout, not writer/reader schema evolution.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class TradeBenchmark:
  @Param(Array("0", "32", "1024"))
  var collectionSize: Int = 0

  private var workload: BenchmarkWorkload = null

  @Setup(Level.Trial)
  def setup(): Unit =
    workload = new BenchmarkWorkload(collectionSize)
    workload.verifyInteroperability()
    BenchmarkWorkload.verifyCustomCoderDispatch()

  @Benchmark def nativeWrite(): Int = workload.nativeWrite()
  @Benchmark def nativeRead(): Trade = workload.nativeRead()
  @Benchmark def javaPrimitivesWrite(): Int = workload.javaPrimitivesWrite()
  @Benchmark def javaPrimitivesRead(): Trade = workload.javaPrimitivesRead()
  @Benchmark def javaGenericWrite(): Int = workload.javaGenericWrite()
  @Benchmark def javaGenericRead(): GenericRecord = workload.javaGenericRead()
  @Benchmark def javaSpecificWrite(): Int = workload.javaSpecificWrite()
  @Benchmark def javaSpecificRead(): JavaTrade = workload.javaSpecificRead()
  @Benchmark def javaCustomWrite(): Int = workload.javaCustomWrite()
  @Benchmark def javaCustomRead(): JavaTrade = workload.javaCustomRead()
  @Benchmark def avro2sWrite(): Int = workload.avro2sWrite()
  @Benchmark def avro2sRead(): ScalaTrade = workload.avro2sRead()

/** Shared benchmark state with explicit verification APIs for regression tests. */
final class BenchmarkWorkload(val collectionSize: Int):
  import BenchmarkWorkload.*

  require(collectionSize >= 0, "collectionSize must be non-negative")
  val expected: Trade = Trade(
    id = 1000000001L,
    symbol = "AVRO-λ",
    price = 123.456,
    // Outside the usual Integer cache so fresh collection decodes allocate
    // boxed values instead of obtaining nearly every element from that cache.
    quantities = Vector.tabulate(collectionSize)(index => 10000 + (index % 257))
  )

  private val nativeSchema = new Schema.Parser().parse(Trade.codec.schemaJson)
  private val javaSchema = JavaTrade.getClassSchema()
  private val scalaSchema = ScalaTrade.SCHEMA$
  private val standardData = specificData(customCoders = false)
  private val customData = specificData(customCoders = true)
  private val scalaData = specificData(customCoders = false)
  private val genericData = new GenericData().setFastReaderEnabled(true)

  private val javaValue = javaTrade(expected)
  private val scalaValue = ScalaTrade(expected.id, expected.symbol, expected.price, expected.quantities.toList)
  private val genericValue: GenericRecord =
    val record = new GenericData.Record(nativeSchema)
    record.put("id", expected.id)
    record.put("symbol", expected.symbol)
    record.put("price", expected.price)
    record.put("quantities", javaQuantities(expected.quantities))
    record

  private val capacity = 128 + collectionSize * 5
  private val nativeOutput = new BinaryOutput(capacity)
  private val primitives = new JavaOutput(capacity)
  private val generic = new JavaOutput(capacity)
  private val specific = new JavaOutput(capacity)
  private val custom = new JavaOutput(capacity)
  private val scalaSpecific = new JavaOutput(capacity)
  private val primitiveOutput = new JavaAvroOutput(primitives.encoder)
  private val genericWriter = new GenericDatumWriter[GenericRecord](nativeSchema, genericData)
  private val genericReader = new GenericDatumReader[GenericRecord](nativeSchema, nativeSchema, genericData)
  private val specificWriter = new SpecificDatumWriter[JavaTrade](javaSchema, standardData)
  private val specificReader = new SpecificDatumReader[JavaTrade](javaSchema, javaSchema, standardData)
  private val customWriter = new SpecificDatumWriter[JavaTrade](javaSchema, customData)
  private val customReader = new SpecificDatumReader[JavaTrade](javaSchema, javaSchema, customData)
  private val scalaWriter = new SpecificDatumWriter[ScalaTrade](scalaSchema, scalaData)
  // Explicit schemas avoid depending on a static SCHEMA$ field in Scala output.
  private val scalaReader = new SpecificDatumReader[ScalaTrade](scalaSchema, scalaSchema, scalaData)

  private val payload: Array[Byte] =
    nativeWrite()
    nativeOutput.toByteArray

  def nativeWrite(): Int =
    nativeOutput.reset()
    Trade.codec.write(expected, nativeOutput)
    nativeOutput.size

  def javaPrimitivesWrite(): Int =
    primitives.bytes.reset()
    Trade.codec.write(expected, primitiveOutput)
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
    custom.bytes.reset()
    customWriter.write(javaValue, custom.encoder)
    custom.finish()

  def avro2sWrite(): Int =
    scalaSpecific.bytes.reset()
    scalaWriter.write(scalaValue, scalaSpecific.encoder)
    scalaSpecific.finish()

  // Every reader sees the same bytes. End-of-input is checked during setup,
  // outside timing, for all paths. Java datum readers always receive null reuse.
  def nativeRead(): Trade = Trade.codec.read(new BinaryInput(payload))

  def javaPrimitivesRead(): Trade =
    Trade.codec.read(new JavaAvroInput(DecoderFactory.get().binaryDecoder(payload, null)))

  def javaGenericRead(): GenericRecord =
    genericReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))

  def javaSpecificRead(): JavaTrade =
    specificReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))

  def javaCustomRead(): JavaTrade =
    customReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))

  def avro2sRead(): ScalaTrade =
    scalaReader.read(null, DecoderFactory.get().binaryDecoder(payload, null))

  /** Owned payload snapshots for setup and tests; never called in timed code. */
  def encodedByEveryWriter(): Vector[(String, Array[Byte])] =
    nativeWrite()
    javaPrimitivesWrite()
    javaGenericWrite()
    javaSpecificWrite()
    javaCustomWrite()
    avro2sWrite()
    Vector(
      "native" -> nativeOutput.toByteArray,
      "javaPrimitives" -> primitives.bytes.toByteArray,
      "javaGeneric" -> generic.bytes.toByteArray,
      "javaSpecific" -> specific.bytes.toByteArray,
      "javaCustom" -> custom.bytes.toByteArray,
      "avro2s" -> scalaSpecific.bytes.toByteArray
    )

  /** Decode one raw payload in every implementation and normalize outside timing. */
  def decodedByEveryReader(encoded: Array[Byte]): Vector[(String, Trade)] =
    def javaRead[A](name: String)(read: org.apache.avro.io.Decoder => A)(normalize: A => Trade): (String, Trade) =
      val decoder = DecoderFactory.get().binaryDecoder(encoded, null)
      val result = normalize(read(decoder))
      require(decoder.isEnd, s"$name reader left trailing data")
      name -> result

    Vector(
      "native" -> Trade.codec.decode(encoded),
      javaRead("javaPrimitives")(decoder => Trade.codec.read(new JavaAvroInput(decoder)))(identity),
      javaRead("javaGeneric")(decoder => genericReader.read(null, decoder))(fromGeneric),
      javaRead("javaSpecific")(decoder => specificReader.read(null, decoder))(fromJava),
      javaRead("javaCustom")(decoder => customReader.read(null, decoder))(fromJava),
      javaRead("avro2s")(decoder => scalaReader.read(null, decoder))(fromScala)
    )

  def verifyInteroperability(): Unit =
    require(!standardData.useCustomCoders(), "Standard Java baseline enabled custom coders")
    require(customData.useCustomCoders(), "Custom Java baseline disabled custom coders")
    require(!scalaData.useCustomCoders(), "Avro2s baseline enabled custom coders")
    require(standardData.isFastReaderEnabled(), "Standard Java baseline disabled fast reader")
    require(scalaData.isFastReaderEnabled(), "Avro2s baseline disabled fast reader")
    require(genericData.isFastReaderEnabled(), "Generic baseline disabled fast reader")
    require(!customData.isFastReaderEnabled(), "Fast reader would bypass generated customDecode")
    encodedByEveryWriter().foreach { (writer, encoded) =>
      decodedByEveryReader(encoded).foreach { (reader, actual) =>
        require(actual == expected, s"$writer -> $reader interoperability check failed")
      }
    }

object BenchmarkWorkload:
  private final class JavaOutput(capacity: Int):
    val bytes = new ByteArrayOutputStream(capacity)
    val encoder: BinaryEncoder = EncoderFactory.get().binaryEncoder(bytes, null)
    def finish(): Int =
      encoder.flush()
      bytes.size()

  private def specificData(customCoders: Boolean): SpecificData =
    val data = new SpecificData(classOf[JavaTrade].getClassLoader)
    // Override both branches explicitly, regardless of JVM-global properties.
    data.setCustomCoders(customCoders)
    // GenericDatumReader's fast-reader branch bypasses customDecode in 1.12.1.
    // Preserve the default fast reader in the ordinary baseline, and disable
    // it only when the profile explicitly requests generated custom coders.
    data.setFastReaderEnabled(!customCoders)
    data

  private def javaQuantities(values: Vector[Int]): java.util.List[Integer] =
    val result = new java.util.ArrayList[Integer](values.size)
    values.foreach(value => result.add(Integer.valueOf(value)))
    java.util.Collections.unmodifiableList(result)

  private def javaTrade(value: Trade): JavaTrade =
    new JavaTrade(value.id, value.symbol, value.price, javaQuantities(value.quantities))

  def fromJava(value: JavaTrade): Trade =
    Trade(value.getId(), value.getSymbol().toString, value.getPrice(),
      value.getQuantities().asScala.iterator.map(_.intValue()).toVector)

  def fromScala(value: ScalaTrade): Trade =
    Trade(value.id, value.symbol, value.price, value.quantities.toVector)

  def fromGeneric(value: GenericRecord): Trade =
    Trade(
      value.get("id").asInstanceOf[java.lang.Long].longValue(),
      value.get("symbol").toString,
      value.get("price").asInstanceOf[java.lang.Double].doubleValue(),
      value.get("quantities").asInstanceOf[java.util.Collection[Integer]]
        .asScala.iterator.map(_.intValue()).toVector
    )

  final case class CustomCoderDispatch(
      standardEncodeCalls: Int,
      standardDecodeCalls: Int,
      customEncodeCalls: Int,
      customDecodeCalls: Int
  )

  /** Counters exist only in setup/tests, never in a timed generated record. */
  private final class CountingTrade extends JavaTrade:
    var encodeCalls = 0
    var decodeCalls = 0
    override def customEncode(out: Encoder): Unit =
      encodeCalls += 1
      super.customEncode(out)
    override def customDecode(in: ResolvingDecoder): Unit =
      decodeCalls += 1
      super.customDecode(in)

  /** Verify actual generated custom method dispatch, rather than just its flag. */
  def verifyCustomCoderDispatch(): CustomCoderDispatch =
    val schema = JavaTrade.getClassSchema()
    def exercise(enabled: Boolean): (Int, Int) =
      val data = specificData(enabled)
      require(data.isFastReaderEnabled() == !enabled, "Unexpected fast reader setting")
      val writer = new SpecificDatumWriter[JavaTrade](schema, data)
      val reader = new SpecificDatumReader[JavaTrade](schema, schema, data)
      val probe = new CountingTrade()
      probe.setId(1000000001L)
      probe.setSymbol("AVRO-λ")
      probe.setPrice(123.456)
      probe.setQuantities(javaQuantities(Vector(10000, 10256)))
      val bytes = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
      writer.write(probe, encoder)
      encoder.flush()
      val readProbe = new CountingTrade()
      val decoder = DecoderFactory.get().binaryDecoder(bytes.toByteArray, null)
      val decoded = reader.read(readProbe, decoder)
      require(decoded eq readProbe, "Dispatch probe was not reused by Java Avro")
      require(fromJava(decoded) == fromJava(probe), "Dispatch probe data mismatch")
      require(decoder.isEnd, "Dispatch probe left trailing data")
      (probe.encodeCalls, readProbe.decodeCalls)
    val standard = exercise(enabled = false)
    val custom = exercise(enabled = true)
    val result = CustomCoderDispatch(standard._1, standard._2, custom._1, custom._2)
    require(result == CustomCoderDispatch(0, 0, 1, 1), s"Unexpected generated custom coder dispatch: $result")
    result
