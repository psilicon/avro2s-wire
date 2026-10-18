package avro2s.wire.registry

import io.confluent.kafka.schemaregistry.client.rest.entities.{Schema as RegistrySchema}

import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput}
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse
import java.io.IOException
import java.util.concurrent.{Callable, CountDownLatch, ExecutionException, ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.apache.kafka.common.errors.SerializationException

final class RegistryConcurrencySuite extends munit.FunSuite:
  private final case class Number(value: Int)
  private val json = """{"type":"record","name":"ConcurrentNumber","fields":[{"name":"value","type":"int"}]}"""

  private final class NumberCodec extends AvroCodec[Number]:
    val beforeRead = new AtomicReference[() => Unit](() => ())
    val beforeWrite = new AtomicReference[() => Unit](() => ())
    override def schemaJson: String = json
    override def read(in: AvroInput): Number =
      beforeRead.get()()
      Number(in.readInt())
    override def write(value: Number, out: AvroOutput): Unit =
      beforeWrite.get()()
      out.writeInt(value.value)
    override def construct(values: Array[Any]): Number = Number(values(0).asInstanceOf[Int])

  /** Deliberately does not synchronize: the adapter must permit independent loads. */
  private final class Client(writerJson: String = json) extends MockSchemaRegistryClient:
    val loads = new AtomicInteger()
    val fetches = new AtomicInteger()
    val closes = new AtomicInteger()
    val beforeLookup = new AtomicReference[String => Unit](_ => ())
    val beforeFetch = new AtomicReference[Int => Unit](_ => ())
    val beforeClose = new AtomicReference[() => Unit](() => ())
    override def getIdWithResponse(subject: String, schema: ParsedSchema, normalize: Boolean): RegisterSchemaResponse =
      loads.incrementAndGet()
      beforeLookup.get()(subject)
      new RegisterSchemaResponse(new Schema(subject, 1, 1, schema))
    override def registerWithResponse(subject: String, schema: ParsedSchema, normalize: Boolean,
        propagateSchemaTags: Boolean): RegisterSchemaResponse =
      getIdWithResponse(subject, schema, normalize)
    override def getSchemaEntityBySubjectAndId(subject: String, id: Int): RegistrySchema =
      fetches.incrementAndGet()
      beforeFetch.get()(id)
      new RegistrySchema(subject, 1, id, new AvroSchema(writerJson))
    override def close(): Unit =
      closes.incrementAndGet()
      beforeClose.get()()

  private def frame(id: Int, payload: Byte*): Array[Byte] =
    Array[Byte](0, (id >>> 24).toByte, (id >>> 16).toByte, (id >>> 8).toByte, id.toByte) ++ payload

  private def await(latch: CountDownLatch): Unit =
    // Do not await inside MUnit's by-name assert, which takes the suite monitor.
    val arrived = latch.await(10, TimeUnit.SECONDS)
    assert(arrived, "Concurrent operation did not reach its checkpoint")

  private def result[A](job: Future[A]): A =
    try job.get(10, TimeUnit.SECONDS)
    catch case error: ExecutionException => throw error.getCause

  private def submit[A](pool: ExecutorService)(body: => A): Future[A] =
    pool.submit(new Callable[A]:
      override def call(): A = body
    )

  private def tracked[A](pool: ExecutorService)(body: => A): (Future[A], AtomicReference[Thread]) =
    val worker = new AtomicReference[Thread]()
    val job = submit(pool) {
      worker.set(Thread.currentThread())
      body
    }
    (job, worker)

  private def awaitWaiting(job: Future[?], worker: AtomicReference[Thread]): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    def waiting: Boolean =
      val thread = worker.get()
      thread != null && thread.getState == Thread.State.WAITING && !job.isDone
    // Establish that a follower is actually parked before releasing/interruption.
    // These tasks have no other waits; a completed pool task is excluded by isDone.
    while !waiting && !job.isDone && System.nanoTime() < deadline do Thread.`yield`()
    assert(waiting, "Concurrent operation did not park behind the active operation")

  private def failure(body: => Any): Throwable =
    try
      body
      throw new AssertionError("Expected operation to fail")
    catch
      case error: SerializationException => error
      case error: IOException => error

  private def withPool[A](size: Int)(body: ExecutorService => A): A =
    val pool = Executors.newFixedThreadPool(size, runnable =>
      val thread = new Thread(runnable, "registry-concurrency-test")
      thread.setDaemon(true)
      thread
    )
    var primaryFailure: Throwable = null
    try body(pool)
    catch
      case error: Throwable =>
        primaryFailure = error
        throw error
    finally
      pool.shutdownNow()
      val stopped = pool.awaitTermination(10, TimeUnit.SECONDS)
      if !stopped then
        val cleanupFailure = new AssertionError("Concurrent workers did not terminate")
        if primaryFailure == null then throw cleanupFailure
        else primaryFailure.addSuppressed(cleanupFailure)

  test("warm serializer and deserializer calls execute codecs concurrently with separate state") {
    for writing <- Seq(true, false) do withPool(2) { pool =>
      val codec = new NumberCodec
      val client = new Client
      val writer = RegistrySerializer.forValue(codec, client)
      val reader = RegistryDeserializer.forValue(codec, client)
      val one = writer.serialize("a", Number(1))
      val two = writer.serialize("a", Number(2))
      assertEquals(reader.deserialize("a", one), Number(1))
      val entered = new CountDownLatch(2)
      val release = new CountDownLatch(1)
      val gate = () => { entered.countDown(); await(release) }
      if writing then codec.beforeWrite.set(gate) else codec.beforeRead.set(gate)
      try
        if writing then
          val first = submit(pool)(writer.serialize("a", Number(1)))
          val second = submit(pool)(writer.serialize("a", Number(2)))
          await(entered)
          release.countDown()
          val a = result(first)
          val b = result(second)
          assertEquals(a.toVector, one.toVector)
          assertEquals(b.toVector, two.toVector)
          a(0) = 99
          assertEquals(b(0), 0.toByte)
          assertEquals(writer.serialize("a", Number(1)).toVector, one.toVector)
        else
          val first = submit(pool)(reader.deserialize("a", one))
          val second = submit(pool)(reader.deserialize("a", two))
          await(entered)
          release.countDown()
          assertEquals(result(first), Number(1))
          assertEquals(result(second), Number(2))
        assertEquals(client.loads.get(), 1)
        assertEquals(client.fetches.get(), 1)
      finally
        release.countDown()
        writer.close()
        reader.close()
    }
  }

  test("a blocked cold subject does not block a warm serializer subject") {
    withPool(2) { pool =>
      val client = new Client
      val writer = RegistrySerializer.forValue(new NumberCodec, client)
      val warm = writer.serialize("a", Number(3)).toVector
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      client.beforeLookup.set(subject => if subject == "b-value" then { entered.countDown(); await(release) })
      try
        val cold = submit(pool)(writer.serialize("b", Number(4)))
        await(entered)
        val hit = result(submit(pool)(writer.serialize("a", Number(3))))
        assertEquals(hit.toVector, warm)
        release.countDown()
        assertEquals(result(cold).last, 8.toByte)
      finally
        release.countDown()
        writer.close()
    }
  }

  test("a blocked cold schema does not block a warm deserializer schema") {
    withPool(2) { pool =>
      val client = new Client
      val reader = RegistryDeserializer.forValue(new NumberCodec, client)
      assertEquals(reader.deserialize("a", frame(1, 6)), Number(3))
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      client.beforeFetch.set(id => if id == 2 then { entered.countDown(); await(release) })
      try
        val cold = submit(pool)(reader.deserialize("b", frame(2, 8)))
        await(entered)
        assertEquals(result(submit(pool)(reader.deserialize("a", frame(1, 6)))), Number(3))
        release.countDown()
        assertEquals(result(cold), Number(4))
      finally
        release.countDown()
        reader.close()
    }
  }

  test("different subjects can load concurrently through a concurrent client") {
    withPool(2) { pool =>
      val client = new Client
      val writer = RegistrySerializer.forValue(new NumberCodec, client)
      val entered = new CountDownLatch(2)
      val release = new CountDownLatch(1)
      client.beforeLookup.set(_ => { entered.countDown(); await(release) })
      try
        val a = submit(pool)(writer.serialize("a", Number(1)))
        val b = submit(pool)(writer.serialize("b", Number(2)))
        await(entered)
        release.countDown()
        assertEquals(result(a).last, 2.toByte)
        assertEquals(result(b).last, 4.toByte)
        assertEquals(client.loads.get(), 2)
      finally
        release.countDown()
        writer.close()
    }
  }

  test("one same-key load supplies waiting callers and failed loads can be retried") {
    for fail <- Seq(false, true) do withPool(2) { pool =>
      val cache = new BoundedCache[String, Int](2)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val loads = new AtomicInteger()
      val expected = new IOException("temporary")
      def load(): Int =
        loads.incrementAndGet()
        entered.countDown()
        await(release)
        if fail then throw expected else 42
      def call(): Either[Throwable, Int] =
        try Right(cache.getOrLoad("a")(load()))
        catch case error: IOException => Left(error)
      try
        val leader = submit(pool)(call())
        await(entered)
        val (follower, thread) = tracked(pool)(call())
        awaitWaiting(follower, thread)
        release.countDown()
        val wanted = if fail then Left(expected) else Right(42)
        assertEquals(result(leader), wanted)
        assertEquals(result(follower), wanted)
        assertEquals(loads.get(), 1)
        assertEquals(cache.getOrLoad("a") { loads.incrementAndGet(); 43 }, if fail then 43 else 42)
        assertEquals(loads.get(), if fail then 2 else 1)
      finally release.countDown()
    }
  }

  test("close drains registry loading, native encoding and decoding before closing an owned client") {
    for
      writing <- Seq(true, false)
      loading <- Seq(true, false)
    do withPool(2) { pool =>
      val codec = new NumberCodec
      val client = new Client
      val writer = new RegistrySerializer(codec, client, SerializerSettings(), RegistryRole.Value, writing)
      val reader = new RegistryDeserializer(codec, client, DeserializerSettings(), RegistryRole.Value, !writing)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val gate = () => { entered.countDown(); await(release) }
      if loading then
        if writing then client.beforeLookup.set(_ => gate()) else client.beforeFetch.set(_ => gate())
      else if writing then codec.beforeWrite.set(gate) else codec.beforeRead.set(gate)
      def close(): Unit = if writing then writer.close() else reader.close()
      try
        val operation = submit(pool) {
          if writing then writer.serialize("a", Number(1)).last.toInt
          else reader.deserialize("a", frame(1, 2)).value * 2
        }
        await(entered)
        val (closing, thread) = tracked(pool)(close())
        awaitWaiting(closing, thread)
        assertEquals(client.closes.get(), 0)
        release.countDown()
        assertEquals(result(operation), 2)
        result(closing)
        assertEquals(client.closes.get(), 1)
        val error = failure {
          if writing then writer.serialize("a", null) else reader.deserialize("a", null)
        }
        assert(error.getCause.isInstanceOf[IllegalStateException])
      finally
        release.countDown()
        writer.close()
        reader.close()
    }
  }

  test("concurrent closes wait for one cleanup and a failed close stays terminal") {
    withPool(2) { pool =>
      val client = new Client
      val writer = new RegistrySerializer(new NumberCodec, client, SerializerSettings(), RegistryRole.Value, true)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val expected = new IOException("close failed")
      client.beforeClose.set(() => { entered.countDown(); await(release); throw expected })
      try
        val first = submit(pool)(failure(writer.close()))
        await(entered)
        val (second, thread) = tracked(pool)(writer.close())
        awaitWaiting(second, thread)
        release.countDown()
        assert(result(first).getCause eq expected)
        result(second)
        writer.close()
        assertEquals(client.closes.get(), 1)
        assert(failure(writer.serialize("a", Number(1))).getCause.isInstanceOf[IllegalStateException])
      finally release.countDown()
    }
  }

  test("closing one borrowed adapter leaves the shared client usable") {
    val client = new Client
    val one = RegistrySerializer.forValue(new NumberCodec, client)
    val two = RegistrySerializer.forValue(new NumberCodec, client)
    one.close()
    assertEquals(two.serialize("b", Number(2)).last, 4.toByte)
    two.close()
    assertEquals(client.closes.get(), 0)
  }

  test("same-thread close during an operation fails without deadlock or closing the adapter") {
    withPool(1) { pool =>
      val codec = new NumberCodec
      val client = new Client
      val writer = new RegistrySerializer(codec, client, SerializerSettings(), RegistryRole.Value, true)
      codec.beforeWrite.set(() => writer.close())
      val error = result(submit(pool)(failure(writer.serialize("a", Number(1)))))
      assert(error.getCause.isInstanceOf[IllegalStateException])
      assertEquals(client.closes.get(), 0)
      codec.beforeWrite.set(() => ())
      assertEquals(writer.serialize("a", Number(2)).last, 4.toByte)
      writer.close()
      assertEquals(client.closes.get(), 1)
    }
  }

  test("close callbacks reject new operations even on another thread and allow repeated close") {
    withPool(1) { pool =>
      val client = new Client
      val writer = new RegistrySerializer(new NumberCodec, client, SerializerSettings(), RegistryRole.Value, true)
      val sameThread = new AtomicReference[Throwable]()
      val otherThread = new AtomicReference[Throwable]()
      client.beforeClose.set(() => {
        sameThread.set(failure(writer.serialize("a", Number(1))))
        otherThread.set(result(submit(pool)(failure(writer.serialize("a", Number(2))))))
        writer.close()
      })
      writer.close()
      assert(sameThread.get().getCause.isInstanceOf[IllegalStateException])
      assert(otherThread.get().getCause.isInstanceOf[IllegalStateException])
      assertEquals(client.loads.get(), 0)
      assertEquals(client.closes.get(), 1)
    }
  }

  test("the same numeric ID in independent registries keeps separate reader plans") {
    val alternate = """{"type":"record","name":"ConcurrentNumber","fields":[{"name":"ignored","type":"int"},{"name":"value","type":"int"}]}"""
    val one = RegistryDeserializer.forValue(new NumberCodec, new Client)
    val two = RegistryDeserializer.forValue(new NumberCodec, new Client(alternate))
    try
      assertEquals(one.deserialize("a", frame(1, 2)), Number(1))
      assertEquals(two.deserialize("a", frame(1, 18, 14)), Number(7))
      assertEquals(one.deserialize("a", frame(1, 4)), Number(2))
    finally
      one.close()
      two.close()
  }

  test("cache wrapper preserves original checked, unchecked and fatal causes and permits retry") {
    val cache = new BoundedCache[String, Int](1)
    val checked = new IOException("checked")
    val unchecked = new IllegalArgumentException("unchecked")
    val fatal = new OutOfMemoryError("synthetic fatal error")
    assert(intercept[SerializationException](RegistrySupport.guard("test")(cache.getOrLoad("a")(throw checked))).getCause eq checked)
    assert(intercept[SerializationException](RegistrySupport.guard("test")(cache.getOrLoad("a")(throw unchecked))).getCause eq unchecked)
    var observedFatal: OutOfMemoryError = null
    try RegistrySupport.guard("test")(cache.getOrLoad("a")(throw fatal))
    catch case error: OutOfMemoryError => observedFatal = error
    assert(observedFatal eq fatal)
    assertEquals(cache.getOrLoad("a")(1), 1)
    assertEquals(cache.getOrLoad("b")(2), 2)
    assertEquals(cache.getOrLoad("a")(3), 3)
  }

  test("a loader interruption restores only the loader's flag, releases followers, and allows retry") {
    withPool(2) { pool =>
      val cache = new BoundedCache[String, Int](1)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val expected = new InterruptedException("load interrupted")
      def call(load: => Int): (Throwable, Boolean) =
        try
          val error = failure(RegistrySupport.guard("test")(cache.getOrLoad("a")(load)))
          (error, Thread.currentThread().isInterrupted)
        finally Thread.interrupted()
      try
        val leader = submit(pool)(call { entered.countDown(); await(release); throw expected })
        await(entered)
        val (follower, thread) = tracked(pool)(call(throw new AssertionError("Follower must share the load")))
        awaitWaiting(follower, thread)
        release.countDown()
        val (leaderError, leaderInterrupted) = result(leader)
        val (followerError, followerInterrupted) = result(follower)
        assert(leaderError.getCause eq expected)
        assert(followerError.getCause eq expected)
        assert(leaderInterrupted)
        assert(!followerInterrupted)
        assertEquals(cache.getOrLoad("a")(7), 7)
      finally release.countDown()
    }
  }

  test("interrupting a cache waiter preserves its flag without cancelling the shared load") {
    withPool(2) { pool =>
      val cache = new BoundedCache[String, Int](1)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      def call(load: => Int): (Int, Boolean) =
        try
          val value = cache.getOrLoad("a")(load)
          (value, Thread.currentThread().isInterrupted)
        finally Thread.interrupted()
      try
        val leader = submit(pool)(call { entered.countDown(); await(release); 42 })
        await(entered)
        val (follower, thread) = tracked(pool)(call(throw new AssertionError("Follower must share the load")))
        awaitWaiting(follower, thread)
        thread.get().interrupt()
        release.countDown()
        assertEquals(result(leader), (42, false))
        assertEquals(result(follower), (42, true))
        assertEquals(cache.getOrLoad("a")(43), 42)
      finally release.countDown()
    }
  }
