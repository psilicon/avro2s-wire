package avro2s.wire.fixtures

import avro2s.wire.fixtures.stacks.*
import avro2s.wire.javabackend.{JavaAvroInput, JavaAvroOutput}
import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

/** Checks deep values iteratively: case-class equals/toString are independently recursive. */
final class StackSafetySuite extends munit.FunSuite:
  private def onSmallStack(body: => Unit): Unit =
    val failure = new AtomicReference[Throwable]()
    val runnable = new Runnable:
      override def run(): Unit =
        try body
        catch case error: Throwable => failure.set(error)
    val thread = new Thread(null, runnable, "wire-stack-safety", 256 * 1024L)
    thread.setDaemon(true)
    thread.start()
    thread.join(60000L)
    assert(!thread.isAlive, "Stack-safety test did not finish within 60 seconds")
    Option(failure.get()).foreach(error => throw error)

  private def chain(depth: Int): StackNode =
    require(depth > 0)
    (1 until depth).foldLeft(StackNode(None, 0))((tail, value) => StackNode(Some(tail), value))

  private def checkChain(root: StackNode, depth: Int): Unit =
    var current = Option(root)
    var expected = depth - 1
    while current.nonEmpty do
      assertEquals(current.get.value, expected)
      current = current.get.next
      expected -= 1
    assertEquals(expected, -1)

  private def checkEvolved(root: StackNodeV2, depth: Int): Unit =
    var current = Option(root)
    var expected = depth - 1
    while current.nonEmpty do
      assertEquals(current.get.value, expected.toLong)
      assert(current.get.added)
      current = current.get.next
      expected -= 1
    assertEquals(expected, -1)

  test("stack-safe generated codecs read and write 100000 non-tail-recursive records on a small stack") {
    val codec = StackNode.stackSafeCodec
    val depth = 100000
    val value = chain(depth)
    onSmallStack {
      val encoded = codec.encode(value)
      checkChain(codec.decode(encoded), depth)
      val input = new BinaryInput(encoded ++ encoded)
      checkChain(codec.read(input), depth)
      checkChain(codec.read(input), depth)
      input.requireEnd()
    }
  }

  test("the same execution choice covers equal schemas, evolved records, and skipped recursive fields") {
    val depth = 100000
    val value = chain(depth)
    val same = new ResolvingReader(StackNode.schemaJson, StackNode.stackSafeCodec)
    val evolved = new ResolvingReader(StackNode.schemaJson, StackNodeV2.stackSafeCodec)
    val skipped = new ResolvingReader(StackNode.schemaJson, StackHead.stackSafeCodec)
    onSmallStack {
      val encoded = StackNode.stackSafeCodec.encode(value)
      checkChain(same.decode(encoded), depth)
      checkEvolved(evolved.decode(encoded), depth)
      assertEquals(skipped.decode(encoded).value, (depth - 1).toLong)
    }
  }

  test("mutually recursive codecs share one stack-safe execution") {
    val depth = 20000
    val value = (1 until depth).foldLeft(MutualLeft(None, 0)) { (tail, i) =>
      MutualLeft(Some(MutualRight(Some(tail), -i)), i)
    }
    val codec = MutualLeft.stackSafeCodec
    onSmallStack {
      var current = Option(codec.decode(codec.encode(value)))
      var expected = depth - 1
      while current.nonEmpty do
        val left = current.get
        assertEquals(left.value, expected)
        if expected == 0 then
          assert(left.next.isEmpty)
          current = None
        else
          assertEquals(left.next.get.value, -expected)
          current = left.next.get.next
        expected -= 1
      assertEquals(expected, -1)
    }
  }

  test("recursive general unions preserve continuations through arrays and maps") {
    val depth = 20000
    val value = (1 until depth).foldLeft(RecursiveContainers(None, 0)) { (tail, i) =>
      val child: RecursiveContainers | Vector[RecursiveContainers] | Map[String, RecursiveContainers] =
        i % 3 match
          case 0 => tail
          case 1 => Vector(tail)
          case _ => Map("child" -> tail)
      RecursiveContainers(Some(child), i)
    }
    val codec = RecursiveContainers.stackSafeCodec
    // A metadata difference forces a resolving plan instead of the equal-JSON shortcut.
    val writer = RecursiveContainers.schemaJson.replace("\"name\":\"RecursiveContainers\"", "\"doc\":\"writer\",\"name\":\"RecursiveContainers\"")
    assert(writer != RecursiveContainers.schemaJson)
    val resolver = new ResolvingReader(writer, codec)
    def check(root: RecursiveContainers): Unit =
      var current = Option(root)
      var expected = depth - 1
      while current.nonEmpty do
        val node = current.get
        assertEquals(node.value, expected)
        current = node.child.map {
          case child: RecursiveContainers =>
            assertEquals(expected % 3, 0)
            child
          case children: Vector[?] =>
            assertEquals(expected % 3, 1)
            assertEquals(children.size, 1)
            children.head.asInstanceOf[RecursiveContainers]
          case children: Map[?, ?] =>
            assertEquals(expected % 3, 2)
            assert(children.keySet == Set("child"))
            children.asInstanceOf[Map[String, RecursiveContainers]]("child")
        }
        expected -= 1
      assertEquals(expected, -1)
    onSmallStack {
      val encoded = codec.encode(value)
      check(codec.decode(encoded))
      check(resolver.decode(encoded))
    }
  }

  test("failed stack-safe reads unwind record scopes and preserve opt-in depth limits") {
    val depth = 100000
    val codec = StackNode.stackSafeCodec
    val encoded = codec.encode(chain(depth))
    val resolver = new ResolvingReader(StackNode.schemaJson, StackNodeV2.stackSafeCodec)
    onSmallStack {
      // The first depth-1 bytes are non-null union indices. EOF is reached
      // while all their parent records are still awaiting a child result.
      for read <- Vector[AvroInput => Any](codec.read, resolver.read) do
        val truncated = new BinaryInput(encoded.take(depth - 1))
        intercept[AvroDecodingException](read(truncated))
        truncated.requireEnd()
        val capped = new BinaryInput(encoded, DecodeLimits(maxNestingDepth = Some(32)))
        val error = intercept[AvroDecodingException](read(capped))
        assert(error.getMessage.contains("maxNestingDepth"))
        capped.readFixed(capped.remaining)
        capped.requireEnd()
      checkChain(codec.decode(codec.encode(chain(4))), 4)
      checkEvolved(resolver.decode(codec.encode(chain(4))), 4)
    }
  }

  test("both codec implementations agree with independent Java datum codecs") {
    val value = chain(8)
    val schema = new Schema.Parser().parse(StackNode.schemaJson)
    def generic(node: StackNode): GenericRecord =
      val record = new GenericData.Record(schema)
      record.put("next", node.next.map(generic).orNull)
      record.put("value", node.value)
      record
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    new GenericDatumWriter[GenericRecord](schema).write(generic(value), encoder)
    encoder.flush()
    val expected = bytes.toByteArray
    for codec <- Vector[AvroCodec[StackNode]](StackNode.codec, StackNode.stackSafeCodec) do
      assertEquals(codec.encode(value).toVector, expected.toVector)
      assertEquals(codec.decode(expected), value)
      val decoder = DecoderFactory.get().binaryDecoder(codec.encode(value), null)
      assertEquals(new GenericDatumReader[GenericRecord](schema).read(null, decoder), generic(value))
      assert(decoder.isEnd)
    val javaOutput = new ByteArrayOutputStream()
    val javaEncoder = EncoderFactory.get().binaryEncoder(javaOutput, null)
    StackNode.stackSafeCodec.write(value, new JavaAvroOutput(javaEncoder))
    javaEncoder.flush()
    assertEquals(javaOutput.toByteArray.toVector, expected.toVector)
    assertEquals(StackNode.stackSafeCodec.read(new JavaAvroInput(
      DecoderFactory.get().binaryDecoder(expected, null))), value)
  }

  test("stack-safe execution preserves collection block, union, truncation, and trailing-data validation") {
    val value = RecursiveContainers(Some(Vector(RecursiveContainers(None, 1))), 2)
    val encoded = RecursiveContainers.codec.encode(value)
    assertEquals(RecursiveContainers.stackSafeCodec.encode(value).toVector, encoded.toVector)
    for end <- 0 until encoded.length do
      intercept[AvroDecodingException](RecursiveContainers.stackSafeCodec.decode(encoded.take(end)))
    intercept[AvroDecodingException](RecursiveContainers.stackSafeCodec.decode(encoded ++ Array[Byte](0)))
    intercept[AvroDecodingException](RecursiveContainers.stackSafeCodec.decode(Array[Byte](8)))
    intercept[AvroDecodingException](RecursiveContainers.stackSafeCodec.decode(encoded,
      DecodeLimits(maxCollectionItems = Some(0L))))
    val mixedBlocks = Array[Byte](3, 4, 2, 3, 2, 6, 0, 1, 8, 2, 120, 2, 121, 0)
    assertEquals(Blocks.stackSafeCodec.decode(mixedBlocks), Blocks(Vector(1L, -2L, 3L), Map("x" -> "y")))
  }
