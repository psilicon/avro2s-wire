package avro2s.wire.benchmarks

import _root_.avro2s.wire.benchmarks.comparison.{ComparativeWorkload, ComparisonFixtures}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class LogicalComparisonBenchmark:
  private var workload: ComparativeWorkload[?] = null

  @Setup(Level.Trial)
  def setup(): Unit =
    workload = ComparisonFixtures.logical()
    workload.verifyInteroperability()

  @Benchmark def nativeRead(): Any = workload.nativeRead()
  @Benchmark def nativeWrite(): Int = workload.nativeWrite()
  @Benchmark def javaPrimitivesRead(): Any = workload.javaPrimitivesRead()
  @Benchmark def javaPrimitivesWrite(): Int = workload.javaPrimitivesWrite()
  @Benchmark def javaGenericRead(): Any = workload.javaGenericRead()
  @Benchmark def javaGenericWrite(): Int = workload.javaGenericWrite()
  @Benchmark def javaSpecificRead(): Any = workload.javaSpecificRead()
  @Benchmark def javaSpecificWrite(): Int = workload.javaSpecificWrite()
  @Benchmark def avro2sRead(): Any = workload.avro2sRead()
  @Benchmark def avro2sWrite(): Int = workload.avro2sWrite()
