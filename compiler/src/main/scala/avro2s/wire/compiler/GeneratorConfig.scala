package avro2s.wire.compiler

/** The generated value type for decimal and big-decimal logical values. */
enum DecimalType:
  case Scala, Java

/** Generation options apply consistently to every reachable named schema. */
final case class GeneratorConfig(decimalType: DecimalType = DecimalType.Scala)
