package avro2s.wire.registry

import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumWriter}
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders

import java.io.ByteArrayOutputStream
import java.net.{HttpURLConnection, URI, URLEncoder}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Cross-product regressions for references, contexts, cached clients and GUIDs.
  * Registration deliberately uses raw HTTP, so these tests do not accidentally
  * rely on parsed-schema caches populated while registering a schema.
  */
final class RegistryReferenceIntegrationSuite extends munit.FunSuite:
  private var registryUrl: String = _
  private var admin: SchemaRegistryClient = _
  private val json = new ObjectMapper()

  override def beforeAll(): Unit =
    super.beforeAll()
    registryUrl = sys.env.get("AVRO2S_WIRE_REGISTRY_URL").map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalStateException(
        "RegistryReferenceIntegrationSuite requires AVRO2S_WIRE_REGISTRY_URL; start an isolated Schema Registry and set the URL to opt in"
      )
    }
    admin = new CachedSchemaRegistryClient(registryUrl, 64)

  override def afterAll(): Unit =
    try
      if admin != null then admin.close()
    finally super.afterAll()

  private case class Child(x: Int, y: Int)
  private case class Root(child: Child)
  private case class Middle(child: Child)
  private case class DeepRoot(middle: Middle)

  private val childXY = """{"type":"record","name":"Child","fields":[{"name":"x","type":"int"},{"name":"y","type":"int"}]}"""
  private val childYX = """{"type":"record","name":"Child","fields":[{"name":"y","type":"int"},{"name":"x","type":"int"}]}"""
  private val rootJson = """{"type":"record","name":"Root","fields":[{"name":"child","type":"Child"}]}"""
  private val middleJson = """{"type":"record","name":"Middle","fields":[{"name":"child","type":"Child"}]}"""
  private val deepRootJson = """{"type":"record","name":"DeepRoot","fields":[{"name":"middle","type":"Middle"}]}"""
  private val expected = Root(Child(10, 20))

  private val childCodec: AvroCodec[Child] = new AvroCodec[Child]:
    def schemaJson: String = childXY
    def read(in: AvroInput): Child = Child(in.readInt(), in.readInt())
    def write(value: Child, out: AvroOutput): Unit =
      out.writeInt(value.x)
      out.writeInt(value.y)
    override def construct(values: Array[Any]): Child = Child(values(0).asInstanceOf[Int], values(1).asInstanceOf[Int])

  private val rootCodec: AvroCodec[Root] = new AvroCodec[Root]:
    def schemaJson: String = rootJson.replace("\"Child\"", childXY)
    def read(in: AvroInput): Root = Root(childCodec.read(in))
    def write(value: Root, out: AvroOutput): Unit = childCodec.write(value.child, out)
    override def construct(values: Array[Any]): Root = Root(values(0).asInstanceOf[Child])
    override def namedCodec(name: String): AvroCodec[?] = name match
      case "Child" => childCodec
      case _ => super.namedCodec(name)

  private val middleCodec: AvroCodec[Middle] = new AvroCodec[Middle]:
    def schemaJson: String = middleJson.replace("\"Child\"", childXY)
    def read(in: AvroInput): Middle = Middle(childCodec.read(in))
    def write(value: Middle, out: AvroOutput): Unit = childCodec.write(value.child, out)
    override def construct(values: Array[Any]): Middle = Middle(values(0).asInstanceOf[Child])

  private val deepRootCodec: AvroCodec[DeepRoot] = new AvroCodec[DeepRoot]:
    def schemaJson: String = deepRootJson.replace("\"Middle\"", middleCodec.schemaJson)
    def read(in: AvroInput): DeepRoot = DeepRoot(middleCodec.read(in))
    def write(value: DeepRoot, out: AvroOutput): Unit = middleCodec.write(value.middle, out)
    override def construct(values: Array[Any]): DeepRoot = DeepRoot(values(0).asInstanceOf[Middle])
    override def namedCodec(name: String): AvroCodec[?] = name match
      case "Middle" => middleCodec
      case "Child" => childCodec
      case _ => super.namedCodec(name)

  private case class Reference(name: String, subject: String)
  private case class Registered(subject: String, id: Int, guid: UUID, datum: Array[Byte]):
    def frame: Array[Byte] = ByteBuffer.allocate(5 + datum.length).put(0.toByte).putInt(id).put(datum).array()
    def headers: RecordHeaders =
      val value = ByteBuffer.allocate(17).put(1.toByte).putLong(guid.getMostSignificantBits)
        .putLong(guid.getLeastSignificantBits).array()
      val result = new RecordHeaders()
      result.add("__value_schema_id", value)
      result

  private case class Contexts(names: Vector[String]):
    val topic = "reference-events"
    def subject(index: Int, name: String): String = s":.${names(index)}:$name"
    def rootSubject(index: Int): String = subject(index, s"$topic-value")

  private def withContexts[A](body: (Contexts, ArrayBuffer[String]) => A): A =
    val token = UUID.randomUUID().toString.replace('-', '_')
    val contexts = Contexts(Vector(s"wire_refs_a_$token", s"wire_refs_b_$token"))
    val subjects = ArrayBuffer.empty[String]
    try body(contexts, subjects)
    finally
      // Parents must be removed before their referenced children.
      subjects.reverseIterator.foreach { subject =>
        try admin.deleteSubject(subject)
        catch case error: RestClientException if error.getStatus == 404 => ()
      }

  private def escaped(value: String): String = URLEncoder.encode(value, UTF_8.name())

  private def request(path: String, body: Option[JsonNode] = None): JsonNode =
    val connection = URI.create(registryUrl.stripSuffix("/") + path).toURL.openConnection()
      .asInstanceOf[HttpURLConnection]
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(10000)
    try
      body.foreach { value =>
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "application/vnd.schemaregistry.v1+json")
        connection.setDoOutput(true)
        Using.resource(connection.getOutputStream)(_.write(value.toString.getBytes(UTF_8)))
      }
      val status = connection.getResponseCode
      val stream = if status == 200 then connection.getInputStream else connection.getErrorStream
      val response = if stream == null then "" else Using.resource(stream)(in => new String(in.readAllBytes(), UTF_8))
      assertEquals(status, 200, s"Registry request $path failed: $response")
      json.readTree(response)
    finally connection.disconnect()

  private def register(
      subjects: ArrayBuffer[String],
      subject: String,
      schema: String,
      references: Vector[Reference] = Vector.empty
  ): (Int, UUID) =
    val body = json.createObjectNode()
    body.put("schema", schema)
    val refs = body.putArray("references")
    references.foreach { reference =>
      refs.addObject().put("name", reference.name).put("subject", reference.subject).put("version", 1)
    }
    val result = request(s"/subjects/${escaped(subject)}/versions", Some(body))
    subjects += subject
    val id = result.get("id").intValue()
    val registered = request(s"/schemas/ids/$id?subject=${escaped(subject)}")
    assertEquals(registered.path("references").elements().asScala.map(_.get("subject").asText()).toVector,
      references.map(_.subject))
    (id, UUID.fromString(registered.get("guid").asText()))

  private def datum(schemaJson: String): Array[Byte] =
    def record(schema: Schema): GenericData.Record =
      val value = new GenericData.Record(schema)
      schema.getFields.asScala.foreach { field =>
        val item: AnyRef = field.schema().getType match
          case Schema.Type.RECORD => record(field.schema())
          case Schema.Type.INT => Int.box(if field.name() == "x" then 10 else 20)
          case unexpected => fail(s"Unexpected test schema field: $unexpected")
        value.put(field.name(), item)
      }
      value
    val schema = new Schema.Parser().parse(schemaJson)
    val output = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(output, null)
    new GenericDatumWriter[GenericData.Record](schema).write(record(schema), encoder)
    encoder.flush()
    output.toByteArray

  private def registerRoots(contexts: Contexts, subjects: ArrayBuffer[String], qualified: Boolean): Vector[Registered] =
    Vector(childXY, childYX).zipWithIndex.map { (child, index) =>
      val childSubject = contexts.subject(index, "child")
      register(subjects, childSubject, child)
      val reference = if qualified then childSubject else "child"
      val subject = contexts.rootSubject(index)
      val (id, guid) = register(subjects, subject, rootJson, Vector(Reference("Child", reference)))
      Registered(subject, id, guid, datum(rootJson.replace("\"Child\"", child)))
    }

  private def lookup(contexts: Contexts, index: Int, policy: String): SchemaLookup = policy match
    case "subject" => SchemaLookup.Subject(contexts.rootSubject(index))
    case "context" => SchemaLookup.Context(contexts.names(index))
    case "topic" => SchemaLookup.TopicName(Some(contexts.names(index)))
    case unexpected => fail(s"Unknown test lookup policy: $unexpected")

  test("numeric reference resolution is independent of context lookup order and upstream parsed caches") {
    withContexts { (contexts, subjects) =>
      val roots = registerRoots(contexts, subjects, qualified = false)
      assertEquals(roots.map(_.id).distinct.size, 1)
      for
        policy <- Vector("subject", "context", "topic")
        order <- Vector(Vector(0, 1), Vector(1, 0))
      do
        Using.Manager { use =>
          val client = use(new CachedSchemaRegistryClient(registryUrl, 64))
          // Confluent 8.1.5's parsed-schema cache can return the first context's
          // resolved Child for both calls. Wire must not trust that cached parse.
          order.foreach(index => client.getSchemaBySubjectAndId(roots(index).subject, roots(index).id))
          val readers = contexts.names.indices.map(index => use(RegistryDeserializer.forValue(rootCodec, client,
            DeserializerSettings(schemaLookup = lookup(contexts, index, policy))))).toVector
          def check(indices: Vector[Int]): Unit = indices.foreach { index =>
            assertEquals(readers(index).deserialize(contexts.topic, roots(index).frame), expected,
              s"policy=$policy order=$order context=$index")
          }
          check(order)
          check(order.reverse)
          RegistryCaches.refresh(readers.head, readers.tail*)
          check(order.reverse)
          check(order)
        }.get
    }
  }

  test("GUID roots with relative references are rejected with cold and numeric-warmed clients") {
    withContexts { (contexts, subjects) =>
      val roots = registerRoots(contexts, subjects, qualified = false)
      // Equal GUIDs do not imply equal resolved schemas for relative references.
      assertEquals(roots(0).guid, roots(1).guid)
      for
        warmNumeric <- Vector(false, true)
        index <- roots.indices
      do
        Using.Manager { use =>
          val client = use(new CachedSchemaRegistryClient(registryUrl, 64))
          val reader = use(RegistryDeserializer.forValue(rootCodec, client,
            DeserializerSettings(schemaLookup = SchemaLookup.Subject(roots(index).subject))))
          if warmNumeric then
            assertEquals(reader.deserialize(contexts.topic, roots(index).frame), expected)
            client.getSchemaBySubjectAndId(roots(index).subject, roots(index).id)
          def reject(): Unit =
            val error = intercept[SerializationException] {
              reader.deserialize(contexts.topic, roots(index).headers, roots(index).datum)
            }
            val message = Option(error.getCause).fold(error.getMessage)(_.getMessage).toLowerCase
            assert(message.contains("relative") || message.contains("fully qualified"), message)
          reject()
          RegistryCaches.refresh(reader)
          reject()
        }.get
    }
  }

  test("fully qualified references decode through numeric IDs and GUIDs in both context orders") {
    withContexts { (contexts, subjects) =>
      val roots = registerRoots(contexts, subjects, qualified = true)
      assertNotEquals(roots(0).guid, roots(1).guid)
      for order <- Vector(Vector(0, 1), Vector(1, 0)) do
        Using.Manager { use =>
          val client = use(new CachedSchemaRegistryClient(registryUrl, 64))
          val readers = roots.map(root => use(RegistryDeserializer.forValue(rootCodec, client,
            DeserializerSettings(schemaLookup = SchemaLookup.Subject(root.subject)))))
          def check(indices: Vector[Int]): Unit = indices.foreach { index =>
            val root = roots(index)
            // GUID is deliberately first, before any subject-aware numeric read.
            assertEquals(readers(index).deserialize(contexts.topic, root.headers, root.datum), expected)
            assertEquals(readers(index).deserialize(contexts.topic, root.frame), expected)
          }
          check(order)
          RegistryCaches.refresh(readers.head, readers.tail*)
          check(order.reverse)
        }.get
    }
  }

  test("GUID qualified root references preserve context for nested relative references") {
    withContexts { (contexts, subjects) =>
      val roots = Vector(childXY, childYX).zipWithIndex.map { (child, index) =>
        register(subjects, contexts.subject(index, "child"), child)
        val middleSubject = contexts.subject(index, "middle")
        register(subjects, middleSubject, middleJson, Vector(Reference("Child", "child")))
        val subject = contexts.rootSubject(index)
        val (id, guid) = register(subjects, subject, deepRootJson, Vector(Reference("Middle", middleSubject)))
        val expanded = deepRootJson.replace("\"Middle\"", middleJson.replace("\"Child\"", child))
        Registered(subject, id, guid, datum(expanded))
      }
      assertNotEquals(roots(0).guid, roots(1).guid)
      for order <- Vector(Vector(0, 1), Vector(1, 0)) do
        Using.Manager { use =>
          val client = use(new CachedSchemaRegistryClient(registryUrl, 64))
          // Prewarm the vulnerable parser with both Middle entities, whose raw
          // schema and relative descriptor match despite different child layouts.
          order.foreach { index =>
            val subject = contexts.subject(index, "middle")
            val id = client.getSchemaMetadata(subject, 1).getId
            client.getSchemaBySubjectAndId(subject, id)
          }
          val reader = use(RegistryDeserializer.forValue(deepRootCodec, client))
          def check(indices: Vector[Int]): Unit = indices.foreach { index =>
            val root = roots(index)
            assertEquals(reader.deserialize(contexts.topic, root.headers, root.datum), DeepRoot(Middle(expected.child)))
          }
          check(order)
          RegistryCaches.refresh(reader)
          check(order.reverse)
        }.get
    }
  }
