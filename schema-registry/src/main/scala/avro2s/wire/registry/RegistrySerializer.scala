package avro2s.wire.registry

import avro2s.wire.runtime.{AvroCodec, BinaryOutput, Bytes}
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import java.util.Arrays
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Serializer

/** Confluent schema identity framing around bytes produced by a generated codec.
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
  private val identities = new BoundedCache[String, Bytes](settings.cacheCapacity)
  private val lifecycle = new RegistryLifecycle("Serializer")

  private[registry] def registryClient: SchemaRegistryClient = client
  private[registry] def registryLifecycle: RegistryLifecycle = lifecycle
  private[registry] def clearRegistryCache(): Unit = identities.clear()

  override def serialize(topic: String, data: A): Array[Byte] = serialize(topic, null, data)

  override def serialize(topic: String, headers: Headers, data: A): Array[Byte] =
    RegistrySupport.guard("serialization") {
      lifecycle.use {
        if data == null then null
        else
          settings.schemaIdPlacement match
            case SchemaIdPlacement.PayloadPrefix =>
              if headers != null && headers.lastHeader(role.schemaIdHeader) != null then
                throw new IllegalArgumentException("Schema ID headers conflict with payload-prefix framing")
            case SchemaIdPlacement.HeaderGuid =>
              require(headers != null, "GUID header framing requires Kafka Headers")
          val subject = settings.subjectNameStrategy.subject(topic, schema.rawSchema(), role)
          val identity = identities.getOrLoad(subject) {
            RegistrySupport.schemaIdentity(client, subject, schema, settings)
          }
          // Prefix and datum share one local buffer in classic mode. In header
          // mode, finish encoding before changing the caller's headers.
          val out = new BinaryOutput(settings = settings.writerSettings)
          if settings.schemaIdPlacement == SchemaIdPlacement.PayloadPrefix then out.writeFixed(identity)
          codec.write(data, out)
          val payload = out.toByteArray
          if settings.schemaIdPlacement == SchemaIdPlacement.HeaderGuid then
            val existing = headers.lastHeader(role.schemaIdHeader)
            if existing == null || !Arrays.equals(existing.value(), identity.unsafeArray) then
              // Kafka exposes header arrays to callers. Never lend the cached
              // identity's storage to a header or to another serialization call.
              headers.add(role.schemaIdHeader, identity.toArray)
          payload
      }
    }

  override def close(): Unit = RegistrySupport.guard("close") {
    lifecycle.close {
      identities.clear()
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
