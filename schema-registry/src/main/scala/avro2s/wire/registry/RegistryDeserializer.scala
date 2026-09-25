package avro2s.wire.registry

import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.AvroCodec
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import java.util.Arrays
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Deserializer

/** Reads one classic Confluent-framed datum into the supplied generated model.
  * The role and settings are fixed at construction; Kafka configure is unnecessary.
  */
final class RegistryDeserializer[A] private[registry] (
    codec: AvroCodec[A],
    client: SchemaRegistryClient,
    settings: DeserializerSettings,
    role: RegistryRole,
    private val ownsClient: Boolean
) extends Deserializer[A]:
  require(client != null, "client must be non-null")
  require(settings != null, "settings must be non-null")
  RegistrySupport.namedSchema(codec)
  private val readers = new BoundedCache[Int, ResolvingReader[A]](settings.cacheCapacity)
  private var closed = false

  override def deserialize(topic: String, data: Array[Byte]): A = synchronized {
    RegistrySupport.guard("deserialization") {
      ensureOpen()
      if data == null then null.asInstanceOf[A]
      else decode(data)
    }
  }

  override def deserialize(topic: String, headers: Headers, data: Array[Byte]): A = synchronized {
    RegistrySupport.guard("deserialization") {
      ensureOpen()
      if data != null && headers != null && headers.lastHeader(role.schemaIdHeader) != null then
        throw new IllegalArgumentException("Schema ID header framing is unsupported")
      if data == null then null.asInstanceOf[A]
      else decode(data)
    }
  }

  private def decode(data: Array[Byte]): A =
    if data.length < 5 then throw new IllegalArgumentException("Confluent frame is shorter than five bytes")
    if data(0) != 0 then throw new IllegalArgumentException("Unsupported Confluent magic byte")
    if settings.decodeLimits.maxInputBytes.exists(data.length - 5 > _) then
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
  /** A ready-to-use key deserializer borrowing an application-owned client. */
  def forKey[A](
      codec: AvroCodec[A],
      client: SchemaRegistryClient,
      settings: DeserializerSettings = DeserializerSettings()
  ): RegistryDeserializer[A] =
    new RegistryDeserializer(codec, client, settings, RegistryRole.Key, ownsClient = false)

  /** A ready-to-use value deserializer borrowing an application-owned client. */
  def forValue[A](
      codec: AvroCodec[A],
      client: SchemaRegistryClient,
      settings: DeserializerSettings = DeserializerSettings()
  ): RegistryDeserializer[A] =
    new RegistryDeserializer(codec, client, settings, RegistryRole.Value, ownsClient = false)

  /** A key deserializer that creates and owns its registry client. */
  def forKey[A](codec: AvroCodec[A], connection: RegistryConnection): RegistryDeserializer[A] =
    forKey(codec, connection, DeserializerSettings())

  def forKey[A](
      codec: AvroCodec[A], connection: RegistryConnection, settings: DeserializerSettings
  ): RegistryDeserializer[A] =
    RegistrySupport.withOwnedClient(connection) { client =>
      new RegistryDeserializer(codec, client, settings, RegistryRole.Key, ownsClient = true)
    }

  /** A value deserializer that creates and owns its registry client. */
  def forValue[A](codec: AvroCodec[A], connection: RegistryConnection): RegistryDeserializer[A] =
    forValue(codec, connection, DeserializerSettings())

  def forValue[A](
      codec: AvroCodec[A], connection: RegistryConnection, settings: DeserializerSettings
  ): RegistryDeserializer[A] =
    RegistrySupport.withOwnedClient(connection) { client =>
      new RegistryDeserializer(codec, client, settings, RegistryRole.Value, ownsClient = true)
    }
