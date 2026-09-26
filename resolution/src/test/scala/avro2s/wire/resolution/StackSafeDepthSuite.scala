package avro2s.wire.resolution

import avro2s.wire.runtime.*
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite

final class StackSafeDepthSuite extends FunSuite:
  private def record(name: String, fields: String): String =
    s"""{"type":"record","name":"$name","fields":[$fields]}"""

  private def row(json: String, named: Map[String, AvroCodec[?]] = Map.empty): AvroCodec[Vector[Any]] =
    new AvroCodec[Vector[Any]]:
      override val schemaJson: String = json
      override val execution: CodecExecution = CodecExecution.StackSafe
      override def read(in: AvroInput): Vector[Any] =
        throw new UnsupportedOperationException("Use the resolution plan")
      override def write(value: Vector[Any], out: AvroOutput): Unit =
        throw new UnsupportedOperationException("Read-only test codec")
      override def construct(values: Array[Any]): Vector[Any] = values.toVector
      override def namedCodec(name: String): AvroCodec[?] = named.getOrElse(name, super.namedCodec(name))

  // Compile schemas outside this thread: stack safety here concerns value execution.
  // Stack size is a platform-dependent hint; depth is large enough to exercise the
  // trampoline even on JVMs which choose a larger native stack for this thread.
  private def smallStack[A](body: => A): A =
    val result = new AtomicReference[Either[Throwable, A]]()
    val thread = new Thread(null, new Runnable:
      override def run(): Unit =
        try result.set(Right(body))
        catch case error: Throwable => result.set(Left(error))
    , "stack-safe-resolution", 256 * 1024L)
    thread.setDaemon(true)
    thread.start()
    thread.join(30000)
    assert(!thread.isAlive, "Stack-safe resolution did not finish")
    result.get() match
      case Right(value) => value
      case Left(error) => throw error

  private val depth = 20000
  private val children = """{"name":"children","type":{"type":"array","items":{"type":"map","values":"Node"}}}"""
  private val writerNode = record("Node", s"""{"name":"value","type":"int"},$children""")
  private val readerNode = record("Node", s"""$children,{"name":"value","type":"long"},
    {"name":"label","type":"string","default":"new"}""")

  private def writeTree(out: BinaryOutput): Unit =
    var level = 0
    while level < depth do
      out.writeInt(level)
      out.writeArrayStart(if level == depth - 1 then 0 else 1)
      if level < depth - 1 then
        out.writeMapStart(1)
        out.writeString("child")
      level += 1
    out.writeArrayEnd()
    level = depth - 1
    while level > 0 do
      out.writeMapEnd()
      out.writeArrayEnd()
      level -= 1

  test("stack-safe resolution handles records through both arrays and maps at 20000 levels") {
    val out = new BinaryOutput()
    writeTree(out)
    val reader = ResolvingReader(writerNode, row(readerNode))
    val result = smallStack(reader.decode(out.toByteArray))
    var current = result
    var level = 0
    while level < depth do
      assertEquals(current(1), level.toLong)
      assertEquals(current(2), "new")
      val children = current(0).asInstanceOf[Vector[Map[String, Vector[Any]]]]
      if level == depth - 1 then assert(children.isEmpty)
      else current = children.head("child")
      level += 1
  }

  test("stack-safe skipping traverses recursive records, arrays and maps without model codecs") {
    val writer = record("Root", s"""{"name":"discard","type":$writerNode},{"name":"keep","type":"int"}""")
    val reader = record("Root", """{"name":"keep","type":"long"}""")
    val out = new BinaryOutput()
    writeTree(out)
    out.writeInt(42)
    val resolving = ResolvingReader(writer, row(reader))
    assertEquals(smallStack(resolving.decode(out.toByteArray)), Vector[Any](42L))
  }

  test("stack-safe union resolution unwinds every record on deep malformed input") {
    val writer = record("Node", """{"name":"next","type":["null","Node"]}""")
    val reader = record("Node", """{"name":"next","type":["null","Node"],"doc":"changed"}""")
    val out = new BinaryOutput()
    var level = 1
    while level < depth do
      out.writeIndex(1)
      level += 1
    out.writeIndex(2)
    val in = new BinaryInput(out.toByteArray)
    val resolving = ResolvingReader(writer, row(reader))
    smallStack {
      intercept[AvroDecodingException](resolving.read(in))
      // The invalid union index consumed the last byte. No record scope may leak.
      in.requireEnd()
    }
  }

  test("stack-safe plans retain opt-in nesting and collection budgets") {
    val out = new BinaryOutput()
    writeTree(out)
    val reader = ResolvingReader(writerNode, row(readerNode))
    intercept[AvroDecodingException] {
      reader.decode(out.toByteArray, DecodeLimits(maxNestingDepth = Some(100)))
    }
    intercept[AvroDecodingException] {
      reader.decode(out.toByteArray, DecodeLimits(maxCollectionItems = Some(100)))
    }
  }

  test("stack-safe default materialization constructs each nested record afresh") {
    val defaultDepth = 96
    val child = record("DefaultNode", """{"name":"next","type":["null","DefaultNode"]}""")
    var defaultJson = """{"next":null}"""
    var level = 1
    while level < defaultDepth do
      defaultJson = s"""{"next":$defaultJson}"""
      level += 1
    val reader = record("Root", s"""{"name":"added","type":$child,"default":$defaultJson}""")
    val resolving = ResolvingReader(record("Root", ""), row(reader, Map("DefaultNode" -> row(child))))
    val first = smallStack(resolving.decode(Array.emptyByteArray))
    val second = smallStack(resolving.decode(Array.emptyByteArray))
    var a = first.head.asInstanceOf[Vector[Any]]
    var b = second.head.asInstanceOf[Vector[Any]]
    level = 0
    while level < defaultDepth do
      assert(a ne b)
      val nextA = a.head.asInstanceOf[Option[Vector[Any]]]
      val nextB = b.head.asInstanceOf[Option[Vector[Any]]]
      if level == defaultDepth - 1 then
        assertEquals(nextA, None)
        assertEquals(nextB, None)
      else
        a = nextA.get
        b = nextB.get
      level += 1
  }
