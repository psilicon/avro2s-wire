package avro2s.wire.benchmarks

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class NestedUnionBenchmark:
  @Param(Array("0", "1", "4"))
  var depth: Int = 0

  private var workload: CodecWorkload[?] = null

  @Setup(Level.Trial)
  def setup(): Unit =
    workload = CodecWorkloads.nested(depth)
    workload.verifyInteroperability()

  @Benchmark def nativeWrite(): Int = workload.nativeWrite()
  @Benchmark def nativeRead(): Any = workload.nativeRead()
  @Benchmark def javaPrimitivesWrite(): Int = workload.javaPrimitivesWrite()
  @Benchmark def javaPrimitivesRead(): Any = workload.javaPrimitivesRead()
  @Benchmark def nativeEncode(): Array[Byte] = workload.nativeEncode()
  @Benchmark def nativeDecode(): Any = workload.nativeDecode()
  @Benchmark def javaPrimitivesEncode(): Array[Byte] = workload.javaPrimitivesEncode()
  @Benchmark def javaPrimitivesDecode(): Any = workload.javaPrimitivesDecode()
