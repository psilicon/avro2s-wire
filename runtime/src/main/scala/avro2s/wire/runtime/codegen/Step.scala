package avro2s.wire.runtime.codegen

import avro2s.wire.runtime.AvroInput

/** Generated-code support: a suspended computation executed by one iterative driver.
  * Composition nodes are immutable. Frames and pending work belong to one run.
  */
sealed abstract class Step[+A]:
  final def map[B](f: A => B): Step[B] = Step.mapped(this, f)

  final def flatMap[B](f: A => Step[B]): Step[B] =
    Step.bind(this, f)

object Step:
  /** Generated execution state for one operation, never shared between calls.
    * Return a child computation to suspend, or null when result is available.
    * After a child completes, advance receives its result. The first invocation
    * must ignore that argument. cleanup runs once, on completion or failure.
    * Construct a fresh frame per operation; use defer to build a reusable program.
    */
  abstract class Frame[+A] extends Step[A]:
    def advance(completed: Any): Step[?]
    def result: A
    def cleanup(): Unit = ()

  private final case class Done(value: Any) extends Step[Any]
  private final case class Eval(value: () => Any) extends Step[Any]
  private final case class Suspend(next: () => Step[Any]) extends Step[Any]
  private final case class Bind(source: Step[Any], resume: Any => Step[Any]) extends Step[Any]
  private final case class Mapped(source: Step[Any], transform: Any => Any) extends Step[Any]
  private final case class Ensure(body: () => Step[Any], cleanup: () => Unit) extends Step[Any]
  private final case class Record(in: AvroInput, fields: () => Step[Any]) extends Step[Any]

  def done[A](value: A): Step[A] = Done(value).asInstanceOf[Step[A]]

  /** Defers a primitive read, write or construction until the driver reaches it. */
  def delay[A](value: => A): Step[A] = Eval(() => value).asInstanceOf[Step[A]]

  /** Recursive edges must suspend their child operation rather than run a new driver. */
  def defer[A](next: => Step[A]): Step[A] =
    Suspend(() => next.asInstanceOf[Step[Any]]).asInstanceOf[Step[A]]

  /** Runs cleanup on success and failure, with ordinary try/finally semantics. */
  def guarantee[A](body: => Step[A])(cleanup: => Unit): Step[A] =
    Ensure(() => body.asInstanceOf[Step[Any]], () => cleanup).asInstanceOf[Step[A]]

  private[codegen] def record[A](in: AvroInput)(fields: => Step[A]): Step[A] =
    Record(in, () => fields.asInstanceOf[Step[Any]]).asInstanceOf[Step[A]]

  private def bind[A, B](source: Step[A], f: A => Step[B]): Step[B] =
    Bind(source.asInstanceOf[Step[Any]], f.asInstanceOf[Any => Step[Any]]).asInstanceOf[Step[B]]

  private def mapped[A, B](source: Step[A], f: A => B): Step[B] =
    Mapped(source.asInstanceOf[Step[Any]], f.asInstanceOf[Any => Any]).asInstanceOf[Step[B]]

  def run[A](initial: Step[A]): A = initial match
    // Flat generated codecs need no continuation storage. Keep this entry small
    // enough for the JVM to inline and eliminate their suspended leaf wrapper.
    case Eval(evaluate) => evaluate().asInstanceOf[A]
    case Done(value) => value.asInstanceOf[A]
    case _ => runSuspended(initial)

  private def runSuspended[A](initial: Step[A]): A =
    var current = initial.asInstanceOf[Step[Any]]
    // Reuse the computation nodes as continuation frames. The pending array is
    // local to this invocation and allocated only when something must suspend.
    var pending: Array[Step[Any]] = null
    var size = 0
    var value: Any = null
    try
      while true do
        var ready = false
        current match
          case frame: Frame[?] =>
            if pending == null then pending = new Array[Step[Any]](8)
            else if size == pending.length then
              val grown = new Array[Step[Any]](pending.length * 2)
              System.arraycopy(pending, 0, grown, 0, size)
              pending = grown
            pending(size) = current
            size += 1
            val child = frame.advance(value)
            if child == null then
              size -= 1
              pending(size) = null
              try value = frame.result
              finally frame.cleanup()
              ready = true
            else current = child.asInstanceOf[Step[Any]]
          case Done(result) =>
            value = result
            ready = true
          case Eval(evaluate) =>
            value = evaluate()
            ready = true
          case Suspend(next) => current = next()
          case _: Bind | _: Mapped | _: Ensure | _: Record =>
            if pending == null then pending = new Array[Step[Any]](8)
            else if size == pending.length then
              val grown = new Array[Step[Any]](pending.length * 2)
              System.arraycopy(pending, 0, grown, 0, size)
              pending = grown
            // Reserve storage before entering, so allocation failure cannot
            // strand an entered scope. A rejected enter installs no leave.
            current match
              case Record(in, _) => in.enterRecord()
              case _ => ()
            pending(size) = current
            size += 1
            current match
              case Bind(source, _) => current = source
              case Mapped(source, _) => current = source
              case Ensure(body, _) => current = body()
              case Record(_, fields) => current = fields()
              case _ => throw new IllegalStateException("Invalid continuation")
        // Maps and cleanup can consume a value directly; neither needs a Done
        // node or another trip through the operation-dispatch loop.
        while ready do
          if size == 0 then return value.asInstanceOf[A]
          pending(size - 1) match
            case frame: Frame[?] =>
              // Resume in place. Keeping the frame installed also guarantees
              // cleanup if advance fails before returning its next child.
              val child = frame.advance(value)
              if child == null then
                size -= 1
                pending(size) = null
                try value = frame.result
                finally frame.cleanup()
              else
                current = child.asInstanceOf[Step[Any]]
                ready = false
            case continuation =>
              size -= 1
              pending(size) = null
              continuation match
                case Bind(_, resume) =>
                  current = resume(value)
                  ready = false
                case Mapped(_, transform) => value = transform(value)
                case Ensure(_, cleanup) => cleanup()
                case Record(in, _) => in.leaveRecord()
                case _ => throw new IllegalStateException("Invalid continuation")
      throw new IllegalStateException("Unreachable step driver state")
    catch
      case error: Throwable =>
        // A failed continuation discards unfinished computations but still exits
        // each entered record. An outer failing finally supersedes an inner error.
        var failure = error
        while size > 0 do
          size -= 1
          val frame = pending(size)
          pending(size) = null
          frame match
            case Ensure(_, cleanup) =>
              try cleanup()
              catch case next: Throwable => failure = next
            case Record(in, _) =>
              try in.leaveRecord()
              catch case next: Throwable => failure = next
            case frame: Frame[?] =>
              try frame.cleanup()
              catch case next: Throwable => failure = next
            case _ => ()
        throw failure
