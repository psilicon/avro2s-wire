package avro2s.wire.registry

import avro2s.wire.runtime.DecodeLimits
import org.apache.avro.Schema

/** The three classic Confluent subject names for a named Avro root. */
enum SubjectNameStrategy:
  case TopicName, RecordName, TopicRecordName

  def subject(topic: String, schema: Schema, isKey: Boolean): String =
    val fullName = schema.getFullName
    this match
      case TopicName =>
        require(topic != null && topic.nonEmpty, "TopicName requires a nonempty topic")
        s"$topic-${if isKey then "key" else "value"}"
      case RecordName => fullName
      case TopicRecordName =>
        require(topic != null && topic.nonEmpty, "TopicRecordName requires a nonempty topic")
        s"$topic-$fullName"

/** The same settings apply to each serializer or deserializer instance. */
final case class RegistrySettings(
    autoRegisterSchemas: Boolean = true,
    normalizeSchemas: Boolean = false,
    subjectNameStrategy: SubjectNameStrategy = SubjectNameStrategy.TopicName,
    cacheCapacity: Int = 1024,
    decodeLimits: DecodeLimits = DecodeLimits.default
):
  require(cacheCapacity > 0, "cacheCapacity must be positive")
  require(subjectNameStrategy != null, "subjectNameStrategy must be non-null")
  require(decodeLimits != null, "decodeLimits must be non-null")
