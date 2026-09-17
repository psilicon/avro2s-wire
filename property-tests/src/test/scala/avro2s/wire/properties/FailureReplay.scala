package avro2s.wire.properties

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import scala.jdk.CollectionConverters.*

object FailureReplay:
  def save(c: SchemaCase, directory: Path, details: String): Unit =
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("schema.avsc"), c.schema.toString, UTF_8)
    c.values.zipWithIndex.foreach { (v, i) =>
      Files.write(directory.resolve(f"value-$i%04d.bin"), JavaOracle.encode(c.schema, v))
    }
    Files.writeString(directory.resolve("failure.txt"), details, UTF_8)

  def load(directory: Path): SchemaCase =
    val schema = new Schema.Parser().setValidateDefaults(true).parse(directory.resolve("schema.avsc").toFile)
    val paths = Files.list(directory)
    val values = try paths.iterator().asScala.filter(p => p.getFileName.toString.matches("value-[0-9]+\\.bin"))
      .toVector.sortBy(_.getFileName.toString).map(p => JavaOracle.decode(schema, Files.readAllBytes(p)))
    finally paths.close()
    require(values.nonEmpty, s"No replay values in $directory")
    SchemaCase(schema, values)

  private def fingerprint(c: SchemaCase): String =
    c.schema.toString + c.values.map(v => java.util.Base64.getEncoder.encodeToString(JavaOracle.encode(c.schema, v))).mkString("|")

  /** Greedy, bounded, joint schema/value shrinking. The predicate must preserve failure kind. */
  def minimize(original: SchemaCase, budget: Int)(stillFails: SchemaCase => Boolean): (SchemaCase, Int) =
    var current = original
    var attempts = 0
    val seen = scala.collection.mutable.Set(fingerprint(current))
    var searching = true
    while searching && attempts < budget do
      searching = false
      val candidates = SchemaCases.shrink(current).iterator
      while candidates.hasNext && !searching && attempts < budget do
        val candidate = candidates.next()
        if seen.add(fingerprint(candidate)) then
          attempts += 1
          if stillFails(candidate) then
            current = candidate
            searching = true
    (current, attempts)
