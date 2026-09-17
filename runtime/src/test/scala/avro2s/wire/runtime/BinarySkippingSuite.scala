package avro2s.wire.runtime

class BinarySkippingSuite extends munit.FunSuite:
  test("skipping strings, bytes and fixed values leaves the next field intact") {
    val output = new BinaryOutput()
    output.writeString("λ 日本語 🚀")
    output.writeBytes(Bytes.fromArray(Array[Byte](1, 2, 3)))
    output.writeFixed(Bytes.fromArray(Array[Byte](4, 5)))
    output.writeLong(Long.MinValue)
    val input = new BinaryInput(output.toByteArray)
    input.skipString()
    input.skipBytes()
    input.skipFixed(2)
    assertEquals(input.readLong(), Long.MinValue)
    input.requireEnd()
  }

  test("skipped strings retain strict UTF-8 validation") {
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](4, -64, -128)).skipString())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](6, -16, -97, -102)).skipString())
  }

  test("skipping still enforces lengths, resource limits and truncation") {
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](8, 1, 2)).skipBytes())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](1)).skipBytes())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](4, 97, 98), DecodeLimits(maxStringBytes = 1)).skipString())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](4, 1, 2), DecodeLimits(maxBytesLength = 1)).skipBytes())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](1)).skipFixed(2))
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](1)).skipFixed(-1))
  }

  test("string/bytes promotions enforce both wire and reader length limits") {
    val out = new BinaryOutput()
    out.writeString("λ🚀")
    val bytes = out.toByteArray
    assertEquals(new BinaryInput(bytes).readBytesAsString(), "λ🚀")
    assertEquals(new BinaryInput(bytes).readStringAsBytes(), Bytes.fromArray("λ🚀".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
    for limits <- Vector(DecodeLimits(maxStringBytes = 1), DecodeLimits(maxBytesLength = 1)) do
      intercept[AvroDecodingException](new BinaryInput(bytes, limits).readBytesAsString())
      intercept[AvroDecodingException](new BinaryInput(bytes, limits).readStringAsBytes())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](4, -64, -128)).readBytesAsString())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](4, -64, -128)).readStringAsBytes())
  }
