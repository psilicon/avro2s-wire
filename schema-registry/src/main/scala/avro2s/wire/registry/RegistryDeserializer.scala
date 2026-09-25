package avro2s.wire.registry

import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.AvroCodec
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import java.util.{Arrays, Map as JMap}
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Deserializer
import scala.jdk.CollectionConverters.*

/** Reads one classic Confluent-framed datum into the supplied generated model. */
final class RegistryDeserializer[A] private[registry] (
    codec: AvroCodec[A],
    client: SchemaRegistryClient,
    settings: RegistrySettings,
    private val ownsClient: Boolean
) extends Deserializer[A]:
  def this(codec: AvroCodec[A], client: SchemaRegistryClient, settings: RegistrySettings) =
    this(codec, client, settings, false)

  def this(codec: AvroCodec[A], client: SchemaRegistryClient) =
    this(codec, client, RegistrySettings(), false)

  require(client != null, "client must be non-null")
  require(settings != null, "settings must be non-null")
  RegistrySupport.namedSchema(codec)
  private val readers = new BoundedCache[Int, ResolvingReader[A]](settings.cacheCapacity)
  private var isKey = false
  private var used = false
  private var closed = false

  override def configure(configs: JMap[String, ?], isKey: Boolean): Unit = synchronized {
    RegistrySupport.guard("configuration") {
      ensureOpen()
      if used then throw new IllegalStateException("Cannot reconfigure a deserializer after use")
      RegistrySupport.validateConfig(configs, settings)
      this.isKey = isKey
    }
  }

  override def deserialize(topic: String, data: Array[Byte]): A = synchronized {
    RegistrySupport.guard("deserialization") {
      ensureOpen()
      used = true
      if data == null then null.asInstanceOf[A]
      else decode(data)
    }
  }

  override def deserialize(topic: String, headers: Headers, data: Array[Byte]): A = synchronized {
    RegistrySupport.guard("deserialization") {
      ensureOpen()
      used = true
      val header = if isKey then "__key_schema_id" else "__value_schema_id"
      if data != null && headers != null && headers.lastHeader(header) != null then
        throw new IllegalArgumentException("Schema ID header framing is unsupported")
      if data == null then null.asInstanceOf[A]
      else decode(data)
    }
  }

  private def decode(data: Array[Byte]): A =
    if data.length < 5 then throw new IllegalArgumentException("Confluent frame is shorter than five bytes")
    if data(0) != 0 then throw new IllegalArgumentException("Unsupported Confluent magic byte")
    if data.length - 5 > settings.decodeLimits.maxInputBytes then
      throw new IllegalArgumentException("Avro datum exceeds maxInputBytes")
    val id = ((data(1) & 0xff) << 24) | ((data(2) & 0xff) << 16) |
      ((data(3) & 0xff) << 8) | (data(4) & 0xff)
    if id <= 0 then throw new IllegalArgumentException("Invalid Confluent schema ID")
    val reader = readers.getOrLoad(id) {
      val writer = RegistrySupport.writerSchema(client.getSchemaById(id))
      new ResolvingReader(writer, codec)
    }
    reader.decode(Arrays.copyOfRange(data, 5, data.length), settings.decodeLimits)

  override def close(): Unit = synchronized {
    if !closed then
      closed = true
      readers.clear()
      if ownsClient then RegistrySupport.guard("close")(client.close())
  }

  private def ensureOpen(): Unit =
    if closed then throw new IllegalStateException("Deserializer is closed")

object RegistryDeserializer:
  /** Creates an adapter that closes its own cached registry client. */
  def fromConfig[A](
      codec: AvroCodec[A],
      urls: List[String],
      capacity: Int = 1024,
      config: Map[String, AnyRef] = Map.empty,
      settings: RegistrySettings = RegistrySettings()
  ): RegistryDeserializer[A] =
    RegistrySupport.validateConfig(config.asJava, settings)
    val client = RegistrySupport.ownedClient(urls, capacity, config)
    try new RegistryDeserializer(codec, client, settings, ownsClient = true)
    catch
      case scala.util.control.NonFatal(e) =>
        client.close()
        throw e
