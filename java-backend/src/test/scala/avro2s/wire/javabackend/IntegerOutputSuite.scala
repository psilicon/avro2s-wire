package avro2s.wire.javabackend

import avro2s.wire.runtime.{BinaryOutput, Bytes}
import java.io.ByteArrayOutputStream
import munit.FunSuite
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

final class IntegerOutputSuite extends FunSuite:
  private val capacities = List(0, 1, 4, 5, 9, 10, 16, 32, 64)
  private val prefixLengths = List(0, 1, 4, 9, 15, 31)
  private val suffix = Array[Byte](0x55, 0, 0x7f)

  test("int output matches Java at every encoded width, buffer boundary and sign") {
    val boundaries = (1 to 4).flatMap { width =>
      val boundary = 1 << (7 * width - 1)
      List(boundary - 1, boundary, boundary + 1, -boundary - 1, -boundary, -boundary + 1)
    }
    val values = (List(Int.MinValue, Int.MaxValue, 0, -1, 1) ++ boundaries).distinct
    for capacity <- capacities; prefixLength <- prefixLengths; value <- values do
      val prefix = Array.fill[Byte](prefixLength)(0x33)
      val native = new BinaryOutput(capacity)
      native.writeFixed(Bytes.fromArray(prefix))
      native.writeInt(value)
      native.writeFixed(Bytes.fromArray(suffix))
      val stream = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().directBinaryEncoder(stream, null)
      encoder.writeFixed(prefix)
      encoder.writeInt(value)
      encoder.writeFixed(suffix)
      encoder.flush()
      assertEquals(native.toByteArray.toList, stream.toByteArray.toList,
        s"capacity=$capacity prefix=$prefixLength value=$value")
      val decoder = DecoderFactory.get().binaryDecoder(native.toByteArray, null)
      decoder.skipFixed(prefixLength)
      assertEquals(decoder.readInt(), value)
      val tail = new Array[Byte](suffix.length)
      decoder.readFixed(tail)
      assertEquals(tail.toList, suffix.toList)
  }

  test("long output matches Java at every encoded width, buffer boundary and sign") {
    val boundaries = (1 to 9).flatMap { width =>
      val boundary = 1L << (7 * width - 1)
      List(boundary - 1L, boundary, boundary + 1L, -boundary - 1L, -boundary, -boundary + 1L)
    }
    val values = (List(Long.MinValue, Long.MaxValue, 0L, -1L, 1L) ++ boundaries).distinct
    for capacity <- capacities; prefixLength <- prefixLengths; value <- values do
      val prefix = Array.fill[Byte](prefixLength)(0x33)
      val native = new BinaryOutput(capacity)
      native.writeFixed(Bytes.fromArray(prefix))
      native.writeLong(value)
      native.writeFixed(Bytes.fromArray(suffix))
      val stream = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().directBinaryEncoder(stream, null)
      encoder.writeFixed(prefix)
      encoder.writeLong(value)
      encoder.writeFixed(suffix)
      encoder.flush()
      assertEquals(native.toByteArray.toList, stream.toByteArray.toList,
        s"capacity=$capacity prefix=$prefixLength value=$value")
      val decoder = DecoderFactory.get().binaryDecoder(native.toByteArray, null)
      decoder.skipFixed(prefixLength)
      assertEquals(decoder.readLong(), value)
      val tail = new Array[Byte](suffix.length)
      decoder.readFixed(tail)
      assertEquals(tail.toList, suffix.toList)
  }

  test("mixed integer output matches Java after growth and repeated reset") {
    val native = new BinaryOutput(0)
    val random = new java.util.Random(731239L)
    for _ <- 0 until 4 do
      native.reset()
      val stream = new ByteArrayOutputStream()
      val encoder = EncoderFactory.get().directBinaryEncoder(stream, null)
      for _ <- 0 until 1000 do
        val intValue = random.nextInt()
        val longValue = random.nextLong()
        native.writeInt(intValue)
        native.writeLong(longValue)
        encoder.writeInt(intValue)
        encoder.writeLong(longValue)
      encoder.flush()
      assertEquals(native.toByteArray.toList, stream.toByteArray.toList)
  }
