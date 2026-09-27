package avro2s.wire.runtime

/** How native writers handle unpaired UTF-16 surrogates in JVM strings. */
enum MalformedStringPolicy:
  /** Reject malformed input before appending the string to the output. */
  case Reject
  /** Use the JDK UTF-8 encoder's replacement bytes, matching String.getBytes(UTF_8). */
  case Replace

/** Immutable settings for native binary writing, including string map keys. */
final case class WriterSettings(malformedStrings: MalformedStringPolicy = MalformedStringPolicy.Reject):
  require(malformedStrings != null, "malformedStrings must not be null")

object WriterSettings:
  val default: WriterSettings = WriterSettings()
