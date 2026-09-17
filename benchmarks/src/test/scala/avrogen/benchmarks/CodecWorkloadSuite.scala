package avrogen.benchmarks

import avrogen.runtime.BinaryOutput
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite

final class CodecWorkloadSuite extends FunSuite:
  test("every expanded workload interoperates with independent Java datum codecs") {
    val workloads =
      (for kind <- Vector("int", "long"); distribution <- CodecWorkloads.integerDistributions
        yield CodecWorkloads.integer(kind, distribution)) ++
      CodecWorkloads.stringProfiles.map(CodecWorkloads.string) ++
      Vector(0, 32, 4096).map(CodecWorkloads.bytes) ++
      Vector(0, 4, 128).map(CodecWorkloads.collections) ++
      Vector(0, 1, 4).map(CodecWorkloads.nested)
    assertEquals(workloads.size, 24)
    workloads.foreach(_.verifyInteroperability())
  }

  test("integer distributions exercise the advertised encoded widths and signed extremes") {
    def intWidths(distribution: String): Set[Int] = CodecWorkloads.ints(distribution).map { value =>
      val output = new BinaryOutput()
      output.writeInt(value)
      output.size
    }.toSet
    def longWidths(distribution: String): Set[Int] = CodecWorkloads.longs(distribution).map { value =>
      val output = new BinaryOutput()
      output.writeLong(value)
      output.size
    }.toSet
    assertEquals(intWidths("one-byte"), Set(1))
    assertEquals(longWidths("one-byte"), Set(1))
    assertEquals(intWidths("medium"), Set(3))
    assertEquals(longWidths("medium"), Set(3))
    assertEquals(intWidths("wide"), Set(5))
    assertEquals(longWidths("wide"), Set(10))
    assertEquals(intWidths("mixed"), (1 to 5).toSet)
    assertEquals(longWidths("mixed"), (1 to 10).toSet)
    assert(CodecWorkloads.ints("mixed").contains(Int.MinValue))
    assert(CodecWorkloads.ints("mixed").contains(Int.MaxValue))
    assert(CodecWorkloads.longs("mixed").contains(Long.MinValue))
    assert(CodecWorkloads.longs("mixed").contains(Long.MaxValue))
    assertEquals(CodecWorkloads.ints("mixed"), CodecWorkloads.ints("mixed"))
  }

  test("text profiles contain equal code point counts with distinct UTF-8 widths") {
    CodecWorkloads.stringProfiles.foreach { profile =>
      val text = CodecWorkloads.text(profile)
      val count = if profile == "empty" then 0 else if profile.endsWith("short") then 32 else 4096
      assertEquals(text.codePointCount(0, text.length), count, profile)
      val expectedBytes = if profile.startsWith("ascii") then count
        else if profile.startsWith("emoji") then count * 4 else count * 5 / 2
      assertEquals(text.getBytes(UTF_8).length, expectedBytes, profile)
    }
  }

  test("reusable writers reset completely and allocating APIs return owned results") {
    val workload = CodecWorkloads.collections(128)
    val first = workload.encodedByEveryWriter()
    val second = workload.encodedByEveryWriter()
    first.zip(second).foreach { (before, after) =>
      assertEquals(before._1, after._1)
      assertEquals(before._2.toVector, after._2.toVector, before._1)
      assert(before._2 ne after._2, before._1)
    }
    val encoded = workload.nativeEncode()
    encoded(0) = (encoded(0) ^ 0xff).toByte
    assertEquals(workload.nativeDecode(), workload.expected)
    assert(workload.nativeRead() ne workload.nativeRead())
    assert(workload.javaPrimitivesRead() ne workload.javaPrimitivesRead())
    assert(workload.nativeDecode() ne workload.nativeDecode())
    assert(workload.javaPrimitivesDecode() ne workload.javaPrimitivesDecode())
  }

  test("nested workload includes all general union branches at each tree node") {
    val workload = CodecWorkloads.nested(4)
    def count(value: avrogen.fixtures.performance.PerfNested): Int =
      assertEquals(value.choices.size, 4)
      assertEquals(value.choices.head, None)
      assert(value.choices(1).exists(_.isInstanceOf[Int]))
      assert(value.choices(2).exists(_.isInstanceOf[String]))
      assert(value.choices(3).exists(_.isInstanceOf[avrogen.fixtures.performance.PerfLeaf]))
      1 + value.children.map(count).sum
    assertEquals(count(workload.expected), 31)
  }
