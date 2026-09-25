package avro2s.wire.runtime

import munit.FunSuite

final class OptionalDecodeLimitsSuite extends FunSuite:
  private def encoded(write: BinaryOutput => Unit): Array[Byte] =
    val out = new BinaryOutput(0)
    write(out)
    out.toByteArray

  test("all resource limits are absent by default") {
    val limits = DecodeLimits()
    assertEquals(limits.maxInputBytes, None)
    assertEquals(limits.maxStringBytes, None)
    assertEquals(limits.maxBytesLength, None)
    assertEquals(limits.maxCollectionItems, None)
    assertEquals(limits.maxNestingDepth, None)
    assertEquals(DecodeLimits.default, limits)
  }

  test("default decoding accepts a string larger than the former 16 MiB ceiling") {
    val size = 16 * 1024 * 1024 + 1
    val prefix = encoded(_.writeLong(size.toLong))
    val bytes = Array.fill[Byte](prefix.length + size)('x'.toByte)
    System.arraycopy(prefix, 0, bytes, 0, prefix.length)
    val in = new BinaryInput(bytes)
    val value = in.readString()
    assertEquals(value.length, size)
    assertEquals(value.charAt(0), 'x')
    assertEquals(value.charAt(size - 1), 'x')
    in.requireEnd()
  }

  test("default decoding accepts input and bytes larger than the former 64 MiB ceilings") {
    // Build the input directly so the test needs only the input and decoded copy.
    val size = 64 * 1024 * 1024 + 1
    val prefix = encoded(_.writeLong(size.toLong))
    val bytes = new Array[Byte](prefix.length + size)
    System.arraycopy(prefix, 0, bytes, 0, prefix.length)
    bytes(prefix.length) = 42
    bytes(bytes.length - 1) = 17
    val in = new BinaryInput(bytes)
    val value = in.readBytes()
    assertEquals(value.size, size)
    assertEquals(value.unsafeArray(0), 42.toByte)
    assertEquals(value.unsafeArray(size - 1), 17.toByte)
    in.requireEnd()
  }

  test("optional byte and string ceilings admit their exact boundaries independently") {
    val text = encoded(_.writeString("λ"))
    val stringInput = new BinaryInput(text, DecodeLimits(maxStringBytes = Some(2)))
    assertEquals(stringInput.readString(), "λ")
    stringInput.requireEnd()
    val payload = encoded(_.writeBytes(Bytes.fromArray(Array[Byte](1, 2))))
    val bytesInput = new BinaryInput(payload, DecodeLimits(maxBytesLength = Some(2)))
    assertEquals(bytesInput.readBytes(), Bytes.fromArray(Array[Byte](1, 2)))
    bytesInput.requireEnd()
    val boundedInput = new BinaryInput(text, DecodeLimits(maxInputBytes = Some(text.length)))
    assertEquals(boundedInput.readString(), "λ")
    boundedInput.requireEnd()
    intercept[AvroDecodingException] {
      new BinaryInput(text, DecodeLimits(maxInputBytes = Some(text.length - 1)))
    }
  }

  test("zero remains an actual limit instead of disabling a resource ceiling") {
    new BinaryInput(Array.emptyByteArray, DecodeLimits(maxInputBytes = Some(0))).requireEnd()
    intercept[AvroDecodingException] {
      new BinaryInput(Array[Byte](0), DecodeLimits(maxInputBytes = Some(0)))
    }
    val strings = DecodeLimits(maxStringBytes = Some(0))
    assertEquals(new BinaryInput(Array[Byte](0), strings).readString(), "")
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](2, 97), strings).readString())
    val bytes = DecodeLimits(maxBytesLength = Some(0))
    assertEquals(new BinaryInput(Array[Byte](0), bytes).readBytes(), Bytes.empty)
    assertEquals(new BinaryInput(Array.emptyByteArray, bytes).readFixed(0), Bytes.empty)
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](2, 1), bytes).readBytes())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](1), bytes).readFixed(1))
    val items = DecodeLimits(maxCollectionItems = Some(0L))
    assertEquals(new BinaryInput(Array[Byte](0), items).readArrayStart(), 0L)
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](2), items).readArrayStart())
    val depth = DecodeLimits(maxNestingDepth = Some(0))
    intercept[AvroDecodingException](new BinaryInput(Array.emptyByteArray, depth).enterRecord())
    assertEquals(new BinaryInput(Array[Byte](0), depth).readArrayStart(), 0L)
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](2), depth).readArrayStart())
  }

  test("length validity is checked before narrowing even without resource limits") {
    val readers: List[BinaryInput => Unit] = List(
      in => { in.readString(); () },
      in => { in.readBytes(); () },
      in => { in.readStringAsBytes(); () },
      in => { in.readBytesAsString(); () },
      _.skipString(),
      _.skipBytes()
    )
    // 2^32 narrows to zero and 2^32+1 narrows to one: neither may be accepted.
    for size <- List(-1L, 2L, Int.MaxValue.toLong + 1L, 1L << 32, (1L << 32) + 1L, Long.MaxValue)
        read <- readers do
      val bytes = encoded { out => out.writeLong(size); out.writeFixed(Bytes.fromArray(Array[Byte](97))) }
      intercept[AvroDecodingException](read(new BinaryInput(bytes)))
  }

  test("absent item limits do not accumulate an overflowing artificial budget") {
    val in = new BinaryInput(encoded { out =>
      out.writeLong(Long.MaxValue)
      out.writeLong(Long.MaxValue)
      out.writeLong(0)
    })
    // Read only block headers; no attempt is made to materialize these counts.
    assertEquals(in.readArrayStart(), Long.MaxValue)
    assertEquals(in.arrayNext(), Long.MaxValue)
    assertEquals(in.arrayNext(), 0L)
    in.requireEnd()
  }

  test("configured cumulative item limits enforce exact Long boundaries without overflow") {
    val limits = DecodeLimits(maxCollectionItems = Some(Long.MaxValue))
    val exact = new BinaryInput(encoded { out =>
      out.writeLong(Long.MaxValue - 1)
      out.writeLong(1)
      out.writeLong(0)
    }, limits)
    assertEquals(exact.readArrayStart(), Long.MaxValue - 1)
    assertEquals(exact.arrayNext(), 1L)
    assertEquals(exact.arrayNext(), 0L)
    exact.requireEnd()
    val excess = new BinaryInput(encoded { out =>
      out.writeLong(Long.MaxValue)
      out.writeLong(1)
    }, limits)
    assertEquals(excess.readArrayStart(), Long.MaxValue)
    intercept[AvroDecodingException](excess.arrayNext())
  }

  test("depth bookkeeping has no default ceiling and preserves explicit boundaries") {
    val in = new BinaryInput(Array.emptyByteArray)
    // This checks bookkeeping, not the stack safety of recursive generated codecs.
    for _ <- 0 until 1024 do in.enterRecord()
    for _ <- 0 until 1024 do in.leaveRecord()
    in.requireEnd()
    val limited = new BinaryInput(Array.emptyByteArray, DecodeLimits(maxNestingDepth = Some(129)))
    for _ <- 0 until 129 do limited.enterRecord()
    intercept[AvroDecodingException](limited.enterRecord())
    for _ <- 0 until 129 do limited.leaveRecord()
    limited.requireEnd()
  }

  test("negative configured resource limits are invalid") {
    intercept[IllegalArgumentException](DecodeLimits(maxInputBytes = Some(-1)))
    intercept[IllegalArgumentException](DecodeLimits(maxStringBytes = Some(-1)))
    intercept[IllegalArgumentException](DecodeLimits(maxBytesLength = Some(-1)))
    intercept[IllegalArgumentException](DecodeLimits(maxCollectionItems = Some(-1L)))
    intercept[IllegalArgumentException](DecodeLimits(maxNestingDepth = Some(-1)))
  }
