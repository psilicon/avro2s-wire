package avro2s.wire.benchmarks

import munit.FunSuite

final class BigDecimalBenchmarkSuite extends FunSuite:
  test("all big-decimal benchmark cases preserve exact scale and integer across all three writers/readers") {
    for digits <- Vector(6, 50, 500); scale <- Vector(0, 6, -6) do
      val workload = new BigDecimalWorkload(digits, scale)
      workload.verifyInteroperability()
      assertEquals(workload.javaValues.length, 16)
      assertEquals(workload.javaValues.count(_.signum() < 0), 8)
      workload.javaValues.indices.foreach { index =>
        val expected = workload.javaValues(index)
        assertEquals(expected.precision(), digits)
        assertEquals(expected.scale(), scale)
        assertEquals(workload.scalaValues(index).bigDecimal, expected)
        workload.encodedByEveryWriter(index).foreach { (writer, bytes) =>
          Vector(workload.readScala(bytes).bigDecimal, workload.readJavaMapping(bytes), workload.readAvro(bytes))
            .foreach { actual =>
              assertEquals(actual.scale(), expected.scale(), writer)
              assertEquals(actual.unscaledValue(), expected.unscaledValue(), writer)
            }
        }
      }
  }

  test("read paths construct fresh logical values and reset write paths retain a complete value") {
    val workload = new BigDecimalWorkload(50, -6)
    val bytes = workload.encodedValues(0)
    assert(workload.readScala(bytes) ne workload.readScala(bytes))
    assert(workload.readScala(bytes).bigDecimal ne workload.readScala(bytes).bigDecimal)
    assert(workload.readJavaMapping(bytes) ne workload.readJavaMapping(bytes))
    assert(workload.readAvro(bytes) ne workload.readAvro(bytes))
    val first = workload.encodedByEveryWriter(0)
    val second = workload.encodedByEveryWriter(0)
    first.zip(second).foreach { (before, after) =>
      assertEquals(before._2.toVector, after._2.toVector, before._1)
      assert(before._2 ne after._2)
    }
    val writers = Vector[() => Int](() => workload.nativeScalaMappingWrite(),
      () => workload.nativeJavaMappingWrite(), () => workload.avroJavaConversionWrite())
    writers.foreach { write => (0 until 32).foreach { _ =>
      val size = write()
      assert(workload.encodedValues.exists(_.length == size))
    } }
  }
