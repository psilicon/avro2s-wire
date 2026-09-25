package avro2s.wire.runtime

import munit.FunSuite

final class BinaryRuntimeSuite extends FunSuite:
  private def encoded(write: BinaryOutput => Unit): Array[Byte] =
    val out = new BinaryOutput(0)
    write(out)
    out.toByteArray

  private def input(write: BinaryOutput => Unit): BinaryInput = new BinaryInput(encoded(write))

  test("integer encoding uses Avro zigzag wire bytes") {
    val vectors = List(
      0 -> List(0), -1 -> List(1), 1 -> List(2), -2 -> List(3),
      64 -> List(128, 1), -64 -> List(127),
      Int.MaxValue -> List(254, 255, 255, 255, 15),
      Int.MinValue -> List(255, 255, 255, 255, 15)
    )
    vectors.foreach { (value, expected) =>
      val bytes = encoded(_.writeInt(value))
      assertEquals(bytes.map(_ & 0xff).toList, expected)
      val in = new BinaryInput(bytes)
      assertEquals(in.readInt(), value)
      in.requireEnd()
    }
  }

  test("integer and long extremes and deterministic random values round-trip") {
    val random = new java.util.Random(29101L)
    val ints = Array(Int.MinValue, Int.MaxValue, 0, -1, 1) ++ Array.fill(2000)(random.nextInt())
    val longs = Array(Long.MinValue, Long.MaxValue, 0L, -1L, 1L) ++ Array.fill(2000)(random.nextLong())
    val in = input { out =>
      ints.foreach(out.writeInt)
      longs.foreach(out.writeLong)
    }
    ints.foreach(value => assertEquals(in.readInt(), value))
    longs.foreach(value => assertEquals(in.readLong(), value))
    in.requireEnd()
  }

  test("floating point wire data is little endian and preserves raw bits") {
    assertEquals(encoded(_.writeFloat(1.0f)).map(_ & 0xff).toList, List(0, 0, 128, 63))
    assertEquals(encoded(_.writeDouble(1.0d)).map(_ & 0xff).toList, List(0, 0, 0, 0, 0, 0, 240, 63))
    val floatBits = List(0, Int.MinValue, 0x7f800000, 0xff800000, 0x7fc01234, 1)
    val doubleBits = List(0L, Long.MinValue, 0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff8000012345678L, 1L)
    val in = input { out =>
      floatBits.foreach(bits => out.writeFloat(java.lang.Float.intBitsToFloat(bits)))
      doubleBits.foreach(bits => out.writeDouble(java.lang.Double.longBitsToDouble(bits)))
    }
    floatBits.foreach(bits => assertEquals(java.lang.Float.floatToRawIntBits(in.readFloat()), bits))
    doubleBits.foreach(bits => assertEquals(java.lang.Double.doubleToRawLongBits(in.readDouble()), bits))
    in.requireEnd()
  }

  test("boolean, null, enum and union indices round-trip") {
    val in = input { out =>
      out.writeNull()
      out.writeBoolean(false)
      out.writeBoolean(true)
      out.writeEnum(123)
      out.writeIndex(Int.MaxValue)
    }
    in.readNull()
    assertEquals(in.readBoolean(), false)
    assertEquals(in.readBoolean(), true)
    assertEquals(in.readEnum(), 123)
    assertEquals(in.readIndex(), Int.MaxValue)
    in.requireEnd()
  }

  test("UTF-8 encodes ASCII, multibyte characters and supplementary code points") {
    val strings = List("", "plain text", "\u0000\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff", "Avro 日本語 🥑 𝄞")
    strings.foreach { string =>
      val bytes = string.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val expected = encoded { out =>
        out.writeLong(bytes.length.toLong)
        out.writeFixed(Bytes.fromArray(bytes))
      }
      assertEquals(encoded(_.writeString(string)).toList, expected.toList)
      val in = new BinaryInput(expected)
      assertEquals(in.readString(), string)
      in.requireEnd()
    }
  }

  test("UTF-8 rejects malformed, overlong, surrogate and out-of-range sequences") {
    val invalid = List(
      List(0x80), List(0xc0, 0x80), List(0xc2), List(0xc2, 0x20),
      List(0xe0, 0x80, 0x80), List(0xed, 0xa0, 0x80), List(0xe2, 0x82),
      List(0xf0, 0x80, 0x80, 0x80), List(0xf4, 0x90, 0x80, 0x80),
      List(0xf0, 0x90, 0x80), List(0xf5, 0x80, 0x80, 0x80), List(0xff)
    )
    invalid.foreach { bytes =>
      val in = input { out =>
        out.writeLong(bytes.size.toLong)
        out.writeFixed(Bytes.fromArray(bytes.map(_.toByte).toArray))
      }
      intercept[AvroDecodingException](in.readString())
    }
  }

  test("string encoding rejects unpaired surrogates before writing") {
    val invalid = List(Array(0xd800.toChar), Array(0xdc00.toChar), Array(0xd800.toChar, 'a'))
    invalid.foreach { chars =>
      val out = new BinaryOutput()
      intercept[IllegalArgumentException](out.writeString(new String(chars)))
      assertEquals(out.size, 0)
    }
  }

  test("Bytes copies public array boundaries and uses value equality") {
    val source = Array[Byte](1, 2, 3)
    val first = Bytes.fromArray(source)
    source(0) = 9
    val exported = first.toArray
    exported(1) = 9
    val second = Bytes.fromArray(Array[Byte](1, 2, 3))
    assertEquals(first, second)
    assertEquals(first.hashCode(), second.hashCode())
    assertEquals(first.size, 3)
    assertEquals(Bytes.empty, Bytes.fromArray(Array.emptyByteArray))
    assert(!first.equals(Array[Byte](1, 2, 3)))
  }

  test("decoded bytes and fixed own their data") {
    val bytes = encoded { out =>
      out.writeBytes(Bytes.fromArray(Array[Byte](4, 5)))
      out.writeFixed(Bytes.fromArray(Array[Byte](6, 7)))
    }
    val in = new BinaryInput(bytes)
    val first = in.readBytes()
    val second = in.readFixed(2)
    java.util.Arrays.fill(bytes, 0.toByte)
    assertEquals(first.toArray.toList, List[Byte](4, 5))
    assertEquals(second.toArray.toList, List[Byte](6, 7))
    in.requireEnd()
  }

  test("output reset and exported data have independent ownership") {
    val out = new BinaryOutput(0)
    out.writeInt(1)
    val beforeReset = out.toByteArray
    out.reset()
    assertEquals(out.size, 0)
    out.writeInt(2)
    assertEquals(beforeReset.toList, List[Byte](2))
    val exported = out.toByteArray
    exported(0) = 127
    assertEquals(out.toByteArray.toList, List[Byte](4))
  }

  test("array and map empty encodings each contain exactly one terminator") {
    val bytes = encoded { out =>
      out.writeArrayStart(0)
      out.writeArrayEnd()
      out.writeMapStart(0)
      out.writeMapEnd()
    }
    assertEquals(bytes.toList, List[Byte](0, 0))
    val in = new BinaryInput(bytes)
    assertEquals(in.readArrayStart(), 0L)
    assertEquals(in.readMapStart(), 0L)
    in.requireEnd()
  }

  test("collections decode multiple positive and sized negative blocks") {
    val in = input { out =>
      out.writeLong(1)
      out.writeInt(42)
      out.writeLong(-2)
      out.writeLong(2)
      out.writeInt(1)
      out.writeInt(2)
      out.writeLong(0)
    }
    assertEquals(in.readArrayStart(), 1L)
    assertEquals(in.readInt(), 42)
    assertEquals(in.arrayNext(), 2L)
    assertEquals(in.readInt(), 1)
    assertEquals(in.readInt(), 2)
    assertEquals(in.arrayNext(), 0L)
    in.requireEnd()
  }

  test("nested collection boundaries restore the enclosing sized block") {
    val outerContents = encoded { out =>
      out.writeString("key")
      out.writeLong(-2)
      out.writeLong(2)
      out.writeInt(1)
      out.writeInt(2)
      out.writeLong(0)
    }
    val in = input { out =>
      out.writeLong(-1)
      out.writeLong(outerContents.length.toLong)
      out.writeFixed(Bytes.fromArray(outerContents))
      out.writeLong(0)
      out.writeInt(17)
    }
    assertEquals(in.readMapStart(), 1L)
    assertEquals(in.readString(), "key")
    assertEquals(in.readArrayStart(), 2L)
    assertEquals(in.readInt(), 1)
    assertEquals(in.readInt(), 2)
    assertEquals(in.arrayNext(), 0L)
    assertEquals(in.mapNext(), 0L)
    assertEquals(in.readInt(), 17)
    in.requireEnd()
  }

  test("sized collection blocks reject lengths shorter or longer than their contents") {
    List(1L, 3L).foreach { blockSize =>
      val in = input { out =>
        out.writeLong(-2)
        out.writeLong(blockSize)
        out.writeInt(1)
        out.writeInt(2)
        out.writeLong(0)
      }
      intercept[AvroDecodingException] {
        in.readArrayStart()
        in.readInt()
        in.readInt()
        in.arrayNext()
      }
    }
  }

  test("a sized block can contain zero-byte null items") {
    val in = input { out =>
      out.writeLong(-2)
      out.writeLong(0)
      out.writeLong(0)
    }
    assertEquals(in.readArrayStart(), 2L)
    in.readNull()
    in.readNull()
    assertEquals(in.arrayNext(), 0L)
    in.requireEnd()
  }

  test("malformed varints reject overflow and unterminated data") {
    val badInts = List(List(0x80), List(0xff, 0xff, 0xff, 0xff, 0x10), List.fill(6)(0x80))
    badInts.foreach(bytes => intercept[AvroDecodingException](new BinaryInput(bytes.map(_.toByte).toArray).readInt()))
    val badLongs = List(List(0x80), List.fill(9)(0xff) :+ 2, List.fill(11)(0x80))
    badLongs.foreach(bytes => intercept[AvroDecodingException](new BinaryInput(bytes.map(_.toByte).toArray).readLong()))
  }

  test("invalid boolean, indices and lengths are rejected") {
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](2)).readBoolean())
    intercept[AvroDecodingException](input(_.writeInt(-1)).readEnum())
    intercept[AvroDecodingException](input(_.writeLong(-1)).readIndex())
    intercept[AvroDecodingException](input(_.writeLong(Int.MaxValue.toLong + 1)).readIndex())
    intercept[AvroDecodingException](input(_.writeLong(-1)).readString())
    intercept[AvroDecodingException](input(_.writeLong(Long.MaxValue)).readBytes())
    intercept[AvroDecodingException](new BinaryInput(Array.emptyByteArray).readFixed(-1))
    intercept[AvroDecodingException](input(_.writeLong(Long.MinValue)).readArrayStart())
    intercept[AvroDecodingException](input { out => out.writeLong(-1); out.writeLong(-1) }.readMapStart())
    intercept[IllegalArgumentException](new BinaryOutput().writeArrayStart(-1))
  }

  test("configured byte and string limits reject oversized data") {
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](0), DecodeLimits(maxInputBytes = Some(0))))
    val string = encoded(_.writeString("large"))
    intercept[AvroDecodingException](new BinaryInput(string, DecodeLimits(maxStringBytes = Some(4))).readString())
    val bytes = encoded(_.writeBytes(Bytes.fromArray(Array[Byte](1, 2))))
    intercept[AvroDecodingException](new BinaryInput(bytes, DecodeLimits(maxBytesLength = Some(1))).readBytes())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](1, 2), DecodeLimits(maxBytesLength = Some(1))).readFixed(2))
  }

  test("collection budget is cumulative across blocks and collections") {
    val bytes = encoded { out =>
      out.writeLong(2)
      out.writeLong(0)
      out.writeLong(2)
      out.writeLong(0)
    }
    val in = new BinaryInput(bytes, DecodeLimits(maxCollectionItems = Some(3L)))
    assertEquals(in.readArrayStart(), 2L) // Two null items consume no bytes.
    assertEquals(in.arrayNext(), 0L)
    intercept[AvroDecodingException](in.readArrayStart())
    val blocks = new BinaryInput(encoded { out => out.writeLong(2); out.writeLong(2) }, DecodeLimits(maxCollectionItems = Some(3L)))
    assertEquals(blocks.readArrayStart(), 2L)
    intercept[AvroDecodingException](blocks.arrayNext())
  }

  test("record and collection depth share a bounded budget") {
    val in = new BinaryInput(encoded(_.writeLong(1)), DecodeLimits(maxNestingDepth = Some(1)))
    in.enterRecord()
    intercept[AvroDecodingException](in.readArrayStart())
    in.leaveRecord()
    val records = new BinaryInput(Array.emptyByteArray, DecodeLimits(maxNestingDepth = Some(1)))
    records.enterRecord()
    intercept[AvroDecodingException](records.enterRecord())
    records.leaveRecord()
    records.requireEnd()
    intercept[AvroDecodingException](records.leaveRecord())
  }

  test("incomplete or mismatched collection reading is rejected") {
    val incomplete = input(_.writeLong(1))
    incomplete.readArrayStart()
    intercept[AvroDecodingException](incomplete.requireEnd())
    intercept[AvroDecodingException](incomplete.mapNext())
    intercept[AvroDecodingException](new BinaryInput(Array[Byte](0)).arrayNext())
  }

  test("codec convenience methods reject every truncated prefix and trailing bytes") {
    val codec = new AvroCodec[(Long, String, Double)]:
      override val schemaJson = "{}"
      override def write(value: (Long, String, Double), out: AvroOutput): Unit =
        out.writeLong(value._1)
        out.writeString(value._2)
        out.writeDouble(value._3)
      override def read(in: AvroInput): (Long, String, Double) =
        (in.readLong(), in.readString(), in.readDouble())
    val value = (Long.MinValue, "hello 🥑", -0.0d)
    val bytes = codec.encode(value)
    assertEquals(codec.decode(bytes), value)
    (0 until bytes.length).foreach { length =>
      intercept[AvroDecodingException](codec.decode(bytes.take(length)))
    }
    intercept[AvroDecodingException](codec.decode(bytes ++ Array[Byte](0)))
    intercept[AvroDecodingException](codec.decode(bytes, DecodeLimits(maxStringBytes = Some(1))))
  }
