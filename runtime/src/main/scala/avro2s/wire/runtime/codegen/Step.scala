package avro2s.wire.runtime.codegen

/** Generated-code support: a suspended computation executed by one iterative driver.
  * Composition is immutable. The driver's pending work belongs to a single run.
  */
sealed abstract class Step[+A]:
  final def map[B](f: A => B): Step[B] = flatMap(value => Step.done(f(value)))

  final def flatMap[B](f: A => Step[B]): Step[B] =
    Step.bind(this, f)

object Step:
  private final case class Done(value: Any) extends Step[Any]
  private final case class Suspend(next: () => Step[Any]) extends Step[Any]
  private final case class Bind(source: Step[Any], resume: Any => Step[Any]) extends Step[Any]
  private final case class Ensure(source: Step[Any], cleanup: () => Unit) extends Step[Any]

  private sealed trait Frame
  private final case class Continue(resume: Any => Step[Any]) extends Frame
  private final case class Cleanup(action: () => Unit) extends Frame

  def done[A](value: A): Step[A] = Done(value).asInstanceOf[Step[A]]

  /** Defers a primitive read, write or construction until the driver reaches it. */
  def delay[A](value: => A): Step[A] = defer(done(value))

  /** Recursive edges must suspend their child operation rather than run a new driver. */
  def defer[A](next: => Step[A]): Step[A] =
    Suspend(() => next.asInstanceOf[Step[Any]]).asInstanceOf[Step[A]]

  /** Runs cleanup on success and failure, with ordinary try/finally semantics. */
  def guarantee[A](body: => Step[A])(cleanup: => Unit): Step[A] =
    Ensure(defer(body).asInstanceOf[Step[Any]], () => cleanup).asInstanceOf[Step[A]]

  private def bind[A, B](source: Step[A], f: A => Step[B]): Step[B] =
    Bind(source.asInstanceOf[Step[Any]], f.asInstanceOf[Any => Step[Any]]).asInstanceOf[Step[B]]

  def run[A](initial: Step[A]): A =
    var current = initial.asInstanceOf[Step[Any]]
    var pending = List.empty[Frame]
    try
      while true do
        current match
          case Done(value) => pending match
            case Nil => return value.asInstanceOf[A]
            case Continue(resume) :: rest =>
              pending = rest
              current = resume(value)
            case Cleanup(action) :: rest =>
              pending = rest
              action()
          case Suspend(next) => current = next()
          case Bind(source, resume) =>
            pending = Continue(resume) :: pending
            current = source
          case Ensure(source, cleanup) =>
            pending = Cleanup(cleanup) :: pending
            current = source
      throw new IllegalStateException("Unreachable step driver state")
    catch
      case error: Throwable =>
        // A failed continuation discards unfinished computations but still exits
        // each entered record. An outer failing finally supersedes an inner error.
        var failure = error
        while pending.nonEmpty do
          val frame = pending.head
          pending = pending.tail
          frame match
            case Cleanup(action) =>
              try action()
              catch case next: Throwable => failure = next
            case _: Continue => ()
        throw failure
