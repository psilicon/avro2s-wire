package avro2s.wire.benchmarks

import _root_.avro2s.wire.runtime.{BinaryInput, BinaryOutput, LogicalValues}
import java.io.ByteArrayOutputStream
import java.math.{BigDecimal as JavaDecimal, BigInteger}
import java.util.concurrent.TimeUnit
import org.apache.avro.{Conversions, LogicalTypes, Schema}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import org.openjdk.jmh.annotations.*

/** One complete big-decimal logical value, including its enclosing bytes field.
  * "Java mapping" is java.math.BigDecimal on the native Wire runtime, not the
  * optional Java primitive backend. The Avro baseline performs logical conversion.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = Array("-Xms512m", "-Xmx512m"))
@Threads(1)
class BigDecimalBenchmark:
  @Param(Array("6", "50", "500"))
  var digits: Int = 0

  @Param(Array("0", "6", "-6"))
  var scale: Int = 0

  private var workload: BigDecimalWorkload = null

  @Setup(Level.Trial)
  def setup(): Unit =
    workload = new BigDecimalWorkload(digits, scale)
    workload.verifyInteroperability()

  @Benchmark def nativeScalaMappingRead(): BigDecimal = workload.nativeScalaMappingRead()
  @Benchmark def nativeScalaMappingWrite(): Int = workload.nativeScalaMappingWrite()
  @Benchmark def nativeJavaMappingRead(): JavaDecimal = workload.nativeJavaMappingRead()
  @Benchmark def nativeJavaMappingWrite(): Int = workload.nativeJavaMappingWrite()
  @Benchmark def avroJavaConversionRead(): JavaDecimal = workload.avroJavaConversionRead()
  @Benchmark def avroJavaConversionWrite(): Int = workload.avroJavaConversionWrite()

private[benchmarks] final class BigDecimalWorkload(digits: Int, scale: Int):
  require(digits > 0)
  val javaValues: Array[JavaDecimal] = Array.tabulate(16) { sample =>
    // All magnitudes have the requested decimal precision; half are negative.
    // No random generation, parsing, rounding or rescaling is timed.
    val magnitude = (0 until digits).map { position =>
      if position == 0 then ('1' + sample % 9).toChar
      else ('0' + (sample * 7 + position * 3) % 10).toChar
    }.mkString
    val integer = new BigInteger(if sample % 2 == 0 then magnitude else "-" + magnitude)
    new JavaDecimal(integer, scale)
  }
  val scalaValues: Array[BigDecimal] = javaValues.map(BigDecimal.exact)

  private val logicalType = LogicalTypes.bigDecimal()
  private val schema = logicalType.addToSchema(Schema.create(Schema.Type.BYTES))
  private val conversion = new Conversions.BigDecimalConversion()
  private val decoderFactory = DecoderFactory.get()
  private val scalaOutput = new BinaryOutput(1024)
  private val javaMappingOutput = new BinaryOutput(1024)
  private val avroOutput = new ByteArrayOutputStream(1024)
  private val avroEncoder = EncoderFactory.get().binaryEncoder(avroOutput, null)
  private var cursor = 0

  // Every reader uses the same immutable corpus produced by official Avro.
  val encodedValues: Array[Array[Byte]] = javaValues.map { value =>
    writeAvro(value)
    avroOutput.toByteArray
  }

  private def nextIndex(): Int =
    val index = cursor
    cursor = (cursor + 1) & 15
    index

  def readScala(bytes: Array[Byte]): BigDecimal =
    LogicalValues.readBigDecimal(new BinaryInput(bytes))

  def readJavaMapping(bytes: Array[Byte]): JavaDecimal =
    LogicalValues.readJavaBigDecimal(new BinaryInput(bytes))

  def readAvro(bytes: Array[Byte]): JavaDecimal =
    val decoder = decoderFactory.binaryDecoder(bytes, null)
    conversion.fromBytes(decoder.readBytes(null), schema, logicalType)

  private def writeScala(value: BigDecimal): Int =
    scalaOutput.reset()
    LogicalValues.writeBigDecimal(value, scalaOutput)
    scalaOutput.size

  private def writeJavaMapping(value: JavaDecimal): Int =
    javaMappingOutput.reset()
    LogicalValues.writeJavaBigDecimal(value, javaMappingOutput)
    javaMappingOutput.size

  private def writeAvro(value: JavaDecimal): Int =
    avroOutput.reset()
    avroEncoder.writeBytes(conversion.toBytes(value, schema, logicalType))
    avroEncoder.flush()
    avroOutput.size()

  def nativeScalaMappingRead(): BigDecimal = readScala(encodedValues(nextIndex()))
  def nativeJavaMappingRead(): JavaDecimal = readJavaMapping(encodedValues(nextIndex()))
  def avroJavaConversionRead(): JavaDecimal = readAvro(encodedValues(nextIndex()))
  def nativeScalaMappingWrite(): Int = writeScala(scalaValues(nextIndex()))
  def nativeJavaMappingWrite(): Int = writeJavaMapping(javaValues(nextIndex()))
  def avroJavaConversionWrite(): Int = writeAvro(javaValues(nextIndex()))

  // Snapshot copies are only for untimed verification, never timed write paths.
  def encodedByEveryWriter(index: Int): Vector[(String, Array[Byte])] =
    writeScala(scalaValues(index))
    writeJavaMapping(javaValues(index))
    writeAvro(javaValues(index))
    Vector("nativeScalaMapping" -> scalaOutput.toByteArray,
      "nativeJavaMapping" -> javaMappingOutput.toByteArray,
      "avroJavaConversion" -> avroOutput.toByteArray)

  def verifyInteroperability(): Unit =
    javaValues.indices.foreach { index =>
      val expected = javaValues(index)
      encodedByEveryWriter(index).foreach { (writer, bytes) =>
        require(java.util.Arrays.equals(bytes, encodedValues(index)), s"$writer emitted different bytes")
        val decoded = Vector(readScala(bytes).bigDecimal, readJavaMapping(bytes), readAvro(bytes))
        decoded.foreach { actual =>
          require(actual.scale() == expected.scale(), s"$writer changed scale")
          require(actual.unscaledValue() == expected.unscaledValue(), s"$writer changed unscaled value")
        }
        val nativeInput = new BinaryInput(bytes)
        LogicalValues.readJavaBigDecimal(nativeInput)
        nativeInput.requireEnd()
        val avroInput = decoderFactory.binaryDecoder(bytes, null)
        conversion.fromBytes(avroInput.readBytes(null), schema, logicalType)
        require(avroInput.isEnd, s"$writer left trailing bytes")
      }
    }
