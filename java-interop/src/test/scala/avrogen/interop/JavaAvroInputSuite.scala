package avrogen.interop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import munit.FunSuite
import org.apache.avro.Schema
import org.apache.avro.io.{BinaryDecoder, DecoderFactory, EncoderFactory}

final class JavaAvroInputSuite extends FunSuite:
  private def read(buffer: ByteBuffer): avrogen.runtime.Bytes =
    val decoder = new BinaryDecoder():
      override def readBytes(reuse: ByteBuffer): ByteBuffer = buffer
    new JavaAvroInput(decoder).readBytes()

  test("bytes use the remaining slice and preserve the source buffer position") {
    val storage = Array[Byte](99, 98, 1, 2, 97, 96)
    val parent = ByteBuffer.wrap(storage)
    parent.position(1)
    parent.limit(5)
    val slice = parent.slice()
    slice.position(1)
    slice.limit(3)
    slice.mark()

    val decoded = read(slice)
    assertEquals(decoded.toArray.toSeq, Seq[Byte](1, 2))
    assertEquals(slice.position(), 1)
    assertEquals(slice.limit(), 3)
    slice.reset()
    storage(2) = 42
    assertEquals(decoded.toArray.toSeq, Seq[Byte](1, 2))
  }

  test("bytes accept direct buffers") {
    val direct = ByteBuffer.allocateDirect(4)
    direct.put(Array[Byte](99, 1, 2, 98))
    direct.position(1)
    direct.limit(3)

    assertEquals(read(direct).toArray.toSeq, Seq[Byte](1, 2))
    assertEquals(direct.position(), 1)
  }

  test("bytes accept read-only buffers and empty remaining ranges") {
    val buffer = ByteBuffer.wrap(Array[Byte](99, 1, 2, 98)).asReadOnlyBuffer()
    buffer.position(1)
    buffer.limit(3)
    assertEquals(read(buffer).toArray.toSeq, Seq[Byte](1, 2))

    buffer.position(3)
    assertEquals(read(buffer).size, 0)
    assertEquals(buffer.position(), 3)
  }

  test("null hooks advance Java Avro validating encoder and decoder state") {
    val schema = new Schema.Parser().parse(
      """{"type":"record","name":"NullThenInt","fields":[{"name":"nothing","type":"null"},{"name":"number","type":"int"}]}"""
    )
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().validatingEncoder(
      schema, EncoderFactory.get().binaryEncoder(bytes, null)
    )
    val out = new JavaAvroOutput(encoder)
    out.writeNull()
    out.writeInt(7)
    encoder.flush()
    assertEquals(bytes.toByteArray.toSeq, Seq[Byte](14))

    val decoder = DecoderFactory.get().validatingDecoder(
      schema, DecoderFactory.get().binaryDecoder(bytes.toByteArray, null)
    )
    val in = new JavaAvroInput(decoder)
    in.readNull()
    assertEquals(in.readInt(), 7)
  }
