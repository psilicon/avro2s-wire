package avro2s.wire.benchmarks

import munit.FunSuite

final class TradeBenchmarkSuite extends FunSuite:
  test("every genuine baseline writer can be read by every reader") {
    Seq(0, 32, 1024).foreach { size =>
      val workload = new BenchmarkWorkload(size)
      val encoded = workload.encodedByEveryWriter()
      assertEquals(encoded.map(_._1).toSet,
        Set("native", "javaPrimitives", "javaGeneric", "javaSpecific", "javaCustom", "avro2s"))
      encoded.foreach { (writer, payload) =>
        val decoded = workload.decodedByEveryReader(payload)
        assertEquals(decoded.size, 6)
        decoded.foreach { (reader, actual) =>
          assertEquals(actual, workload.expected, s"$writer -> $reader, collectionSize=$size")
        }
      }
    }
  }

  test("custom and standard Java baselines dispatch different generated code paths") {
    assertEquals(BenchmarkWorkload.verifyCustomCoderDispatch(),
      BenchmarkWorkload.CustomCoderDispatch(0, 0, 1, 1))
  }

  test("collection payloads contain nontrivial boxed values outside the default Integer cache") {
    val workload = new BenchmarkWorkload(1024)
    assert(workload.expected.quantities.forall(_ >= 10000))
    assertEquals(workload.expected.quantities.distinct.size, 257)
    assertEquals(BenchmarkWorkload.fromJava(workload.javaSpecificRead()), workload.expected)
    assertEquals(BenchmarkWorkload.fromJava(workload.javaCustomRead()), workload.expected)
    assertEquals(BenchmarkWorkload.fromScala(workload.avro2sRead()), workload.expected)
  }

  test("ordinary reader methods allocate fresh result records rather than reusing results") {
    val workload = new BenchmarkWorkload(32)
    assert(workload.nativeRead() ne workload.nativeRead())
    assert(workload.javaPrimitivesRead() ne workload.javaPrimitivesRead())
    assert(workload.javaGenericRead() ne workload.javaGenericRead())
    assert(workload.javaSpecificRead() ne workload.javaSpecificRead())
    assert(workload.javaCustomRead() ne workload.javaCustomRead())
    assert(workload.avro2sRead() ne workload.avro2sRead())
  }

  test("reused output buffers produce complete payloads on repeated writes") {
    val workload = new BenchmarkWorkload(32)
    val first = workload.encodedByEveryWriter()
    val second = workload.encodedByEveryWriter()
    first.zip(second).foreach { (previous, current) =>
      assertEquals(current._1, previous._1)
      assertEquals(current._2.toSeq, previous._2.toSeq, current._1)
    }
  }
