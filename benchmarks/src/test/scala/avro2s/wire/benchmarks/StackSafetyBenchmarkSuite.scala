package avro2s.wire.benchmarks

import _root_.avro2s.wire.fixtures.stacks.{RecursiveContainers, StackNode}

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

  test("optional scaling shapes include 256 linked records and 64 records across all recursive union paths") {
    val longer = StackSafetyWorkload("recursive-256", "direct").expected.asInstanceOf[StackNode]
    var current = Option(longer)
    var records = 0
    while current.nonEmpty do
      records += 1
      current = current.get.next
    assertEquals(records, 256)

    var container = Option(StackSafetyWorkload("recursive-containers", "direct")
      .expected.asInstanceOf[RecursiveContainers])
    var containerRecords = 0
    var recordBranches = 0
    var arrayBranches = 0
    var mapBranches = 0
    while container.nonEmpty do
      containerRecords += 1
      container = container.get.child.map {
        case child: RecursiveContainers =>
          recordBranches += 1
          child
        case children: Vector[?] =>
          arrayBranches += 1
          assertEquals(children.size, 1)
          children.head.asInstanceOf[RecursiveContainers]
        case children: Map[?, ?] =>
          mapBranches += 1
          assertEquals(children.size, 1)
          children.asInstanceOf[Map[String, RecursiveContainers]]("child")
      }
    assertEquals(containerRecords, 64)
    assertEquals((recordBranches, arrayBranches, mapBranches), (21, 21, 21))
  }
