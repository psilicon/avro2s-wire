package avro2s.wire.registry

import avro2s.wire.runtime.{AvroCodec, BinaryOutput, Bytes}
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Serializer

/** Classic Confluent framing around the bytes produced by a generated codec.
  * The role and settings are fixed at construction; Kafka configure is unnecessary.
  * Calls may run concurrently; close drains admitted calls before releasing an
  * owned client. An injected client remains caller-owned.
  */
final class RegistrySerializer[A] private[registry] (
    codec: AvroCodec[A],
    client: SchemaRegistryClient,
    settings: SerializerSettings,
    role: RegistryRole,
    private val ownsClient: Boolean
) extends Serializer[A] with RegistryAdapter:
  require(client != null, "client must be non-null")
  require(settings != null, "settings must be non-null")
  private val schema = RegistrySupport.namedSchema(codec)
  RegistrySupport.requireSubjectStrategy(schema, settings.subjectNameStrategy)
  private val prefixes = new BoundedCache[String, Bytes](settings.cacheCapacity)
  private val lifecycle = new RegistryLifecycle("Serializer")

  private[registry] def registryClient: SchemaRegistryClient = client
  private[registry] def registryLifecycle: RegistryLifecycle = lifecycle
  private[registry] def clearRegistryCache(): Unit = prefixes.clear()

  override def serialize(topic: String, data: A): Array[Byte] = serialize(topic, null, data)

  override def serialize(topic: String, headers: Headers, data: A): Array[Byte] =
    RegistrySupport.guard("serialization") {
      lifecycle.use {
        if data == null then null
        else
          if headers != null && headers.lastHeader(role.schemaIdHeader) != null then
            throw new IllegalArgumentException("Schema ID header framing is unsupported")
          val subject = settings.subjectNameStrategy.subject(topic, schema.rawSchema(), role)
          val prefix = prefixes.getOrLoad(subject) {
            val id = RegistrySupport.schemaId(client, subject, schema, settings)
            Bytes.unsafeWrap(Array[Byte](0, (id >>> 24).toByte, (id >>> 16).toByte,
              (id >>> 8).toByte, id.toByte))
          }
          // Keep mutable storage local. Prefix and datum share the working buffer;
          // only the final, independently owned frame is copied out.
          val out = new BinaryOutput(settings = settings.writerSettings)
          out.writeFixed(prefix)
          codec.write(data, out)
          out.toByteArray
      }
    }

  override def close(): Unit = RegistrySupport.guard("close") {
    lifecycle.close {
      prefixes.clear()
      if ownsClient then client.close()
    }
  }

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
