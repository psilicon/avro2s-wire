package avro2s.wire.runtime

import java.math.{BigDecimal as JavaDecimal, BigInteger}
import munit.FunSuite

final class DecimalLogicalValuesSuite extends FunSuite:
  private def encoded(write: AvroOutput => Unit): Array[Byte] =
    val out = new BinaryOutput()
    write(out)
    out.toByteArray

  private def payload(write: AvroOutput => Unit): Bytes = Bytes.fromArray(encoded(write))

  private val values = List(
    new JavaDecimal("0"),
    new JavaDecimal("0.00000"),
    new JavaDecimal("1.2"),
    new JavaDecimal("1.2000"),
    new JavaDecimal("-123.45"),
    new JavaDecimal("12345678901234567890123456789012345678901234567890.12345678"),
    new JavaDecimal(new BigInteger("9" * 512), 173),
    new JavaDecimal(new BigInteger("-9" + "8" * 511), -173)
  ) ++ List(Int.MinValue, Int.MinValue + 1, -1000000, -1, 0, 1, 1000000, Int.MaxValue - 1, Int.MaxValue)
    .flatMap(scale => List(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(-1L))
      .map(unscaled => new JavaDecimal(unscaled, scale)))

  test("big-decimal preserves each value's exact precision, signed scale and unscaled integer") {
    values.foreach { expected =>
      val bytes = encoded(out => LogicalValues.writeJavaBigDecimal(expected, out))
      val javaIn = new BinaryInput(bytes)
      val javaResult = LogicalValues.readJavaBigDecimal(javaIn)
      assertEquals(javaResult, expected)
      javaIn.requireEnd()
      val scalaIn = new BinaryInput(bytes)
      val scalaResult = LogicalValues.readBigDecimal(scalaIn)
      assertEquals(scalaResult.bigDecimal, expected)
      assertEquals(scalaResult.scale, expected.scale())
      assertEquals(scalaResult.precision, expected.precision())
      scalaIn.requireEnd()
      val scalaBytes = encoded(out => LogicalValues.writeBigDecimal(BigDecimal.exact(expected), out))
      assertEquals(scalaBytes.toList, bytes.toList)
      val raw = new BinaryInput(bytes).readBytes()
      assertEquals(LogicalValues.javaBigDecimalFromBytes(raw), expected)
      assertEquals(LogicalValues.bigDecimalFromBytes(raw).bigDecimal, expected)
    }
  }

  test("big-decimal uses an outer bytes value containing bytes(unscaled) followed by int(scale)") {
    List(
      new JavaDecimal("123.45") -> List(8, 4, 0x30, 0x39, 4),
      new JavaDecimal("-123.45") -> List(8, 4, 0xcf, 0xc7, 4),
      new JavaDecimal("0.00") -> List(6, 2, 0, 4),
      new JavaDecimal(new BigInteger("12345"), -2) -> List(8, 4, 0x30, 0x39, 3)
    ).foreach { (value, expected) =>
      val bytes = encoded(out => LogicalValues.writeJavaBigDecimal(value, out))
      assertEquals(bytes.map(_ & 0xff).toList, expected)
      assertEquals(LogicalValues.readJavaBigDecimal(new BinaryInput(bytes)), value)
    }
  }

  test("big-decimal rejects empty or truncated payloads, invalid lengths and malformed scale varints") {
    val malformed = List(
      Bytes.empty,
      payload(_.writeBytes(Bytes.empty)),
      payload { out => out.writeBytes(Bytes.empty); out.writeInt(0) },
      payload(_.writeBytes(Bytes.fromArray(Array[Byte](1)))),
      payload(_.writeLong(-1L)),
      payload(_.writeLong(Int.MaxValue.toLong)),
      payload(_.writeLong(Long.MaxValue)),
      payload { out => out.writeLong(2L); out.writeFixed(Bytes.fromArray(Array[Byte](1))) },
      payload { out => out.writeBytes(Bytes.fromArray(Array[Byte](1))); out.writeFixed(Bytes.fromArray(Array[Byte](0x80.toByte))) },
      payload { out => out.writeBytes(Bytes.fromArray(Array[Byte](1))); out.writeLong(Int.MaxValue.toLong + 1L) },
      payload { out => out.writeBytes(Bytes.fromArray(Array[Byte](1))); out.writeLong(Int.MinValue.toLong - 1L) },
      Bytes.fromArray(Array.fill[Byte](10)(0xff.toByte))
    )
    malformed.foreach { bytes =>
      intercept[AvroDecodingException](LogicalValues.javaBigDecimalFromBytes(bytes))
      intercept[AvroDecodingException](LogicalValues.bigDecimalFromBytes(bytes))
      val wire = encoded(_.writeBytes(bytes))
      intercept[AvroDecodingException](LogicalValues.readJavaBigDecimal(new BinaryInput(wire)))
      intercept[AvroDecodingException](LogicalValues.readBigDecimal(new BinaryInput(wire)))
    }
    val valid = encoded(out => LogicalValues.writeJavaBigDecimal(new JavaDecimal("123.45"), out))
    valid.indices.foreach { length =>
      intercept[AvroDecodingException](LogicalValues.readJavaBigDecimal(new BinaryInput(valid.take(length))))
    }
  }

  test("big-decimal rejects trailing nested bytes while consuming only its enclosing field") {
    val trailing = payload { out =>
      out.writeBytes(Bytes.fromArray(Array[Byte](1)))
      out.writeInt(0)
      out.writeInt(0)
    }
    intercept[AvroDecodingException](LogicalValues.javaBigDecimalFromBytes(trailing))
    intercept[AvroDecodingException](LogicalValues.bigDecimalFromBytes(trailing))
    intercept[AvroDecodingException] {
      LogicalValues.readJavaBigDecimal(new BinaryInput(encoded(_.writeBytes(trailing))))
    }
    val in = new BinaryInput(encoded { out =>
      LogicalValues.writeJavaBigDecimal(new JavaDecimal("1.00"), out)
      out.writeInt(42)
    })
    assertEquals(LogicalValues.readJavaBigDecimal(in), new JavaDecimal("1.00"))
    assertEquals(in.readInt(), 42)
    in.requireEnd()
  }

  test("big-decimal respects enclosing bytes and total input limits") {
    val value = new JavaDecimal("123.45")
    val bytes = encoded(out => LogicalValues.writeJavaBigDecimal(value, out))
    assertEquals(bytes.length, 5)
    intercept[AvroDecodingException] {
      LogicalValues.readJavaBigDecimal(new BinaryInput(bytes, DecodeLimits(maxBytesLength = Some(3))))
    }
    intercept[AvroDecodingException] {
      LogicalValues.readJavaBigDecimal(new BinaryInput(bytes, DecodeLimits(maxInputBytes = Some(4))))
    }
    val in = new BinaryInput(bytes, DecodeLimits(maxInputBytes = Some(5), maxBytesLength = Some(4)))
    assertEquals(LogicalValues.readJavaBigDecimal(in), value)
    in.requireEnd()
  }

  test("Java constrained decimals match Scala output and preserve precision beyond decimal128") {
    List("123.45", "-123.45", "0.00", "12345678901234567890123456789012345678901234567890.12").foreach { text =>
      val value = new JavaDecimal(text)
      val scalaValue = BigDecimal.exact(value)
      val bytes = encoded(out => LogicalValues.writeJavaDecimal(value, out, 60, 2))
      assertEquals(LogicalValues.readJavaDecimal(new BinaryInput(bytes), 60, 2), value)
      assertEquals(LogicalValues.javaDecimalFromBytes(new BinaryInput(bytes).readBytes(), 60, 2), value)
      assertEquals(encoded(out => LogicalValues.writeDecimal(scalaValue, out, 60, 2)).toList, bytes.toList)
      val fixed = encoded(out => LogicalValues.writeJavaFixedDecimal(value, out, 32, 60, 2))
      assertEquals(LogicalValues.readJavaFixedDecimal(new BinaryInput(fixed), 32, 60, 2), value)
      assertEquals(encoded(out => LogicalValues.writeFixedDecimal(scalaValue, out, 32, 60, 2)).toList, fixed.toList)
    }
  }

  test("Java constrained decimal rescaling, precision and fixed capacity use the existing exact rules") {
    List("1.2", "1.2000").foreach { text =>
      val bytes = encoded(out => LogicalValues.writeJavaDecimal(new JavaDecimal(text), out, 3, 2))
      assertEquals(LogicalValues.readJavaDecimal(new BinaryInput(bytes), 3, 2), new JavaDecimal("1.20"))
    }
    val failures: List[AvroOutput => Unit] = List(
      out => LogicalValues.writeJavaDecimal(new JavaDecimal("1.234"), out, 4, 2),
      out => LogicalValues.writeJavaDecimal(new JavaDecimal("123.45"), out, 4, 2),
      out => LogicalValues.writeJavaFixedDecimal(new JavaDecimal("128"), out, 1, 3, 0),
      out => LogicalValues.writeJavaFixedDecimal(new JavaDecimal("-129"), out, 1, 3, 0),
      out => LogicalValues.writeJavaFixedDecimal(JavaDecimal.ONE, out, 0, 1, 0),
      out => LogicalValues.writeJavaDecimal(JavaDecimal.ONE, out, 0, 0),
      out => LogicalValues.writeJavaDecimal(JavaDecimal.ONE, out, 1, 2)
    )
    failures.foreach { write =>
      val out = new BinaryOutput()
      intercept[IllegalArgumentException](write(out))
      assertEquals(out.size, 0)
    }
    intercept[AvroDecodingException](LogicalValues.javaDecimalFromBytes(Bytes.empty, 3, 0))
    intercept[AvroDecodingException] {
      LogicalValues.javaDecimalFromBytes(Bytes.fromArray(Array[Byte](0x30, 0x39)), 4, 2)
    }
  }
