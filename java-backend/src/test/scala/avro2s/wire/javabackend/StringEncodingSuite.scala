package avro2s.wire.javabackend

import avro2s.wire.runtime.{AvroDecodingException, BinaryInput, BinaryOutput, Bytes, DecodeLimits}
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

final class StringEncodingSuite extends FunSuite:
  private def javaEncoded(value: String): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    encoder.writeInt(12345)
    encoder.writeString(value)
    encoder.writeInt(-54321)
    encoder.flush()
    bytes.toByteArray

  test("ASCII boundaries and Unicode after long prefixes match Java wire bytes") {
    val lengths = List(0, 1, 7, 8, 63, 64, 127, 128, 1023, 4096)
    val strings = lengths.flatMap { length =>
      val ascii = Vector.tabulate(length)(i => (i % 128).toChar).mkString
      List(ascii, ascii + "é", ascii + "λ漢字", ascii + "🚀", "🚀" + ascii, ascii + "é" + ascii)
    }
    strings.foreach { value =>
      List(0, 1, 7, 64, 8192).foreach { capacity =>
        val out = new BinaryOutput(capacity)
        out.writeInt(12345)
        out.writeString(value)
        out.writeInt(-54321)
        val expected = javaEncoded(value)
        assertEquals(out.toByteArray.toSeq, expected.toSeq)
        val in = new BinaryInput(expected)
        assertEquals(in.readInt(), 12345)
        assertEquals(in.readString(), value)
        assertEquals(in.readInt(), -54321)
        in.requireEnd()
        val javaIn = DecoderFactory.get().binaryDecoder(out.toByteArray, null)
        assertEquals(javaIn.readInt(), 12345)
        assertEquals(javaIn.readString(), value)
        assertEquals(javaIn.readInt(), -54321)
        assert(javaIn.isEnd)
      }
    }
  }

  test("deterministic Unicode strings agree with independent Java encoding") {
    val random = new java.util.Random(752349L)
    (0 until 250).foreach { _ =>
      val value = new java.lang.StringBuilder()
      (0 until random.nextInt(100)).foreach { _ =>
        val point = random.nextInt(5) match
          case 0 | 1 => random.nextInt(128)
          case 2 => 128 + random.nextInt(0x800 - 128)
          case 3 => 0xe000 + random.nextInt(0x10000 - 0xe000)
          case _ => 0x10000 + random.nextInt(0x110000 - 0x10000)
        value.appendCodePoint(point)
      }
      val out = new BinaryOutput(0)
      out.writeInt(12345)
      out.writeString(value.toString)
      out.writeInt(-54321)
      assertEquals(out.toByteArray.toSeq, javaEncoded(value.toString).toSeq)
      val in = new BinaryInput(out.toByteArray)
      in.readInt()
      assertEquals(in.readString(), value.toString)
      in.readInt()
      in.requireEnd()
    }
  }

  test("invalid UTF-16 after an ASCII prefix leaves existing output intact") {
    List(0, 64, 4096).foreach { length =>
      List("\ud800", "\udc00", "\ud800x", "\udc00\ud800").foreach { suffix =>
        val out = new BinaryOutput(0)
        out.writeString("prior value")
        val before = out.toByteArray
        intercept[IllegalArgumentException](out.writeString("a" * length + suffix))
        assertEquals(out.toByteArray.toSeq, before.toSeq)
        out.writeString("valid 🚀")
        val in = new BinaryInput(out.toByteArray)
        assertEquals(in.readString(), "prior value")
        assertEquals(in.readString(), "valid 🚀")
        in.requireEnd()
      }
    }
  }

  test("malformed UTF-8 after ASCII prefixes remains rejected during reads and skipping") {
    val invalid = List(
      Array(0x80), Array(0xc0, 0x80), Array(0xe0, 0x80, 0x80),
      Array(0xed, 0xa0, 0x80), Array(0xf4, 0x90, 0x80, 0x80), Array(0xe2, 0x82)
    )
    for length <- List(0, 64, 4096); tail <- invalid do
      val payload = Array.fill[Byte](length)(0x61.toByte) ++ tail.map(_.toByte)
      val out = new BinaryOutput()
      out.writeLong(payload.length.toLong)
      out.writeFixed(Bytes.fromArray(payload))
      val bytes = out.toByteArray
      intercept[AvroDecodingException](new BinaryInput(bytes).readString())
      intercept[AvroDecodingException](new BinaryInput(bytes).readBytesAsString())
      intercept[AvroDecodingException](new BinaryInput(bytes).readStringAsBytes())
      intercept[AvroDecodingException](new BinaryInput(bytes).skipString())
  }

  test("ASCII and Unicode string reads retain byte limits and truncation checks") {
    List("x" * 128, "漢🚀" * 40).foreach { value =>
      val out = new BinaryOutput()
      out.writeString(value)
      val encoded = out.toByteArray
      val byteLength = value.getBytes(UTF_8).length
      intercept[AvroDecodingException] {
        new BinaryInput(encoded, DecodeLimits(maxStringBytes = byteLength - 1)).readString()
      }
      assertEquals(new BinaryInput(encoded, DecodeLimits(maxStringBytes = byteLength)).readString(), value)
      (0 until encoded.length).foreach { end =>
        intercept[AvroDecodingException](new BinaryInput(encoded.take(end)).readString())
      }
    }
  }
