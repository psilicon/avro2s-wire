package avro2s.wire.resolution

import avro2s.wire.runtime.*
import munit.FunSuite

final class DecimalResolutionSuite extends DecimalResolutionChecks(CodecExecution.Direct)
final class StackSafeDecimalResolutionSuite extends DecimalResolutionChecks(CodecExecution.StackSafe)

abstract class DecimalResolutionChecks(executionMode: CodecExecution) extends FunSuite:
  private def readOnly[A](json: String, representation: DecimalRepresentation,
      named: Map[String, AvroCodec[?]] = Map.empty)(build: Array[Any] => A): AvroCodec[A] =
    new AvroCodec[A]:
      override def execution: CodecExecution = executionMode
      def schemaJson: String = json
      override def decimalRepresentation: DecimalRepresentation = representation
      def read(in: AvroInput): A = throw new UnsupportedOperationException()
      def write(value: A, out: AvroOutput): Unit = throw new UnsupportedOperationException()
      override def construct(values: Array[Any]): A = build(values)
      override def namedCodec(name: String): AvroCodec[?] = named.getOrElse(name, super.namedCodec(name))

  test("decimal metadata does not load codecs for unused reader union alternatives") {
    val reader = """[{"type":"bytes","logicalType":"big-decimal"},{"type":"record","name":"Unused","fields":[]}]"""
    val codec = readOnly(reader, DecimalRepresentation.Java)(_(0))
    val out = new BinaryOutput()
    // Inner bytes length=1, unscaled=10, scale=1: 1.0.
    out.writeBytes(Bytes.fromArray(Array[Byte](2, 10, 2)))
    val value = ResolvingReader("\"bytes\"", codec).decode(out.toByteArray)
    assertEquals(value, new java.math.BigDecimal("1.0"))
    assertEquals(value.getClass, classOf[java.math.BigDecimal])
  }

  test("nested named codecs retain their own decimal representation including reader defaults") {
    val childJson = """{"type":"record","name":"Child","fields":[{"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"}},{"name":"added","type":{"type":"bytes","logicalType":"big-decimal"},"default":"\u0002\u000a\u0002"}]}"""
    val writer = """{"type":"record","name":"Root","fields":[{"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"}},{"name":"child","type":{"type":"record","name":"Child","fields":[{"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"}}]}}]}"""
    val reader = s"""{"type":"record","name":"Root","fields":[{"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"}},{"name":"child","type":$childJson}]}"""
    val child = readOnly(childJson, DecimalRepresentation.Java)(_.toVector)
    val root = readOnly(reader, DecimalRepresentation.Scala, Map("Child" -> child))(_.toVector)
    val out = new BinaryOutput()
    out.writeBytes(Bytes.fromArray(Array[Byte](2, 10, 2)))
    out.writeBytes(Bytes.fromArray(Array[Byte](2, 100, 4)))
    val actual = ResolvingReader(writer, root).decode(out.toByteArray)
    assertEquals(actual(0).getClass, classOf[scala.BigDecimal])
    val values = actual(1).asInstanceOf[Vector[Any]]
    assertEquals(values(0).getClass, classOf[java.math.BigDecimal])
    assertEquals(values(0), new java.math.BigDecimal("1.00"))
    assertEquals(values(1), new java.math.BigDecimal("1.0"))
  }

  test("big-decimal resolver rejects invalid storage and malformed defaults") {
    for storage <- Vector("int", "long", "string") do
      val reader = s"""{"type":"$storage","logicalType":"big-decimal"}"""
      intercept[SchemaResolutionException] {
        ResolvingReader("\"bytes\"", readOnly(reader, DecimalRepresentation.Scala)(_(0)))
      }
    val writer = """{"type":"record","name":"R","fields":[]}"""
    val reader = """{"type":"record","name":"R","fields":[{"name":"amount","type":{"type":"bytes","logicalType":"big-decimal"},"default":""}]}"""
    intercept[AvroDecodingException] {
      ResolvingReader(writer, readOnly(reader, DecimalRepresentation.Scala)(_(0)))
    }
  }
