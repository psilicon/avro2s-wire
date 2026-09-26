package avro2s.wire.benchmarks

final class StackSafetyBenchmarkSuite extends munit.FunSuite:
  test("every stack-safety benchmark shape checks both executions against the same bytes and models") {
    for shape <- StackSafetyWorkload.shapes do
      val direct = StackSafetyWorkload(shape, "direct")
      val safe = StackSafetyWorkload(shape, "stack-safe")
      direct.verify()
      safe.verify()
      assertEquals(safe.referenceBytes.toVector, direct.referenceBytes.toVector, shape)
      assertEquals(safe.encode().toVector, direct.encode().toVector, shape)
      assertEquals(safe.decode(), direct.decode(), shape)
      assertEquals(safe.resolvedDecode(), direct.resolvedDecode(), shape)
  }

  test("timed stack-safety methods return fresh arrays and model instances") {
    for shape <- StackSafetyWorkload.shapes; execution <- StackSafetyWorkload.executions do
      val workload = StackSafetyWorkload(shape, execution)
      val first = workload.encode()
      val second = workload.encode()
      assert(first ne second)
      assertEquals(first.toVector, second.toVector)
      assert(workload.decode().asInstanceOf[AnyRef] ne workload.decode().asInstanceOf[AnyRef])
      assert(workload.resolvedDecode().asInstanceOf[AnyRef] ne workload.resolvedDecode().asInstanceOf[AnyRef])
      first(0) = (first(0) ^ 0xff).toByte
      workload.verify()
  }

  test("benchmark setup rejects unknown parameter values") {
    intercept[IllegalArgumentException](StackSafetyWorkload("shallow", "unknown"))
    intercept[IllegalArgumentException](StackSafetyWorkload("unknown", "direct"))
  }
