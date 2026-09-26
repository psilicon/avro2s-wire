package avro2s.wire.resolution

import avro2s.wire.runtime.*
import java.util.IdentityHashMap
import java.util.concurrent.{Callable, CountDownLatch, ExecutionException, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

final class StackSafeConcurrencySuite extends munit.FunSuite:
  private val metadataSchema = """{"type":"record","name":"Metadata","fields":[
    {"name":"tags","type":{"type":"array","items":"string"}},
    {"name":"stamp","type":"long"}]}"""
  private val children = """{"name":"children","type":{"type":"array","items":{"type":"map","values":"Node"}}}"""
  private val writerSchema = s"""{"type":"record","name":"Node","fields":[
    {"name":"number","type":"int"},$children,
    {"name":"tail","type":"int"},{"name":"valid","type":"boolean"}]}"""
  private val readerSchema = s"""{"type":"record","name":"Node","fields":[
    $children,{"name":"number","type":"long"},
    {"name":"metadata","type":$metadataSchema,"default":{"tags":["initial"],"stamp":7}},
    {"name":"tail","type":"long"},{"name":"valid","type":"boolean"}]}"""

  private def codec[A](json: String, named: Map[String, AvroCodec[?]] = Map.empty)(make: Array[Any] => A): AvroCodec[A] =
    new AvroCodec[A]:
      override val schemaJson: String = json
      override val execution: CodecExecution = CodecExecution.StackSafe
      override def read(in: AvroInput): A = throw new UnsupportedOperationException("Use the resolution plan")
      override def write(value: A, out: AvroOutput): Unit = throw new UnsupportedOperationException("Read-only test codec")
      override def construct(values: Array[Any]): A = make(values)
      override def namedCodec(name: String): AvroCodec[?] = named.getOrElse(name, super.namedCodec(name))

  private def wire(depth: Int, number: Int): Array[Byte] =
    val out = new BinaryOutput()
    def node(left: Int, value: Int): Unit =
      out.writeInt(value)
      if left == 0 then out.writeArrayStart(0)
      else
        out.writeArrayStart(2)
        out.writeMapStart(2)
        out.writeString("left")
        node(0, value + 100000)
        out.writeString("next")
        node(left - 1, value + 1)
        out.writeMapEnd()
        out.writeMapStart(1)
        out.writeString("right")
        node(0, value + 200000)
        out.writeMapEnd()
      out.writeArrayEnd()
      out.writeInt(-value)
      out.writeBoolean(true)
    node(depth, number)
    out.toByteArray

  test("one stack-safe resolving reader isolates concurrent recursive reads, failures and defaults") {
    // Mutable test models make accidental default sharing observable. The same
    // compiled plan and codecs serve every worker; only inputs belong to callers.
    val metadata = codec(metadataSchema)(values => values)
    val readerCodec = codec(readerSchema, Map("Metadata" -> metadata))(_.toVector)
    val reader = ResolvingReader(writerSchema, readerCodec)
    val workers = 8
    val ready = new CountDownLatch(workers)
    val start = new CountDownLatch(1)
    val threadIds = new AtomicInteger()
    val pool = Executors.newFixedThreadPool(workers, runnable =>
      val thread = new Thread(runnable, s"resolving-reader-worker-${threadIds.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    )
    var primaryFailure: Throwable = null
    try
      val jobs = (0 until workers).map { worker =>
        pool.submit(new Callable[Vector[(Array[Any], Long)]]:
          override def call(): Vector[(Array[Any], Long)] =
            ready.countDown()
            // MUnit evaluates assert's by-name condition under the suite monitor.
            // Await outside it so the coordinating thread can release this latch.
            val released = start.await(10, TimeUnit.SECONDS)
            assert(released, "Workers were not released together")
            val retainedDefaults = ArrayBuffer.empty[(Array[Any], Long)]
            for iteration <- 0 until 12 do
              val depth = 64 + (worker + iteration) % 8
              val number = worker * 1000000 + iteration * 1000
              val bytes = wire(depth, number)
              if iteration % 3 == 0 then
                val malformed = bytes.clone()
                malformed(malformed.length - 1) = 2 // Root boolean, after all its children.
                val failedInput = new BinaryInput(malformed)
                intercept[AvroDecodingException](reader.read(failedInput))
                failedInput.requireEnd() // Every entered record must have unwound.
              val in = new BinaryInput(bytes)
              var current = reader.read(in)
              in.requireEnd()
              var ordinal = 0
              def checkRow(value: Vector[Any], expectedNumber: Int): Vector[Map[String, Vector[Any]]] =
                assertEquals(value(1), expectedNumber.toLong)
                assertEquals(value(3), -expectedNumber.toLong)
                assertEquals(value(4), true)
                val default = value(2).asInstanceOf[Array[Any]]
                assertEquals(default(0), Vector("initial"))
                assertEquals(default(1), 7L)
                val marker = (worker.toLong << 32) + (iteration.toLong << 16) + ordinal
                ordinal += 1
                default(1) = marker
                retainedDefaults += ((default, marker))
                value(0).asInstanceOf[Vector[Map[String, Vector[Any]]]]
              var level = 0
              while level < depth do
                val children = checkRow(current, number + level)
                assertEquals(children.size, 2)
                assertEquals(children.head.keySet, Set("left", "next"))
                assertEquals(children(1).keySet, Set("right"))
                assert(checkRow(children.head("left"), number + level + 100000).isEmpty)
                assert(checkRow(children(1)("right"), number + level + 200000).isEmpty)
                current = children.head("next")
                level += 1
              assert(checkRow(current, number + depth).isEmpty)
            retainedDefaults.toVector
        )
      }
      val started = ready.await(10, TimeUnit.SECONDS)
      assert(started, "Concurrent workers did not start")
      start.countDown()
      val seen = new IdentityHashMap[Array[Any], java.lang.Boolean]()
      jobs.foreach { job =>
        val defaults =
          try job.get(30, TimeUnit.SECONDS)
          catch case error: ExecutionException => throw error.getCause
        defaults.foreach { (value, expected) =>
          assert(seen.put(value, java.lang.Boolean.TRUE) == null, "A default record was shared between reads or nodes")
          assertEquals(value(1), expected)
        }
      }
    catch
      case error: Throwable =>
        primaryFailure = error
        throw error
    finally
      start.countDown()
      pool.shutdownNow()
      if !pool.awaitTermination(10, TimeUnit.SECONDS) then
        val cleanupFailure = new IllegalStateException("Concurrent workers did not terminate")
        if primaryFailure == null then throw cleanupFailure
        else primaryFailure.addSuppressed(cleanupFailure)
  }
