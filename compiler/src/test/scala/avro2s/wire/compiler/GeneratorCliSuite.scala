package avro2s.wire.compiler

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class GeneratorCliSuite extends munit.FunSuite:
  private def withDirectory(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("wire-generator-options-")
    try body(directory)
    finally
      val stream = Files.walk(directory)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally stream.close()

  test("CLI combines repeated mappings, logical modes and Java decimal selection for a directory") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      val nested = Files.createDirectory(input.resolve("nested"))
      Files.writeString(input.resolve("a-parent.avsc"), """{"type":"record","name":"Parent","namespace":"old","fields":[
        {"name":"child","type":"old.detail.Child"},
        {"name":"date","type":{"type":"int","logicalType":"date"}},
        {"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"}}
      ]}""")
      Files.writeString(nested.resolve("z-child.avsc"), """{"type":"record","name":"Child","namespace":"old.detail","fields":[
        {"name":"id","type":{"type":"string","logicalType":"uuid"}}
      ]}""")
      val output = directory.resolve("output")
      Main.main(Array("--namespace-map", "old=app", "--namespace-map", "old.detail=model",
        "--logical-type", "date=raw", "--logical-type", "uuid=converted", "--decimal-type", "java",
        input.toString, output.toString))
      val parent = Files.readString(output.resolve("app/Parent.scala"))
      val child = Files.readString(output.resolve("model/Child.scala"))
      assert(parent.contains("child: _root_.model.Child"))
      assert(parent.contains("date: _root_.scala.Int"))
      assert(parent.contains("amount: _root_.java.math.BigDecimal"))
      assert(parent.contains("case \"old.detail.Child\" => _root_.model.Child.codec"))
      assert(child.contains("id: _root_.java.util.UUID"))
      assert(!parent.contains("lazy val stackSafeCodec:"))
      assert(!child.contains("lazy val stackSafeCodec:"))
      assert(!Files.exists(output.resolve("old")))
      val config = GeneratorConfig(DecimalType.Java, Map("old" -> "app", "old.detail" -> "model"),
        Map(LogicalType.Date -> LogicalTypeMode.Raw, LogicalType.Uuid -> LogicalTypeMode.Converted))
      val paths = SchemaCompiler.generate(input, output, config)
      val timestamps = paths.map(Files.getLastModifiedTime(_))
      assertEquals(SchemaCompiler.generate(input, output, config), paths)
      assertEquals(paths.map(Files.getLastModifiedTime(_)), timestamps)
    }
  }

  test("CLI opts every cross-file and mutually recursive type in and removes alternatives when disabled") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(input.resolve("a-parent.avsc"), """{"type":"record","name":"Parent","namespace":"original","fields":[
        {"name":"leaf","type":"Leaf"},
        {"name":"peer","type":["null",{"type":"record","name":"Inner","fields":[{"name":"back","type":["null","Parent"]}]}]},
        {"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"}}
      ]}""")
      Files.writeString(input.resolve("z-leaf.avsc"), """{"type":"record","name":"Leaf","namespace":"original","fields":[
        {"name":"date","type":{"type":"int","logicalType":"date"}},
        {"name":"kind","type":{"type":"enum","name":"Kind","symbols":["stackSafeCodec","Other"]}},
        {"name":"token","type":{"type":"fixed","name":"Token","size":4}}
      ]}""")
      val output = directory.resolve("output")
      val options = Array("--namespace-map", "original=models", "--decimal-type", "java", "--logical-type", "date=raw")
      val paths = Vector("Inner", "Kind", "Leaf", "Parent", "Token").map(name => output.resolve(s"models/$name.scala"))
      val arguments = options ++ Array(input.toString, output.toString)
      Main.main(arguments)
      val directSources = paths.map(Files.readString(_))
      assert(directSources.forall(!_.contains("runtime.codegen")))
      assert(directSources(1).contains("case stackSafeCodec\n"))

      // A presence flag works after positionals too; no boolean string is required.
      Main.main(arguments :+ "--generate-stack-safe-codecs")
      val enabledSources = paths.map(Files.readString(_))
      enabledSources.foreach { source =>
        assert(source.contains("lazy val stackSafeCodec:"))
        assertEquals("given codec:".r.findAllIn(source).size, 1)
        val safe = source.substring(source.indexOf("\n  lazy val stackSafeCodec:"))
        assert(safe.contains("DecimalRepresentation.Java"))
        assert(safe.contains("Set(\"date\")"))
      }
      val parent = enabledSources(3)
      val inner = enabledSources(0)
      assert(parent.contains("_root_.models.Leaf.stackSafeCodec.readStep(in)"))
      assert(parent.contains("_root_.models.Inner.stackSafeCodec.readStep(in)"))
      assert(inner.contains("_root_.models.Parent.stackSafeCodec.readStep(in)"))
      for name <- Vector("Inner", "Kind", "Leaf", "Parent", "Token") do
        assert(parent.contains(s"case \"original.$name\" => _root_.models.$name.stackSafeCodec"))
      assert(enabledSources(1).contains("case avro_symbol_stackSafeCodec\n"))

      val config = GeneratorConfig(decimalType = DecimalType.Java, namespaceMappings = Map("original" -> "models"),
        logicalTypes = Map(LogicalType.Date -> LogicalTypeMode.Raw), generateStackSafeCodecs = true)
      val timestamps = paths.map(Files.getLastModifiedTime(_))
      assertEquals(SchemaCompiler.generate(input, output, config), paths)
      assertEquals(paths.map(Files.getLastModifiedTime(_)), timestamps)
      Main.main(arguments)
      assertEquals(paths.map(Files.readString(_)), directSources)
    }
  }

  test("CLI can map the default namespace or strip a named namespace") {
    withDirectory { directory =>
      val input = directory.resolve("Plain.avsc")
      Files.writeString(input, """{"type":"record","name":"Plain","fields":[]}""")
      val named = directory.resolve("named")
      Main.main(Array("--namespace-map", "=app", input.toString, named.toString))
      assert(Files.exists(named.resolve("app/Plain.scala")))
      Files.writeString(input, """{"type":"record","name":"Plain","namespace":"old","fields":[]}""")
      val plain = directory.resolve("plain")
      Main.main(Array("--namespace-map", "old=", input.toString, plain.toString))
      assert(Files.exists(plain.resolve("Plain.scala")))
    }
  }

  test("CLI rejects malformed, duplicated and unknown options before writing anything") {
    withDirectory { directory =>
      val input = directory.resolve("Input.avsc")
      Files.writeString(input, """{"type":"record","name":"Input","fields":[]}""")
      val output = directory.resolve("output")
      val invalid = Vector(
        Vector("--namespace-map", "old"), Vector("--namespace-map", "old=new=extra"),
        Vector("--namespace-map", "../old=new"), Vector("--namespace-map", "old=new..model"),
        Vector("--namespace-map", "old=new", "--namespace-map", "old=other"),
        Vector("--logical-type", "date"), Vector("--logical-type", "date=RAW"),
        Vector("--logical-type", "imaginary=raw"), Vector("--logical-type", "=raw"),
        Vector("--logical-type", "date=raw", "--logical-type", "date=converted"),
        Vector("--decimal-type", "java", "--decimal-type", "scala"), Vector("--unknown"),
        Vector("--generate-stack-safe-codecs", "--generate-stack-safe-codecs"),
        Vector("--generate-stack-safe-codecs=true"), Vector("--generate-stack-safe-codecs", "false")
      )
      for options <- invalid do
        intercept[GenerationException] { Main.main((options ++ Vector(input.toString, output.toString)).toArray) }
        assert(!Files.exists(output), clues(options))
      for flag <- Vector("--namespace-map", "--logical-type", "--decimal-type") do
        val error = intercept[GenerationException] { Main.main(Array(input.toString, output.toString, flag)) }
        assert(error.getMessage.contains("Missing"), clues(flag))
    }
  }

  test("mapping collisions between separate schema files fail before any output is written") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(input.resolve("a.avsc"), """{"type":"record","name":"Clash","namespace":"before","fields":[]}""")
      Files.writeString(input.resolve("b.avsc"), """{"type":"record","name":"Clash","namespace":"after","fields":[]}""")
      val output = directory.resolve("output")
      val error = intercept[GenerationException] {
        SchemaCompiler.generate(input, output, GeneratorConfig(namespaceMappings = Map("before" -> "after")))
      }
      assert(error.getMessage.contains("same Scala source"))
      assert(!Files.exists(output))
    }
  }

  test("type-package collisions across files fail before creating output") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(input.resolve("a.avsc"), """{"type":"record","name":"Item","namespace":"before","fields":[]}""")
      Files.writeString(input.resolve("b.avsc"), """{"type":"record","name":"Child","namespace":"nested","fields":[]}""")
      val output = directory.resolve("output")
      val error = intercept[GenerationException] {
        SchemaCompiler.generate(input, output,
          GeneratorConfig(namespaceMappings = Map("before" -> "model", "nested" -> "model.Item")))
      }
      assert(error.getMessage.contains("model.Item"))
      assert(!Files.exists(output))
    }
  }
