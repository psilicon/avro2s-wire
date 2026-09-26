package avro2s.wire.runtime

import avro2s.wire.runtime.codegen.{StackSafe, Step}
import java.util.concurrent.atomic.AtomicReference

final class StepSuite extends munit.FunSuite:
  private def onSmallStack(body: => Unit): Unit =
    val failure = new AtomicReference[Throwable]()
    val thread = new Thread(null, () =>
      try body
      catch case error: Throwable => failure.set(error)
    , "step-test", 256 * 1024L)
    thread.setDaemon(true)
    thread.start()
    thread.join(30000)
    assert(!thread.isAlive, "step execution did not finish")
    if failure.get() != null then throw failure.get()

  test("left-associated composition and deferred recursion use a bounded JVM stack") {
    onSmallStack {
      var program = Step.done(0)
      var i = 0
      while i < 100000 do
        program = program.flatMap(value => Step.done(value + 1))
        i += 1
      assertEquals(Step.run(program), 100000)
      def recursive(left: Int): Step[Int] =
        if left == 0 then Step.done(0)
        else Step.defer(recursive(left - 1)).map(_ + 1)
      assertEquals(Step.run(recursive(100000)), 100000)
    }
  }

  test("guarantees unwind deeply nested entered scopes on success and failure") {
    onSmallStack {
      var active = 0
      var cleaned = 0
      val original = new IllegalArgumentException("leaf failure")
      def nested(left: Int, fail: Boolean): Step[Int] = Step.defer {
        if left == 0 then Step.delay { if fail then throw original else 7 }
        else
          active += 1
          Step.guarantee(nested(left - 1, fail)) {
            active -= 1
            cleaned += 1
          }
      }
      assertEquals(Step.run(nested(100000, false)), 7)
      assertEquals(active, 0)
      assertEquals(cleaned, 100000)
      val thrown = intercept[IllegalArgumentException](Step.run(nested(100000, true)))
      assert(thrown eq original)
      assertEquals(active, 0)
      assertEquals(cleaned, 200000)
    }
  }

  test("cleanup uses finally ordering and runs outer cleanup even if inner cleanup fails") {
    val events = scala.collection.mutable.ArrayBuffer.empty[String]
    val outerFailure = new IllegalStateException("outer")
    val program = Step.guarantee {
      Step.guarantee(Step.delay[Int](throw new IllegalArgumentException("body"))) {
        events += "inner"
        throw new IllegalArgumentException("inner")
      }
    } {
      events += "outer"
      throw outerFailure
    }
    val thrown = intercept[IllegalStateException](Step.run(program))
    assert(thrown eq outerFailure)
    assertEquals(events.toVector, Vector("inner", "outer"))
  }

  test("record hooks are balanced and a failed enter does not run leave") {
    val in = new BinaryInput(Array.emptyByteArray, DecodeLimits(maxNestingDepth = Some(1)))
    val tooDeep = StackSafe.readRecord(in)(StackSafe.readRecord(in)(Step.done(())))
    intercept[AvroDecodingException](Step.run(tooDeep))
    in.requireEnd()
    intercept[IllegalArgumentException] {
      Step.run(StackSafe.readRecord(in)(Step.delay(throw new IllegalArgumentException("field"))))
    }
    in.requireEnd()
    Step.run(StackSafe.readRecord(in)(Step.done(())))
    in.requireEnd()
  }

  test("programs suspend side effects and may be executed independently more than once") {
    var calls = 0
    val program = Step.delay { calls += 1; calls }.map(_ * 2)
    assertEquals(calls, 0)
    assertEquals(Step.run(program), 2)
    assertEquals(Step.run(program), 4)
  }
