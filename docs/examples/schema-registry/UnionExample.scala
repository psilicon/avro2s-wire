package example.registry

import avro2s.wire.registry.{
  DeserializerSettings, RegistryConnection, RegistryDeserializer, RegistrySerializer,
  SchemaIdPlacement, SchemaLookup, SerializerSettings, SubjectNameStrategy
}
import avro2s.wire.runtime.{DecodeLimits, WriterSettings}
import example.trading.{Event, OrderCancelled, OrderPlaced}
import org.apache.kafka.common.header.internals.RecordHeaders
import scala.util.Using

/** Requires Schema Registry and the complete Event.avsc union registered as
  * events-value. This round trip does not require a Kafka broker or send records.
  * See docs/schema-registry-unions.md for generation and run commands.
  */
object UnionExample:
  def main(args: Array[String]): Unit =
    val registryUrl = args.headOption.getOrElse(
      sys.env.getOrElse("AVRO2S_WIRE_REGISTRY_URL", "http://localhost:18081"))
    val topic = "events"
    val connection = RegistryConnection(
      urls = List(registryUrl),
      cacheCapacity = 256,
      properties = Map[String, AnyRef](
        "http.connect.timeout.ms" -> "5000",
        "http.read.timeout.ms" -> "10000"
      )
    )
    val writerSettings = SerializerSettings(
      autoRegisterSchemas = false,
      normalizeSchemas = false,
      subjectNameStrategy = SubjectNameStrategy.TopicName,
      cacheCapacity = 256,
      writerSettings = WriterSettings.default,
      schemaIdPlacement = SchemaIdPlacement.HeaderGuid
    )
    val readerSettings = DeserializerSettings(
      cacheCapacity = 256,
      decodeLimits = DecodeLimits.default,
      schemaLookup = SchemaLookup.ById
    )
    val events: Vector[Event] = Vector(
      OrderPlaced(101L, "ABC", 3),
      OrderCancelled(101L, "No longer needed")
    )

    Using.Manager { use =>
      // Each adapter creates and closes its own client from this connection.
      // Event.codec describes the entire union, including its branch indexes.
      val writer = use(RegistrySerializer.forValue(Event.codec, connection, writerSettings))
      val reader = use(RegistryDeserializer.forValue(Event.codec, connection, readerSettings))
      events.foreach { event =>
        val headers = new RecordHeaders()
        val payload = writer.serialize(topic, headers, event)
        val decoded = reader.deserialize(topic, headers, payload)
        require(decoded == event, s"Unexpected decoded event: $decoded")
        val schemaHeader = headers.lastHeader("__value_schema_id")
        println(s"$decoded (${payload.length} payload bytes, ${schemaHeader.value().length} schema-header bytes)")
      }
    }.get
