package avro2s.wire.registry

import avro2s.wire.fixtures.Trade
import avro2s.wire.resolution.SchemaResolutionException
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroSerializer}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import org.apache.kafka.common.errors.SerializationException
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory

import java.io.ByteArrayOutputStream
import java.util.{HashMap, UUID}
import scala.jdk.CollectionConverters.*

/** These tests intentionally talk to the service configured by AVRO2S_WIRE_REGISTRY_URL. */
final class RegistryIntegrationSuite extends munit.FunSuite:
  private var registryUrl: String = _
  private var client: SchemaRegistryClient = _

  override def beforeAll(): Unit =
    super.beforeAll()
    registryUrl = sys.env.get("AVRO2S_WIRE_REGISTRY_URL").map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalStateException(
        "RegistryIntegrationSuite requires AVRO2S_WIRE_REGISTRY_URL; start an isolated Schema Registry and set the URL to opt in"
      )
    }
    client = new CachedSchemaRegistryClient(registryUrl, 256)

  override def afterAll(): Unit =
    try
      if client != null then client.close()
    finally super.afterAll()

  private def ownedSubject(label: String): String = s"avro2s-wire-$label-${UUID.randomUUID()}"

  private def withSubject[A](label: String)(body: String => A): A =
    val subject = ownedSubject(label)
    try body(subject)
    finally cleanupSubject(subject)

  private def withTopic[A](label: String)(body: String => A): A =
    val topic = ownedSubject(label)
    val subject = s"$topic-value"
    try body(topic)
    finally cleanupSubject(subject)

  private def cleanupSubject(subject: String): Unit =
    try client.deleteCompatibility(subject)
    catch
      case error: RestClientException if error.getStatus == 404 => ()
    try client.deleteSubject(subject)
    catch
      case error: RestClientException if error.getStatus == 404 => ()

  private def schema(json: String): Schema = new Schema.Parser().parse(json)

  private def tradeSchemaWithWriterOnlyField(): Schema =
    val original = schema(Trade.codec.schemaJson)
    val writer = Schema.createRecord(original.getName, original.getDoc, original.getNamespace, original.isError)
    val copied = original.getFields.asScala.map(field => new Schema.Field(field, field.schema())).toList
    val extra = new Schema.Field("venue", Schema.create(Schema.Type.STRING), "writer-only venue", "unknown")
    writer.setFields((copied :+ extra).asJava)
    writer

  private def tradeSchemaWithoutQuantities(): Schema =
    val original = schema(Trade.codec.schemaJson)
    val writer = Schema.createRecord(original.getName, original.getDoc, original.getNamespace, original.isError)
    writer.setFields(original.getFields.asScala.take(3)
      .map(field => new Schema.Field(field, field.schema())).asJava)
    writer

  private def tradeSchemaWithReference(referenceName: String): String =
    val mapper = new ObjectMapper()
    val root = mapper.readTree(Trade.codec.schemaJson).asInstanceOf[ObjectNode]
    val fields = root.get("fields").asInstanceOf[ArrayNode]
    val referenceField = mapper.createObjectNode()
    referenceField.put("name", "writerOnly")
    referenceField.put("type", referenceName)
    fields.add(referenceField)
    root.toString

  private def recordSchema(namespace: String, fields: String): Schema =
    schema(s"""{"type":"record","name":"PolicyRecord","namespace":"$namespace","fields":[$fields]}""")

  private def register(subject: String, value: Schema): Int = client.register(subject, new AvroSchema(value))

  private val intField = """{"name":"value","type":"int"}"""
  private val intDefaultField = """{"name":"value","type":"int","default":0}"""
  private val longField = """{"name":"value","type":"long"}"""
  private val stringDefaultField = """{"name":"value","type":"string","default":""}"""

  private def setCompatibility(subject: String, compatibility: String): Unit =
    client.updateCompatibility(subject, compatibility)

  private def assertRegistrationRejected(subject: String, candidate: Schema): Unit =
    val error = intercept[RestClientException] { register(subject, candidate) }
    assertEquals(error.getStatus, 409)

  test("real registry enforces BACKWARD, FORWARD, FULL, and NONE compatibility") {
    val namespace = s"avro2s.wire.integration.policy.n${UUID.randomUUID().toString.replace('-', '_')}"

    withSubject("backward") { subject =>
      setCompatibility(subject, "BACKWARD")
      register(subject, recordSchema(namespace, intField))
      val promoted = recordSchema(namespace, longField)
      register(subject, promoted)
      assertRegistrationRejected(subject, recordSchema(namespace, stringDefaultField))
    }

    withSubject("forward") { subject =>
      setCompatibility(subject, "FORWARD")
      register(subject, recordSchema(namespace, longField))
      register(subject, recordSchema(namespace, intField))
      assertRegistrationRejected(subject, recordSchema(namespace, stringDefaultField))
    }

    withSubject("full") { subject =>
      setCompatibility(subject, "FULL")
      register(subject, recordSchema(namespace, intField))
      val withDefault = recordSchema(namespace, s"$intField,{\"name\":\"note\",\"type\":\"string\",\"default\":\"\"}")
      register(subject, withDefault)
      assertRegistrationRejected(subject, recordSchema(namespace, longField))
    }

    withSubject("none") { subject =>
      setCompatibility(subject, "NONE")
      register(subject, recordSchema(namespace, intField))
      register(subject, recordSchema(namespace, stringDefaultField))
      assertEquals(client.getAllVersions(subject).size(), 2)
    }
  }

  test("BACKWARD_TRANSITIVE, FORWARD_TRANSITIVE, and FULL_TRANSITIVE reject incompatible older history") {
    val namespace = s"avro2s.wire.integration.transitive.n${UUID.randomUUID().toString.replace('-', '_')}"
    val v1 = recordSchema(namespace, intDefaultField)
    val v2 = recordSchema(namespace, "")
    val v3 = recordSchema(namespace, stringDefaultField)

    for (latestOnly, transitive) <- Seq(
        "BACKWARD" -> "BACKWARD_TRANSITIVE",
        "FORWARD" -> "FORWARD_TRANSITIVE",
        "FULL" -> "FULL_TRANSITIVE"
    ) do
      withSubject(s"${latestOnly.toLowerCase}-transitive") { subject =>
        setCompatibility(subject, latestOnly)
        register(subject, v1)
        register(subject, v2)
        assert(client.testCompatibility(subject, new AvroSchema(v3)), s"$latestOnly should accept v3 against the latest v2")

        setCompatibility(subject, transitive)
        assertRegistrationRejected(subject, v3)
        assertEquals(client.getAllVersions(subject).size(), 2)
      }
  }

  test("Wire serializer registers once and the Wire deserializer resolves the registered writer schema") {
    withTopic("wire-adapter") { subject =>
      // The generated Trade codec is a real fixture codec. Use a per-test topic to isolate its subject.
      val topic = subject
      val settings = SerializerSettings(autoRegisterSchemas = true, subjectNameStrategy = SubjectNameStrategy.TopicName)
      val serializer = RegistrySerializer.forValue(Trade.codec, client, settings)
      val deserializer = RegistryDeserializer.forValue(Trade.codec, client)
      try
        val expected = Trade(101L, "AVRO", 37.5, Vector(1, -2, 9))
        val bytes = serializer.serialize(topic, expected)
        assertEquals(deserializer.deserialize(topic, bytes), expected)
        assert(client.getLatestSchemaMetadata(s"$topic-value").getId > 0)
      finally
        serializer.close()
        deserializer.close()
    }
  }

  test("default serializer settings fail on a missing subject and succeed after real preregistration") {
    withTopic("no-auto-register") { subject =>
      val serializer = RegistrySerializer.forValue(Trade.codec, client)
      val value = Trade(5L, "PRE", 1.25, Vector(8))
      try
        val missing = intercept[SerializationException] { serializer.serialize(subject, value) }
        assert(missing.getCause.isInstanceOf[RestClientException])
        assertEquals(missing.getCause.asInstanceOf[RestClientException].getStatus, 404)
        assert(!client.getAllSubjects().asScala.exists(_ == s"$subject-value"))
        val schemaId = register(s"$subject-value", schema(Trade.codec.schemaJson))
        assert(schemaId > 0)
        val bytes = serializer.serialize(subject, value)
        assertEquals(client.getAllVersions(s"$subject-value").asScala.toVector, Vector(Int.box(1)))
        val reader = RegistryDeserializer.forValue(Trade.codec, client)
        try assertEquals(reader.deserialize(subject, bytes), value)
        finally reader.close()
      finally serializer.close()
    }
  }

  test("Wire and Confluent serializers interoperate through the actual registry") {
    withTopic("confluent-interop") { subject =>
      val topic = subject
      val config = new HashMap[String, Object]()
      config.put("schema.registry.url", registryUrl)
      val confluentSerializer = new KafkaAvroSerializer(client)
      val confluentDeserializer = new KafkaAvroDeserializer(client)
      confluentSerializer.configure(config, false)
      confluentDeserializer.configure(config, false)
      val settings = SerializerSettings(autoRegisterSchemas = true, subjectNameStrategy = SubjectNameStrategy.TopicName)
      val wireSerializer = RegistrySerializer.forValue(Trade.codec, client, settings)
      val wireDeserializer = RegistryDeserializer.forValue(Trade.codec, client)
      try
        val expected = Trade(77L, "CROSS", 12.75, Vector(3, 4, 5))

        val fromWire = confluentDeserializer.deserialize(topic, wireSerializer.serialize(topic, expected))
          .asInstanceOf[GenericRecord]
        assertEquals(fromWire.get("id").asInstanceOf[Long], expected.id)
        assertEquals(fromWire.get("symbol").toString, expected.symbol)
        assertEquals(fromWire.get("price").asInstanceOf[Double], expected.price)
        assertEquals(fromWire.get("quantities").asInstanceOf[java.util.Collection[?]].asScala.map(_.asInstanceOf[Int]).toVector, expected.quantities)

        val genericSchema = schema(Trade.codec.schemaJson)
        val generic = new GenericData.Record(genericSchema)
        generic.put("id", expected.id)
        generic.put("symbol", expected.symbol)
        generic.put("price", expected.price)
        generic.put("quantities", expected.quantities.map(Int.box).asJava)
        val toWire = wireDeserializer.deserialize(topic, confluentSerializer.serialize(topic, generic))
        assertEquals(toWire, expected)
      finally
        wireSerializer.close()
        wireDeserializer.close()
        confluentSerializer.close()
        confluentDeserializer.close()
    }
  }

  test("Wire reader ignores a writer-only field registered by Confluent") {
    withTopic("writer-evolution") { subject =>
      val topic = subject
      setCompatibility(s"$topic-value", "NONE")
      val config = new HashMap[String, Object]()
      config.put("schema.registry.url", registryUrl)
      val confluentSerializer = new KafkaAvroSerializer(client)
      confluentSerializer.configure(config, false)
      val wireDeserializer = RegistryDeserializer.forValue(Trade.codec, client)
      val writerSchema = tradeSchemaWithWriterOnlyField()
      val writer = new GenericData.Record(writerSchema)
      writer.put("id", 88L)
      writer.put("symbol", "EVOLVE")
      writer.put("price", 9.5d)
      writer.put("quantities", java.util.Arrays.asList(Int.box(2), Int.box(6)))
      writer.put("venue", "LON")
      try
        val bytes = confluentSerializer.serialize(topic, writer)
        assertEquals(wireDeserializer.deserialize(topic, bytes), Trade(88L, "EVOLVE", 9.5, Vector(2, 6)))

        val missingFieldSchema = tradeSchemaWithoutQuantities()
        val missingFieldWriter = new GenericData.Record(missingFieldSchema)
        missingFieldWriter.put("id", 89L)
        missingFieldWriter.put("symbol", "MISSING")
        missingFieldWriter.put("price", 10.5d)
        val missingFieldBytes = confluentSerializer.serialize(topic, missingFieldWriter)
        val missing = intercept[SerializationException] { wireDeserializer.deserialize(topic, missingFieldBytes) }
        assert(missing.getCause.isInstanceOf[SchemaResolutionException])
      finally
        confluentSerializer.close()
        wireDeserializer.close()
    }
  }

  test("Wire reader resolves a referenced writer-only named schema from a fresh registry client") {
    withSubject("writer-child") { childSubject =>
      val childSchema = Schema.createRecord(
        "WriterOnlyChild",
        "Nested writer-only field used by the registry integration suite",
        "avro2s.wire.integration.references",
        false
      )
      childSchema.setFields(java.util.Collections.singletonList(
        new Schema.Field("label", Schema.create(Schema.Type.STRING), "child label", null)
      ))
      val childId = register(childSubject, childSchema)
      assert(childId > 0)

      withTopic("referenced-writer") { topic =>
        val reference = new SchemaReference(childSchema.getFullName, childSubject, 1)
        val references = java.util.Collections.singletonList(reference)
        val resolved = java.util.Collections.singletonMap(childSchema.getFullName, childSchema.toString)
        val writerSchema = new AvroSchema(
          tradeSchemaWithReference(childSchema.getFullName),
          references,
          resolved,
          null
        )
        val subject = s"$topic-value"
        val id = client.register(subject, writerSchema)
        assert(id > 0)

        val rawSchema = writerSchema.rawSchema()
        val record = new GenericData.Record(rawSchema)
        record.put("id", 90L)
        record.put("symbol", "REFERENCE")
        record.put("price", 11.25d)
        record.put("quantities", java.util.Arrays.asList(Int.box(7), Int.box(8)))
        val nested = new GenericData.Record(rawSchema.getField("writerOnly").schema())
        nested.put("label", "skip this nested value")
        record.put("writerOnly", nested)

        val output = new ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(output, null)
        new GenericDatumWriter[GenericRecord](rawSchema).write(record, encoder)
        encoder.flush()
        val datum = output.toByteArray
        val framed = Array[Byte](0, (id >>> 24).toByte, (id >>> 16).toByte, (id >>> 8).toByte, id.toByte) ++ datum

        val freshClient = new CachedSchemaRegistryClient(registryUrl, 32)
        val deserializer = RegistryDeserializer.forValue(Trade.codec, freshClient)
        try
          assertEquals(deserializer.deserialize(topic, framed), Trade(90L, "REFERENCE", 11.25, Vector(7, 8)))
        finally
          deserializer.close()
          freshClient.close()
      }
    }
  }
