package avro2s.wire.fixtures

import avro2s.wire.javabackend.JavaAvroOutput
import avro2s.wire.runtime.{BinaryInput, BinaryOutput}
import java.io.ByteArrayOutputStream
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.{DecoderFactory, EncoderFactory}
import scala.jdk.CollectionConverters.*

final class BulkIntegerInteropSuite extends munit.FunSuite:
  test("bulk defaults interoperate with Java blocking encoders and independent array readers") {
    val intSchema = new Schema.Parser().parse("""{"type":"array","items":"int"}""")
    val longSchema = new Schema.Parser().parse("""{"type":"array","items":"long"}""")
    val integerCases = Vector(Vector.empty[Int], Vector(Int.MinValue, -1, 0, 1, Int.MaxValue),
      Vector.tabulate(1100)(i => i * 10001).slice(19, 1060))
    val longCases = Vector(Vector.empty[Long], Vector(Long.MinValue, -1L, 0L, 1L, Long.MaxValue),
      Vector.tabulate(1100)(i => Long.MinValue + i * 10001L).slice(19, 1060))
    integerCases.foreach { values =>
      val stream = new ByteArrayOutputStream()
      val encoder = new EncoderFactory().configureBlockSize(64).blockingBinaryEncoder(stream, null)
      new JavaAvroOutput(encoder).writeIntArray(values)
      encoder.flush()
      val decoder = DecoderFactory.get().binaryDecoder(stream.toByteArray, null)
      val javaValues = new GenericDatumReader[java.util.Collection[java.lang.Integer]](intSchema).read(null, decoder)
      assertEquals(javaValues.asScala.iterator.map(_.intValue).toVector, values)
      assert(decoder.isEnd)
      val in = new BinaryInput(stream.toByteArray)
      val actual = Vector.newBuilder[Int]
      var remaining = in.readArrayStart()
      while remaining != 0L do
        while remaining > 0L do
          actual += in.readInt()
          remaining -= 1L
        remaining = in.arrayNext()
      assertEquals(actual.result(), values)
      in.requireEnd()
      val native = new BinaryOutput(0)
      native.writeIntArray(values)
      val nativeDecoder = DecoderFactory.get().binaryDecoder(native.toByteArray, null)
      val nativeValues = new GenericDatumReader[java.util.Collection[java.lang.Integer]](intSchema).read(null, nativeDecoder)
      assertEquals(nativeValues.asScala.iterator.map(_.intValue).toVector, values)
      assert(nativeDecoder.isEnd)
    }
    longCases.foreach { values =>
      val stream = new ByteArrayOutputStream()
      val encoder = new EncoderFactory().configureBlockSize(64).blockingBinaryEncoder(stream, null)
      new JavaAvroOutput(encoder).writeLongArray(values)
      encoder.flush()
      val decoder = DecoderFactory.get().binaryDecoder(stream.toByteArray, null)
      val javaValues = new GenericDatumReader[java.util.Collection[java.lang.Long]](longSchema).read(null, decoder)
      assertEquals(javaValues.asScala.iterator.map(_.longValue).toVector, values)
      assert(decoder.isEnd)
      val native = new BinaryOutput(0)
      native.writeLongArray(values)
      val nativeDecoder = DecoderFactory.get().binaryDecoder(native.toByteArray, null)
      val nativeValues = new GenericDatumReader[java.util.Collection[java.lang.Long]](longSchema).read(null, nativeDecoder)
      assertEquals(nativeValues.asScala.iterator.map(_.longValue).toVector, values)
      assert(nativeDecoder.isEnd)
    }
  }

  test("generated primitive arrays dispatch whole arrays without bypassing enclosing map or option callbacks") {
    val value = EdgeCases(Vector(Some(9), None), Some(Vector(Map("one" -> 1L))), Vector(null), Empty())
    assertEquals(EdgeCases.codec.decode(EdgeCases.codec.encode(value)), value)
    val nested = CollectionEdges(Vector(Map("one" -> 1L)),
      Map("values" -> Vector(Int.MinValue, 0, Int.MaxValue), "empty" -> Vector.empty), 44L)
    val buffer = new ByteArrayOutputStream()
    val encoder = new EncoderFactory().configureBlockSize(64).blockingBinaryEncoder(buffer, null)
    CollectionEdges.codec.write(nested, new JavaAvroOutput(encoder))
    encoder.flush()
    assertEquals(CollectionEdges.codec.decode(buffer.toByteArray), nested)
    assertEquals(CollectionEdges.codec.decode(CollectionEdges.codec.encode(nested)), nested)
  }
