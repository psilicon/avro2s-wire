package avro2s.wire.benchmarks

import _root_.avro2s.wire.fixtures.Trade
import _root_.avro2s.wire.fixtures.performance.PerfCollections
import _root_.avro2s.wire.fixtures.stacks.{RecursiveContainers, StackNode, StackNodeV2}
import _root_.avro2s.wire.resolution.ResolvingReader
import _root_.avro2s.wire.runtime.AvroCodec
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** End-to-end allocating APIs; inputs, codec selection, and resolution plans are
  * prepared outside timing. Run with -prof gc to compare allocation as well as
  * latency. Both modes consume identical bytes and return identical types.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class StackSafetyBenchmark:
  @Param(Array("direct", "stack-safe"))
  var execution: String = "direct"

  @Param(Array("shallow", "collections", "recursive"))
  var shape: String = "shallow"

  private var workload: StackSafetyWorkload = null

  @Setup(Level.Trial)
  def setup(): Unit =
    workload = StackSafetyWorkload(shape, execution)
    workload.verify()

  @Benchmark def encode(): Array[Byte] = workload.encode()
  @Benchmark def decode(): Any = workload.decode()
  @Benchmark def resolvedDecode(): Any = workload.resolvedDecode()

/** Public checks let tests validate every parameter combination independently of JMH. */
trait StackSafetyWorkload:
  def expected: Any
  def expectedResolved: Any
  def encode(): Array[Byte]
  def decode(): Any
  def resolvedDecode(): Any
  def referenceBytes: Array[Byte]

  final def verify(): Unit =
    require(java.util.Arrays.equals(encode(), referenceBytes), "Codec executions disagree on encoded bytes")
    require(decode() == expected, "Codec executions disagree on the decoded model")
    require(resolvedDecode() == expectedResolved, "Codec executions disagree on the resolved model")

object StackSafetyWorkload:
  // The default JMH/runner matrix remains the original three shapes. These
  // additional shapes are available through an explicit parameter override.
  val shapes: Vector[String] = Vector("shallow", "collections", "recursive", "recursive-256", "recursive-containers")
  val executions: Vector[String] = Vector("direct", "stack-safe")

  def apply(shape: String, execution: String): StackSafetyWorkload =
    require(executions.contains(execution), s"Unknown codec execution: $execution")
    shape match
      case "shallow" =>
        val value = Trade(1000000001L, "AVRO-λ", 123.456, Vector.empty)
        sameModel(Trade.codec, Trade.stackSafeCodec, value, execution)
      case "collections" =>
        val value = PerfCollections(Vector.tabulate(128)(i => 10000 + i),
          (0 until 32).iterator.map(i => s"key-$i" -> s"value-$i-λ").toMap)
        sameModel(PerfCollections.codec, PerfCollections.stackSafeCodec, value, execution)
      case "recursive" | "recursive-256" =>
        val depth = if shape == "recursive" then 32 else 256
        val value = (1 until depth).foldLeft(StackNode(None, 10000))((tail, i) => StackNode(Some(tail), 10000 + i))
        val resolved = (1 until depth).foldLeft(StackNodeV2(10000L, None, true)) {
          (tail, i) => StackNodeV2(10000L + i, Some(tail), true)
        }
        create(StackNode.codec, StackNode.stackSafeCodec, value, StackNode.schemaJson,
          StackNodeV2.codec, StackNodeV2.stackSafeCodec, resolved, execution)
      case "recursive-containers" =>
        val value = (1 until 64).foldLeft(RecursiveContainers(None, 10000)) { (tail, i) =>
          val child: RecursiveContainers | Vector[RecursiveContainers] | Map[String, RecursiveContainers] =
            i % 3 match
              case 0 => tail
              case 1 => Vector(tail)
              case _ => Map("child" -> tail)
          RecursiveContainers(Some(child), 10000 + i)
        }
        sameModel(RecursiveContainers.codec, RecursiveContainers.stackSafeCodec, value, execution)
      case _ => throw new IllegalArgumentException(s"Unknown benchmark shape: $shape")

  private def sameModel[A](direct: AvroCodec[A], safe: => AvroCodec[A], value: A, execution: String): StackSafetyWorkload =
    // Metadata differs, so resolution really compiles/traverses a plan. This does
    // not accidentally benchmark the identical-schema shortcut under this name.
    val writerJson = "{\"doc\":\"benchmark writer metadata\"," + direct.schemaJson.drop(1)
    create(direct, safe, value, writerJson, direct, safe, value, execution)

  private def create[A, B](direct: AvroCodec[A], safe: => AvroCodec[A], value: A,
      writerJson: String, readerDirect: AvroCodec[B], readerSafe: => AvroCodec[B], resolved: B,
      execution: String): StackSafetyWorkload =
    // Direct-only application code does not initialize the unused alternative.
    // Keep that property here as well: extra class loading can affect JIT choices.
    val codec = if execution == "direct" then direct else safe
    val readerCodec = if execution == "direct" then readerDirect else readerSafe
    new StackSafetyWorkload:
      private val payload = direct.encode(value)
      private val reader = new ResolvingReader(writerJson, readerCodec)
      override def expected: Any = value
      override def expectedResolved: Any = resolved
      override def encode(): Array[Byte] = codec.encode(value)
      override def decode(): A = codec.decode(payload)
      override def resolvedDecode(): B = reader.decode(payload)
      override def referenceBytes: Array[Byte] = payload.clone()
