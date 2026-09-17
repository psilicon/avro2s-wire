package avro2s.wire.runtime

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import munit.FunSuite

final class StrictUtf8Suite extends FunSuite:
  private val utf8 = StandardCharsets.UTF_8
  private def frame(bytes: Array[Byte]): Array[Byte] =
    val out = new BinaryOutput(0)
    out.writeBytes(Bytes.fromArray(bytes))
    out.toByteArray

  private def strictDecoder = utf8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

  test("all one-byte and two-byte inputs agree with the strict JDK decoder") {
    val decoder = strictDecoder
    def check(bytes: Array[Byte]): Unit =
      val expected = try Some(decoder.decode(ByteBuffer.wrap(bytes)).toString)
        catch case _: CharacterCodingException => None
      val in = new BinaryInput(frame(bytes))
      expected match
        case Some(value) =>
          assertEquals(in.readString(), value)
          in.requireEnd()
        case None => intercept[AvroDecodingException](in.readString())
    check(Array.emptyByteArray)
    for first <- 0 to 255 do
      check(Array(first.toByte))
      for second <- 0 to 255 do check(Array(first.toByte, second.toByte))
  }

  test("seeded multibyte mutations agree with strict decoding including literal replacement text") {
    val random = new java.util.Random(892019L)
    val decoder = strictDecoder
    val originals = Vector("λé漢😀", "\ufffd", "before\ufffdafter", "\u0000\ud7ff\ue000\uffff", "𐀀\udbff\udfff")
    for iteration <- 0 until 12000 do
      val original = originals(iteration % originals.size).getBytes(utf8)
      val mutated = original.clone()
      mutated(random.nextInt(mutated.length)) = random.nextInt(256).toByte
      val expected = try Some(decoder.decode(ByteBuffer.wrap(mutated)).toString)
        catch case _: CharacterCodingException => None
      for promoted <- Vector(false, true) do
        val in = new BinaryInput(frame(mutated))
        def read(): String = if promoted then in.readBytesAsString() else in.readString()
        expected match
          case Some(value) =>
            assertEquals(read(), value)
            in.requireEnd()
          case None => intercept[AvroDecodingException](read())
  }

  test("every single UTF-16 code unit either matches strict JDK encoding or preserves prior output") {
    val encoder = utf8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    for code <- 0 to 0xffff do
      val value = code.toChar.toString
      val expected = try
        val encoded = encoder.encode(CharBuffer.wrap(value))
        val bytes = new Array[Byte](encoded.remaining())
        encoded.get(bytes)
        Some(bytes)
      catch case _: CharacterCodingException => None
      val out = new BinaryOutput(0)
      out.writeInt(73)
      val prefix = out.toByteArray
      expected match
        case Some(bytes) =>
          out.writeString(value)
          val in = new BinaryInput(out.toByteArray)
          assertEquals(in.readInt(), 73)
          assertEquals(in.readBytes().toArray.toSeq, bytes.toSeq)
          in.requireEnd()
        case None =>
          intercept[IllegalArgumentException](out.writeString(value))
          assertEquals(out.toByteArray.toSeq, prefix.toSeq)
  }

  test("replacement sentinels and supplementary text remain valid across word scan boundaries") {
    val values = for
      size <- Vector(0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 63, 64, 65, 1024)
      marker <- Vector("?", "\ufffd", "😀", "λé漢", "?\ufffd😀?")
      index <- Vector(0, size / 2, size)
    yield "a".repeat(index) + marker + "a".repeat(size - index)
    values.foreach { value =>
      val out = new BinaryOutput(0)
      out.writeString(value)
      out.writeInt(923)
      val in = new BinaryInput(out.toByteArray)
      assertEquals(in.readString(), value)
      assertEquals(in.readInt(), 923)
      in.requireEnd()
    }
  }

  test("unpaired surrogates at every byte-word position fail without appending or damaging existing bytes") {
    val invalid = Vector("\ud800", "\udfff", "\ud800a", "\ud800\ud800", "\udfff\ud800")
    for
      prefix <- Vector("", "?", "\ufffd", "😀", "漢字")
      index <- 0 until 40
      suffix <- Vector("", "x", "?", "😀")
      broken <- invalid
    do
      val value = prefix + "a".repeat(index) + broken + suffix
      val out = new BinaryOutput(0)
      out.writeString("already written λ")
      val before = out.toByteArray
      intercept[IllegalArgumentException](out.writeString(value))
      assertEquals(out.toByteArray.toSeq, before.toSeq)
      out.writeInt(77)
      val in = new BinaryInput(out.toByteArray)
      assertEquals(in.readString(), "already written λ")
      assertEquals(in.readInt(), 77)
      in.requireEnd()
  }

  test("replacement fallback respects length and sized collection boundaries") {
    val out = new BinaryOutput(0)
    val body = frame("\ufffd😀".getBytes(utf8))
    out.writeLong(-1)
    out.writeLong(body.length)
    out.writeFixed(Bytes.fromArray(body))
    out.writeLong(0)
    out.writeString("following")
    val in = new BinaryInput(out.toByteArray)
    assertEquals(in.readArrayStart(), 1L)
    assertEquals(in.readString(), "\ufffd😀")
    assertEquals(in.arrayNext(), 0L)
    assertEquals(in.readString(), "following")
    in.requireEnd()
    val truncated = new BinaryOutput(0)
    truncated.writeLong(-1)
    truncated.writeLong(body.length - 1)
    truncated.writeFixed(Bytes.fromArray(body))
    truncated.writeLong(0)
    val short = new BinaryInput(truncated.toByteArray)
    short.readArrayStart()
    intercept[AvroDecodingException](short.readString())
  }

  test("short-string threshold and one-byte length-prefix boundary retain exact UTF-8 and atomic rejection") {
    val thresholdValues = for
      size <- Vector(15, 16, 17)
      unit <- Vector("a", "é", "漢", "\ufffd")
    yield unit.repeat(size)
    val supplementary = Vector("😀".repeat(7) + "a", "😀".repeat(8), "😀".repeat(8) + "a")
    val prefixBoundary = Vector("a".repeat(63), "a".repeat(64), "漢".repeat(21), "漢".repeat(21) + "a")
    (thresholdValues ++ supplementary ++ prefixBoundary).foreach { value =>
      val expected = value.getBytes(utf8)
      val out = new BinaryOutput(0)
      out.writeString(value)
      assertEquals(out.toByteArray.toSeq, frame(expected).toSeq)
      if expected.length == 63 then assertEquals(out.toByteArray.head & 0xff, 126)
      if expected.length == 64 then assertEquals(out.toByteArray.take(2).map(_ & 0xff).toSeq, Seq(128, 1))
    }
    for size <- Vector(15, 16, 17); surrogate <- Vector('\ud800', '\udfff') do
      val out = new BinaryOutput(0)
      out.writeString("preserved")
      val before = out.toByteArray
      val invalid = "a".repeat(size - 1) + surrogate
      intercept[IllegalArgumentException](out.writeString(invalid))
      assertEquals(out.toByteArray.toSeq, before.toSeq)
  }
