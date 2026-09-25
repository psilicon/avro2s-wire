package avro2s.wire.registry

import avro2s.wire.runtime.AvroCodec
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import java.util.{LinkedHashMap, Map as JMap}
import org.apache.avro.Schema
import org.apache.kafka.common.errors.SerializationException
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[registry] object RegistrySupport:
  def namedSchema[A](codec: AvroCodec[A]): AvroSchema =
    require(codec != null, "codec must be non-null")
    val parsed = new AvroSchema(codec.schemaJson)
    requireNamed(parsed.rawSchema())
    parsed

  def requireNamed(schema: Schema): Unit =
    schema.getType match
      case Schema.Type.RECORD | Schema.Type.ENUM | Schema.Type.FIXED => ()
      case _ => throw new IllegalArgumentException(
        "Registry adapters require a named Avro record, enum, or fixed root"
      )

  def writerSchema(schema: Any): String = schema match
    case avro: AvroSchema =>
      if avro.metadata() != null || avro.ruleSet() != null then
        throw new IllegalArgumentException("Registry schema metadata and rules are unsupported")
      val raw = avro.rawSchema()
      requireNamed(raw)
      // rawSchema includes resolved references; toString retains defaults and aliases.
      raw.toString
    case _ => throw new IllegalArgumentException("Registry schema is not Avro")

  def clientProperties(properties: Map[String, AnyRef]): Map[String, AnyRef] =
    require(properties.keys.forall(_ != null), "Registry client property names must be non-null")
    for key <- Seq("auto.register.schemas", "normalize.schemas") if properties.contains(key) do
      throw new IllegalArgumentException(s"Set $key through SerializerSettings, not RegistryConnection.properties")
    if properties.contains("schema.registry.url") then
      throw new IllegalArgumentException("Set Schema Registry URLs through RegistryConnection.urls")
    val unsupported = Seq(
      "use.latest.version", "use.latest.with.metadata", "use.schema.id", "use.schema.guid",
      "id.compatibility.strict", "latest.compatibility.strict", "key.subject.name.strategy",
      "value.subject.name.strategy", "context.name.strategy", "schema.reflection",
      "avro.reflection.allow.null", "avro.use.logical.type.converters"
    )
    for key <- unsupported if properties.contains(key) do
      val value = properties(key)
      val harmless = key match
        case "use.latest.version" => value != null && value.toString.equalsIgnoreCase("false")
        case "use.schema.id" => value != null && value.toString == "-1"
        case "use.schema.guid" => value == null
        case _ => false
      if !harmless then throw new IllegalArgumentException(s"Unsupported registry configuration: $key")
    for key <- properties.keys if key.startsWith("rule.") || key.startsWith("rules.") do
      throw new IllegalArgumentException(s"Unsupported registry configuration: $key")
    for key <- properties.keys if key.contains(".schema.id.") ||
        key == "key.schema.id" || key == "value.schema.id" do
      throw new IllegalArgumentException(s"Unsupported registry configuration: $key")
    // Inactive SerDe selectors are accepted for migration, but have no client meaning.
    // In particular, forwarding the null GUID default breaks the client's config copying.
    properties -- unsupported

  def withOwnedClient[A](connection: RegistryConnection)(create: SchemaRegistryClient => A): A =
    require(connection != null, "connection must be non-null")
    val client = new CachedSchemaRegistryClient(
      connection.urls.asJava, connection.cacheCapacity, connection.clientProperties.asJava)
    try create(client)
    catch
      case NonFatal(error) =>
        try client.close()
        catch case NonFatal(closeError) => error.addSuppressed(closeError)
        throw error

  def guard[A](operation: String)(body: => A): A =
    try body
    catch
      case e: SerializationException => throw e
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw new SerializationException(s"Avro registry $operation interrupted", e)
      case NonFatal(e) => throw new SerializationException(s"Avro registry $operation failed", e)

/** A small access-order LRU. Callers synchronize all access on their adapter. */
private[registry] final class BoundedCache[K, V](maxEntries: Int):
  private val entries = new LinkedHashMap[K, V](16, 0.75f, true):
    override def removeEldestEntry(eldest: JMap.Entry[K, V]): Boolean = size() > maxEntries

  def getOrLoad(key: K)(load: => V): V =
    val hit = entries.get(key)
    if hit != null then hit
    else
      val value = load
      entries.put(key, value)
      value

  def clear(): Unit = entries.clear()
