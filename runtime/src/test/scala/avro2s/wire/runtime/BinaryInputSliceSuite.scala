package avro2s.wire.runtime

final class BinaryInputSliceSuite extends munit.FunSuite:
  private val prefix = Array[Byte](-1, -1, -1, -1, -1)
  private val suffix = Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

  private def encoded(write: BinaryOutput => Unit): Array[Byte] =
    val out = new BinaryOutput()
    write(out)
    out.toByteArray

  private def sliced(body: Array[Byte], limits: DecodeLimits = DecodeLimits.default): BinaryInput =
    BinaryInput.slice(prefix ++ body ++ suffix, prefix.length, body.length, limits)

  test("slices read scalar values and text without consuming surrounding bytes") {
    val body = encoded { out =>
      out.writeInt(Int.MinValue)
      out.writeLong(Long.MaxValue)
      out.writeFloat(1.25f)
      out.writeDouble(-9.5d)
      out.writeString("café λ 🚀")
      out.writeBoolean(true)
    }
    val in = sliced(body)
    assertEquals(in.remaining, body.length)
    assertEquals(in.readInt(), Int.MinValue)
    assertEquals(in.readLong(), Long.MaxValue)
    assertEquals(in.readFloat(), 1.25f)
    assertEquals(in.readDouble(), -9.5d)
    assertEquals(in.readString(), "café λ 🚀")
    assert(in.readBoolean())
    assertEquals(in.remaining, 0)
    in.requireEnd()
    intercept[AvroDecodingException](in.readBoolean())
  }

  test("range validation rejects negative and overflowing indices before reading") {
    val bytes = Array[Byte](0, 0, 0)
    for (offset, length) <- List(
      (-1, 0), (Int.MinValue, 1), (4, 0), (Int.MaxValue, 0),
      (0, -1), (1, Int.MinValue), (0, 4), (2, 2),
      (1, Int.MaxValue), (Int.MaxValue, Int.MaxValue)
    ) do
      intercept[IllegalArgumentException](BinaryInput.slice(bytes, offset, length))
    BinaryInput.slice(bytes, 0, 0).requireEnd()
    BinaryInput.slice(bytes, bytes.length, 0).requireEnd()
    BinaryInput.slice(Array.emptyByteArray, 0, 0).requireEnd()
  }

  test("input limits and diagnostic offsets count only the selected datum") {
    val body = encoded(_.writeString("λ"))
    val in = sliced(body, DecodeLimits(maxInputBytes = Some(body.length)))
    assertEquals(in.readString(), "λ")
    in.requireEnd()
    val tooLarge = intercept[AvroDecodingException] {
      sliced(body, DecodeLimits(maxInputBytes = Some(body.length - 1)))
    }
    assert(tooLarge.getMessage.endsWith("at byte 0"))
    sliced(Array.emptyByteArray, DecodeLimits(maxInputBytes = Some(0))).requireEnd()
    intercept[AvroDecodingException](sliced(Array[Byte](0), DecodeLimits(maxInputBytes = Some(0))))

    val invalid = sliced(Array[Byte](1, 2))
    assert(invalid.readBoolean())
    val error = intercept[AvroDecodingException](invalid.readBoolean())
    assertEquals(error.getMessage, "Boolean must be encoded as 0 or 1 at byte 2")
  }

  test("requireEnd rejects trailing bytes inside the slice and ignores bytes outside it") {
    val bytes = prefix ++ Array[Byte](1, 0) ++ suffix
    val exact = BinaryInput.slice(bytes, prefix.length, 1)
    assert(exact.readBoolean())
    exact.requireEnd()
    val trailing = BinaryInput.slice(bytes, prefix.length, 2)
    assert(trailing.readBoolean())
    val error = intercept[AvroDecodingException](trailing.requireEnd())
    assertEquals(error.getMessage, "Trailing data: 1 bytes at byte 1")
  }

  test("varints cannot borrow their final bytes from outside the slice") {
    for value <- List(64L, Int.MinValue.toLong, Long.MinValue, Long.MaxValue) do
      val body = encoded(_.writeLong(value))
      val bytes = prefix ++ body ++ suffix
      val in = BinaryInput.slice(bytes, prefix.length, body.length - 1)
      intercept[AvroDecodingException](in.readLong())
    val intBody = encoded(_.writeInt(Int.MaxValue))
    val intInput = BinaryInput.slice(prefix ++ intBody ++ suffix, prefix.length, intBody.length - 1)
    intercept[AvroDecodingException](intInput.readInt())
    intercept[AvroDecodingException](sliced(Array.fill[Byte](5)(-1)).readInt())
    intercept[AvroDecodingException](sliced(Array.fill[Byte](10)(-1)).readLong())
  }

  test("string reads and skips validate UTF-8 and respect the slice end") {
    val body = encoded(_.writeString("λ🚀"))
    for read <- List[BinaryInput => Unit](
      in => { in.readString(); () }, _.skipString(),
      in => { in.readBytesAsString(); () }, in => { in.readStringAsBytes(); () }
    ) do
      val truncated = BinaryInput.slice(prefix ++ body ++ suffix, prefix.length, body.length - 1)
      intercept[AvroDecodingException](read(truncated))
      // The length itself is in bounds, but the UTF-8 sequence is incomplete.
      intercept[AvroDecodingException](read(sliced(Array[Byte](2, -62))))
      intercept[AvroDecodingException](read(sliced(Array[Byte](4, -64, -128))))
      intercept[AvroDecodingException](read(sliced(body, DecodeLimits(maxStringBytes = Some(5)))))
    val skipped = sliced(body)
    skipped.skipString()
    skipped.requireEnd()
  }

  test("nested sized blocks restore the enclosing slice boundary") {
    val body = encoded { out =>
      out.writeLong(-1) // One array element: another sized array.
      out.writeLong(4)  // Inner count, size, value and terminator.
      out.writeLong(-1)
      out.writeLong(1)
      out.writeInt(42)
      out.writeLong(0)
      out.writeLong(0)
    }
    def readInner(in: BinaryInput): Unit =
      assertEquals(in.readArrayStart(), 1L)
      assertEquals(in.readArrayStart(), 1L)
      assertEquals(in.readInt(), 42)
      assertEquals(in.arrayNext(), 0L)

    val complete = sliced(body)
    readInner(complete)
    assertEquals(complete.arrayNext(), 0L)
    complete.requireEnd()

    // The real outer terminator is present in the backing array, outside the slice.
    val truncated = BinaryInput.slice(prefix ++ body ++ suffix, prefix.length, body.length - 1)
    readInner(truncated)
    intercept[AvroDecodingException](truncated.arrayNext())
  }

  test("sized blocks cannot extend past the slice or borrow from their next block") {
    val overlong = sliced(Array[Byte](1, 4, 84)) // Count -1, size 2, only one byte remains.
    intercept[AvroDecodingException](overlong.readArrayStart())

    val shortBlock = sliced(Array[Byte](1, 2, -128, 1, 0))
    assertEquals(shortBlock.readArrayStart(), 1L)
    intercept[AvroDecodingException](shortBlock.readInt())

    val unconsumed = sliced(Array[Byte](1, 4, 84, 86, 0))
    assertEquals(unconsumed.readArrayStart(), 1L)
    assertEquals(unconsumed.readInt(), 42)
    intercept[AvroDecodingException](unconsumed.arrayNext())
    intercept[AvroDecodingException](unconsumed.requireEnd())
  }

  test("slices preserve byte ownership and byte, item and nesting limits") {
    val body = encoded { out =>
      out.writeBytes(Bytes.fromArray(Array[Byte](1, 2)))
      out.writeFixed(Bytes.fromArray(Array[Byte](3, 4)))
    }
    val backing = prefix ++ body ++ suffix
    val in = BinaryInput.slice(backing, prefix.length, body.length)
    val bytes = in.readBytes()
    val fixed = in.readFixed(2)
    in.requireEnd()
    java.util.Arrays.fill(backing, 0.toByte)
    assertEquals(bytes, Bytes.fromArray(Array[Byte](1, 2)))
    assertEquals(fixed, Bytes.fromArray(Array[Byte](3, 4)))
    intercept[AvroDecodingException](sliced(body, DecodeLimits(maxBytesLength = Some(1))).readBytes())
    intercept[AvroDecodingException](sliced(Array[Byte](1, 2), DecodeLimits(maxBytesLength = Some(1))).readFixed(2))

    val collection = encoded { out =>
      out.writeArrayStart(2)
      out.writeInt(1)
      out.writeInt(2)
      out.writeArrayEnd()
    }
    intercept[AvroDecodingException](sliced(collection, DecodeLimits(maxCollectionItems = Some(1L))).readArrayStart())
    intercept[AvroDecodingException](sliced(collection, DecodeLimits(maxNestingDepth = Some(0))).readArrayStart())
    intercept[AvroDecodingException](sliced(Array.emptyByteArray, DecodeLimits(maxNestingDepth = Some(0))).enterRecord())
  }
