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
        Vector("--root-type-name", "bad..Name"),
        Vector("--root-type-name", "one.Name", "--root-type-name", "two.Name"),
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
      for flag <- Vector("--namespace-map", "--logical-type", "--decimal-type", "--root-type-name") do
        val error = intercept[GenerationException] { Main.main(Array(input.toString, output.toString, flag)) }
        assert(error.getMessage.contains("Missing"), clues(flag))
    }
  }

  test("CLI generates a primitive-only root union with an explicit Scala name") {
    withDirectory { directory =>
      val input = directory.resolve("events-v1.avsc")
      Files.writeString(input, "[\"null\",\"int\",\"string\"]")
      val output = directory.resolve("output")
      Main.main(Array("--root-type-name", "events.Value", "--generate-stack-safe-codecs", input.toString, output.toString))
      val source = Files.readString(output.resolve("events/Value.scala"))
      assert(source.contains("type Value = _root_.scala.Option[_root_.scala.Int | _root_.java.lang.String]"))
      assert(source.contains("lazy val stackSafeCodec:"))
      val paths = SchemaCompiler.generate(input, output, GeneratorConfig(rootTypeName = Some("events.Value"), generateStackSafeCodecs = true))
      val timestamps = paths.map(Files.getLastModifiedTime(_))
      assertEquals(SchemaCompiler.generate(input, output, GeneratorConfig(rootTypeName = Some("events.Value"), generateStackSafeCodecs = true)), paths)
      assertEquals(paths.map(Files.getLastModifiedTime(_)), timestamps)
    }
  }

  test("directory roots infer distinct names from filenames and branch namespaces") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(input.resolve("Events.avsc"), """[
        {"type":"record","name":"source.deep.Created","fields":[]},
        {"type":"record","name":"source.other.Cancelled","fields":[]}
      ]""")
      Files.writeString(input.resolve("Numbers.avsc"), "[\"int\",\"long\"]")
      val output = directory.resolve("output")
      Main.main(Array("--namespace-map", "source=models", "--namespace-map", "=primitives", input.toString, output.toString))
      assert(Files.exists(output.resolve("models/Events.scala")))
      assert(Files.exists(output.resolve("models/deep/Created.scala")))
      assert(Files.exists(output.resolve("models/other/Cancelled.scala")))
      assert(Files.exists(output.resolve("primitives/Numbers.scala")))
    }
  }

  test("an explicit root name can select the only union among named cross-file schemas") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      Files.writeString(input.resolve("a-events.avsc"), "[\"source.Created\",\"string\"]")
      Files.writeString(input.resolve("z-created.avsc"), """{"type":"record","name":"source.Created","fields":[]}""")
      val output = directory.resolve("output")
      val paths = SchemaCompiler.generate(input, output, GeneratorConfig(rootTypeName = Some("events.Event")))
      assertEquals(paths.map(output.relativize(_)).map(_.toString).toSet, Set("events/Event.scala", "source/Created.scala"))
      assert(Files.readString(output.resolve("events/Event.scala")).contains("_root_.source.Created | _root_.java.lang.String"))
    }
  }

  test("ambiguous explicit names invalid filenames and inferred collisions fail before writing") {
    withDirectory { directory =>
      val input = Files.createDirectory(directory.resolve("schemas"))
      val first = input.resolve("first-events.avsc")
      Files.writeString(first, "[\"int\",\"string\"]")
      val output = directory.resolve("output")
      val inferred = intercept[GenerationException](SchemaCompiler.generate(input, output))
      assert(inferred.getMessage.contains("Cannot infer"))
      assert(!Files.exists(output))
      Files.writeString(input.resolve("Second.avsc"), "[\"long\",\"boolean\"]")
      val explicit = intercept[GenerationException](SchemaCompiler.generate(input, output, GeneratorConfig(rootTypeName = Some("events.Event"))))
      assert(explicit.getMessage.contains("exactly one root union"))
      assert(!Files.exists(output))
      Files.delete(first)
      Files.writeString(input.resolve("Record.avsc"), """{"type":"record","name":"Second","fields":[]}""")
      val collision = intercept[GenerationException](SchemaCompiler.generate(input, output))
      assert(collision.getMessage.contains("same Scala source"))
      assert(!Files.exists(output))
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
