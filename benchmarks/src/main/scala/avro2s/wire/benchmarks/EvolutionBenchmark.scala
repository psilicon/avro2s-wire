package avro2s.wire.benchmarks

import _root_.avro2s.wire.fixtures.evolution.v1.{Account as OldAccount, Status}
import _root_.avro2s.wire.fixtures.evolution.v2.{Account as NewAccount}
import _root_.avro2s.wire.resolution.ResolvingReader
import _root_.avro2s.wire.runtime.Bytes
import _root_.avro2s.wire.benchmarks.comparison.ComparativeWorkload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import org.openjdk.jmh.annotations.*
import scala.jdk.CollectionConverters.*

/** Cold plan construction is a separate operation from warm datum reads.
  * Java returns GenericRecord/Utf8; native returns the generated immutable model.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class EvolutionBenchmark:
  @Param(Array("0", "4096"))
  var discardedBytes: Int = 0

  private var payload: Array[Byte] = null
  private var nativeReader: ResolvingReader[NewAccount] = null
  private var javaReader: GenericDatumReader[GenericRecord] = null

  @Setup(Level.Trial)
  def setup(): Unit =
    val old = OldAccount(10000, "account λ", Bytes.fromArray(new Array[Byte](discardedBytes)),
      Status.LEGACY, Map("balance" -> 1234567L), Some("selected"), None)
    nativeReader = new ResolvingReader(OldAccount.schemaJson, NewAccount.codec)
    val writer = new Schema.Parser().parse(OldAccount.schemaJson)
    val reader = new Schema.Parser().parse(NewAccount.schemaJson)
    // Independent datum writer constructs the shared payload; no native encoding
    // helper supplies the reference bytes used by either resolving reader.
    val reference = new GenericData.Record(writer)
    reference.put("id", Int.box(10000))
    reference.put("name", "account λ")
    reference.put("discarded", ByteBuffer.wrap(new Array[Byte](discardedBytes)))
    reference.put("status", new GenericData.EnumSymbol(writer.getField("status").schema(), "LEGACY"))
    reference.put("attributes", Map("balance" -> Long.box(1234567L)).asJava)
    reference.put("choice", "selected")
    reference.put("child", null)
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    new GenericDatumWriter[GenericRecord](writer).write(reference, encoder)
    encoder.flush()
    payload = bytes.toByteArray
    javaReader = new GenericDatumReader[GenericRecord](writer, reader, new GenericData().setFastReaderEnabled(true))
    val nativeValue = nativeResolved()
    val javaValue = javaResolved()
    require(nativeValue.label == javaValue.get("label").toString)
    require(nativeValue.id == javaValue.get("id").asInstanceOf[java.lang.Long].longValue())
    require(nativeValue.status.toString == javaValue.get("status").toString)
    require(nativeValue.active && nativeValue.tags == Vector("fresh"))
    require(ComparativeWorkload.normalize(nativeValue, reader) == ComparativeWorkload.normalize(javaValue, reader),
      "Resolved readers disagree on a field, promoted value, union branch or default")
    require(nativeSameSchema() == old)

  @Benchmark def nativeResolved(): NewAccount = nativeReader.decode(payload)
  @Benchmark def javaResolved(): GenericRecord =
    val in = DecoderFactory.get().binaryDecoder(payload, null)
    val value = javaReader.read(null, in)
    require(in.isEnd)
    value
  @Benchmark def nativeSameSchema(): OldAccount = OldAccount.codec.decode(payload)
  @Benchmark def compileResolution(): ResolvingReader[NewAccount] =
    new ResolvingReader(OldAccount.schemaJson, NewAccount.codec)
