package avro2s.wire.registry

import avro2s.wire.runtime.{DecodeLimits, WriterSettings}
import org.apache.avro.Schema

/** The three classic Confluent subject names. Record-name strategies require a
  * record root; TopicName also supports enum and fixed roots.
  */
enum SubjectNameStrategy:
  case TopicName, RecordName, TopicRecordName

  private[registry] def subject(topic: String, schema: Schema, role: RegistryRole): String =
    val fullName = schema.getFullName
    this match
      case TopicName =>
        require(topic != null && topic.nonEmpty, "TopicName requires a nonempty topic")
        s"$topic-${role.subjectSuffix}"
      case RecordName => fullName
      case TopicRecordName =>
        require(topic != null && topic.nonEmpty, "TopicRecordName requires a nonempty topic")
        s"$topic-$fullName"

/** Immutable settings for writing generated values to Schema Registry subjects. */
final case class SerializerSettings(
    autoRegisterSchemas: Boolean = false,
    normalizeSchemas: Boolean = false,
    subjectNameStrategy: SubjectNameStrategy = SubjectNameStrategy.TopicName,
    cacheCapacity: Int = 1024,
    writerSettings: WriterSettings = WriterSettings.default,
    schemaIdPlacement: SchemaIdPlacement = SchemaIdPlacement.PayloadPrefix
):
  require(cacheCapacity > 0, "cacheCapacity must be positive")
  require(subjectNameStrategy != null, "subjectNameStrategy must be non-null")
  require(writerSettings != null, "writerSettings must be non-null")
  require(schemaIdPlacement != null, "schemaIdPlacement must be non-null")

/** Immutable settings for reading framed values into a generated model. */
final case class DeserializerSettings(
    cacheCapacity: Int = 1024,
    decodeLimits: DecodeLimits = DecodeLimits.default,
    schemaLookup: SchemaLookup = SchemaLookup.ById
):
  require(cacheCapacity > 0, "cacheCapacity must be positive")
  require(decodeLimits != null, "decodeLimits must be non-null")
  require(schemaLookup != null, "schemaLookup must be non-null")
  schemaLookup.validate()

private[registry] enum RegistryRole(val subjectSuffix: String):
  case Key extends RegistryRole("key")
  case Value extends RegistryRole("value")

  def schemaIdHeader: String = s"__${subjectSuffix}_schema_id"
