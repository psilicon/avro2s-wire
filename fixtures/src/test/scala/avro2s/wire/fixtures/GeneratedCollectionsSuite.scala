package avro2s.wire.fixtures

import avro2s.wire.javabackend.JavaAvroOutput
import avro2s.wire.runtime.{AvroDecodingException, BinaryInput, BinaryOutput, DecodeLimits}
import java.io.ByteArrayOutputStream
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.io.{Encoder, EncoderFactory}
import scala.collection.immutable.{HashMap, ListMap, TreeMap, VectorMap}
import scala.jdk.CollectionConverters.*

final class GeneratedCollectionsSuite extends munit.FunSuite:
  private def encoded(write: Encoder => Unit): Array[Byte] =
    val buffer = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(buffer, null)
    write(encoder)
    encoder.flush()
    buffer.toByteArray

  test("generated codecs decode collections larger than the former million-item ceiling") {
    val count = 1000001
    val out = new BinaryOutput()
    out.writeArrayStart(count)
    val prefix = out.toByteArray
    // Each long zero occupies one byte; the array and following empty map each
    // end with one zero byte. Construct no second million-element collection.
    val bytes = new Array[Byte](prefix.length + count + 2)
    System.arraycopy(prefix, 0, bytes, 0, prefix.length)
    val value = Blocks.codec.decode(bytes)
    assertEquals(value.values.size, count)
    assert(value.values.forall(_ == 0L))
    assertEquals(value.labels, Map.empty[String, String])
  }

  test("map builders preserve duplicate keys across blocks and colliding String hashes") {
    // Aa and BB have the same String hash. Equal-length combinations preserve
    // that collision and exercise the immutable map's collision-node path.
    val keys = Vector.fill(5)(Vector("Aa", "BB")).foldLeft(Vector("")) { (prefixes, pieces) =>
      for prefix <- prefixes; piece <- pieces yield prefix + piece
    }
    assertEquals(keys.map(_.hashCode).distinct.size, 1)
    val entries = keys.zipWithIndex.map((key, index) => key -> s"value-$index-λ") ++
      Vector(keys.head -> "last-first", keys.last -> "last-last")
    val expected = Blocks(Vector.empty, entries.toMap)
    val schema = new Schema.Parser().parse(Blocks.codec.schemaJson)
    for firstCount <- Vector(1, 4, 5, 32) do
      val first = encoded(out => entries.take(firstCount).foreach { (key, value) => out.writeString(key); out.writeString(value) })
      val bytes = encoded { out =>
        out.writeLong(0L) // Empty values array.
        out.writeLong(-firstCount.toLong)
        out.writeLong(first.length.toLong)
        out.writeFixed(first)
        out.writeLong((entries.size - firstCount).toLong)
        entries.drop(firstCount).foreach { (key, value) => out.writeString(key); out.writeString(value) }
        out.writeLong(0L)
      }
      assertEquals(Blocks.codec.decode(bytes), expected)
      val javaValue = new GenericDatumReader[GenericRecord](schema).read(null,
        org.apache.avro.io.DecoderFactory.get().binaryDecoder(bytes, null))
      val javaMap = javaValue.get("labels").asInstanceOf[java.util.Map[CharSequence, CharSequence]]
        .asScala.iterator.map((key, value) => key.toString -> value.toString).toMap
      assertEquals(javaMap, expected.labels)
      val following = Blocks(Vector(99L), Map("following" -> "record"))
      val input = new BinaryInput(bytes ++ Blocks.codec.encode(following))
      assertEquals(Blocks.codec.read(input), expected)
      assertEquals(Blocks.codec.read(input), following)
      input.requireEnd()
      // Duplicate wire entries still count towards the input's item budget.
      intercept[AvroDecodingException] {
        Blocks.codec.decode(bytes, DecodeLimits.default.copy(maxCollectionItems = Some(entries.size - 1L)))
      }
  }

  test("collection traversal preserves vector slices and arbitrary immutable map implementations") {
    val numbers = Vector.tabulate(1100)(i => i.toLong * 10001L).slice(13, 1060)
    val entries = Vector.tabulate(40)(i => s"key-$i" -> s"value-$i-λ")
    val maps: Vector[Map[String, String]] = Vector(
      Map.empty, Map("single" -> "value"), Map.from(entries.take(4)),
      HashMap.from(entries), ListMap.from(entries), VectorMap.from(entries), TreeMap.from(entries)
    )
    val schema = new Schema.Parser().parse(Blocks.codec.schemaJson)
    maps.foreach { labels =>
      val expected = Blocks(numbers, labels)
      // The blocking encoder needs startItem callbacks and can split a large
      // collection into several sized blocks while traversing it.
      val buffer = new ByteArrayOutputStream()
      val encoder = new EncoderFactory().configureBlockSize(64).blockingBinaryEncoder(buffer, null)
      Blocks.codec.write(expected, new JavaAvroOutput(encoder))
      encoder.flush()
      assertEquals(Blocks.codec.decode(buffer.toByteArray), expected)
      val javaValue = new GenericDatumReader[GenericRecord](schema).read(null,
        org.apache.avro.io.DecoderFactory.get().binaryDecoder(Blocks.codec.encode(expected), null))
      val javaNumbers = javaValue.get("values").asInstanceOf[java.util.Collection[java.lang.Long]]
        .asScala.iterator.map(_.longValue).toVector
      val javaLabels = javaValue.get("labels").asInstanceOf[java.util.Map[CharSequence, CharSequence]]
        .asScala.iterator.map((key, value) => key.toString -> value.toString).toMap
      assertEquals(javaNumbers, expected.values)
      assertEquals(javaLabels, expected.labels)
    }
    val nested = CollectionEdges(Vector(Map.empty, HashMap.from(entries.map((key, value) => key -> value.length.toLong))),
      VectorMap("empty" -> Vector.empty[Int], "slice" -> Vector.tabulate(1100)(identity).slice(31, 1070)), 123L)
    assertEquals(CollectionEdges.codec.decode(CollectionEdges.codec.encode(nested)), nested)
  }
