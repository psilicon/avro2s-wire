package avro2s.wire.registry

import avro2s.wire.fixtures.{Status, Trade}
import avro2s.wire.fixtures.unions.UnionFixed
import avro2s.wire.runtime.AvroCodec
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.rest.entities.{Metadata, Rule, RuleKind, RuleMode, RuleSet}
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse
import org.apache.kafka.common.errors.SerializationException

final class RegistrySemanticsSuite extends munit.FunSuite:
  private val schema = new AvroSchema(Trade.schemaJson)
  private val trade = Trade(1L, "ABC", 2.0, Vector(3))
  private val emptyRules = new RuleSet(java.util.List.of(), java.util.List.of(), java.util.List.of())
  private val metadata = new Metadata(java.util.Map.of(),
    java.util.Map.of("confluent:version", "1"), java.util.Set.of())

  private def rules(phase: String): RuleSet =
    val rule = new Rule("check", null, RuleKind.CONDITION,
      if phase == "migration" then RuleMode.UPGRADE else RuleMode.WRITE,
      "CEL", null, null, "true", null, null, false)
    val one = java.util.List.of(rule)
    val none = java.util.List.of[Rule]()
    new RuleSet(if phase == "migration" then one else none,
      if phase == "domain" then one else none,
      if phase == "encoding" then one else none)

  private def response(id: Int, ruleSet: RuleSet = null): RegisterSchemaResponse =
    val result = new RegisterSchemaResponse(id)
    result.setSchemaType("AVRO")
    result.setMetadata(metadata)
    result.setRuleSet(ruleSet)
    result

  test("metadata and empty rule sets preserve writer schema resolution") {
    for described <- Seq(schema.copy(metadata, null), schema.copy(metadata, emptyRules), schema.copy(null, emptyRules)) do
      assertEquals(RegistrySupport.writerSchema(described), schema.rawSchema().toString)
  }

  test("all nonempty rule phases are rejected for reading") {
    for phase <- Seq("migration", "domain", "encoding") do
      val error = intercept[IllegalArgumentException] {
        RegistrySupport.writerSchema(schema.copy(metadata, rules(phase)))
      }
      assert(error.getMessage.contains("rules are unsupported"))
  }

  test("lookup metadata round trips through a fresh reader client") {
    val writerClient = new MockSchemaRegistryClient()
    val readerClient = new MockSchemaRegistryClient()
    // The lookup can omit confluent:version; use no rule set so its identity also matches.
    val versioned = schema.copy(metadata, null)
    assertEquals(writerClient.register("trades-value", versioned), readerClient.register("trades-value", versioned))
    val writer = RegistrySerializer.forValue(Trade.codec, writerClient)
    val reader = RegistryDeserializer.forValue(Trade.codec, readerClient)
    try assertEquals(reader.deserialize("trades", writer.serialize("trades", trade)), trade)
    finally
      writer.close()
      reader.close()
  }

  test("both schema selection modes inspect every returned rule phase") {
    for
      registering <- Seq(false, true)
      phase <- Seq("migration", "domain", "encoding")
    do
      val client = new MockSchemaRegistryClient:
        override def registerWithResponse(subject: String, parsed: ParsedSchema,
            normalize: Boolean, propagate: Boolean): RegisterSchemaResponse = response(7, rules(phase))
        override def getIdWithResponse(subject: String, parsed: ParsedSchema,
            normalize: Boolean): RegisterSchemaResponse = response(7, rules(phase))
      val writer = RegistrySerializer.forValue(Trade.codec, client,
        SerializerSettings(autoRegisterSchemas = registering))
      try
        val error = intercept[SerializationException](writer.serialize("trades", trade))
        assert(error.getCause.getMessage.contains("rules are unsupported"))
      finally writer.close()
  }

  test("rejected responses are retried and successful responses are cached") {
    var calls = 0
    val client = new MockSchemaRegistryClient:
      override def getIdWithResponse(subject: String, parsed: ParsedSchema,
          normalize: Boolean): RegisterSchemaResponse =
        calls += 1
        assert(normalize)
        response(7, if calls == 1 then rules("domain") else emptyRules)
    val writer = RegistrySerializer.forValue(Trade.codec, client, SerializerSettings(normalizeSchemas = true))
    try
      intercept[SerializationException](writer.serialize("trades", trade))
      val bytes = writer.serialize("trades", trade)
      assertEquals(bytes(4), 7.toByte)
      assertEquals(writer.serialize("trades", trade).toVector, bytes.toVector)
      assertEquals(calls, 2)
    finally writer.close()
  }

  test("normalization and disabled tag propagation reach the response registration API") {
    var calls = 0
    val client = new MockSchemaRegistryClient:
      override def registerWithResponse(subject: String, parsed: ParsedSchema,
          normalize: Boolean, propagate: Boolean): RegisterSchemaResponse =
        calls += 1
        assertEquals(subject, "trades-key")
        assert(normalize)
        assert(!propagate)
        response(3, emptyRules)
    val writer = RegistrySerializer.forKey(Trade.codec, client,
      SerializerSettings(autoRegisterSchemas = true, normalizeSchemas = true))
    try
      writer.serialize("trades", trade)
      writer.serialize("trades", trade)
      assertEquals(calls, 1)
    finally writer.close()
  }

  test("imported schema ID zero is valid for serialization and deserialization") {
    val client = new MockSchemaRegistryClient()
    client.register("trades-value", schema, 1, 0)
    val writer = RegistrySerializer.forValue(Trade.codec, client)
    val reader = RegistryDeserializer.forValue(Trade.codec, client)
    try
      val bytes = writer.serialize("trades", trade)
      assertEquals(bytes.take(5).toVector, Vector.fill(5)(0.toByte))
      assertEquals(reader.deserialize("trades", bytes), trade)
    finally
      writer.close()
      reader.close()
  }

  test("record-name strategies reject enum and fixed roots at construction") {
    val client = new MockSchemaRegistryClient()
    for
      codec <- Seq[AvroCodec[?]](Status.codec, UnionFixed.codec)
      strategy <- Seq(SubjectNameStrategy.RecordName, SubjectNameStrategy.TopicRecordName)
    do
      val error = intercept[IllegalArgumentException] {
        RegistrySerializer.forValue(codec, client, SerializerSettings(subjectNameStrategy = strategy))
      }
      assert(error.getMessage.contains("requires an Avro record root"))
    RegistrySerializer.forValue(Status.codec, client).close()
    RegistrySerializer.forValue(UnionFixed.codec, client).close()
  }

  test("recognized unsupported SerDe options reject unprefixed and client-prefixed spellings") {
    val options = Map(
      "validation.rules.execution" -> "BEFORE_DOMAIN_RULES",
      "validation.rules.executor.class" -> "custom.Validator",
      "validation.rules.fail.fast" -> "true",
      "avro.remove.java.properties" -> "true",
      "specific.avro.reader" -> "true",
      "specific.avro.key.type" -> "example.Key",
      "specific.avro.value.type" -> "example.Value",
      "avro.fail.on.trailing.data" -> "false",
      "propagate.schema.tags" -> "true",
      "schema.format" -> "resolved",
      "execution.environment" -> "SERVER",
      "latest.cache.size" -> "2",
      "max.schemas.per.subject" -> "2",
      "auto.register.schemas" -> "true",
      "normalize.schemas" -> "true",
      "rule.executors" -> "custom",
      "value.schema.id.deserializer" -> "custom"
    )
    for
      (key, value) <- options
      prefix <- Seq("", "schema.registry.")
    do
      intercept[IllegalArgumentException] {
        RegistryConnection(List("http://localhost:18081"), properties = Map((prefix + key) -> value))
      }
  }

  test("client options and custom authentication settings remain available") {
    val properties = Map[String, AnyRef](
      "schema.registry.http.read.timeout.ms" -> Int.box(20),
      "latest.cache.ttl.sec" -> Int.box(2),
      "schema.registry.ssl.endpoint.identification.algorithm" -> "https",
      "bearer.auth.custom.provider.class" -> "example.Provider",
      "example.auth.setting" -> "custom",
      "example.schema.id.setting" -> "custom"
    )
    val connection = RegistryConnection(List("http://localhost:18081"), properties = properties)
    assertEquals(connection.clientProperties, properties)
    assertEquals(connection.clientHeaders, Map.empty[String, String])
  }

  test("inactive selectors are removed for both configuration spellings") {
    for prefix <- Seq("", "schema.registry.") do
      val connection = RegistryConnection(List("http://localhost:18081"), properties = Map(
        (prefix + "use.latest.version") -> "false",
        (prefix + "use.schema.id") -> "-1",
        (prefix + "use.schema.guid") -> null
      ))
      assertEquals(connection.clientProperties, Map.empty[String, AnyRef])
  }

  test("HTTP header configuration is extracted without losing other client settings") {
    val connection = RegistryConnection(List("http://localhost:18081"), properties = Map(
      "request.header.X-Tenant" -> "trading",
      "schema.registry.request.header.X-Trace" -> "audit",
      "http.read.timeout.ms" -> "20"
    ))
    assertEquals(connection.clientHeaders, Map("X-Tenant" -> "trading", "X-Trace" -> "audit"))
    assertEquals(connection.clientProperties, Map[String, AnyRef]("http.read.timeout.ms" -> "20"))
    for properties <- Seq(
      Map[String, AnyRef]("request.header.X-Tenant" -> null),
      Map[String, AnyRef]("request.header." -> "value"),
      Map[String, AnyRef]("request.header.X-Tenant" -> "a", "schema.registry.request.header.x-tenant" -> "b")
    ) do
      intercept[IllegalArgumentException] {
        RegistryConnection(List("http://localhost:18081"), properties = properties)
      }
  }
