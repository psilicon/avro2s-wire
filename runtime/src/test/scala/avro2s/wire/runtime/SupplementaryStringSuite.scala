package avro2s.wire.runtime

import java.nio.charset.StandardCharsets

/** Supplementary Unicode and mixed-tail regression cases for the strict string writer. */
final class SupplementaryStringSuite extends munit.FunSuite:
  private def check(value: String): Unit =
    val expected = value.getBytes(StandardCharsets.UTF_8)
    val out = new BinaryOutput(0)
    out.writeInt(17)
    out.writeString(value)
    out.writeInt(-731)
    val in = new BinaryInput(out.toByteArray)
    assertEquals(in.readInt(), 17)
    assertEquals(in.readLong(), expected.length.toLong)
    assertEquals(in.readFixed(expected.length).toArray.toSeq, expected.toSeq)
    assertEquals(in.readInt(), -731)
    in.requireEnd()

  test("supplementary-only strings retain exact JDK bytes through thresholds and large values") {
    val random = new java.util.Random(818713L)
    Vector(1, 7, 8, 9, 15, 16, 17, 63, 64, 65, 4096).foreach { count =>
      val chars = new java.lang.StringBuilder()
      for index <- 0 until count do
        val point = index % 3 match
          case 0 => 0x10000
          case 1 => 0x10ffff
          case _ => 0x10000 + random.nextInt(0x100000)
        chars.appendCodePoint(point)
      check(chars.toString)
    }
  }

  test("supplementary-led mixed text and malformed pairs preserve exact output and failure atomicity") {
    Vector(13, 14, 15, 59, 60, 127, 4095).foreach { ascii => check("😀" + "a".repeat(ascii)) }
    check("😀" + "漢".repeat(19) + "aa") // 63 encoded bytes.
    check("😀" + "漢".repeat(19) + "aaa") // 64 encoded bytes.
    check("😀a漢".repeat(1365) + "😀")
    for
      pairs <- Vector(1, 8, 9, 64, 512)
      middle <- Vector("a", "é", "漢", "\ufffd", "?")
    do
      val prefix = "😀".repeat(pairs)
      check(prefix + middle)
      check(prefix + middle + "𐀀")
      for invalid <- Vector("\ud800", "\udfff", "\ud800a", "\ud800\ud800") do
        val out = new BinaryOutput(0)
        out.writeString("preserved λ")
        val before = out.toByteArray
        intercept[IllegalArgumentException](out.writeString(prefix + invalid))
        assertEquals(out.toByteArray.toSeq, before.toSeq)
  }
