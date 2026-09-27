package avro2s.wire.runtime

import java.nio.charset.StandardCharsets
import munit.FunSuite

final class WriterSettingsSuite extends FunSuite:
  private val utf8 = StandardCharsets.UTF_8
  private val replacing = WriterSettings(malformedStrings = MalformedStringPolicy.Replace)

  private val stringCodec = new AvroCodec[String]:
    override val schemaJson = "\"string\""
    override def read(in: AvroInput): String = in.readString()
    override def write(value: String, out: AvroOutput): Unit = out.writeString(value)

  test("writer settings reject null and retain strict immutable defaults") {
    assertEquals(WriterSettings.default.malformedStrings, MalformedStringPolicy.Reject)
    assertEquals(WriterSettings().copy(malformedStrings = MalformedStringPolicy.Replace), replacing)
    assertEquals(WriterSettings.default.malformedStrings, MalformedStringPolicy.Reject)
    intercept[IllegalArgumentException](WriterSettings(malformedStrings = null))
    intercept[IllegalArgumentException](new BinaryOutput(settings = null))
    intercept[IllegalArgumentException](stringCodec.encode("valid", null))
  }

  test("replacement writes exactly the JDK bytes for valid and malformed strings across size thresholds") {
    val fragments = Vector(
      "", "?", "é漢", "😀", "\ud83d", "\ude00", "\ud800x", "\ud800\ud800",
      "\udfff\ud800", "\ud800\udfff\udfff", "?😀\ud800?", "😀\udfff😀"
    )
    for
      padding <- Vector(0, 1, 7, 8, 14, 15, 16, 17, 31, 63, 64, 1024)
      fragment <- fragments
      value <- Vector("a".repeat(padding) + fragment, fragment + "a".repeat(padding))
    do
      val expected = value.getBytes(utf8)
      val out = new BinaryOutput(0, replacing)
      out.writeInt(37)
      out.writeString(value)
      out.writeInt(-79)
      val in = new BinaryInput(out.toByteArray)
      assertEquals(in.readInt(), 37)
      assertEquals(in.readBytes().toArray.toSeq, expected.toSeq)
      assertEquals(in.readInt(), -79)
      in.requireEnd()
  }

  test("default and explicit rejection remain atomic for short and long malformed strings") {
    for
      settings <- Vector(WriterSettings.default, WriterSettings(MalformedStringPolicy.Reject))
      length <- Vector(1, 15, 16, 17, 64, 1024)
      surrogate <- Vector('\ud800', '\udfff')
    do
      val value = "a".repeat(length - 1) + surrogate
      val out = new BinaryOutput(0, settings)
      out.writeString("preserved 😀")
      val before = out.toByteArray
      intercept[IllegalArgumentException](out.writeString(value))
      assertEquals(out.toByteArray.toSeq, before.toSeq)
      intercept[IllegalArgumentException](stringCodec.encode(value))
      intercept[IllegalArgumentException](stringCodec.encode(value, settings))
  }

  test("replacement applies to map keys through the ordinary writer API") {
    val key = "broken\ud800key"
    val out = new BinaryOutput(0, replacing)
    out.writeMapStart(1)
    out.startItem()
    out.writeString(key)
    out.writeString("value\udfff")
    out.writeMapEnd()
    val in = new BinaryInput(out.toByteArray)
    assertEquals(in.readMapStart(), 1L)
    assertEquals(in.readString(), new String(key.getBytes(utf8), utf8))
    assertEquals(in.readString(), "value?")
    assertEquals(in.mapNext(), 0L)
    in.requireEnd()
  }

  test("codec settings and reused outputs retain independent owned payloads") {
    val firstValue = "😀\ud800?"
    val firstExpected = stringCodec.encode(new String(firstValue.getBytes(utf8), utf8))
    val out = new BinaryOutput(0, replacing)
    stringCodec.write(firstValue, out)
    val first = out.toByteArray
    assertEquals(first.toSeq, firstExpected.toSeq)
    assertEquals(stringCodec.encode(firstValue, replacing).toSeq, firstExpected.toSeq)
    out.reset()
    val secondValue = "\udfff" + "a".repeat(1024)
    stringCodec.write(secondValue, out)
    val second = out.toByteArray
    assertEquals(first.toSeq, firstExpected.toSeq)
    assertEquals(stringCodec.decode(second), "?" + "a".repeat(1024))
    first(0) = 0
    assertEquals(stringCodec.decode(second), "?" + "a".repeat(1024))
    out.reset()
    out.writeString("")
    assertEquals(out.toByteArray.toSeq, Seq(0.toByte))
  }
