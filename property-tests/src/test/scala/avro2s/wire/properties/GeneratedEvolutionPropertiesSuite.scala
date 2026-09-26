package avro2s.wire.properties

import avro2s.wire.compiler.GeneratorConfig
import avro2s.wire.resolution.{ResolvingReader, SchemaResolutionException}
import java.nio.file.{Files, Path}
import org.apache.avro.{Schema, SchemaCompatibility}
import org.apache.avro.generic.GenericData
import scala.concurrent.duration.*

/** Evolves compiled Scala models while keeping each Avro fullname isolated by classloader. */
final class GeneratedEvolutionPropertiesSuite extends munit.FunSuite:
  override val munitTimeout = 10.minutes
  private val generation = GeneratorConfig(generateStackSafeCodecs = true)
  private lazy val target = Path.of(sys.props("avro2s.wire.property.target"), "generated-evolution")

  private def schema(json: String): Schema = new Schema.Parser().setValidateDefaults(true).parse(json)
  private def record(schema: Schema, values: AnyRef*): GenericData.Record =
    val result = new GenericData.Record(schema)
    values.zipWithIndex.foreach((value, index) => result.put(index, value))
    result
  private def one(s: Schema, datum: AnyRef): SchemaCase = SchemaCase(s, Vector(datum))
  private def compile(c: SchemaCase, name: String): CompiledCases =
    CompiledCases.compile(Vector(c), Files.createTempDirectory(target, s"$name-"), generation)

  private def rejects(writer: Schema, reader: CompiledCase, bytes: Array[Byte]): Unit =
    reader.codecs.foreach { codec =>
      intercept[SchemaResolutionException](new ResolvingReader(writer.toString, codec).decode(bytes))
    }

  private def direction(writer: SchemaCase, reader: SchemaCase, writerCode: CompiledCase, label: String): Unit =
    val bytesAndResolved = writer.values.indices.map { index =>
      val bytes = writerCode.codec.encode(writerCode.values(index))
      writerCode.alternatives.foreach(codec =>
        assertEquals(codec.encode(writerCode.values(index)).toVector, bytes.toVector, s"$label writer execution changed bytes"))
      bytes -> EvolutionOracle.resolve(writer.schema, reader.schema, bytes)
    }.toVector
    val expectedReader = SchemaCase(reader.schema, bytesAndResolved.map(_._2))
    val resolvedCode = compile(expectedReader, s"${label.replaceAll("[^A-Za-z0-9]+", "-")}-reader")
    try
      bytesAndResolved.zip(writer.values).zipWithIndex.foreach { case (((bytes, javaResolved), writerValue), index) =>
        val javaWriter = JavaOracle.decode(writer.schema, bytes)
        assertEquals(JavaOracle.normalized(writer.schema, javaWriter), JavaOracle.normalized(writer.schema, writerValue), s"$label native writer output")
        resolvedCode.cases.head.codecs.foreach { codec =>
          val nativeResolved = new ResolvingReader(writer.schema.toString, codec).decode(bytes)
          assert(JavaOracle.nativeEqual(nativeResolved, resolvedCode.cases.head.values(index)), s"$label generated reader model: $nativeResolved != ${resolvedCode.cases.head.values(index)}")
          val nativeReaderWire = codec.encode(nativeResolved)
          val javaReader = JavaOracle.decode(reader.schema, nativeReaderWire)
          assertEquals(JavaOracle.normalized(reader.schema, javaReader), JavaOracle.normalized(reader.schema, javaResolved), s"$label Java resolution oracle")
        }
      }
    finally resolvedCode.close()

  private def compatiblePair(name: String, old: SchemaCase, fresh: SchemaCase, reverse: Boolean = true): Unit =
    val oldCompiled = compile(old, s"$name-v1")
    val freshCompiled = compile(fresh, s"$name-v2")
    try
      direction(old, fresh, oldCompiled.cases.head, s"$name backward-compatible V1 writer -> V2 reader")
      if reverse then direction(fresh, old, freshCompiled.cases.head, s"$name forward-compatible V2 writer -> V1 reader")
    finally
      freshCompiled.close()
      oldCompiled.close()

  test("generated record evolution resolves native output in both compatible directions") {
    Files.createDirectories(target)
    val ns = "avro2s.wire.generatedevolution.records"
    val oldWithAdd = schema(s"""{"type":"record","name":"WithAdd","namespace":"$ns","fields":[{"name":"id","type":"int"}]}""")
    val newWithAdd = schema(s"""{"type":"record","name":"WithAdd","namespace":"$ns","fields":[{"name":"id","type":"int"},{"name":"note","type":"string","default":"new"}]}""")
    compatiblePair("record-add-default", one(oldWithAdd, record(oldWithAdd, Int.box(17))),
      one(newWithAdd, record(newWithAdd, Int.box(17), "present")))

    val oldWithRemoval = schema(s"""{"type":"record","name":"WithRemoval","namespace":"$ns","fields":[{"name":"id","type":"long"},{"name":"obsolete","type":"string","default":"legacy"}]}""")
    val newWithRemoval = schema(s"""{"type":"record","name":"WithRemoval","namespace":"$ns","fields":[{"name":"id","type":"long"}]}""")
    compatiblePair("record-remove-defaulted", one(oldWithRemoval, record(oldWithRemoval, Long.box(9007199254740993L), "stored")),
      one(newWithRemoval, record(newWithRemoval, Long.box(9007199254740993L))))

    val oldBranches = schema(s"""{"type":"record","name":"BranchProbe","namespace":"$ns","fields":[{"name":"value","type":["int","string","null"]}]}""")
    val newBranches = schema(s"""{"type":"record","name":"BranchProbe","namespace":"$ns","fields":[{"name":"value","type":["null","string","long"]}]}""")
    val branchValues = Vector[AnyRef](Int.box(16777217), "branch-🚀", null)
    val oldBranchCase = SchemaCase(oldBranches, branchValues.map(value => record(oldBranches, value)))
    val newBranchCase = one(newBranches, record(newBranches, Long.box(16777217L)))
    compatiblePair("union-branch-reorder-and-promotion", oldBranchCase, newBranchCase, reverse = false)
    val oldCode = compile(oldBranchCase, "union-branches-old-reader")
    val newCode = compile(newBranchCase, "union-branches-new-writer")
    try
      val bytes = newCode.cases.head.codec.encode(newCode.cases.head.values.head)
      intercept[org.apache.avro.AvroTypeException](EvolutionOracle.resolve(newBranches, oldBranches, bytes))
      rejects(newBranches, oldCode.cases.head, bytes)
    finally
      newCode.close()
      oldCode.close()
  }

  test("generated enum and fixed roots exercise aliases, symbol fallback, and fixed-size failure") {
    Files.createDirectories(target)
    val ns = "avro2s.wire.generatedevolution.roots"
    val oldSameNameEnum = schema(s"""{"type":"enum","name":"StableStatus","namespace":"$ns","symbols":["A","B","C"]}""")
    val newSameNameEnum = schema(s"""{"type":"enum","name":"StableStatus","namespace":"$ns","symbols":["A","C"],"default":"C"}""")
    val oldSameNameEnumCase = SchemaCase(oldSameNameEnum, Vector("A", "B", "C").map(s => new GenericData.EnumSymbol(oldSameNameEnum, s)))
    val newSameNameEnumCase = one(newSameNameEnum, new GenericData.EnumSymbol(newSameNameEnum, "C"))
    compatiblePair("enum-symbol-removal-default", oldSameNameEnumCase, newSameNameEnumCase)

    val oldEnum = schema(s"""{"type":"enum","name":"OldStatus","namespace":"$ns","symbols":["A","B"]}""")
    val newEnum = schema(s"""{"type":"enum","name":"Status","namespace":"$ns","aliases":["$ns.OldStatus"],"symbols":["B","A","C"],"default":"A"}""")
    val oldEnumValue = new GenericData.EnumSymbol(oldEnum, "B")
    val newEnumValue = new GenericData.EnumSymbol(newEnum, "C")
    val oldEnumCase = one(oldEnum, oldEnumValue)
    val newEnumCase = one(newEnum, newEnumValue)
    val oldEnumCompiled = compile(oldEnumCase, "enum-old")
    val newEnumCompiled = compile(newEnumCase, "enum-new")
    try
      direction(oldEnumCase, newEnumCase, oldEnumCompiled.cases.head, "enum alias backward-compatible V1 writer -> V2 reader")
      // The renamed writer fullname has no alias on the old reader, so reverse resolution is not legal.
      val reverseBytes = newEnumCompiled.cases.head.codec.encode(newEnumCompiled.cases.head.values.head)
      intercept[org.apache.avro.AvroTypeException](EvolutionOracle.resolve(newEnum, oldEnum, reverseBytes))
      rejects(newEnum, oldEnumCompiled.cases.head, reverseBytes)
    finally
      newEnumCompiled.close()
      oldEnumCompiled.close()

    val oldFixed = schema(s"""{"type":"fixed","name":"OldToken","namespace":"$ns","size":4}""")
    val newFixed = schema(s"""{"type":"fixed","name":"Token","namespace":"$ns","aliases":["$ns.OldToken"],"size":4}""")
    val fixedBytes = Array[Byte](0, 1, -1, 42)
    val oldFixedCase = one(oldFixed, new GenericData.Fixed(oldFixed, fixedBytes))
    val newFixedCase = one(newFixed, new GenericData.Fixed(newFixed, fixedBytes))
    val oldFixedCompiled = compile(oldFixedCase, "fixed-old")
    val newFixedCompiled = compile(newFixedCase, "fixed-new")
    try direction(oldFixedCase, newFixedCase, oldFixedCompiled.cases.head, "fixed alias backward-compatible V1 writer -> V2 reader")
    finally
      newFixedCompiled.close()
      oldFixedCompiled.close()

    val oldAliasReader = compile(oldFixedCase, "fixed-alias-old-reader")
    val newAliasWriter = compile(newFixedCase, "fixed-alias-new-writer")
    try
      val bytes = newAliasWriter.cases.head.codec.encode(newAliasWriter.cases.head.values.head)
      intercept[org.apache.avro.AvroTypeException](EvolutionOracle.resolve(newFixed, oldFixed, bytes))
      rejects(newFixed, oldAliasReader.cases.head, bytes)
    finally
      newAliasWriter.close()
      oldAliasReader.close()

    val fixedFour = schema(s"""{"type":"fixed","name":"SizeProbe","namespace":"$ns","size":4}""")
    val fixedEight = schema(s"""{"type":"fixed","name":"SizeProbe","namespace":"$ns","size":8}""")
    val fourCase = one(fixedFour, new GenericData.Fixed(fixedFour, Array[Byte](1, 2, 3, 4)))
    val eightCase = one(fixedEight, new GenericData.Fixed(fixedEight, Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)))
    val fourCompiled = compile(fourCase, "fixed-size-four")
    val eightCompiled = compile(eightCase, "fixed-size-eight")
    try
      val bytes = fourCompiled.cases.head.codec.encode(fourCompiled.cases.head.values.head)
      intercept[org.apache.avro.AvroTypeException](EvolutionOracle.resolve(fixedFour, fixedEight, bytes))
      rejects(fixedFour, eightCompiled.cases.head, bytes)
    finally
      eightCompiled.close()
      fourCompiled.close()
  }

  test("nullable added field without a default fails and aliases are not transitively accumulated") {
    Files.createDirectories(target)
    val ns = "avro2s.wire.generatedevolution.transitive"
    val v1 = schema(s"""{"type":"record","name":"NullableProbe","namespace":"$ns","fields":[{"name":"id","type":"int"}]}""")
    val v2 = schema(s"""{"type":"record","name":"NullableProbe","namespace":"$ns","fields":[{"name":"id","type":"int"},{"name":"maybe","type":["null","string"]}]}""")
    val oldCase = one(v1, record(v1, Int.box(1)))
    val newCase = one(v2, record(v2, Int.box(1), null))
    val oldCompiled = compile(oldCase, "nullable-old")
    val newCompiled = compile(newCase, "nullable-new")
    try
      val bytes = oldCompiled.cases.head.codec.encode(oldCompiled.cases.head.values.head)
      intercept[org.apache.avro.AvroTypeException](EvolutionOracle.resolve(v1, v2, bytes))
      rejects(v1, newCompiled.cases.head, bytes)
    finally
      newCompiled.close()
      oldCompiled.close()

    def named(name: String, alias: Option[String]): SchemaCase =
      val aliases = alias.fold("")(a => s",\"aliases\":[\"$ns.$a\"]")
      val current = schema(s"""{"type":"record","name":"$name","namespace":"$ns"$aliases,"fields":[{"name":"id","type":"int"}]}""")
      one(current, record(current, Int.box(73)))
    val first = named("Legacy", None)
    val second = named("Middle", Some("Legacy"))
    val third = named("Current", Some("Middle"))
    val firstCode = compile(first, "chain-v1")
    val secondCode = compile(second, "chain-v2")
    val thirdCode = compile(third, "chain-v3")
    try
      direction(first, second, firstCode.cases.head, "alias chain backward-compatible V1 writer -> V2 reader")
      direction(second, third, secondCode.cases.head, "alias chain backward-compatible V2 writer -> V3 reader")
      val firstBytes = firstCode.cases.head.codec.encode(firstCode.cases.head.values.head)
      assertEquals(
        SchemaCompatibility.checkReaderWriterCompatibility(third.schema, first.schema).getType,
        SchemaCompatibility.SchemaCompatibilityType.INCOMPATIBLE,
        "V3 does not directly accept the V1 fullname alias"
      )
      rejects(first.schema, thirdCode.cases.head, firstBytes)
    finally
      thirdCode.close()
      secondCode.close()
      firstCode.close()
  }
