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

  def validateConfig(config: JMap[String, ?], settings: RegistrySettings): Unit =
    if config == null then throw new IllegalArgumentException("Kafka configuration must be non-null")
    def bool(key: String, expected: Boolean): Unit =
      if config.containsKey(key) && config.get(key).toString.toBooleanOption != Some(expected) then
        throw new IllegalArgumentException(s"$key conflicts with RegistrySettings")
    bool("auto.register.schemas", settings.autoRegisterSchemas)
    bool("normalize.schemas", settings.normalizeSchemas)
    val unsupported = Seq(
      "use.latest.version", "use.latest.with.metadata", "use.schema.id",
      "id.compatibility.strict", "latest.compatibility.strict", "key.subject.name.strategy",
      "value.subject.name.strategy", "context.name.strategy", "schema.reflection",
      "avro.reflection.allow.null", "avro.use.logical.type.converters"
    )
    for key <- unsupported if config.containsKey(key) do
      val value = config.get(key)
      val harmless = key match
        case "use.latest.version" => value.toString.equalsIgnoreCase("false")
        case "use.schema.id" => value.toString == "-1"
        case _ => false
      if !harmless then throw new IllegalArgumentException(s"Unsupported registry configuration: $key")
    for key <- config.keySet().asScala if key.startsWith("rule.") || key.startsWith("rules.") do
      throw new IllegalArgumentException(s"Unsupported registry configuration: $key")
    for key <- config.keySet().asScala if key.contains(".schema.id.") ||
        key == "key.schema.id" || key == "value.schema.id" do
      throw new IllegalArgumentException(s"Unsupported registry configuration: $key")

  def ownedClient(urls: List[String], capacity: Int, config: Map[String, AnyRef]): SchemaRegistryClient =
    require(urls != null && urls.nonEmpty && urls.forall(url => url != null && url.nonEmpty),
      "At least one Schema Registry URL is required")
    require(capacity > 0, "Registry client capacity must be positive")
    new CachedSchemaRegistryClient(urls.asJava, capacity, config.asJava)

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
