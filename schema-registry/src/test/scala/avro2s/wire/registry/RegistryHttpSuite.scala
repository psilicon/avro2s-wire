package avro2s.wire.registry

import avro2s.wire.fixtures.Trade
import com.sun.net.httpserver.HttpServer
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.rest.entities.{Metadata, Rule, RuleKind, RuleMode, RuleSet, Schema}
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import org.apache.kafka.common.errors.SerializationException
import scala.jdk.CollectionConverters.*

/** Exercise the real Confluent HTTP client without requiring a registry or broker. */
final class RegistryHttpSuite extends munit.FunSuite:
  private val trade = Trade(1L, "ABC", 2.0, Vector(3))
  private val metadata = new Metadata(java.util.Map.of(),
    java.util.Map.of("owner", "trading", "confluent:version", "1"), java.util.Set.of())
  private val emptyRules = new RuleSet(java.util.List.of(), java.util.List.of(), java.util.List.of())
  private case class Request(path: String, query: String, tenant: String, trace: String)

  private def response(ruleSet: RuleSet = emptyRules): String =
    val avro = new AvroSchema(Trade.schemaJson).copy(metadata, ruleSet)
    new RegisterSchemaResponse(new Schema("trades-value", 1, 0, avro)).toJson

  private def withServer[A](body: String)(run: (String, ConcurrentLinkedQueue[Request]) => A): A =
    val requests = new ConcurrentLinkedQueue[Request]()
    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(executor)
    server.createContext("/", exchange =>
      try
        exchange.getRequestBody.readAllBytes()
        requests.add(Request(exchange.getRequestURI.getPath, exchange.getRequestURI.getQuery,
          exchange.getRequestHeaders.getFirst("X-Tenant"), exchange.getRequestHeaders.getFirst("X-Trace")))
        val bytes = body.getBytes(UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/vnd.schemaregistry.v1+json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
      finally exchange.close()
    )
    server.start()
    try run(s"http://127.0.0.1:${server.getAddress.getPort}", requests)
    finally
      server.stop(0)
      executor.shutdownNow()

  private def connection(url: String): RegistryConnection = RegistryConnection(List(url), properties = Map(
    "request.header.X-Tenant" -> "trading",
    "schema.registry.request.header.X-Trace" -> "audit",
    "http.connect.timeout.ms" -> "2000",
    "http.read.timeout.ms" -> "2000",
    "max.retries" -> "0"
  ))

  test("owned client sends configured headers and inspects metadata in both schema selection modes") {
    for registering <- Seq(false, true) do
      withServer(response()) { (url, requests) =>
        val writer = RegistrySerializer.forValue(Trade.codec, connection(url),
          SerializerSettings(autoRegisterSchemas = registering, normalizeSchemas = true))
        try
          val bytes = writer.serialize("trades", trade)
          assertEquals(bytes.take(5).toVector, Vector.fill(5)(0.toByte))
          assertEquals(writer.serialize("trades", trade).toVector, bytes.toVector)
          val actual = requests.asScala.toVector
          assertEquals(actual.size, 1)
          assertEquals(actual.head.path, if registering then "/subjects/trades-value/versions" else "/subjects/trades-value")
          assert(actual.head.query.contains("normalize=true"))
          assertEquals(actual.head.tenant, "trading")
          assertEquals(actual.head.trace, "audit")
        finally writer.close()
      }
  }

  test("bare registration success needs no follow-up schema request") {
    withServer("""{"id":0}""") { (url, requests) =>
      val writer = RegistrySerializer.forValue(Trade.codec, connection(url),
        SerializerSettings(autoRegisterSchemas = true))
      try
        writer.serialize("trades", trade)
        assertEquals(requests.size(), 1)
      finally writer.close()
    }
  }

  test("server-added migration, domain, and encoding rules reject both writing modes") {
    for
      registering <- Seq(false, true)
      phase <- Seq("migration", "domain", "encoding")
    do
      val rule = new Rule("server-added", null, RuleKind.CONDITION,
        if phase == "migration" then RuleMode.UPGRADE else RuleMode.WRITE,
        "CEL", null, null, "true", null, null, false)
      val one = java.util.List.of(rule)
      val none = java.util.List.of[Rule]()
      val ruleSet = new RuleSet(if phase == "migration" then one else none,
        if phase == "domain" then one else none,
        if phase == "encoding" then one else none)
      withServer(response(ruleSet)) { (url, requests) =>
        val writer = RegistrySerializer.forValue(Trade.codec, connection(url),
          SerializerSettings(autoRegisterSchemas = registering))
        try
          val error = intercept[SerializationException](writer.serialize("trades", trade))
          assert(error.getCause.getMessage.contains("rules are unsupported"))
          assertEquals(requests.size(), 1)
        finally writer.close()
      }
  }

  test("fresh HTTP readers accept imported zero IDs with inert metadata and empty rules") {
    withServer(response()) { (url, requests) =>
      val reader = RegistryDeserializer.forValue(Trade.codec, connection(url))
      try
        val framed = Array.fill[Byte](5)(0) ++ Trade.codec.encode(trade)
        assertEquals(reader.deserialize("trades", framed), trade)
        assertEquals(reader.deserialize("trades", framed), trade)
        val actual = requests.asScala.toVector
        assertEquals(actual.size, 1)
        assertEquals(actual.head.path, "/schemas/ids/0")
        assertEquals(actual.head.tenant, "trading")
      finally reader.close()
    }
  }
