package avro2s.wire.registry

import avro2s.wire.runtime.AvroCodec
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Serializer

/** Classic Confluent framing around the bytes produced by a generated codec.
  * The role and settings are fixed at construction; Kafka configure is unnecessary.
  * An injected client remains caller-owned. Calls and close are synchronized.
  */
final class RegistrySerializer[A] private[registry] (
    codec: AvroCodec[A],
    client: SchemaRegistryClient,
    settings: SerializerSettings,
    role: RegistryRole,
    private val ownsClient: Boolean
) extends Serializer[A]:
  require(client != null, "client must be non-null")
  require(settings != null, "settings must be non-null")
  private val schema = RegistrySupport.namedSchema(codec)
  private val ids = new BoundedCache[String, Int](settings.cacheCapacity)
  private var closed = false

  override def serialize(topic: String, data: A): Array[Byte] = synchronized {
    RegistrySupport.guard("serialization") {
      ensureOpen()
      if data == null then null
      else
        val subject = settings.subjectNameStrategy.subject(topic, schema.rawSchema(), role)
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
      if data != null && headers != null && headers.lastHeader(role.schemaIdHeader) != null then
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
  /** A ready-to-use key serializer borrowing an application-owned client. */
  def forKey[A](
      codec: AvroCodec[A],
      client: SchemaRegistryClient,
      settings: SerializerSettings = SerializerSettings()
  ): RegistrySerializer[A] =
    new RegistrySerializer(codec, client, settings, RegistryRole.Key, ownsClient = false)

  /** A ready-to-use value serializer borrowing an application-owned client. */
  def forValue[A](
      codec: AvroCodec[A],
      client: SchemaRegistryClient,
      settings: SerializerSettings = SerializerSettings()
  ): RegistrySerializer[A] =
    new RegistrySerializer(codec, client, settings, RegistryRole.Value, ownsClient = false)

  /** A key serializer that creates and owns its registry client. */
  def forKey[A](codec: AvroCodec[A], connection: RegistryConnection): RegistrySerializer[A] =
    forKey(codec, connection, SerializerSettings())

  def forKey[A](
      codec: AvroCodec[A], connection: RegistryConnection, settings: SerializerSettings
  ): RegistrySerializer[A] =
    RegistrySupport.withOwnedClient(connection) { client =>
      new RegistrySerializer(codec, client, settings, RegistryRole.Key, ownsClient = true)
    }

  /** A value serializer that creates and owns its registry client. */
  def forValue[A](codec: AvroCodec[A], connection: RegistryConnection): RegistrySerializer[A] =
    forValue(codec, connection, SerializerSettings())

  def forValue[A](
      codec: AvroCodec[A], connection: RegistryConnection, settings: SerializerSettings
  ): RegistrySerializer[A] =
    RegistrySupport.withOwnedClient(connection) { client =>
      new RegistrySerializer(codec, client, settings, RegistryRole.Value, ownsClient = true)
    }
