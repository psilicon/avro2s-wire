package avro2s.wire.registry

import avro2s.wire.runtime.DecodeLimits
import org.apache.avro.Schema

/** The three classic Confluent subject names for a named Avro root. */
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
    cacheCapacity: Int = 1024
):
  require(cacheCapacity > 0, "cacheCapacity must be positive")
  require(subjectNameStrategy != null, "subjectNameStrategy must be non-null")

/** Immutable settings for reading framed values into a generated model. */
final case class DeserializerSettings(
    cacheCapacity: Int = 1024,
    decodeLimits: DecodeLimits = DecodeLimits.default
):
  require(cacheCapacity > 0, "cacheCapacity must be positive")
  require(decodeLimits != null, "decodeLimits must be non-null")

private[registry] enum RegistryRole(val subjectSuffix: String):
  case Key extends RegistryRole("key")
  case Value extends RegistryRole("value")

  def schemaIdHeader: String = s"__${subjectSuffix}_schema_id"
