package avro2s.wire.registry

import avro2s.wire.fixtures.rootunions.*
import avro2s.wire.runtime.{AvroCodec, AvroInput, AvroOutput, Bytes}
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroSerializer, SerializerWithSchema}
import io.confluent.kafka.serializers.schema.id.HeaderSchemaIdSerializer
import io.confluent.kafka.serializers.subject.TopicNameStrategy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class RegistryRootUnionSuite extends munit.FunSuite:
  private val topic = "events"

  private def subject(key: Boolean): String = topic + (if key then "-key" else "-value")
  private def headerName(key: Boolean): String = if key then "__key_schema_id" else "__value_schema_id"

  private def javaConfig(placement: SchemaIdPlacement, key: Boolean): java.util.Map[String, AnyRef] =
    val options = Map[String, AnyRef](
      "schema.registry.url" -> "mock://root-union-interop",
      "auto.register.schemas" -> Boolean.box(false),
      "key.subject.name.strategy" -> classOf[TopicNameStrategy].getName,
      "value.subject.name.strategy" -> classOf[TopicNameStrategy].getName
    )
    (if placement == SchemaIdPlacement.HeaderGuid then options.updated(
      (if key then "key" else "value") + ".schema.id.serializer", classOf[HeaderSchemaIdSerializer].getName)
    else options).asJava

  private def javaWriter(client: MockSchemaRegistryClient, placement: SchemaIdPlacement,
      key: Boolean = false): KafkaAvroSerializer =
    val writer = new KafkaAvroSerializer(client)
    writer.configure(javaConfig(placement, key), key)
    writer

  private def javaReader(client: MockSchemaRegistryClient, key: Boolean = false): KafkaAvroDeserializer =
    val reader = new KafkaAvroDeserializer(client)
    reader.configure(javaConfig(SchemaIdPlacement.PayloadPrefix, key), key)
    reader

  private def nativeWriter[A](codec: AvroCodec[A], client: MockSchemaRegistryClient,
      placement: SchemaIdPlacement, key: Boolean = false): RegistrySerializer[A] =
    val settings = SerializerSettings(schemaIdPlacement = placement)
    if key then RegistrySerializer.forKey(codec, client, settings)
    else RegistrySerializer.forValue(codec, client, settings)

  private def nativeReader[A](codec: AvroCodec[A], client: MockSchemaRegistryClient,
      key: Boolean = false): RegistryDeserializer[A] =
    if key then RegistryDeserializer.forKey(codec, client)
    else RegistryDeserializer.forValue(codec, client)

  private def genericEvent(schema: Schema, value: RootEvents): GenericRecord =
    val alternatives = if schema.getType == Schema.Type.UNION then schema.getTypes.asScala.toVector else Vector(schema)
    value match
      case RootCreated(id) =>
        val record = new GenericData.Record(alternatives.find(_.getName == "RootCreated").get)
        record.put("id", id)
        record
      case RootCancelled(reason) =>
        val record = new GenericData.Record(alternatives.find(_.getName == "RootCancelled").get)
        record.put("reason", reason)
        record

  private def assertEvent(actual: Any, expected: RootEvents): Unit =
    val record = actual.asInstanceOf[GenericRecord]
    expected match
      case RootCreated(id) =>
        assertEquals(record.getSchema.getName, "RootCreated")
        assertEquals(record.get("id"), Long.box(id))
      case RootCancelled(reason) =>
        assertEquals(record.getSchema.getName, "RootCancelled")
        assertEquals(record.get("reason").toString, reason)

  private def datumBytes(placement: SchemaIdPlacement, frame: Array[Byte]): Array[Byte] =
    if placement == SchemaIdPlacement.PayloadPrefix then frame.drop(5) else frame

  private def assertIdentity(client: MockSchemaRegistryClient, schema: AvroSchema,
      placement: SchemaIdPlacement, key: Boolean, headers: RecordHeaders, bytes: Array[Byte]): Unit =
    placement match
      case SchemaIdPlacement.PayloadPrefix =>
        assertEquals(bytes.head, 0.toByte)
        val id = ByteBuffer.wrap(bytes, 1, 4).getInt()
        assertEquals(id, client.getId(subject(key), schema))
        assertEquals(client.getSchemaById(id).asInstanceOf[AvroSchema].rawSchema().getType, Schema.Type.UNION)
      case SchemaIdPlacement.HeaderGuid =>
        val encoded = headers.lastHeader(headerName(key)).value()
        assertEquals(encoded.length, 17)
        assertEquals(encoded.head, 1.toByte)
        val input = ByteBuffer.wrap(encoded, 1, 16)
        val guid = new UUID(input.getLong(), input.getLong()).toString
        assertEquals(guid, client.getGuid(subject(key), schema))
        assertEquals(client.getSchemaByGuid(guid, null).asInstanceOf[AvroSchema].rawSchema().getType, Schema.Type.UNION)

  test("named root unions interoperate with Confluent explicit-schema serializers in both framing modes and roles") {
    for
      placement <- SchemaIdPlacement.values
      key <- Vector(false, true)
      codec <- Vector[AvroCodec[RootEvents]](RootEvents.codec, RootEvents.stackSafeCodec)
    do
      val client = new MockSchemaRegistryClient()
      val schema = new AvroSchema(codec.schemaJson)
      client.register(subject(key), schema)
      Using.Manager { use =>
        val wireWriter = use(nativeWriter(codec, client, placement, key))
        val wireReader = use(nativeReader(codec, client, key))
        val confluentWriter = use(javaWriter(client, placement, key))
        val confluentReader = use(javaReader(client, key))
        // Passing the union explicitly is essential: schema discovery on a
        // GenericRecord otherwise selects the individual branch record schema.
        val explicitWriter: SerializerWithSchema[AnyRef] = confluentWriter
        for value <- Vector[RootEvents](RootCreated(17L), RootCancelled("changed")) do
          val wireHeaders = new RecordHeaders()
          val wireBytes = wireWriter.serialize(topic, wireHeaders, value)
          val javaHeaders = new RecordHeaders()
          val javaBytes = explicitWriter.serialize(topic, javaHeaders, genericEvent(schema.rawSchema(), value), schema)
          assertEquals(wireBytes.toVector, javaBytes.toVector)
          assertEquals(datumBytes(placement, wireBytes).toVector, codec.encode(value).toVector)
          assertIdentity(client, schema, placement, key, wireHeaders, wireBytes)
          assertEquals(wireReader.deserialize(topic, javaHeaders, javaBytes), value)
          assertEvent(confluentReader.deserialize(topic, wireHeaders, wireBytes), value)
        assertEquals(client.getAllVersions(subject(key)).asScala.toVector, Vector(Int.box(1)))
      }.get
      client.close()
  }

  test("exact lookup requires the whole union even when the selected record is already registered") {
    for placement <- SchemaIdPlacement.values do
      val client = new MockSchemaRegistryClient()
      val recordId = client.register(subject(false), new AvroSchema(RootCreated.schemaJson))
      Using.Manager { use =>
        val writer = use(nativeWriter(RootEvents.codec, client, placement))
        intercept[SerializationException](writer.serialize(topic, new RecordHeaders(), RootCreated(9L)))
        val unionSchema = new AvroSchema(RootEvents.schemaJson)
        val unionId = client.register(subject(false), unionSchema)
        assert(recordId != unionId)
        val headers = new RecordHeaders()
        val bytes = writer.serialize(topic, headers, RootCreated(9L))
        assertIdentity(client, unionSchema, placement, false, headers, bytes)
        assertEquals(datumBytes(placement, bytes).toVector, RootEvents.codec.encode(RootCreated(9L)).toVector)
      }.get
      client.close()
  }

  test("optional automatic registration registers the union rather than its selected branch") {
    for placement <- SchemaIdPlacement.values do
      val client = new MockSchemaRegistryClient()
      val schema = new AvroSchema(RootEvents.schemaJson)
      Using.Manager { use =>
        val writer = use(RegistrySerializer.forValue(RootEvents.codec, client,
          SerializerSettings(autoRegisterSchemas = true, schemaIdPlacement = placement)))
        val headers = new RecordHeaders()
        val bytes = writer.serialize(topic, headers, RootCancelled("registered"))
        assertIdentity(client, schema, placement, false, headers, bytes)
        assertEquals(client.getAllVersions(subject(false)).asScala.toVector, Vector(Int.box(1)))
      }.get
      client.close()
  }

  test("nullable root None is a framed Avro null while JVM null remains an unframed Kafka tombstone") {
    for
      placement <- SchemaIdPlacement.values
      codec <- Vector[AvroCodec[RootNullableEvents]](RootNullableEvents.codec, RootNullableEvents.stackSafeCodec)
    do
      val client = new MockSchemaRegistryClient()
      val schema = new AvroSchema(codec.schemaJson)
      Using.Manager { use =>
        val writer = use(nativeWriter(codec, client, placement))
        val reader = use(nativeReader(codec, client))
        val emptyHeaders = new RecordHeaders()
        assertEquals(writer.serialize(topic, emptyHeaders, null), null)
        assertEquals(reader.deserialize(topic, emptyHeaders, null.asInstanceOf[Array[Byte]]), null)
        assertEquals(emptyHeaders.toArray.length, 0)
        assert(client.getAllSubjects.isEmpty)
        client.register(subject(false), schema)
        val confluentReader = use(javaReader(client))
        val confluentWriter = use(javaWriter(client, placement))
        val explicitWriter: SerializerWithSchema[AnyRef] = confluentWriter
        for value <- Vector[RootNullableEvents](None, Some(RootCreated(5L)), Some(RootCancelled("stop"))) do
          val headers = new RecordHeaders()
          val bytes = writer.serialize(topic, headers, value)
          assert(bytes != null)
          assertEquals(datumBytes(placement, bytes).toVector, codec.encode(value).toVector)
          assertIdentity(client, schema, placement, false, headers, bytes)
          assertEquals(reader.deserialize(topic, headers, bytes), value)
          value match
            case None =>
              assertEquals(datumBytes(placement, bytes).toVector, Vector(2.toByte))
              assertEquals(confluentReader.deserialize(topic, headers, bytes), null)
              // Confluent's public serializer also reserves Java null for a
              // Kafka tombstone, even when supplied an explicit union schema.
              assertEquals(explicitWriter.serialize(topic, new RecordHeaders(), null, schema), null)
            case Some(event) =>
              assertEvent(confluentReader.deserialize(topic, headers, bytes), event)
              val javaHeaders = new RecordHeaders()
              val javaBytes = explicitWriter.serialize(topic, javaHeaders, genericEvent(schema.rawSchema(), event), schema)
              assertEquals(reader.deserialize(topic, javaHeaders, javaBytes), value)
      }.get
      client.close()
  }

  test("primitive-only unions retain the union selector and Avro length when the selected branch is bytes") {
    for
      placement <- SchemaIdPlacement.values
      codec <- Vector[AvroCodec[RootPrimitives]](RootPrimitives.codec, RootPrimitives.stackSafeCodec)
    do
      val client = new MockSchemaRegistryClient()
      val schema = new AvroSchema(codec.schemaJson)
      client.register(subject(false), schema)
      Using.Manager { use =>
        val writer = use(nativeWriter(codec, client, placement))
        val reader = use(nativeReader(codec, client))
        val confluentWriter = use(javaWriter(client, placement))
        val confluentReader = use(javaReader(client))
        val explicitWriter: SerializerWithSchema[AnyRef] = confluentWriter
        val bytesValue = Bytes.fromArray(Array[Byte](11, 22, -1))
        val values: Vector[(RootPrimitives, AnyRef)] = Vector(
          Some("text") -> "text", Some(17) -> Int.box(17), Some(17L) -> Long.box(17L),
          Some(1.5f) -> Float.box(1.5f), Some(1.5d) -> Double.box(1.5d),
          Some(true) -> Boolean.box(true), Some(bytesValue) -> ByteBuffer.wrap(bytesValue.toArray)
        )
        for (value, datum) <- values do
          val headers = new RecordHeaders()
          val bytes = writer.serialize(topic, headers, value)
          val javaHeaders = new RecordHeaders()
          val javaBytes = explicitWriter.serialize(topic, javaHeaders, datum, schema)
          assertEquals(bytes.toVector, javaBytes.toVector)
          assertEquals(reader.deserialize(topic, javaHeaders, javaBytes), value)
          val javaValue = confluentReader.deserialize(topic, headers, bytes)
          datum match
            case expected: ByteBuffer =>
              assertEquals(javaValue.asInstanceOf[ByteBuffer], expected)
              // Branch 7 and length 3 use zig-zag encoding; this must not use
              // Confluent's special raw payload convention for a bytes root.
              assertEquals(datumBytes(placement, bytes).toVector, Vector[Byte](14, 6, 11, 22, -1))
            case _: String => assertEquals(javaValue.toString, datum.toString)
            case _ =>
              assertEquals(javaValue.getClass, datum.getClass)
              assertEquals(javaValue, datum)
      }.get
      client.close()
  }

  test("union resolution handles reordered branches promoted fields and skipped fields") {
    val writerSchema = new AvroSchema("""[
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCancelled","fields":[
        {"name":"extra","type":"int"},{"name":"reason","type":"string"}
      ]},
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCreated","fields":[{"name":"id","type":"int"}]}
    ]""")
    val cancelled = new GenericData.Record(writerSchema.rawSchema().getTypes.get(0))
    cancelled.put("extra", 123)
    cancelled.put("reason", "old event")
    val created = new GenericData.Record(writerSchema.rawSchema().getTypes.get(1))
    created.put("id", 42)
    for
      placement <- SchemaIdPlacement.values
      codec <- Vector[AvroCodec[RootEvents]](RootEvents.codec, RootEvents.stackSafeCodec)
    do
      val client = new MockSchemaRegistryClient()
      client.register(subject(false), writerSchema)
      Using.Manager { use =>
        val writer = use(javaWriter(client, placement))
        val explicitWriter: SerializerWithSchema[AnyRef] = writer
        val reader = use(nativeReader(codec, client))
        for (datum, expected) <- Vector[(GenericRecord, RootEvents)](created -> RootCreated(42L), cancelled -> RootCancelled("old event")) do
          val headers = new RecordHeaders()
          val bytes = explicitWriter.serialize(topic, headers, datum, writerSchema)
          assertEquals(reader.deserialize(topic, headers, bytes), expected)
      }.get
      client.close()
  }

  test("root union resolution constructs missing branch fields from reader defaults") {
    val writerSchema = new AvroSchema("""[
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCreated","fields":[]},"string"
    ]""")
    val datum = new GenericData.Record(writerSchema.rawSchema().getTypes.get(0))
    val readerSchemaJson = """[
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCreated","fields":[
        {"name":"id","type":"long","default":37}
      ]},
      {"type":"record","name":"avro2s.wire.fixtures.rootunions.RootCancelled","fields":[{"name":"reason","type":"string"}]}
    ]"""
    for placement <- SchemaIdPlacement.values; base <- Vector[AvroCodec[RootEvents]](RootEvents.codec, RootEvents.stackSafeCodec) do
      val codec = new AvroCodec[RootEvents]:
        override def execution = base.execution
        override val schemaJson = readerSchemaJson
        override def read(in: AvroInput): RootEvents = base.read(in)
        override def write(value: RootEvents, out: AvroOutput): Unit = base.write(value, out)
        override def namedCodec(name: String): AvroCodec[?] = base.namedCodec(name)
      val client = new MockSchemaRegistryClient()
      client.register(subject(false), writerSchema)
      Using.Manager { use =>
        val writer = use(javaWriter(client, placement))
        val explicitWriter: SerializerWithSchema[AnyRef] = writer
        val reader = use(nativeReader(codec, client))
        val headers = new RecordHeaders()
        val bytes = explicitWriter.serialize(topic, headers, datum, writerSchema)
        assertEquals(reader.deserialize(topic, headers, bytes), RootCreated(37L))
      }.get
      client.close()
  }

  test("named writers resolve into union readers and a selected union branch resolves into a named reader") {
    for placement <- SchemaIdPlacement.values; stackSafe <- Vector(false, true) do
      val unionCodec = if stackSafe then RootEvents.stackSafeCodec else RootEvents.codec
      val recordCodec = if stackSafe then RootCreated.stackSafeCodec else RootCreated.codec
      val client = new MockSchemaRegistryClient()
      client.register(subject(false), new AvroSchema(RootEvents.schemaJson))
      client.register(subject(false), new AvroSchema(RootCreated.schemaJson))
      Using.Manager { use =>
        val recordWriter = use(nativeWriter(recordCodec, client, placement))
        val unionWriter = use(nativeWriter(unionCodec, client, placement))
        val unionReader = use(nativeReader(unionCodec, client))
        val recordReader = use(nativeReader(recordCodec, client))
        val value = RootCreated(88L)
        val recordHeaders = new RecordHeaders()
        val recordBytes = recordWriter.serialize(topic, recordHeaders, value)
        assertEquals(unionReader.deserialize(topic, recordHeaders, recordBytes), value)
        val unionHeaders = new RecordHeaders()
        val unionBytes = unionWriter.serialize(topic, unionHeaders, value)
        assertEquals(recordReader.deserialize(topic, unionHeaders, unionBytes), value)
        val otherHeaders = new RecordHeaders()
        val otherBytes = unionWriter.serialize(topic, otherHeaders, RootCancelled("not a creation"))
        intercept[SerializationException](recordReader.deserialize(topic, otherHeaders, otherBytes))
        // A failed branch read must not poison the compiled union reader.
        assertEquals(recordReader.deserialize(topic, unionHeaders, unionBytes), value)
      }.get
      client.close()
  }

  test("record-name subject strategies reject unnamed root unions before registry access") {
    val client = new MockSchemaRegistryClient()
    for strategy <- Vector(SubjectNameStrategy.RecordName, SubjectNameStrategy.TopicRecordName) do
      for placement <- SchemaIdPlacement.values do
        val settings = SerializerSettings(subjectNameStrategy = strategy, schemaIdPlacement = placement)
        intercept[IllegalArgumentException](RegistrySerializer.forValue(RootEvents.codec, client, settings))
        intercept[IllegalArgumentException](RegistrySerializer.forKey(RootNullableEvents.stackSafeCodec, client, settings))
    assert(client.getAllSubjects.isEmpty)
    client.close()
  }

  test("a degenerate null-only union retains Kafka tombstone semantics but can read an encoded null branch") {
    for placement <- SchemaIdPlacement.values; codec <- Vector(RootNull.codec, RootNull.stackSafeCodec) do
      val client = new MockSchemaRegistryClient()
      val schema = new AvroSchema(codec.schemaJson)
      Using.Manager { use =>
        val writer = use(nativeWriter(codec, client, placement))
        val reader = use(nativeReader(codec, client))
        val headers = new RecordHeaders()
        assertEquals(writer.serialize(topic, headers, null), null)
        assertEquals(headers.toArray.length, 0)
        assert(client.getAllSubjects.isEmpty)
        val id = client.register(subject(false), schema)
        // A Java datum writer can produce an Avro null union; Kafka's public
        // serializer reserves the same Java null input for its tombstone path.
        val out = new ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(out, null)
        new GenericDatumWriter[AnyRef](schema.rawSchema()).write(null, encoder)
        encoder.flush()
        val datum = out.toByteArray
        assertEquals(datum.toVector, codec.encode(null).toVector)
        val framed = placement match
          case SchemaIdPlacement.PayloadPrefix =>
            ByteBuffer.allocate(5 + datum.length).put(0.toByte).putInt(id).put(datum).array()
          case SchemaIdPlacement.HeaderGuid =>
            val guid = UUID.fromString(client.getGuid(subject(false), schema))
            headers.add(headerName(false), ByteBuffer.allocate(17).put(1.toByte)
              .putLong(guid.getMostSignificantBits).putLong(guid.getLeastSignificantBits).array())
            datum
        assertEquals(reader.deserialize(topic, headers, framed), null)
      }.get
      client.close()
  }
