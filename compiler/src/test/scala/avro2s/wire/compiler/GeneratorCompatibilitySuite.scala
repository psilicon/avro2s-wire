package avro2s.wire.compiler

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.apache.avro.Schema

/** Hashes captured from the generator before namespace/raw options were implemented.
  * The additive stack-safe codec is excluded; models and direct codecs must
  * retain the exact historical output, including specialised collection paths.
  */
class GeneratorCompatibilitySuite extends munit.FunSuite:
  private def resource(name: String): String =
    val stream = getClass.getResourceAsStream(s"/generator-compatibility/$name")
    require(stream != null, s"Missing compatibility resource: $name")
    try new String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private def expected(mode: String): Map[String, String] =
    resource(s"$mode.sha256").linesIterator.filter(_.nonEmpty).map { line =>
      val Array(hash, path) = line.split("  ", 2): @unchecked
      path -> hash
    }.toMap

  private def hashes(config: GeneratorConfig): Map[String, String] =
    val schema = new Schema.Parser().parse(resource("all-types.avsc"))
    CodeGenerator.generate(schema, config).map { source =>
      val alternativeStart = source.content.indexOf("\n  lazy val stackSafeCodec:")
      assert(alternativeStart >= 0, s"Missing alternative codec in ${source.relativePath}")
      val directSource = source.content.substring(0, alternativeStart)
      val hash = MessageDigest.getInstance("SHA-256").digest(directSource.getBytes(UTF_8))
        .map(byte => f"${byte & 0xff}%02x").mkString
      source.relativePath -> hash
    }.toMap

  test("default models and direct codecs remain byte-for-byte identical across all supported types") {
    assertEquals(hashes(GeneratorConfig()), expected("scala"))
  }

  test("Java decimal models and direct codecs remain byte-for-byte identical across all supported types") {
    assertEquals(hashes(GeneratorConfig(decimalType = DecimalType.Java)), expected("java"))
  }

  test("explicit Converted and identity or unmatched mappings do not change generated output") {
    for decimalType <- DecimalType.values do
      val baseline = expected(if decimalType == DecimalType.Java then "java" else "scala")
      val config = GeneratorConfig(
        decimalType = decimalType,
        namespaceMappings = Map("avro2s.wire" -> "avro2s.wire", "elsewhere" -> "models"),
        logicalTypes = LogicalType.values.map(_ -> LogicalTypeMode.Converted).toMap
      )
      assertEquals(hashes(config), baseline)
  }
