package avro2s.wire.benchmarks.comparison

import _root_.avro2s.wire.fixtures.comparison.*
import _root_.avro2s.wire.runtime.Bytes
import java.nio.ByteBuffer
import java.time.*
import java.util.UUID
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import scala.jdk.CollectionConverters.*

object ComparisonFixtures:
  val profiles = Vector("ints-small", "ints-wide", "longs-mixed", "string-ascii", "string-unicode",
    "bytes", "collections-empty", "collections-full", "enum-fixed", "numerics")

  def apply(profile: String): ComparativeWorkload[?] = profile match
    case "ints-small" | "ints-wide" =>
      val values = Vector.tabulate(1024) { i =>
        if profile == "ints-small" then i % 128 - 64
        else if i % 2 == 0 then Int.MaxValue - i else Int.MinValue + i
      }
      new ComparativeWorkload(ComparisonInts.codec, ComparisonInts(values), s =>
        record(s, "values" -> values.map(Int.box).asJava))
    case "longs-mixed" =>
      val boundaries = (0 to 8).flatMap { width =>
        val edge = 1L << (6 + width * 7)
        Vector(edge - 1, -edge, edge, -edge - 1)
      }.toVector ++ Vector(Long.MinValue, Long.MaxValue)
      val random = new java.util.Random(88219L)
      val values = Vector.fill(1024)(boundaries(random.nextInt(boundaries.size)))
      new ComparativeWorkload(ComparisonLongs.codec, ComparisonLongs(values), s =>
        record(s, "values" -> values.map(Long.box).asJava))
    case "string-ascii" | "string-unicode" =>
      // 1,024 Unicode code points in either profile; UTF-8 byte sizes differ.
      val value = if profile == "string-ascii" then "Avro2026".repeat(128) else "λé漢😀".repeat(256)
      new ComparativeWorkload(ComparisonText.codec, ComparisonText(value), s => record(s, "value" -> value))
    case "bytes" =>
      val value = Array.tabulate[Byte](4096)(i => (i * 73 + 17).toByte)
      new ComparativeWorkload(ComparisonBytes.codec, ComparisonBytes(Bytes.fromArray(value)), s =>
        record(s, "value" -> ByteBuffer.wrap(value)))
    case "collections-empty" | "collections-full" =>
      val count = if profile == "collections-empty" then 0 else 64
      val values = Vector.tabulate(count)(i => 10000 + i)
      val labels = Vector.tabulate(count)(i => s"key-$i" -> s"value-$i-λ").toMap
      new ComparativeWorkload(ComparisonCollections.codec, ComparisonCollections(values, labels), s =>
        record(s, "values" -> values.map(Int.box).asJava, "labels" -> labels.asJava))
    case "enum-fixed" =>
      val bytes = Array.tabulate[Byte](16)(i => (i * 11).toByte)
      val history = Vector.tabulate(32)(i => ComparisonKind.fromOrdinal(i % 3))
      val blocks = Vector.fill(32)(ComparisonFingerprint(Bytes.fromArray(bytes)))
      val expected = ComparisonEnumFixed(ComparisonKind.UPDATED, ComparisonFingerprint(Bytes.fromArray(bytes)), history, blocks)
      new ComparativeWorkload(ComparisonEnumFixed.codec, expected, s =>
        val kind = s.getField("kind").schema()
        val fixed = s.getField("fingerprint").schema()
        record(s, "kind" -> new GenericData.EnumSymbol(kind, "UPDATED"),
          "fingerprint" -> new GenericData.Fixed(fixed, bytes),
          "history" -> Vector.tabulate(32)(i => new GenericData.EnumSymbol(kind, kind.getEnumSymbols.get(i % 3))).asJava,
          "blocks" -> Vector.fill(32)(new GenericData.Fixed(fixed, bytes.clone())).asJava))
    case "numerics" =>
      val flags = Vector.tabulate(128)(_ % 3 == 0)
      val floats = Vector.tabulate(128)(i => if i == 0 then -0.0f else (i - 64).toFloat / 7.0f)
      val doubles = Vector.tabulate(128)(i => if i == 0 then -0.0d else (i - 64).toDouble / 11.0d)
      val value = ComparisonNumerics(true, 1.25f, -123.456d, flags, floats, doubles)
      new ComparativeWorkload(ComparisonNumerics.codec, value, s =>
        record(s, "active" -> Boolean.box(true), "ratio" -> Float.box(1.25f), "price" -> Double.box(-123.456d),
          "flags" -> flags.map(Boolean.box).asJava, "floats" -> floats.map(Float.box).asJava,
          "doubles" -> doubles.map(Double.box).asJava))
    case other => throw new IllegalArgumentException(s"Unknown comparison profile: $other")

  def nested(): ComparativeWorkload[ComparisonNested] =
    def native(depth: Int, id: Long): ComparisonNested =
      ComparisonNested(id, Vector(None, Some(10000), Some(s"node-$id-λ"), Some(ComparisonLeaf(id + 10000))),
        if depth == 0 then Vector.empty else Vector(native(depth - 1, id * 2), native(depth - 1, id * 2 + 1)))
    def generic(s: Schema, depth: Int, id: Long): GenericRecord =
      val leaf = s.getField("choices").schema().getElementType.getTypes.get(3)
      val choices: Vector[AnyRef] = Vector(null, Int.box(10000), s"node-$id-λ", record(leaf, "value" -> Long.box(id + 10000)))
      val children: Vector[GenericRecord] = if depth == 0 then Vector.empty else
        Vector(generic(s, depth - 1, id * 2), generic(s, depth - 1, id * 2 + 1))
      record(s, "id" -> Long.box(id), "choices" -> choices.asJava, "children" -> children.asJava)
    new ComparativeWorkload(ComparisonNested.codec, native(3, 1L), s => generic(s, 3, 1L), supportsCustom = false)

  def logical(): ComparativeWorkload[ComparisonLogical] =
    val day = LocalDate.of(1960, 2, 29)
    val timeMs = LocalTime.of(23, 59, 59, 123000000)
    val timeUs = LocalTime.of(0, 0, 0, 123456000)
    val timestampMs = Instant.ofEpochSecond(-1, 123000000)
    val timestampUs = Instant.ofEpochSecond(-1, 123456000)
    val localMs = LocalDateTime.ofInstant(timestampMs, ZoneOffset.UTC)
    val localUs = LocalDateTime.ofInstant(timestampUs, ZoneOffset.UTC)
    val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val value = ComparisonLogical(day, timeMs, timeUs, timestampMs, timestampUs, localMs, localUs, uuid)
    new ComparativeWorkload(ComparisonLogical.codec, value, s =>
      record(s, "day" -> day, "timeMs" -> timeMs, "timeUs" -> timeUs, "timestampMs" -> timestampMs,
        "timestampUs" -> timestampUs, "localMs" -> localMs, "localUs" -> localUs, "uuid" -> uuid), supportsCustom = false)

  def decimal(): ComparativeWorkload[ComparisonDecimal] =
    val amount = new java.math.BigDecimal("1234567890123456789012345678901234567890.1234567890")
    val fixed = amount.negate()
    // Construct from the exact Java decimal, without a decimal128 arithmetic step.
    val value = ComparisonDecimal(BigDecimal.exact(amount), ComparisonDecimalFixed(BigDecimal.exact(fixed)))
    new ComparativeWorkload(ComparisonDecimal.codec, value, s =>
      record(s, "amount" -> amount, "fixedAmount" -> fixed), supportsAvro2s = false, supportsCustom = false)

  private def record(schema: Schema, fields: (String, AnyRef)*): GenericRecord =
    val value = new GenericData.Record(schema)
    fields.foreach((name, item) => value.put(name, item))
    value
