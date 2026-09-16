package avrogen.benchmarks

import avrogen.fixtures.Trade
import avrogen.interop.{JavaAvroInput, JavaAvroOutput}
import avrogen.runtime.{BinaryInput, BinaryOutput}
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{BinaryEncoder, DecoderFactory, EncoderFactory}
import org.openjdk.jmh.annotations.*
import scala.jdk.CollectionConverters.*

/**
 * One generated Scala model through native and Java binary primitives, plus
 * Java Avro's generic-record baseline. The generic baseline constructs a
 * different result representation; it is not tuned Java specific-record Avro
 * or avro2s and cannot establish performance against those implementations.
 *
 * Writers reuse their buffers and return the byte count without an output
 * copy. Readers all start with a fresh decoder and construct fresh values;
 * the Java primitives reader also allocates its adapter. Schema parsing and
 * application-value construction happen outside the measured operations.
 * Native decoder validation remains enabled.
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

  private var value: Trade = null
  private var genericValue: GenericRecord = null
  private var payload: Array[Byte] = null
  private var nativeOutput: BinaryOutput = null
  private var primitiveBytes: ByteArrayOutputStream = null
  private var genericBytes: ByteArrayOutputStream = null
  private var primitiveEncoder: BinaryEncoder = null
  private var genericEncoder: BinaryEncoder = null
  private var primitiveOutput: JavaAvroOutput = null
  private var genericWriter: GenericDatumWriter[GenericRecord] = null
  private var genericReader: GenericDatumReader[GenericRecord] = null

  @Setup(Level.Trial)
  def setup(): Unit =
    value = Trade(
      id = 1000000001L,
      symbol = "AVRO-λ",
      price = 123.456,
      quantities = Vector.tabulate(collectionSize)(index => (index % 257) - 128)
    )
    val schema = new Schema.Parser().parse(Trade.codec.schemaJson)
    genericValue = new GenericData.Record(schema)
    genericValue.put("id", value.id)
    genericValue.put("symbol", value.symbol)
    genericValue.put("price", value.price)
    val quantities = new java.util.ArrayList[Integer](collectionSize)
    value.quantities.foreach(quantity => quantities.add(Integer.valueOf(quantity)))
    // Java's generic datum API requires a Java collection. Keep its fixture
    // read-only after setup just like the Scala value.
    genericValue.put("quantities", java.util.Collections.unmodifiableList(quantities))

    val capacity = 128 + collectionSize * 5
    nativeOutput = new BinaryOutput(capacity)
    primitiveBytes = new ByteArrayOutputStream(capacity)
    genericBytes = new ByteArrayOutputStream(capacity)
    primitiveEncoder = EncoderFactory.get().binaryEncoder(primitiveBytes, null)
    genericEncoder = EncoderFactory.get().binaryEncoder(genericBytes, null)
    primitiveOutput = new JavaAvroOutput(primitiveEncoder)
    genericWriter = new GenericDatumWriter[GenericRecord](schema)
    genericReader = new GenericDatumReader[GenericRecord](schema)

    nativeWrite()
    val nativePayload = nativeOutput.toByteArray
    javaPrimitivesWrite()
    val primitivePayload = primitiveBytes.toByteArray
    javaGenericWrite()
    val genericPayload = genericBytes.toByteArray

    // Check every writer against every reader before accepting timed work.
    // Do not require identical block partitioning in their encoded bytes.
    Seq(nativePayload, primitivePayload, genericPayload).foreach { encoded =>
      require(Trade.codec.decode(encoded) == value, "Native interoperability check failed")
      val primitiveDecoder = DecoderFactory.get().binaryDecoder(encoded, null)
      val viaPrimitives = Trade.codec.read(new JavaAvroInput(primitiveDecoder))
      require(viaPrimitives == value, "Java primitives interoperability check failed")
      require(primitiveDecoder.isEnd, "Java primitives reader left trailing data")
      val genericDecoder = DecoderFactory.get().binaryDecoder(encoded, null)
      val viaGeneric = genericReader.read(null, genericDecoder)
      require(asTrade(viaGeneric) == value, "Java generic interoperability check failed")
      require(genericDecoder.isEnd, "Java generic reader left trailing data")
    }
    payload = nativePayload

  private def asTrade(record: GenericRecord): Trade =
    Trade(
      record.get("id").asInstanceOf[java.lang.Long].longValue(),
      record.get("symbol").toString,
      record.get("price").asInstanceOf[java.lang.Double].doubleValue(),
      record.get("quantities").asInstanceOf[java.util.Collection[Integer]]
        .asScala.iterator.map(_.intValue()).toVector
    )

  @Benchmark
  def nativeWrite(): Int =
    nativeOutput.reset()
    Trade.codec.write(value, nativeOutput)
    nativeOutput.size

  @Benchmark
  def javaPrimitivesWrite(): Int =
    primitiveBytes.reset()
    Trade.codec.write(value, primitiveOutput)
    primitiveEncoder.flush()
    primitiveBytes.size()

  @Benchmark
  def javaGenericWrite(): Int =
    genericBytes.reset()
    genericWriter.write(genericValue, genericEncoder)
    genericEncoder.flush()
    genericBytes.size()

  // Payload completeness was checked in setup. None of the measured readers
  // adds an end-of-input check beyond reading the record itself.
  @Benchmark
  def nativeRead(): Trade =
    Trade.codec.read(new BinaryInput(payload))

  @Benchmark
  def javaPrimitivesRead(): Trade =
    val decoder = DecoderFactory.get().binaryDecoder(payload, null)
    Trade.codec.read(new JavaAvroInput(decoder))

  @Benchmark
  def javaGenericRead(): GenericRecord =
    val decoder = DecoderFactory.get().binaryDecoder(payload, null)
    genericReader.read(null, decoder)
