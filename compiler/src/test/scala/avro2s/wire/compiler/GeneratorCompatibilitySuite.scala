package avro2s.wire.compiler

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.apache.avro.Schema

/** Hashes captured from the generator before namespace/raw options were implemented.
  * Keep these fixed: intentional output changes require reviewing the generated diff.
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
      val hash = MessageDigest.getInstance("SHA-256").digest(source.content.getBytes(UTF_8))
        .map(byte => f"${byte & 0xff}%02x").mkString
      source.relativePath -> hash
    }.toMap

  test("default generation remains byte-for-byte identical across all supported types") {
    assertEquals(hashes(GeneratorConfig()), expected("scala"))
  }

  test("Java decimal generation remains byte-for-byte identical across all supported types") {
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
