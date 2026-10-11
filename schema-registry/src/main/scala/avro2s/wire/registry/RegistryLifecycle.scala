package avro2s.wire.registry

import java.util.concurrent.locks.ReentrantReadWriteLock

/** Shared operation leases and an exclusive, terminal close.
  * Fairness prevents later operations from continually overtaking a waiting close.
  * Cleanup must not wait for another thread to close this same lifecycle.
  */
private[registry] final class RegistryLifecycle(label: String):
  private val lock = new ReentrantReadWriteLock(true)
  @volatile private var closed = false

  def use[A](body: => A): A =
    // Also reject callbacks from client.close, including another thread whose
    // completion the closing client may be waiting for.
    ensureOpen()
    val read = lock.readLock()
    read.lock()
    try
      ensureOpen()
      body
    finally read.unlock()

  def close(body: => Unit): Unit =
    // A read-to-write upgrade cannot succeed. Fail before attempting the lock.
    if lock.getReadHoldCount != 0 then
      throw new IllegalStateException(s"Cannot close $label during one of its operations")
    val write = lock.writeLock()
    write.lock()
    try
      if !closed then
        closed = true
        body
    finally write.unlock()

  private def ensureOpen(): Unit =
    if closed then throw new IllegalStateException(s"$label is closed")
