package avro2s.wire.runtime

import java.nio.{ByteBuffer, ByteOrder}
import munit.FunSuite

final class NumericBoundarySuite extends FunSuite:
  /** Arithmetic reference deliberately avoids the codec's shifts/unrolled paths. */
  private def wire(value: BigInt): Array[Byte] =
    var unsigned = if value >= 0 then value * 2 else -value * 2 - 1
    val result = scala.collection.mutable.ArrayBuffer.empty[Byte]
    while unsigned >= 128 do
      result += ((unsigned % 128).toInt + 128).toByte
      unsigned /= 128
    result += unsigned.toByte
    result.toArray

  private val intValues =
    val edges = (0 until 32).flatMap { shift =>
      val edge = BigInt(1) << shift
      Vector(edge - 1, edge, -edge, -edge - 1)
    }
    (edges.filter(_.isValidInt).map(_.toInt) ++ Vector(Int.MinValue, Int.MaxValue)).distinct

  private val longValues =
    val edges = (0 until 64).flatMap { shift =>
      val edge = BigInt(1) << shift
      Vector(edge - 1, edge, -edge, -edge - 1)
    }
    (edges.filter(_.isValidLong).map(_.toLong) ++ Vector(Long.MinValue, Long.MaxValue)).distinct

  test("every integer width agrees with arithmetic wire bytes through growth and reset") {
    Vector(0, 1, 4, 5, 9, 10, 16).foreach { capacity =>
      val out = new BinaryOutput(capacity)
      intValues.foreach { value =>
        out.reset()
        out.writeInt(value)
        assertEquals(out.toByteArray.toVector, wire(BigInt(value)).toVector)
      }
      longValues.foreach { value =>
        out.reset()
        out.writeLong(value)
        assertEquals(out.toByteArray.toVector, wire(BigInt(value)).toVector)
      }
      out.reset()
      intValues.foreach(out.writeInt)
      longValues.foreach(out.writeLong)
      val expected = intValues.flatMap(v => wire(BigInt(v))) ++ longValues.flatMap(v => wire(BigInt(v)))
      assertEquals(out.toByteArray.toVector, expected.toVector)
    }
  }

  test("varints consume exactly their width on fast paths and checked tails") {
    intValues.foreach { value =>
      Vector(0, 1, 16).foreach { padding =>
        val bytes = wire(BigInt(value)) ++ Array.fill[Byte](padding)(0x55)
        val in = new BinaryInput(bytes)
        assertEquals(in.readInt(), value)
        assertEquals(in.remaining, padding)
        assertEquals(in.readFixed(padding).toArray.toVector, Vector.fill[Byte](padding)(0x55))
        in.requireEnd()
      }
    }
    longValues.foreach { value =>
      Vector(0, 1, 16).foreach { padding =>
        val bytes = wire(BigInt(value)) ++ Array.fill[Byte](padding)(0x55)
        val in = new BinaryInput(bytes)
        assertEquals(in.readLong(), value)
        assertEquals(in.remaining, padding)
        assertEquals(in.readFixed(padding).toArray.toVector, Vector.fill[Byte](padding)(0x55))
        in.requireEnd()
      }
    }
    // Legal nonminimal encodings must retain their historical acceptance.
    (1 to 10).foreach { size =>
      val bytes = Array.fill[Byte](size - 1)(0x80.toByte) ++ Array[Byte](0)
      Vector(0, 16).foreach { padding =>
        val in = new BinaryInput(bytes ++ Array.fill[Byte](padding)(0x55))
        assertEquals(in.readLong(), 0L)
        assertEquals(in.remaining, padding)
        if size <= 5 then
          val ints = new BinaryInput(bytes ++ Array.fill[Byte](padding)(0x55))
          assertEquals(ints.readInt(), 0)
          assertEquals(ints.remaining, padding)
      }
    }
  }

  test("all overflowing terminal bytes are rejected even with ample following input") {
    (0x10 to 0xff).foreach { last =>
      val bytes = Array.fill[Byte](4)(0x80.toByte) ++ Array(last.toByte) ++ Array.fill[Byte](16)(0)
      intercept[AvroDecodingException](new BinaryInput(bytes).readInt())
    }
    (2 to 0xff).foreach { last =>
      val bytes = Array.fill[Byte](9)(0x80.toByte) ++ Array(last.toByte) ++ Array.fill[Byte](16)(0)
      intercept[AvroDecodingException](new BinaryInput(bytes).readLong())
    }
  }

  private def sizedBlock(contents: Array[Byte], declaredSize: Int): BinaryInput =
    // Following bytes deliberately exist outside the block: a fast reader must
    // honor the declared boundary, not merely the backing array's length.
    val bytes = wire(BigInt(-1)) ++ wire(BigInt(declaredSize)) ++ contents ++ Array.fill[Byte](16)(0)
    val in = new BinaryInput(bytes)
    assertEquals(in.readArrayStart(), 1L)
    in

  private def checkBoundaries(contents: Array[Byte])(read: BinaryInput => Unit): Unit =
    contents.indices.foreach { cut =>
      intercept[AvroDecodingException](read(new BinaryInput(contents.take(cut))))
      intercept[AvroDecodingException](read(sizedBlock(contents, cut)))
    }
    val exact = sizedBlock(contents, contents.length)
    read(exact)
    assertEquals(exact.arrayNext(), 0L)
    assertEquals(exact.remaining, 15)

  test("numeric reads cannot cross physical or sized-block boundaries") {
    intValues.foreach { value =>
      checkBoundaries(wire(BigInt(value)))(in => assertEquals(in.readInt(), value))
    }
    longValues.foreach { value =>
      checkBoundaries(wire(BigInt(value)))(in => assertEquals(in.readLong(), value))
    }
    checkBoundaries(Array[Byte](0x34, 0x12, 0xc0.toByte, 0x7f)) { in =>
      assertEquals(java.lang.Float.floatToRawIntBits(in.readFloat()), 0x7fc01234)
    }
    checkBoundaries(Array[Byte](0x78, 0x56, 0x34, 0x12, 0, 0, 0xf8.toByte, 0x7f)) { in =>
      assertEquals(java.lang.Double.doubleToRawLongBits(in.readDouble()), 0x7ff8000012345678L)
    }
  }

  test("unaligned float and double reads and writes preserve raw bits in little endian order") {
    val floats = Vector(0, Int.MinValue, 1, 0x7f800000, 0xff800000, 0x7fc01234, 0xffc01234, 0x3f800000)
    val doubles = Vector(0L, Long.MinValue, 1L, 0x7ff0000000000000L, 0xfff0000000000000L,
      0x7ff8000012345678L, 0xfff8000012345678L, 0x3ff0000000000000L)
    (0 until 16).foreach { offset =>
      val prefix = Array.fill[Byte](offset)(0x55)
      val reference = ByteBuffer.allocate(offset + floats.size * 4 + doubles.size * 8).order(ByteOrder.LITTLE_ENDIAN)
      reference.put(prefix)
      floats.foreach(reference.putInt)
      doubles.foreach(reference.putLong)
      val out = new BinaryOutput(offset)
      out.writeFixed(Bytes.fromArray(prefix))
      floats.foreach(bits => out.writeFloat(java.lang.Float.intBitsToFloat(bits)))
      doubles.foreach(bits => out.writeDouble(java.lang.Double.longBitsToDouble(bits)))
      assertEquals(out.toByteArray.toVector, reference.array().toVector)
      val in = new BinaryInput(reference.array())
      in.skipFixed(offset)
      floats.foreach(bits => assertEquals(java.lang.Float.floatToRawIntBits(in.readFloat()), bits))
      doubles.foreach(bits => assertEquals(java.lang.Double.doubleToRawLongBits(in.readDouble()), bits))
      in.requireEnd()
    }
  }
