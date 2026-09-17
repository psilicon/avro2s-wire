package avrogen.benchmarks

import avrogen.fixtures.evolution.v1.{Account as OldAccount, Status}
import avrogen.fixtures.evolution.v2.{Account as NewAccount}
import avrogen.resolution.ResolvingReader
import avrogen.runtime.Bytes
import java.util.concurrent.TimeUnit
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericRecord}
import org.apache.avro.io.DecoderFactory
import org.openjdk.jmh.annotations.*

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
    payload = OldAccount.codec.encode(old)
    nativeReader = new ResolvingReader(OldAccount.schemaJson, NewAccount.codec)
    val writer = new Schema.Parser().parse(OldAccount.schemaJson)
    val reader = new Schema.Parser().parse(NewAccount.schemaJson)
    javaReader = new GenericDatumReader[GenericRecord](writer, reader, new GenericData().setFastReaderEnabled(true))
    val nativeValue = nativeResolved()
    val javaValue = javaResolved()
    require(nativeValue.label == javaValue.get("label").toString)
    require(nativeValue.id == javaValue.get("id").asInstanceOf[java.lang.Long].longValue())
    require(nativeValue.status.toString == javaValue.get("status").toString)
    require(nativeValue.active && nativeValue.tags == Vector("fresh"))
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
