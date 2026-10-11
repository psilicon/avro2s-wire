package avro2s.wire.registry

import com.google.common.cache.CacheBuilder
import com.google.common.util.concurrent.{ExecutionError, UncheckedExecutionException}
import java.util.concurrent.{Callable, ExecutionException}
import org.apache.kafka.common.errors.SerializationException

/** A bounded cache whose callers share successful loads for the same key.
  * Loading and waiting are synchronous; unrelated keys can load concurrently.
  * The adapter's lifecycle must drain operations before clearing this cache.
  */
private[registry] final class BoundedCache[K, V](maxEntries: Int):
  require(maxEntries > 0, "maxEntries must be positive")

  private val entries = CacheBuilder.newBuilder().maximumSize(maxEntries.toLong).build[K, V]()

  def getOrLoad(key: K)(load: => V): V =
    val hit = entries.getIfPresent(key)
    if hit != null then hit
    else
      try entries.get(key, new Callable[V]:
        override def call(): V =
          try load
          catch
            // A load failure is shared by its waiters. Restore only the loader's
            // interrupt, then wrap it so an outer guard does not interrupt waiters.
            case error: InterruptedException =>
              Thread.currentThread().interrupt()
              throw new SerializationException("Avro registry cache load interrupted", error)
      )
      catch
        case error: ExecutionException => throw error.getCause
        case error: UncheckedExecutionException => throw error.getCause
        // Preserve fatal errors rather than treating Guava's Error wrapper as NonFatal.
        case error: ExecutionError => throw error.getCause

  def clear(): Unit = entries.invalidateAll()
