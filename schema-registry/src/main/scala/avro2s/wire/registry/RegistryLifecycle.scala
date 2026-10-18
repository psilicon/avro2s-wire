package avro2s.wire.registry

import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicLong

/** Shared operation leases, exclusive cache refresh, and a terminal close.
  * Fairness prevents later operations from overtaking a waiting exclusive operation.
  * Callbacks must not create cross-thread cycles through participating lifecycles.
  */
private[registry] final class RegistryLifecycle(label: String):
  val order: Long = RegistryLifecycle.nextOrder.getAndIncrement()
  private val lock = new ReentrantReadWriteLock(true)
  @volatile private var closed = false
  // Read only while this thread owns the write lock.
  private var refreshing = false

  def use[A](body: => A): A =
    // Also reject callbacks from client.close, including another thread whose
    // completion the closing client may be waiting for.
    ensureOpen()
    rejectRefreshCallback("use")
    val read = lock.readLock()
    read.lock()
    try
      ensureOpen()
      body
    finally read.unlock()

  def close(body: => Unit): Unit =
    rejectRefreshCallback("close")
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

  def checkRefreshAllowed(): Unit =
    ensureOpen()
    rejectRefreshCallback("refresh")
    if lock.getReadHoldCount != 0 then
      throw new IllegalStateException(s"Cannot refresh $label during one of its operations")

  def lockForRefresh(): Unit =
    val write = lock.writeLock()
    write.lock()
    try
      ensureOpen()
      refreshing = true
    catch
      case error: Throwable =>
        write.unlock()
        throw error

  def unlockAfterRefresh(): Unit =
    refreshing = false
    lock.writeLock().unlock()

  private def rejectRefreshCallback(operation: String): Unit =
    if lock.isWriteLockedByCurrentThread && refreshing then
      throw new IllegalStateException(s"Cannot $operation $label from its cache refresh callback")

  private def ensureOpen(): Unit =
    if closed then throw new IllegalStateException(s"$label is closed")

private[registry] object RegistryLifecycle:
  private val nextOrder = new AtomicLong()
