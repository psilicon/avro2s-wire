package avro2s.wire.fixtures

import avro2s.wire.javabackend.JavaAvroInput
import avro2s.wire.runtime.*
import org.apache.avro.io.DecoderFactory

class EmptyCollectionsSuite extends munit.FunSuite:
  test("empty nested collections preserve following fields and sequential records") {
    val values = Vector(
      CollectionEdges(Vector.empty, Map.empty, Long.MinValue),
      CollectionEdges(Vector(Map.empty, Map("x" -> 73L), Map.empty),
        Map("empty" -> Vector.empty, "full" -> Vector(11, 12)), Long.MaxValue),
      CollectionEdges(Vector(Map.empty), Map("empty" -> Vector.empty), 19L)
    )
    val bytes = values.flatMap(value => CollectionEdges.codec.encode(value)).toArray
    val native = new BinaryInput(bytes)
    val javaDecoder = DecoderFactory.get().binaryDecoder(bytes, null)
    val javaInput = new JavaAvroInput(javaDecoder)
    values.foreach { expected =>
      assertEquals(CollectionEdges.codec.read(native), expected)
      assertEquals(CollectionEdges.codec.read(javaInput), expected)
    }
    native.requireEnd()
    assert(javaDecoder.isEnd)
  }

  test("empty nested collections preserve enclosing sized and multiple blocks") {
    // The array starts with a sized block containing an empty map, then an
    // unsized block containing {m:7}. The map has a sized block containing a: [].
    val bytes = Array[Byte](1, 2, 0, 2, 2, 2, 109, 14, 0, 0, 1, 6, 2, 97, 0, 0, -58, 1)
    val expected = CollectionEdges(Vector(Map.empty, Map("m" -> 7L)), Map("a" -> Vector.empty), 99L)
    assertEquals(CollectionEdges.codec.decode(bytes), expected)
    assertEquals(CollectionEdges.codec.read(new JavaAvroInput(
      DecoderFactory.get().binaryDecoder(bytes, null))), expected)
    for end <- 0 until bytes.length do
      intercept[AvroDecodingException](CollectionEdges.codec.decode(bytes.take(end)))
    // The first sized block must contain exactly the one-byte empty-map header.
    for invalidSize <- List[Byte](0, 4) do
      val malformed = bytes.clone()
      malformed(1) = invalidSize
      intercept[AvroDecodingException](CollectionEdges.codec.decode(malformed))
  }

  test("empty fast paths still validate array and map headers") {
    val invalid = Vector(
      Array.emptyByteArray,
      Array[Byte](-128),
      Array[Byte](0, -128),
      Array.fill[Byte](9)(-1) :+ 2.toByte,
      Array.fill[Byte](9)(-1) :+ 1.toByte,
      Array[Byte](1),
      Array[Byte](1, 1),
      Array[Byte](0, 1),
      Array[Byte](0, 1, 1),
      Array[Byte](1, 0, 0, 0)
    )
    invalid.foreach { bytes =>
      intercept[AvroDecodingException](Blocks.codec.decode(bytes))
    }
  }

  test("empty collections respect collection and nesting budgets without consuming item budget") {
    val empty = CollectionEdges(Vector.empty, Map.empty, 31L)
    assertEquals(CollectionEdges.codec.decode(CollectionEdges.codec.encode(empty),
      DecodeLimits(maxCollectionItems = Some(0L), maxNestingDepth = Some(1))), empty)
    val nested = CollectionEdges(Vector(Map.empty), Map("empty" -> Vector.empty), 17L)
    val bytes = CollectionEdges.codec.encode(nested)
    assertEquals(CollectionEdges.codec.decode(bytes,
      DecodeLimits(maxCollectionItems = Some(2L), maxNestingDepth = Some(2))), nested)
    intercept[AvroDecodingException] {
      CollectionEdges.codec.decode(bytes, DecodeLimits(maxCollectionItems = Some(1L)))
    }
    intercept[AvroDecodingException] {
      CollectionEdges.codec.decode(bytes, DecodeLimits(maxNestingDepth = Some(1)))
    }
    // The empty first array must not bypass the following nonempty map's limit.
    val followingMap = CollectionEdges(Vector.empty, Map("key" -> Vector.empty), 1L)
    intercept[AvroDecodingException] {
      CollectionEdges.codec.decode(CollectionEdges.codec.encode(followingMap),
        DecodeLimits(maxCollectionItems = Some(0L)))
    }
  }
