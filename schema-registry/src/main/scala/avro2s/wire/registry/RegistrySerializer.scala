package avro2s.wire.registry

import avro2s.wire.runtime.AvroCodec
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import java.util.{Map as JMap}
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Serializer
import scala.jdk.CollectionConverters.*

/** Classic Confluent framing around the bytes produced by a generated codec.
  * An injected client remains caller-owned. Calls and close are synchronized.
  */
final class RegistrySerializer[A] private[registry] (
    codec: AvroCodec[A],
    client: SchemaRegistryClient,
    settings: RegistrySettings,
    private val ownsClient: Boolean
) extends Serializer[A]:
  def this(codec: AvroCodec[A], client: SchemaRegistryClient, settings: RegistrySettings) =
    this(codec, client, settings, false)

  def this(codec: AvroCodec[A], client: SchemaRegistryClient) =
    this(codec, client, RegistrySettings(), false)

  require(client != null, "client must be non-null")
  require(settings != null, "settings must be non-null")
  private val schema = RegistrySupport.namedSchema(codec)
  private val ids = new BoundedCache[String, Int](settings.cacheCapacity)
  private var isKey = false
  private var used = false
  private var closed = false

  override def configure(configs: JMap[String, ?], isKey: Boolean): Unit = synchronized {
    RegistrySupport.guard("configuration") {
      ensureOpen()
      if used then throw new IllegalStateException("Cannot reconfigure a serializer after use")
      RegistrySupport.validateConfig(configs, settings)
      this.isKey = isKey
    }
  }

  override def serialize(topic: String, data: A): Array[Byte] = synchronized {
    RegistrySupport.guard("serialization") {
      ensureOpen()
      used = true
      if data == null then null
      else
        val subject = settings.subjectNameStrategy.subject(topic, schema.rawSchema(), isKey)
        val id = ids.getOrLoad(subject) {
          val found =
            if settings.autoRegisterSchemas then client.register(subject, schema, settings.normalizeSchemas)
            else client.getId(subject, schema, settings.normalizeSchemas)
          if found <= 0 then throw new IllegalArgumentException("Registry returned an invalid schema ID")
          found
        }
        val datum = codec.encode(data)
        val framed = new Array[Byte](5 + datum.length)
        framed(0) = 0
        framed(1) = (id >>> 24).toByte
        framed(2) = (id >>> 16).toByte
        framed(3) = (id >>> 8).toByte
        framed(4) = id.toByte
        System.arraycopy(datum, 0, framed, 5, datum.length)
        framed
    }
  }

  override def serialize(topic: String, headers: Headers, data: A): Array[Byte] = synchronized {
    RegistrySupport.guard("serialization") {
      ensureOpen()
      val header = if isKey then "__key_schema_id" else "__value_schema_id"
      if data != null && headers != null && headers.lastHeader(header) != null then
        throw new IllegalArgumentException("Schema ID header framing is unsupported")
      serialize(topic, data)
    }
  }

  override def close(): Unit = synchronized {
    if !closed then
      closed = true
      ids.clear()
      if ownsClient then RegistrySupport.guard("close")(client.close())
  }

  private def ensureOpen(): Unit =
    if closed then throw new IllegalStateException("Serializer is closed")

object RegistrySerializer:
  /** Creates an adapter that closes its own cached registry client. */
  def fromConfig[A](
      codec: AvroCodec[A],
      urls: List[String],
      capacity: Int = 1024,
      config: Map[String, AnyRef] = Map.empty,
      settings: RegistrySettings = RegistrySettings()
  ): RegistrySerializer[A] =
    RegistrySupport.validateConfig(config.asJava, settings)
    val client = RegistrySupport.ownedClient(urls, capacity, config)
    try new RegistrySerializer(codec, client, settings, ownsClient = true)
    catch
      case scala.util.control.NonFatal(e) =>
        client.close()
        throw e
