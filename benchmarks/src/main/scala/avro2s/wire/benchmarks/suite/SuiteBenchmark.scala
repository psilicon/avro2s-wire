package avro2s.wire.benchmarks.suite

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.BenchmarkParams

/** The suite runner selects supported case/engine/operation combinations from the manifest.
  * Defaults are publication settings; the labelled pilot explicitly overrides them.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(5)
@Threads(1)
class SuiteBenchmark:
  @Param(Array("P03"))
  var caseId: String = "P03"

  @Param(Array("wire"))
  var engine: String = "wire"

  private var workload: SuiteWorkload = null

  @Setup(Level.Trial)
  def setup(params: BenchmarkParams): Unit =
    val operation = params.getBenchmark.substring(params.getBenchmark.lastIndexOf('.') + 1)
    workload = SuiteWorkload.prepared(caseId, engine, operation)

  @Benchmark def encode(): Array[Byte] = workload.encode()
  @Benchmark def decode(): Any = workload.decode()
