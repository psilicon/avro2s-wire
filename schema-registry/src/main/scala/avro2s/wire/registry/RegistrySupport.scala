package avro2s.wire.registry

import avro2s.wire.runtime.{AvroCodec, Bytes}
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.UUID
import org.apache.avro.Schema
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.{AuthenticationException, AuthorizationException, DisconnectException,
  SerializationException, ThrottlingQuotaExceededException, TimeoutException}
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

  def requireSubjectStrategy(schema: AvroSchema, strategy: SubjectNameStrategy): Unit =
    if strategy != SubjectNameStrategy.TopicName then
      require(schema.rawSchema().getType == Schema.Type.RECORD,
        s"$strategy requires an Avro record root; use TopicName for enum or fixed roots")

  private def requireNoRules(rules: RuleSet): Unit =
    if rules != null && (!rules.getMigrationRules.isEmpty ||
        !rules.getDomainRules.isEmpty || !rules.getEncodingRules.isEmpty) then
      throw new IllegalArgumentException("Registry schema rules are unsupported")

  def writerSchema(schema: Any): String = schema match
    case avro: AvroSchema =>
      requireNoRules(avro.ruleSet())
      val raw = avro.rawSchema()
      requireNamed(raw)
      // rawSchema includes resolved references; toString retains defaults and aliases.
      // Registry metadata describes the schema; it does not alter Avro decoding.
      raw.toString
    case _ => throw new IllegalArgumentException("Registry schema is not Avro")

  /** Inspect the registry response before remembering its identity or writing a datum.
    * The registry may add rules during registration, even when the submitted
    * codec schema has none. Confluent returns those rules in the response.
    * Custom clients must implement the response APIs and include attached rules;
    * an ID-only response is treated as an unchanged, rule-free schema. Header
    * framing additionally requires the registry-issued GUID in that response.
    */
  def schemaIdentity(
      client: SchemaRegistryClient,
      subject: String,
      schema: AvroSchema,
      settings: SerializerSettings
  ): Bytes =
    val response =
      if settings.autoRegisterSchemas then
        client.registerWithResponse(subject, schema, settings.normalizeSchemas, false)
      else client.getIdWithResponse(subject, schema, settings.normalizeSchemas)
    require(response != null, "Registry returned no schema response")
    require(response.getSchemaType == null || response.getSchemaType == AvroSchema.TYPE,
      "Registry schema is not Avro")
    requireNoRules(response.getRuleSet)
    val bytes = settings.schemaIdPlacement match
      case SchemaIdPlacement.PayloadPrefix =>
        require(response.getId >= 0, "Registry returned an invalid schema ID")
        ByteBuffer.allocate(5).put(0.toByte).putInt(response.getId).array()
      case SchemaIdPlacement.HeaderGuid =>
        val value = response.getGuid
        require(value != null && value.length == 36, "Registry returned no valid schema GUID")
        val guid = UUID.fromString(value)
        // UUID.fromString also accepts shortened groups; require the registry's
        // canonical 8-4-4-4-12 form instead of silently padding them with zeroes.
        require(guid.toString.equalsIgnoreCase(value), "Registry returned an invalid schema GUID")
        ByteBuffer.allocate(17).put(1.toByte).putLong(guid.getMostSignificantBits)
          .putLong(guid.getLeastSignificantBits).array()
    Bytes.unsafeWrap(bytes)

  private val clientNamespace = "schema.registry."
  private val headerPrefix = "request.header."
  private val unsupportedSerdeProperties = Set(
    "use.latest.version", "use.latest.with.metadata", "use.schema.id", "use.schema.guid",
    "id.compatibility.strict", "latest.compatibility.strict", "key.subject.name.strategy",
    "value.subject.name.strategy", "context.name.strategy", "schema.reflection",
    "avro.reflection.allow.null", "avro.use.logical.type.converters", "avro.remove.java.properties",
    "specific.avro.reader", "specific.avro.key.type", "specific.avro.value.type",
    "avro.fail.on.trailing.data", "propagate.schema.tags", "schema.format", "execution.environment",
    "latest.cache.size", "key.schema.id", "value.schema.id",
    "key.schema.id.serializer", "key.schema.id.deserializer",
    "value.schema.id.serializer", "value.schema.id.deserializer"
  )

  private def propertyName(key: String): String = key.stripPrefix(clientNamespace)

  def clientProperties(properties: Map[String, AnyRef]): Map[String, AnyRef] =
    require(properties.keys.forall(_ != null), "Registry client property names must be non-null")
    properties.filter { (key, value) =>
      val name = propertyName(key)
      if key == "schema.registry.url" || name == "schema.registry.url" then
        throw new IllegalArgumentException("Set Schema Registry URLs through RegistryConnection.urls")
      if name == "auto.register.schemas" || name == "normalize.schemas" then
        throw new IllegalArgumentException(s"Set $key through SerializerSettings, not RegistryConnection.properties")
      if name == "max.schemas.per.subject" then
        throw new IllegalArgumentException(s"Set $key through RegistryConnection.cacheCapacity")
      val unsupported = unsupportedSerdeProperties(name) || name.startsWith("rule.") ||
        name.startsWith("rules.") || name.startsWith("validation.rules.") ||
        name.startsWith("subject.name.strategy.")
      if unsupported then
        val harmless = name match
          case "use.latest.version" => value != null && value.toString.equalsIgnoreCase("false")
          case "use.schema.id" => value != null && value.toString == "-1"
          case "use.schema.guid" => value == null
          case _ => false
        if !harmless then throw new IllegalArgumentException(s"Unsupported registry configuration: $key")
        false
      else
        require(value != null, s"Registry client property $key must be non-null")
        !name.startsWith(headerPrefix)
    }

  def clientHeaders(properties: Map[String, AnyRef]): Map[String, String] =
    val headers = properties.iterator.collect {
      case (key, value) if propertyName(key).startsWith(headerPrefix) =>
        val name = propertyName(key).stripPrefix(headerPrefix)
        require(name.nonEmpty, "Registry HTTP header names must be nonempty")
        name -> value.toString
    }.toVector
    require(headers.map(_._1.toLowerCase(java.util.Locale.ROOT)).distinct.size == headers.size,
      "Registry HTTP header names must be unique, including prefixed aliases")
    headers.toMap

  def withOwnedClient[A](connection: RegistryConnection)(create: SchemaRegistryClient => A): A =
    require(connection != null, "connection must be non-null")
    val client = new CachedSchemaRegistryClient(
      connection.urls.asJava, connection.cacheCapacity, connection.clientProperties.asJava,
      connection.clientHeaders.asJava)
    try create(client)
    catch
      case NonFatal(error) =>
        try client.close()
        catch case NonFatal(closeError) => error.addSuppressed(closeError)
        throw error

  def guard[A](operation: String)(body: => A): A =
    try body
    catch
      case e: KafkaException => throw e
      case e: RestClientException => throw registryException(operation, e)
      // SocketTimeoutException also extends InterruptedIOException. Do not turn
      // an I/O timeout into a thread interruption or change an existing flag.
      case e: InterruptedIOException =>
        throw new TimeoutException(s"Avro registry $operation timed out", e)
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw new SerializationException(s"Avro registry $operation interrupted", e)
      case NonFatal(e) => throw new SerializationException(s"Avro registry $operation failed", e)

  /** Keep retryable registry failures distinct from malformed Avro data, using
    * the same HTTP status categories as Confluent's serializer/deserializer.
    */
  private def registryException(operation: String, error: RestClientException): KafkaException =
    val message = s"Avro registry $operation failed"
    error.getStatus match
      case 401 => new AuthenticationException(message, error)
      case 403 => new AuthorizationException(message, error)
      case 429 => new ThrottlingQuotaExceededException(error.getMessage)
      case 408 | 500 | 503 | 504 => new TimeoutException(message, error)
      case 502 => new DisconnectException(message, error)
      case _ => new SerializationException(message, error)
