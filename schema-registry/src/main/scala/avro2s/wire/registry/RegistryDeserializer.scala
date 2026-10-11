package avro2s.wire.registry

import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.{AvroCodec, BinaryInput}
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import java.nio.ByteBuffer
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
  private val lifecycle = new RegistryLifecycle("Deserializer")

  override def deserialize(topic: String, data: Array[Byte]): A = deserialize(topic, null, data)

  override def deserialize(topic: String, headers: Headers, data: Array[Byte]): A =
    RegistrySupport.guard("deserialization") {
      lifecycle.use {
        if data == null then null.asInstanceOf[A]
        else
          checkHeaders(headers)
          val length = payloadLength(data.length)
          checkMagic(data(0))
          val id = ((data(1) & 0xff) << 24) | ((data(2) & 0xff) << 16) |
            ((data(3) & 0xff) << 8) | (data(4) & 0xff)
          val reader = readerFor(id)
          read(reader, BinaryInput.slice(data, 5, length, settings.decodeLimits))
      }
    }

  /** Kafka normally supplies a buffer. Validate before copying and preserve its cursor. */
  override def deserialize(topic: String, headers: Headers, data: ByteBuffer): A =
    RegistrySupport.guard("deserialization") {
      lifecycle.use {
        if data == null then null.asInstanceOf[A]
        else
          checkHeaders(headers)
          val length = payloadLength(data.remaining())
          val start = data.position()
          checkMagic(data.get(start))
          // The frame is always big-endian, regardless of the buffer's byte order.
          val id = ((data.get(start + 1) & 0xff) << 24) | ((data.get(start + 2) & 0xff) << 16) |
            ((data.get(start + 3) & 0xff) << 8) | (data.get(start + 4) & 0xff)
          val reader = readerFor(id)
          val in =
            if data.hasArray then
              BinaryInput.slice(data.array(), data.arrayOffset() + start + 5, length, settings.decodeLimits)
            else
              val payload = new Array[Byte](length)
              val view = data.duplicate()
              view.position(start + 5)
              view.get(payload)
              new BinaryInput(payload, settings.decodeLimits)
          read(reader, in)
      }
    }

  private def checkHeaders(headers: Headers): Unit =
    if headers != null && headers.lastHeader(role.schemaIdHeader) != null then
      throw new IllegalArgumentException("Schema ID header framing is unsupported")

  private def payloadLength(frameLength: Int): Int =
    if frameLength < 5 then throw new IllegalArgumentException("Confluent frame is shorter than five bytes")
    val length = frameLength - 5
    if settings.decodeLimits.maxInputBytes.exists(length > _) then
      throw new IllegalArgumentException("Avro datum exceeds maxInputBytes")
    length

  private def checkMagic(magic: Byte): Unit =
    if magic != 0 then throw new IllegalArgumentException("Unsupported Confluent magic byte")

  private def readerFor(id: Int): ResolvingReader[A] =
    if id < 0 then throw new IllegalArgumentException("Invalid Confluent schema ID")
    readers.getOrLoad(id) {
      val writer = RegistrySupport.writerSchema(client.getSchemaById(id))
      new ResolvingReader(writer, codec)
    }

  private def read(reader: ResolvingReader[A], in: BinaryInput): A =
    val value = reader.read(in)
    in.requireEnd()
    value

  override def close(): Unit = RegistrySupport.guard("close") {
    lifecycle.close {
      readers.clear()
      if ownsClient then client.close()
    }
  }

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
