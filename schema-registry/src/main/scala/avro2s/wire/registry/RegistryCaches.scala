package avro2s.wire.registry

import java.util.IdentityHashMap

/** Explicit coordination of adapter caches and their shared registry client. */
object RegistryCaches:
  /** Drain active calls, reset the shared client once, and clear all supplied adapters.
    * Subsequent calls reload lazily. Duplicate adapters are harmless; all adapters
    * must be open and use the same client instance. The client is never closed.
    *
    * Include every live Wire adapter using that client. Adapters are not tracked
    * globally, so omitted adapters keep their caches. Coordinate direct client use
    * separately. A custom client's reset callback must not wait for another thread
    * to use, refresh, or close a participating adapter.
    *
    * Calls arriving during refresh wait. If client reset fails, local caches are
    * still cleared and adapters remain open, but the client's refresh is unverified;
    * the failure is propagated and refresh can be retried.
    */
  def refresh(first: RegistryAdapter, rest: RegistryAdapter*): Unit =
    RegistrySupport.guard("cache refresh") {
      val supplied = first +: rest
      require(supplied.forall(_ != null), "Registry adapters must be non-null")
      val seen = new IdentityHashMap[RegistryAdapter, java.lang.Boolean]()
      val adapters = supplied.filter(adapter => seen.put(adapter, java.lang.Boolean.TRUE) == null)
      val client = first.registryClient
      require(adapters.forall(_.registryClient eq client),
        "A cache refresh requires adapters sharing the same registry client instance")
      val ordered = adapters.sortBy(_.registryLifecycle.order)

      // Check every participant before acquiring any lock: a callback may already
      // hold a later adapter's read lock and cannot upgrade it to a write lock.
      ordered.foreach(_.registryLifecycle.checkRefreshAllowed())
      var acquired = 0
      try
        while acquired < ordered.size do
          ordered(acquired).registryLifecycle.lockForRefresh()
          acquired += 1
        try client.reset()
        finally ordered.foreach(_.clearRegistryCache())
      finally
        while acquired > 0 do
          acquired -= 1
          ordered(acquired).registryLifecycle.unlockAfterRefresh()
    }
