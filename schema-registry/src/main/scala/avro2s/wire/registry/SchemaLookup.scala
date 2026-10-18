package avro2s.wire.registry

import io.confluent.kafka.schemaregistry.utils.QualifiedSubject

/** The subject/context hint used when resolving numeric schema IDs.
  * A hint selects the registry namespace; it is not a subject-membership check.
  * GUID lookups do not use this policy. Their root references must specify a
  * named context explicitly; context-relative references are rejected because
  * the GUID response does not identify the parent context.
  */
enum SchemaLookup:
  /** Use the namespace supplied by the client/connection, preserving the original API. */
  case ById
  /** Look up <topic>-key or <topic>-value, optionally in a named context. */
  case TopicName(context: Option[String] = None)
  /** Use an explicit subject, which may already be context-qualified. */
  case Subject(name: String)
  /** Look up an ID in a named context without specifying a subject. */
  case Context(name: String)

  private[registry] def validate(): Unit = this match
    case ById => ()
    case TopicName(context) =>
      require(context != null, "Schema lookup context must be non-null")
      context.foreach(SchemaLookup.contextPrefix)
    case Subject(name) =>
      require(name != null && name.trim.nonEmpty, "Schema lookup subject must be nonempty")
    case Context(name) => SchemaLookup.contextPrefix(name); ()

  private[registry] def subject(topic: String, role: RegistryRole): Option[String] = this match
    case ById => None
    case TopicName(context) =>
      require(topic != null && topic.nonEmpty, "TopicName schema lookup requires a nonempty topic")
      Some(context.fold("")(SchemaLookup.contextPrefix) + s"$topic-${role.subjectSuffix}")
    case Subject(name) => Some(name)
    case Context(name) => Some(SchemaLookup.contextPrefix(name))

object SchemaLookup:
  private def contextPrefix(name: String): String =
    require(name != null && name.trim.nonEmpty, "Schema lookup context must be nonempty")
    require(name == name.trim && !name.contains('*'), "Schema lookup requires one explicit context")
    QualifiedSubject.normalizeContext(name)
