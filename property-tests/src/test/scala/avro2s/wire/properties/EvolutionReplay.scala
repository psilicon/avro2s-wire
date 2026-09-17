package avro2s.wire.properties

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericDatumReader}
import org.apache.avro.io.DecoderFactory
import scala.jdk.CollectionConverters.*

/** Java schema resolution supplies reader physical datums, before independent Scala rendering. */
object EvolutionOracle:
  def resolve(writer: Schema, reader: Schema, bytes: Array[Byte]): AnyRef =
    val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
    val result = new GenericDatumReader[AnyRef](writer, reader).read(null, decoder)
    require(decoder.isEnd, "Java resolving reader left trailing bytes")
    result

  def expected(c: EvolutionCase): SchemaCase =
    // Parsing each graph also checks generated defaults and emitted references.
    new Schema.Parser().setValidateDefaults(true).parse(c.writer.toString)
    new Schema.Parser().setValidateDefaults(true).parse(c.reader.toString)
    val values = c.values.map { value =>
      require(GenericData.get().validate(c.writer, value), s"Invalid generated writer datum: $value")
      val resolved = resolve(c.writer, c.reader, JavaOracle.encode(c.writer, value))
      require(GenericData.get().validate(c.reader, resolved), s"Java produced an invalid reader datum: $resolved")
      resolved
    }
    SchemaCase(c.reader, values)

object EvolutionReplay:
  def save(c: EvolutionCase, directory: Path, details: String): Unit =
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("writer.avsc"), c.writer.toString, UTF_8)
    Files.writeString(directory.resolve("reader.avsc"), c.reader.toString, UTF_8)
    c.values.zipWithIndex.foreach { (value, index) =>
      Files.write(directory.resolve(f"value-$index%04d.bin"), JavaOracle.encode(c.writer, value))
    }
    Files.writeString(directory.resolve("coverage.txt"), c.labels.toVector.sorted.mkString("\n") + "\n", UTF_8)
    Files.writeString(directory.resolve("failure.txt"), details, UTF_8)

  def load(directory: Path): EvolutionCase =
    val writer = new Schema.Parser().setValidateDefaults(true).parse(directory.resolve("writer.avsc").toFile)
    val reader = new Schema.Parser().setValidateDefaults(true).parse(directory.resolve("reader.avsc").toFile)
    val paths = Files.list(directory)
    val values = try paths.iterator().asScala.filter(_.getFileName.toString.matches("value-[0-9]+\\.bin"))
      .toVector.sortBy(_.getFileName.toString).map(path => JavaOracle.decode(writer, Files.readAllBytes(path)))
    finally paths.close()
    require(values.nonEmpty, s"No evolution replay values in $directory")
    val labels = if Files.exists(directory.resolve("coverage.txt")) then
      Files.readAllLines(directory.resolve("coverage.txt"), UTF_8).asScala.filter(_.nonEmpty).toSet
    else Set.empty[String]
    EvolutionCase(writer, reader, values, labels)

  private def fingerprint(c: EvolutionCase): String = c.writer.toString + "\n" + c.reader.toString +
    c.values.map(v => java.util.Base64.getEncoder.encodeToString(JavaOracle.encode(c.writer, v))).mkString("|")

  /** The caller validates compatibility and preserves the original failure phase. */
  def minimize(original: EvolutionCase, budget: Int)(stillFails: EvolutionCase => Boolean): (EvolutionCase, Int) =
    require(budget >= 0, "A shrink budget cannot be negative")
    var current = original
    var attempts = 0
    val seen = scala.collection.mutable.Set(fingerprint(current))
    var searching = true
    while searching && attempts < budget do
      searching = false
      val candidates = EvolutionCases.shrink(current).iterator
      while candidates.hasNext && !searching && attempts < budget do
        val candidate = candidates.next()
        if seen.add(fingerprint(candidate)) then
          attempts += 1
          if stillFails(candidate) then
            current = candidate
            searching = true
    (current, attempts)
