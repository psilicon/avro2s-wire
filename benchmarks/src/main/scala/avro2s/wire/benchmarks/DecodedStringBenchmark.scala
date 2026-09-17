package avro2s.wire.benchmarks

import _root_.avro2s.wire.benchmarks.comparison.{ComparativeWorkload, ComparisonFixtures}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** Every text field/key is a java.lang.String. Collection model types still differ. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class DecodedStringBenchmark:
  @Param(Array("string-ascii", "string-unicode", "collections-full"))
  var profile: String = "string-ascii"
  private var workload: ComparativeWorkload[?] = null

  @Setup(Level.Trial)
  def setup(): Unit =
    workload = ComparisonFixtures(profile)
    workload.verifyInteroperability()
    workload.verifyStringReaders()

  @Benchmark def nativeRead(): Any = workload.nativeRead()
  @Benchmark def javaPrimitivesRead(): Any = workload.javaPrimitivesRead()
  @Benchmark def javaGenericStringRead(): Any = workload.javaGenericStringRead()
  @Benchmark def javaSpecificStringRead(): Any = workload.javaSpecificStringRead()
  @Benchmark def avro2sRead(): Any = workload.avro2sRead()
