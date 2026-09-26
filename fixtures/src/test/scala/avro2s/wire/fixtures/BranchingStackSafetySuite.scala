package avro2s.wire.fixtures

import avro2s.wire.fixtures.stacks.{BranchingNode, StackNode, StackNodeV2}
import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

final class BranchingStackSafetySuite extends munit.FunSuite:
  private type Choice = BranchingNode | String | Long

  private def marker(id: Int): Long = 0x123456780000L + id.toLong * 7919L
  private def label(id: Int): String = s"record-$id-λ"
  private def tail(id: Int): Int = id ^ 0x5a5a5a5a
  private def scalarChoice(id: Int): Option[Choice] = Math.floorMod(id, 3) match
    case 0 => None
    case 1 => Some(s"union-$id")
    case _ => Some(id.toLong * 31L)

  private def leaf(id: Int): BranchingNode =
    BranchingNode(id, None, marker(id), Vector.empty, label(id), Map.empty, scalarChoice(id), tail(id))

  private def sideId(level: Int, seed: Int, slot: Int): Int = -((seed * 10000 + level + 1) * 16 + slot)

  /** Exactly one growing child per level; the other children are distinct leaves. */
  private def branching(depth: Int, seed: Int = 0): BranchingNode =
    require(depth > 0)
    var spine: Option[BranchingNode] = None
    var level = 0
    while level < depth do
      def side(slot: Int): BranchingNode = leaf(sideId(level, seed, slot))
      val id = seed * 100000 + level
      val children = Vector(side(2)) ++
        (if level % 4 == 1 then spine.toVector else Vector(side(3))) ++ Vector(side(4))
      val lookup = Map("first" -> side(5),
        "pivot" -> (if level % 4 == 2 then spine.get else side(6)), "last" -> side(7))
      spine = Some(BranchingNode(id, if level % 4 == 0 then spine else Some(side(1)),
        marker(id), children, label(id), lookup,
        if level % 4 == 3 then spine else scalarChoice(id), tail(id)))
      level += 1
    spine.get

  private def checkScalars(node: BranchingNode, expectedId: Int): Unit =
    assertEquals(node.id, expectedId)
    assertEquals(node.marker, marker(expectedId))
    assertEquals(node.label, label(expectedId))
    assertEquals(node.tail, tail(expectedId))

  private def checkLeaf(node: BranchingNode, expectedId: Int): Unit =
    checkScalars(node, expectedId)
    assert(node.left.isEmpty)
    assert(node.children.isEmpty)
    assert(node.lookup.isEmpty)
    assertEquals(node.choice, scalarChoice(expectedId))

  /** Expected topology is checked independently of case-class equals and builders. */
  private def checkBranching(value: BranchingNode, depth: Int, seed: Int = 0): Unit =
    var node = value
    var level = depth - 1
    while level >= 0 do
      val id = seed * 100000 + level
      checkScalars(node, id)
      assertEquals(node.children.size, 3)
      assertEquals(node.lookup.keySet, Set("first", "pivot", "last"))
      checkLeaf(node.children.head, sideId(level, seed, 2))
      checkLeaf(node.children.last, sideId(level, seed, 4))
      checkLeaf(node.lookup("first"), sideId(level, seed, 5))
      checkLeaf(node.lookup("last"), sideId(level, seed, 7))
      if level % 4 != 0 then checkLeaf(node.left.get, sideId(level, seed, 1))
      if level % 4 != 1 then checkLeaf(node.children(1), sideId(level, seed, 3))
      if level % 4 != 2 then checkLeaf(node.lookup("pivot"), sideId(level, seed, 6))
      if level % 4 != 3 then assertEquals(node.choice, scalarChoice(id))
      if level == 0 then assert(node.left.isEmpty)
      else
        node = level % 4 match
          case 0 => node.left.get
          case 1 => node.children(1)
          case 2 => node.lookup("pivot")
          case _ => node.choice.get match
            case next: BranchingNode => next
            case _ => fail("The spine must select the record union branch")
      level -= 1

  /** Independent schema-specific writer using only Java Avro primitives. Its
    * explicit work list keeps the oracle stack-safe without calling a Wire codec.
    */
  private def referenceBytes(value: BranchingNode): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    var pending = List.empty[() => Unit]
    def later(action: => Unit): Unit = pending = (() => action) :: pending
    def record(node: BranchingNode): Unit =
      later(encoder.writeInt(node.tail))
      later {
        node.choice match
          case None => encoder.writeIndex(0); encoder.writeNull()
          case Some(child: BranchingNode) => encoder.writeIndex(1); record(child)
          case Some(text: String) => encoder.writeIndex(2); encoder.writeString(text)
          case Some(number: Long) => encoder.writeIndex(3); encoder.writeLong(number)
      }
      later {
        encoder.writeMapStart()
        encoder.setItemCount(node.lookup.size.toLong)
        later(encoder.writeMapEnd())
        node.lookup.toVector.reverseIterator.foreach { (key, child) =>
          later { encoder.startItem(); encoder.writeString(key); record(child) }
        }
      }
      later(encoder.writeString(node.label))
      later {
        encoder.writeArrayStart()
        encoder.setItemCount(node.children.size.toLong)
        later(encoder.writeArrayEnd())
        node.children.reverseIterator.foreach(child => later { encoder.startItem(); record(child) })
      }
      later(encoder.writeLong(node.marker))
      later {
        node.left match
          case None => encoder.writeIndex(0); encoder.writeNull()
          case Some(child) => encoder.writeIndex(1); record(child)
      }
      later(encoder.writeInt(node.id))
    record(value)
    while pending.nonEmpty do
      val action = pending.head
      pending = pending.tail
      action()
    encoder.flush()
    bytes.toByteArray

  private def genericBytes(value: BranchingNode): Array[Byte] =
    val schema = new Schema.Parser().parse(BranchingNode.schemaJson)
    def convert(node: BranchingNode): GenericRecord =
      val record = new GenericData.Record(schema)
      record.put("id", node.id)
      record.put("left", node.left.map(convert).orNull)
      record.put("marker", node.marker)
      record.put("children", node.children.map(convert).asJava)
      record.put("label", node.label)
      val lookup = new java.util.LinkedHashMap[String, GenericRecord]()
      node.lookup.foreach((key, child) => lookup.put(key, convert(child)))
      record.put("lookup", lookup)
      record.put("choice", node.choice.map {
        case child: BranchingNode => convert(child)
        case text: String => text
        case number: Long => Long.box(number)
      }.orNull)
      record.put("tail", node.tail)
      record
    val bytes = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bytes, null)
    new GenericDatumWriter[GenericRecord](schema).write(convert(value), encoder)
    encoder.flush()
    bytes.toByteArray

  private def onSmallStack(body: => Unit): Unit =
    val failure = new AtomicReference[Throwable]()
    val task = new Runnable:
      override def run(): Unit =
        try body
        catch case error: Throwable => failure.set(error)
    val thread = new Thread(null, task, "wire-branching-stack-safety", 256 * 1024L)
    thread.setDaemon(true)
    thread.start()
    thread.join(60000L)
    assert(!thread.isAlive, "Branching stack-safety test timed out")
    Option(failure.get()).foreach(error => throw error)

  test("deep branching traversals preserve every sibling, structural field, and scalar sentinel") {
    val depth = 10000
    val value = branching(depth)
    val codec = BranchingNode.stackSafeCodec
    onSmallStack {
      val expectedBytes = referenceBytes(value)
      checkBranching(codec.decode(expectedBytes), depth)
      assert(java.util.Arrays.equals(codec.encode(value), expectedBytes),
        "Generated deep writer differs from the independent Java primitive writer")
    }
  }

  test("branching oracle and both generated codecs agree with Java for all nullable and general union branches") {
    for depth <- 1 to 8; seed <- 0 to 2 do
      val value = branching(depth, seed)
      val expected = genericBytes(value)
      assert(java.util.Arrays.equals(referenceBytes(value), expected))
      for codec <- Vector[AvroCodec[BranchingNode]](BranchingNode.codec, BranchingNode.stackSafeCodec) do
        assert(java.util.Arrays.equals(codec.encode(value), expected))
        checkBranching(codec.decode(expected), depth, seed)
  }

  private final class InjectedFailure(val at: Int) extends RuntimeException(s"Injected callback failure $at")

  /** Only successful enters require a leave. A failing leave performs its
    * bookkeeping before throwing, so tests can detect double cleanup as well.
    */
  private final class FaultInput(bytes: Array[Byte], failAt: Int = -1) extends AvroInput:
    val delegate = new BinaryInput(bytes)
    val events = ArrayBuffer.empty[String]
    val failure = new InjectedFailure(failAt)
    var entered = 0
    var left = 0
    var openRecords = 0
    private def callback[A](name: String)(read: => A): A =
      events += name
      if events.size == failAt then throw failure
      read
    override def enterRecord(): Unit = callback("enterRecord") {
      delegate.enterRecord()
      entered += 1
      openRecords += 1
    }
    override def leaveRecord(): Unit =
      events += "leaveRecord"
      assert(openRecords > 0, "Record was cleaned up twice")
      delegate.leaveRecord()
      left += 1
      openRecords -= 1
      if events.size == failAt then throw failure
    override def readNull(): Unit = callback("readNull")(delegate.readNull())
    override def readBoolean(): Boolean = callback("readBoolean")(delegate.readBoolean())
    override def readInt(): Int = callback("readInt")(delegate.readInt())
    override def readLong(): Long = callback("readLong")(delegate.readLong())
    override def readFloat(): Float = callback("readFloat")(delegate.readFloat())
    override def readDouble(): Double = callback("readDouble")(delegate.readDouble())
    override def readString(): String = callback("readString")(delegate.readString())
    override def readBytes(): Bytes = callback("readBytes")(delegate.readBytes())
    override def readFixed(size: Int): Bytes = callback("readFixed")(delegate.readFixed(size))
    override def readEnum(): Int = callback("readEnum")(delegate.readEnum())
    override def readIndex(): Int = callback("readIndex")(delegate.readIndex())
    override def readArrayStart(): Long = callback("readArrayStart")(delegate.readArrayStart())
    override def arrayNext(): Long = callback("arrayNext")(delegate.arrayNext())
    override def readMapStart(): Long = callback("readMapStart")(delegate.readMapStart())
    override def mapNext(): Long = callback("mapNext")(delegate.mapNext())

  private final class FaultOutput(failAt: Int = -1) extends AvroOutput:
    val delegate = new BinaryOutput()
    val events = ArrayBuffer.empty[String]
    val failure = new InjectedFailure(failAt)
    private final class Collection(val kind: String, val expected: Int):
      var items = 0
    private var collections = List.empty[Collection]
    def openCollections: Int = collections.size
    private def callback(name: String)(write: => Unit): Unit =
      events += name
      if events.size == failAt then throw failure
      write
    override def writeNull(): Unit = callback("writeNull")(delegate.writeNull())
    override def writeBoolean(value: Boolean): Unit = callback("writeBoolean")(delegate.writeBoolean(value))
    override def writeInt(value: Int): Unit = callback("writeInt")(delegate.writeInt(value))
    override def writeLong(value: Long): Unit = callback("writeLong")(delegate.writeLong(value))
    override def writeFloat(value: Float): Unit = callback("writeFloat")(delegate.writeFloat(value))
    override def writeDouble(value: Double): Unit = callback("writeDouble")(delegate.writeDouble(value))
    override def writeString(value: String): Unit = callback("writeString")(delegate.writeString(value))
    override def writeBytes(value: Bytes): Unit = callback("writeBytes")(delegate.writeBytes(value))
    override def writeFixed(value: Bytes): Unit = callback("writeFixed")(delegate.writeFixed(value))
    override def writeEnum(value: Int): Unit = callback("writeEnum")(delegate.writeEnum(value))
    override def writeIndex(value: Int): Unit = callback("writeIndex")(delegate.writeIndex(value))
    override def writeArrayStart(size: Int): Unit = callback("writeArrayStart") {
      delegate.writeArrayStart(size)
      collections = new Collection("array", size) :: collections
    }
    override def writeMapStart(size: Int): Unit = callback("writeMapStart") {
      delegate.writeMapStart(size)
      collections = new Collection("map", size) :: collections
    }
    override def startItem(): Unit = callback("startItem") {
      assert(collections.nonEmpty, "Item callback outside a collection")
      collections.head.items += 1
      assert(collections.head.items <= collections.head.expected, "Too many item callbacks")
      delegate.startItem()
    }
    private def end(kind: String)(write: => Unit): Unit =
      assert(collections.nonEmpty, "Collection closed twice")
      assertEquals(collections.head.kind, kind)
      assertEquals(collections.head.items, collections.head.expected)
      collections = collections.tail
      write
    override def writeArrayEnd(): Unit = callback("writeArrayEnd")(end("array")(delegate.writeArrayEnd()))
    override def writeMapEnd(): Unit = callback("writeMapEnd")(end("map")(delegate.writeMapEnd()))

  test("every branching input callback can fail without losing record cleanup or poisoning codec reuse") {
    val codec = BranchingNode.stackSafeCodec
    val value = branching(6)
    val bytes = referenceBytes(value)
    val successful = new FaultInput(bytes)
    checkBranching(codec.read(successful), 6)
    successful.delegate.requireEnd()
    assertEquals(successful.openRecords, 0)
    val required = Set("enterRecord", "leaveRecord", "readInt", "readLong", "readString", "readNull",
      "readIndex", "readArrayStart", "arrayNext", "readMapStart", "mapNext")
    assert(required.subsetOf(successful.events.toSet))
    for failAt <- 1 to successful.events.size do
      val input = new FaultInput(bytes, failAt)
      val error = intercept[InjectedFailure](codec.read(input))
      assert(error eq input.failure)
      assertEquals(input.openRecords, 0, s"callback $failAt: ${successful.events(failAt - 1)}")
      assertEquals(input.left, input.entered)
      // Collection input state is intentionally discarded after a failed read;
      // every entered record must still have been left exactly once.
      checkBranching(codec.decode(bytes), 6)
  }

  test("every branching output callback can fail without further writes or poisoning codec reuse") {
    val codec = BranchingNode.stackSafeCodec
    val value = branching(6)
    val expected = referenceBytes(value)
    val successful = new FaultOutput()
    codec.write(value, successful)
    assertEquals(successful.openCollections, 0)
    assert(java.util.Arrays.equals(successful.delegate.toByteArray, expected))
    val required = Set("writeInt", "writeLong", "writeString", "writeNull", "writeIndex",
      "writeArrayStart", "writeArrayEnd", "writeMapStart", "writeMapEnd", "startItem")
    assert(required.subsetOf(successful.events.toSet))
    for failAt <- 1 to successful.events.size do
      val output = new FaultOutput(failAt)
      val error = intercept[InjectedFailure](codec.write(value, output))
      assert(error eq output.failure)
      assertEquals(output.events.size, failAt, "No output callbacks should run after a failed write")
      // Failed output contains an incomplete datum and is discarded. Closing
      // its pending collections would append bytes after the reported failure.
      val next = new FaultOutput()
      codec.write(value, next)
      assertEquals(next.openCollections, 0)
      assert(java.util.Arrays.equals(next.delegate.toByteArray, expected))
  }

  test("one generated codec and shared resolving readers keep concurrent operations independent") {
    val codec = BranchingNode.stackSafeCodec
    val writerJson = "{\"doc\":\"concurrent writer\"," + BranchingNode.schemaJson.drop(1)
    val sharedReader = new ResolvingReader(writerJson, codec)
    val sharedEvolved = new ResolvingReader(StackNode.schemaJson, StackNodeV2.stackSafeCodec)
    val start = new CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(4)
    try
      val tasks = (0 until 4).map { worker =>
        executor.submit(new Callable[Unit]:
          override def call(): Unit =
            start.await()
            for iteration <- 0 until 20 do
              val seed = worker * 100 + iteration
              val depth = 12 + iteration % 5
              val value = branching(depth, seed)
              val expected = referenceBytes(value)
              val output = new FaultOutput()
              codec.write(value, output)
              assertEquals(output.openCollections, 0)
              assert(java.util.Arrays.equals(output.delegate.toByteArray, expected))
              val input = new FaultInput(expected)
              checkBranching(codec.read(input), depth, seed)
              input.delegate.requireEnd()
              assertEquals(input.openRecords, 0)
              checkBranching(sharedReader.decode(expected), depth, seed)
              val chain = (1 until 16).foldLeft(StackNode(None, seed))((child, i) => StackNode(Some(child), seed + i))
              var evolved = Option(sharedEvolved.decode(StackNode.stackSafeCodec.encode(chain)))
              var expectedId = seed + 15
              while evolved.nonEmpty do
                assertEquals(evolved.get.value, expectedId.toLong)
                assert(evolved.get.added)
                evolved = evolved.get.next
                expectedId -= 1
              assertEquals(expectedId, seed - 1)
        )
      }
      start.countDown()
      tasks.foreach(_.get(30, TimeUnit.SECONDS))
    finally executor.shutdownNow()
  }
