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

  test("a failed map discards later transformations and unwinds enclosing cleanup once") {
    var cleaned = 0
    var later = 0
    val failure = new IllegalArgumentException("mapping failed")
    val program = Step.guarantee {
      Step.delay(1).map[Int](_ => throw failure).map { value =>
        later += 1
        value + 1
      }
    } { cleaned += 1 }
    assert(intercept[IllegalArgumentException](Step.run(program)) eq failure)
    assertEquals(cleaned, 1)
    assertEquals(later, 0)
  }

  test("per-call frames resume repeated children and remain stack safe at 100000 levels") {
    var cleaned = 0
    final class Frame(left: Int) extends Step.Frame[Int]:
      private var state = 0
      private var answer = 0
      override def advance(completed: Any): Step[?] = state match
        case 0 =>
          state = 1
          if left == 0 then Step.done(0) else new Frame(left - 1)
        case 1 =>
          answer = completed.asInstanceOf[Int] + 1
          state = 2
          Step.delay(answer)
        case _ =>
          answer = completed.asInstanceOf[Int]
          null
      override def result: Int = answer
      override def cleanup(): Unit = cleaned += 1
    onSmallStack {
      val program = Step.defer(new Frame(100000))
      assertEquals(Step.run(program), 100001)
      assertEquals(cleaned, 100001)
      assertEquals(Step.run(program), 100001)
      assertEquals(cleaned, 200002)
    }
  }

  test("frame failures during entry, resumption or result construction clean up once") {
    for phase <- 0 to 2 do
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      val failure = new IllegalArgumentException(s"phase $phase")
      val program = Step.guarantee {
        new Step.Frame[Int]:
          private var state = 0
          override def advance(completed: Any): Step[?] =
            if state == phase then throw failure
            if state == 0 then
              state = 1
              Step.done(42)
            else null
          override def result: Int = throw failure
          override def cleanup(): Unit = events += "frame"
      } { events += "outer" }
      assert(intercept[IllegalArgumentException](Step.run(program)) eq failure)
      assertEquals(events.toVector, Vector("frame", "outer"))
  }

  test("throwing frame cleanup runs once and preserves finally ordering on every exit path") {
    for
      phase <- Vector("entry", "child", "resume", "result", "completed", "immediate")
      failOuter <- Vector(false, true)
    do
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      val bodyFailure = new IllegalArgumentException(phase)
      val innerFailure = new IllegalStateException("inner cleanup")
      val outerFailure = new IllegalStateException("outer cleanup")
      val inner = new Step.Frame[Int]:
        private var started = false
        override def advance(completed: Any): Step[?] =
          if !started then
            started = true
            events += "enter"
            if phase == "entry" then throw bodyFailure
            if phase == "immediate" then null
            else Step.guarantee {
              Step.delay {
                events += "child"
                if phase == "child" then throw bodyFailure
                42
              }
            } { events += "child cleanup" }
          else
            events += "resume"
            if phase == "resume" then throw bodyFailure
            null
        override def result: Int =
          events += "result"
          if phase == "result" then throw bodyFailure
          42
        override def cleanup(): Unit =
          events += "inner cleanup"
          throw innerFailure
      val outer = new Step.Frame[Int]:
        private var started = false
        override def advance(completed: Any): Step[?] =
          if started then fail("An inner cleanup failure must discard the outer continuation")
          started = true
          inner
        override def result: Int = fail("An inner cleanup failure must discard the outer continuation")
        override def cleanup(): Unit =
          events += "outer cleanup"
          if failOuter then throw outerFailure
      val program = Step.guarantee(outer) { events += "final cleanup" }
      val beforeCleanup = phase match
        case "entry" => Vector("enter")
        case "child" => Vector("enter", "child", "child cleanup")
        case "resume" => Vector("enter", "child", "child cleanup", "resume")
        case "immediate" => Vector("enter", "result")
        case _ => Vector("enter", "child", "child cleanup", "resume", "result")
      val context = s"phase=$phase, failing outer cleanup=$failOuter"
      val thrown = intercept[IllegalStateException](Step.run(program))
      assert(thrown eq (if failOuter then outerFailure else innerFailure), context)
      assertEquals(events.toVector, beforeCleanup ++ Vector("inner cleanup", "outer cleanup", "final cleanup"), context)
  }
