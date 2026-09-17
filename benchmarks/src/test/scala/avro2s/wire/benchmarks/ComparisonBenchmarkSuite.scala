package avro2s.wire.benchmarks

import _root_.avro2s.wire.benchmarks.comparison.{ComparativeWorkload, ComparisonFixtures}
import org.apache.avro.Schema
import munit.FunSuite

final class ComparisonBenchmarkSuite extends FunSuite:
  test("every supported comparison writer is read by every independent reader") {
    val workloads = ComparisonFixtures.profiles.map(ComparisonFixtures.apply) ++
      Vector(ComparisonFixtures.nested(), ComparisonFixtures.logical(), ComparisonFixtures.decimal())
    assertEquals(workloads.size, 13)
    workloads.foreach(_.verifyInteroperability())
  }

  test("comparison labels include only implementations with the promised capabilities") {
    val six = Set("native", "javaPrimitives", "javaGeneric", "javaSpecific", "javaCustom", "avro2s")
    ComparisonFixtures.profiles.foreach { profile =>
      val workload = ComparisonFixtures(profile)
      assertEquals(workload.encodedByEveryWriter().map(_._1).toSet, six, profile)
      assertEquals(workload.verifyCustomDispatch(), (0, 0, 1, 1), profile)
    }
    Vector(ComparisonFixtures.nested(), ComparisonFixtures.logical()).foreach { workload =>
      assertEquals(workload.encodedByEveryWriter().map(_._1).toSet, six - "javaCustom")
      intercept[IllegalArgumentException](workload.javaCustomWrite())
    }
    val decimal = ComparisonFixtures.decimal()
    assertEquals(decimal.encodedByEveryWriter().map(_._1).toSet, six -- Set("javaCustom", "avro2s"))
    intercept[IllegalArgumentException](decimal.avro2sWrite())
  }

  test("all timed readers create fresh records and reset writers retain complete data") {
    val workload = ComparisonFixtures("collections-full")
    val readers = Vector[() => Any](
      () => workload.nativeRead(), () => workload.javaPrimitivesRead(), () => workload.javaGenericRead(),
      () => workload.javaSpecificRead(), () => workload.javaCustomRead(), () => workload.avro2sRead())
    readers.foreach { read => assert(read().asInstanceOf[AnyRef] ne read().asInstanceOf[AnyRef]) }
    val first = workload.encodedByEveryWriter()
    val second = workload.encodedByEveryWriter()
    first.zip(second).foreach { (before, after) =>
      assertEquals(before._1, after._1)
      assertEquals(before._2.toVector, after._2.toVector, before._1)
      assert(before._2 ne after._2)
    }
  }

  test("oracle comparison distinguishes numeric union identity and signed floating zero") {
    val union = new Schema.Parser().parse("""["int","long"]""")
    assert(ComparativeWorkload.normalize(7, union) != ComparativeWorkload.normalize(7L, union))
    val floating = Schema.create(Schema.Type.DOUBLE)
    assert(ComparativeWorkload.normalize(-0.0d, floating) != ComparativeWorkload.normalize(0.0d, floating))
  }

  test("logical and decimal readers materialize logical values instead of underlying wire primitives") {
    val logical = ComparisonFixtures.logical()
    val generic = logical.javaGenericRead()
    assert(generic.get("day").isInstanceOf[java.time.LocalDate])
    assert(generic.get("uuid").isInstanceOf[java.util.UUID])
    assert(logical.javaSpecificRead().get(0).isInstanceOf[java.time.LocalDate])
    assert(logical.avro2sRead().get(0).isInstanceOf[java.time.LocalDate])
    val decimal = ComparisonFixtures.decimal()
    assertEquals(decimal.expected.amount.bigDecimal.precision(), 50)
    assertEquals(decimal.expected.amount.bigDecimal.scale(), 10)
    val decoded = decimal.javaGenericRead()
    assertEquals(decoded.get("amount").asInstanceOf[java.math.BigDecimal], decimal.expected.amount.bigDecimal)
    assertEquals(decoded.get("fixedAmount").asInstanceOf[java.math.BigDecimal], decimal.expected.fixedAmount.value.bigDecimal)
    assert(decimal.javaSpecificRead().get(0).isInstanceOf[java.math.BigDecimal])
    decimal.verifyInteroperability()
  }

  test("evolution benchmark verifies every resolved field and keeps reads fresh") {
    Vector(0, 4096).foreach { size =>
      val benchmark = new EvolutionBenchmark()
      benchmark.discardedBytes = size
      benchmark.setup()
      assert(benchmark.nativeResolved() ne benchmark.nativeResolved())
      assert(benchmark.javaResolved() ne benchmark.javaResolved())
    }
  }
