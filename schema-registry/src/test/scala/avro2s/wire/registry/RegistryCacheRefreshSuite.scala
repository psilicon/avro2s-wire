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

final class RegistryCacheRefreshSuite extends munit.FunSuite:
  private final case class Number(value: Int)
  private val json = """{"type":"record","name":"RefreshNumber","fields":[{"name":"value","type":"int"}]}"""

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

  /** Model a cached client separately from the adapter's cache and server state. */
  private final class Client extends MockSchemaRegistryClient:
    val serverId = new AtomicInteger(1)
    val lookups = new AtomicInteger()
    val fetches = new AtomicInteger()
    val resets = new AtomicInteger()
    val closes = new AtomicInteger()
    val beforeLookup = new AtomicReference[() => Unit](() => ())
    val beforeReset = new AtomicReference[() => Unit](() => ())
    private val cachedId = new AtomicInteger()

    override def getIdWithResponse(subject: String, schema: ParsedSchema, normalize: Boolean): RegisterSchemaResponse =
      lookups.incrementAndGet()
      beforeLookup.get()()
      val id = cachedId.updateAndGet(current => if current == 0 then serverId.get() else current)
      new RegisterSchemaResponse(new Schema(subject, 1, id, schema))

    override def getSchemaEntityBySubjectAndId(subject: String, id: Int): RegistrySchema =
      fetches.incrementAndGet()
      new RegistrySchema(subject, 1, id, new AvroSchema(json))

    override def reset(): Unit =
      resets.incrementAndGet()
      beforeReset.get()()
      cachedId.set(0)

    override def close(): Unit = { closes.incrementAndGet(); () }

  private def schemaId(frame: Array[Byte]): Int =
    ((frame(1) & 0xff) << 24) | ((frame(2) & 0xff) << 16) |
      ((frame(3) & 0xff) << 8) | (frame(4) & 0xff)

  private def failure(body: => Any): SerializationException = intercept[SerializationException](body)

  private def await(latch: CountDownLatch): Unit =
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
    val job = submit(pool) { worker.set(Thread.currentThread()); body }
    (job, worker)

  private def awaitWaiting(job: Future[?], worker: AtomicReference[Thread]): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    def waiting: Boolean =
      val thread = worker.get()
      thread != null && thread.getState == Thread.State.WAITING && !job.isDone
    while !waiting && !job.isDone && System.nanoTime() < deadline do Thread.`yield`()
    assert(waiting, "Concurrent operation did not wait behind the lifecycle lock")

  private def withPool[A](size: Int)(body: ExecutorService => A): A =
    val pool = Executors.newFixedThreadPool(size, runnable =>
      val thread = new Thread(runnable, "registry-refresh-test")
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

  test("batch refresh resets both cache layers once and never closes the borrowed client") {
    val client = new Client
    val codec = new NumberCodec
    val writer = RegistrySerializer.forValue(codec, client)
    val reader = RegistryDeserializer.forValue(codec, client)
    try
      val first = writer.serialize("numbers", Number(3))
      assertEquals(reader.deserialize("numbers", first), Number(3))
      client.serverId.set(9)
      client.reset()
      assertEquals(schemaId(writer.serialize("numbers", Number(3))), 1)
      assertEquals(reader.deserialize("numbers", first), Number(3))
      assertEquals(client.lookups.get(), 1)
      assertEquals(client.fetches.get(), 1)

      RegistryCaches.refresh(writer, reader, writer, reader)
      assertEquals(client.resets.get(), 2)
      assertEquals(schemaId(writer.serialize("numbers", Number(3))), 9)
      assertEquals(reader.deserialize("numbers", first), Number(3))
      assertEquals(client.lookups.get(), 2)
      assertEquals(client.fetches.get(), 2)
    finally
      writer.close()
      reader.close()
    assertEquals(client.closes.get(), 0)
  }

  test("failed reset clears local caches, preserves the failure, and allows retry") {
    val client = new Client
    val codec = new NumberCodec
    val writer = RegistrySerializer.forValue(codec, client)
    val reader = RegistryDeserializer.forValue(codec, client)
    try
      val frame = writer.serialize("numbers", Number(3))
      reader.deserialize("numbers", frame)
      val expected = new IOException("reset failed")
      client.beforeReset.set(() => throw expected)
      assert(failure(RegistryCaches.refresh(writer, reader)).getCause eq expected)
      client.beforeReset.set(() => ())
      writer.serialize("numbers", Number(3))
      assertEquals(reader.deserialize("numbers", frame), Number(3))
      assertEquals(client.lookups.get(), 2)
      assertEquals(client.fetches.get(), 2)
      RegistryCaches.refresh(writer, reader)
      assertEquals(client.resets.get(), 2)
      assertEquals(client.closes.get(), 0)
    finally
      writer.close()
      reader.close()
  }

  test("invalid or closed participants leave the client and other adapter caches untouched") {
    val client = new Client
    val otherClient = new Client
    val writer = RegistrySerializer.forValue(new NumberCodec, client)
    val reader = RegistryDeserializer.forValue(new NumberCodec, client)
    val other = RegistrySerializer.forValue(new NumberCodec, otherClient)
    try
      writer.serialize("numbers", Number(3))
      assert(failure(RegistryCaches.refresh(writer, other)).getCause.isInstanceOf[IllegalArgumentException])
      assert(failure(RegistryCaches.refresh(writer, null)).getCause.isInstanceOf[IllegalArgumentException])
      assert(failure(RegistryCaches.refresh(null)).getCause.isInstanceOf[IllegalArgumentException])
      reader.close()
      assert(failure(RegistryCaches.refresh(writer, reader)).getCause.isInstanceOf[IllegalStateException])
      writer.serialize("numbers", Number(3))
      assertEquals(client.lookups.get(), 1)
      assertEquals(client.resets.get(), 0)
      assertEquals(otherClient.resets.get(), 0)
    finally
      writer.close()
      reader.close()
      other.close()
  }

  test("refresh drains an active cache load and later calls wait for reset to finish") {
    withPool(3) { pool =>
      val client = new Client
      val writer = RegistrySerializer.forValue(new NumberCodec, client)
      val reader = RegistryDeserializer.forValue(new NumberCodec, client)
      val loading = new CountDownLatch(1)
      val releaseLoad = new CountDownLatch(1)
      val resetting = new CountDownLatch(1)
      val releaseReset = new CountDownLatch(1)
      client.beforeLookup.set(() => { loading.countDown(); await(releaseLoad) })
      client.beforeReset.set(() => { resetting.countDown(); await(releaseReset) })
      try
        val active = submit(pool)(writer.serialize("numbers", Number(3)))
        await(loading)
        val (refresh, refreshThread) = tracked(pool)(RegistryCaches.refresh(writer, reader))
        awaitWaiting(refresh, refreshThread)
        assertEquals(client.resets.get(), 0)
        releaseLoad.countDown()
        assertEquals(schemaId(result(active)), 1)
        await(resetting)
        val (later, laterThread) = tracked(pool)(writer.serialize("numbers", Number(4)))
        awaitWaiting(later, laterThread)
        assertEquals(client.lookups.get(), 1)
        client.serverId.set(2)
        releaseReset.countDown()
        result(refresh)
        assertEquals(schemaId(result(later)), 2)
        assertEquals(client.lookups.get(), 2)
      finally
        releaseLoad.countDown()
        releaseReset.countDown()
        writer.close()
        reader.close()
    }
  }

  test("overlapping refreshes in opposite argument order complete without deadlock") {
    withPool(2) { pool =>
      val client = new Client
      val writer = RegistrySerializer.forValue(new NumberCodec, client)
      val reader = RegistryDeserializer.forValue(new NumberCodec, client)
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      client.beforeReset.set(() => { entered.countDown(); await(release) })
      try
        val first = submit(pool)(RegistryCaches.refresh(writer, reader))
        await(entered)
        val (second, thread) = tracked(pool)(RegistryCaches.refresh(reader, writer))
        awaitWaiting(second, thread)
        release.countDown()
        result(first)
        result(second)
        assertEquals(client.resets.get(), 2)
      finally
        release.countDown()
        writer.close()
        reader.close()
    }
  }

  test("reset callbacks cannot reenter operations, refresh, or close participating adapters") {
    val client = new Client
    val codec = new NumberCodec
    val writer = RegistrySerializer.forValue(codec, client)
    val reader = RegistryDeserializer.forValue(codec, client)
    try
      val frame = writer.serialize("numbers", Number(3))
      client.beforeReset.set(() => {
        val failures = Vector(
          failure(writer.serialize("numbers", Number(3))),
          failure(reader.deserialize("numbers", frame)),
          failure(writer.close()),
          failure(reader.close()),
          failure(RegistryCaches.refresh(reader, writer))
        )
        failures.foreach(error => assert(error.getCause.isInstanceOf[IllegalStateException]))
      })
      RegistryCaches.refresh(writer, reader)
      client.beforeReset.set(() => ())
      assertEquals(reader.deserialize("numbers", writer.serialize("numbers", Number(4))), Number(4))
      assertEquals(client.closes.get(), 0)
    finally
      writer.close()
      reader.close()
  }

  test("refresh checks every participant before waiting on a lock held by another operation") {
    withPool(2) { pool =>
      val client = new Client
      val codec = new NumberCodec
      val writer = RegistrySerializer.forValue(codec, client)
      val reader = RegistryDeserializer.forValue(codec, client)
      val frame = writer.serialize("numbers", Number(3))
      val writing = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      codec.beforeWrite.set(() => { writing.countDown(); await(release) })
      codec.beforeRead.set(() => RegistryCaches.refresh(writer, reader))
      try
        val active = submit(pool)(writer.serialize("numbers", Number(4)))
        await(writing)
        val rejected = result(submit(pool)(failure(reader.deserialize("numbers", frame))))
        assert(rejected.getCause.isInstanceOf[IllegalStateException])
        assertEquals(client.resets.get(), 0)
        release.countDown()
        result(active)
      finally
        release.countDown()
        writer.close()
        reader.close()
    }
  }
