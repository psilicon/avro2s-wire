package avro2s.wire.properties

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.{ArrayList, LinkedHashMap}
import org.apache.avro.{JsonProperties, Schema}
import org.apache.avro.generic.{GenericData, IndexedRecord}
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import scala.jdk.CollectionConverters.*

/** A compatible pair and independent physical writer datums, never generated Scala values. */
final case class EvolutionCase(writer: Schema, reader: Schema, values: Vector[AnyRef], labels: Set[String])

/**
 * Composes local evolution rules with aliases, field projection, order changes,
 * collection contexts, defaults, and independently generated retained/skipped data.
 * Main-campaign failures are never filtered as allegedly invalid generator output.
 */
object EvolutionCases:
  val rules: Vector[String] = Vector(
    "int-long", "int-float", "int-double", "long-float", "long-double", "float-double",
    "string-bytes", "bytes-string", "enum-reorder", "enum-default", "fixed-alias",
    "union-reorder", "union-promotion", "union-exact-before-promotion", "union-metadata",
    "union-times", "union-named-aliases", "recursive-record", "logical-date", "logical-fixed-alias"
  )
  val contexts: Vector[String] = Vector("direct", "array", "map")
  val defaults: Vector[String] = Vector("primitive", "logical-date", "logical-decimal", "array", "map", "fixed", "record", "nullable")
  val required: Set[String] = rules.map("rule:" + _).toSet ++ contexts.map("context:" + _) ++
    defaults.map("default:" + _) ++ Set("record-alias", "field-alias", "remove-field", "reorder-fields", "recursive-nonterminal")

  private final case class Payload(writer: Schema, reader: Schema, datum: Int => AnyRef)
  private def atom(kind: Schema.Type): Schema = Schema.create(kind)
  private def union(branches: Schema*): Schema = Schema.createUnion(branches.toList.asJava)
  private def field(name: String, schema: Schema): Schema.Field = new Schema.Field(name, schema, null, null.asInstanceOf[AnyRef])
  private def record(name: String, namespace: String, fields: (String, Schema)*): Schema =
    val result = Schema.createRecord(name, null, namespace, false)
    result.setFields(fields.map((name, schema) => field(name, schema)).asJava)
    result
  private def logical(kind: Schema.Type, name: String): Schema =
    val result = atom(kind)
    result.addProp("logicalType", name)
    result
  private def datum(schema: Schema, values: AnyRef*): GenericData.Record =
    val result = new GenericData.Record(schema)
    values.zipWithIndex.foreach((value, index) => result.put(index, value))
    result
  private def ints(index: Int): Int = Vector(-1, 0, 1, 16777217)(Math.floorMod(index, 4))
  private def longs(index: Int): Long = Vector(Long.MinValue, -1L, 0L, 9007199254740993L)(Math.floorMod(index, 4))
  private def strings(index: Int): String = Vector("", "a\u0000b", "café 🚀", "abc" * 50)(Math.floorMod(index, 4))

  private def payload(rule: String, ns: String): Payload =
    import Schema.Type.*
    def numeric(writer: Schema.Type, reader: Schema.Type, value: Int => AnyRef): Payload = Payload(atom(writer), atom(reader), value)
    rule match
      case "int-long" => numeric(INT, LONG, i => Int.box(ints(i)))
      case "int-float" => numeric(INT, FLOAT, i => Int.box(ints(i)))
      case "int-double" => numeric(INT, DOUBLE, i => Int.box(ints(i)))
      case "long-float" => numeric(LONG, FLOAT, i => Long.box(longs(i)))
      case "long-double" => numeric(LONG, DOUBLE, i => Long.box(longs(i)))
      case "float-double" => numeric(FLOAT, DOUBLE, i => Float.box(Vector(-0.0f, Float.MinPositiveValue, 16777216.0f, Float.PositiveInfinity)(Math.floorMod(i, 4))))
      case "string-bytes" => Payload(atom(STRING), atom(BYTES), i => strings(i))
      case "bytes-string" => Payload(atom(BYTES), atom(STRING), i => ByteBuffer.wrap(strings(i).getBytes(UTF_8)))
      case "enum-reorder" | "enum-default" =>
        val writer = Schema.createEnum("State", null, ns, Vector("A", "B", "C").asJava)
        val reader = if rule == "enum-reorder" then Schema.createEnum("State", null, ns, Vector("C", "A", "B").asJava)
          else Schema.createEnum("State", null, ns, Vector("C", "A").asJava, "C")
        Payload(writer, reader, i => new GenericData.EnumSymbol(writer, Vector("A", "B", "C")(Math.floorMod(i, 3))))
      case "fixed-alias" | "logical-fixed-alias" =>
        val size = if rule == "fixed-alias" then 7 else 16
        val writer = Schema.createFixed("OldFixed", null, ns, size)
        val reader = Schema.createFixed("NewFixed", null, ns, size)
        reader.addAlias(writer.getFullName)
        if rule == "logical-fixed-alias" then
          writer.addProp("logicalType", "uuid")
          reader.addProp("logicalType", "uuid")
        Payload(writer, reader, i => new GenericData.Fixed(writer, Array.tabulate[Byte](size)(n => (i * 37 + n * 19).toByte)))
      case "union-reorder" | "union-promotion" =>
        val writer = union(atom(INT), atom(STRING), atom(NULL))
        val reader = if rule == "union-reorder" then union(atom(NULL), atom(STRING), atom(INT))
          else union(atom(STRING), atom(LONG), atom(NULL))
        Payload(writer, reader, i => Math.floorMod(i, 3) match
          case 0 => Int.box(ints(i))
          case 1 => strings(i)
          case _ => null)
      case "union-exact-before-promotion" => Payload(atom(INT), union(atom(LONG), atom(INT)), i => Int.box(ints(i)))
      case "union-metadata" =>
        val changedInt = atom(INT)
        changedInt.addProp("description", "Reader metadata must not change numeric branch identity")
        Payload(union(atom(LONG), atom(INT)), union(atom(LONG), changedInt), i =>
          if i % 2 == 0 then Int.box(ints(i)) else Long.box(longs(i)))
      case "union-times" =>
        val writer = union(logical(LONG, "time-micros"), logical(INT, "time-millis"), atom(NULL))
        val reader = union(atom(NULL), logical(INT, "time-millis"), logical(LONG, "time-micros"))
        Payload(writer, reader, i => Math.floorMod(i, 3) match
          case 0 => Long.box(123456789L + i)
          case 1 => Int.box(123456 + i)
          case _ => null)
      case "union-named-aliases" =>
        val oldA = record("OldA", ns, "value" -> atom(INT))
        val oldB = record("OldB", ns, "text" -> atom(STRING))
        val newA = record("NewA", ns, "value" -> atom(LONG))
        val newB = record("NewB", ns, "text" -> atom(STRING))
        newA.addAlias(oldA.getFullName)
        newB.addAlias(oldB.getFullName)
        Payload(union(oldA, oldB, atom(NULL)), union(newB, atom(NULL), newA), i => Math.floorMod(i, 3) match
          case 0 => datum(oldA, Int.box(ints(i)))
          case 1 => datum(oldB, strings(i))
          case _ => null)
      case "recursive-record" =>
        val writer = Schema.createRecord("Node", null, ns, false)
        writer.setFields(Vector(field("value", atom(INT)), field("next", union(atom(NULL), writer))).asJava)
        val reader = Schema.createRecord("Node", null, ns, false)
        reader.setFields(Vector(field("next", union(atom(NULL), reader)), field("value", atom(LONG)),
          new Schema.Field("added", atom(BOOLEAN), null, Boolean.box(true))).asJava)
        def node(index: Int, depth: Int): GenericData.Record =
          datum(writer, Int.box(ints(index)), if depth == 0 then null else node(index + 1, depth - 1))
        Payload(writer, reader, i => node(i, Math.floorMod(i, 3)))
      case "logical-date" => Payload(atom(INT), logical(INT, "date"), i => Int.box(ints(i)))
      case other => throw new IllegalArgumentException(s"Unknown evolution rule $other")

  private def inContext(inner: Payload, context: String): Payload = context match
    case "direct" => inner
    case "array" => Payload(Schema.createArray(inner.writer), Schema.createArray(inner.reader), i =>
      val values = new ArrayList[AnyRef]()
      if i % 4 != 0 then (0 until 1 + i % 3).foreach(j => values.add(inner.datum(i + j)))
      values)
    case "map" => Payload(Schema.createMap(inner.writer), Schema.createMap(inner.reader), i =>
      val values = new LinkedHashMap[String, AnyRef]()
      if i % 4 != 0 then (0 until 1 + i % 3).foreach(j => values.put(s"key-$j-é", inner.datum(i + j)))
      values)

  private def defaultField(kind: String, ns: String, index: Int): Schema.Field =
    import Schema.Type.*
    val (schema, value): (Schema, AnyRef) = kind match
      case "primitive" => atom(LONG) -> Long.box(-2147483649L)
      case "logical-date" => logical(INT, "date") -> Int.box(-1)
      case "logical-decimal" =>
        val schema = logical(BYTES, "decimal")
        schema.addProp("precision", Int.box(8))
        schema.addProp("scale", Int.box(2))
        schema -> "\u0004Ò"
      case "array" => Schema.createArray(atom(INT)) -> Vector(Int.box(0), Int.box(-1), Int.box(10000)).asJava
      case "map" => Schema.createMap(atom(STRING)) -> Map("first" -> "one", "second" -> "café").asJava
      case "fixed" => Schema.createFixed(s"DefaultFixed$index", null, ns, 3) -> "\u0000ÿ"
      case "record" =>
        val schema = Schema.createRecord(s"DefaultRecord$index", null, ns, false)
        schema.setFields(Vector(field("count", atom(LONG)), new Schema.Field("active", atom(BOOLEAN), null, Boolean.box(true))).asJava)
        schema -> Map[String, AnyRef]("count" -> Long.box(9)).asJava
      case "nullable" => union(atom(NULL), atom(STRING)) -> JsonProperties.NULL_VALUE
    new Schema.Field(s"added$index", schema, null, value)

  private def build(id: String, rule: String, context: String, common: SchemaCase, valueCount: Int,
      aliasRecord: Boolean, aliasField: Boolean, drop: Boolean, order: Int, defaultKinds: Vector[String]): EvolutionCase =
    val ns = s"avro2s.wire.propertyevolution.$id"
    val selected = inContext(payload(rule, ns), context)
    val writer = record("Before", ns, "noise" -> common.schema, "payload" -> selected.writer,
      "sentinel" -> atom(Schema.Type.STRING), "carry" -> common.schema)
    val reader = Schema.createRecord(if aliasRecord then "After" else "Before", null, ns, false)
    if aliasRecord then reader.addAlias(writer.getFullName)
    val changed = field(if aliasField then "changed" else "payload", selected.reader)
    if aliasField then changed.addAlias("payload")
    val retained = (if drop then Vector.empty else Vector(field("noise", common.schema))) ++
      Vector(changed, field("sentinel", atom(Schema.Type.STRING)), field("carry", common.schema)) ++
      defaultKinds.zipWithIndex.map((kind, index) => defaultField(kind, ns, index))
    val reordered = if order % 3 == 1 then retained.reverse
      else if order % 3 == 2 then retained.drop(1) ++ retained.take(1) else retained
    reader.setFields(reordered.asJava)
    val labels = Set(s"rule:$rule", s"context:$context") ++ defaultKinds.map("default:" + _) ++
      Option.when(aliasRecord)("record-alias") ++ Option.when(aliasField)("field-alias") ++
      Option.when(drop)("remove-field") ++ Option.when(order % 3 != 0)("reorder-fields") ++
      Option.when(rule == "recursive-record")("recursive-nonterminal")
    writer.addProp("evolutionSteps", labels.toVector.sorted.mkString(","))
    val values = Vector.tabulate(valueCount) { i =>
      datum(writer, common.values(i % common.values.size), selected.datum(i), s"tail-$i-é",
        common.values((i + 1) % common.values.size))
    }
    EvolutionCase(writer, reader, values, labels)

  lazy val mandatory: Vector[EvolutionCase] = rules.zipWithIndex.flatMap { (rule, index) =>
    Vector.tabulate(2) { variant =>
      val common = SchemaCases.randomCase(1, 4).apply(Gen.Parameters.default, Seed(700000L + index * 2 + variant)).get
      build(s"m${index}v$variant", rule, if variant == 0 then "direct" else contexts(1 + index % 2), common, 4,
        aliasRecord = variant == 1, aliasField = index % 2 == 0, drop = true, order = 1 + index % 2,
        defaultKinds = Vector(defaults((index * 2 + variant) % defaults.size), defaults((index * 2 + variant + 3) % defaults.size)))
    }
  }

  def randomCase(depth: Int, values: Int): Gen[EvolutionCase] = for
    rule <- Gen.oneOf(rules)
    context <- Gen.oneOf(contexts)
    common <- SchemaCases.randomCase(depth, values)
    aliasRecord <- Gen.oneOf(true, false)
    aliasField <- Gen.oneOf(true, false)
    drop <- Gen.oneOf(true, false)
    order <- Gen.choose(0, 2)
    firstDefault <- Gen.choose(0, defaults.size - 1)
    defaultCount <- Gen.choose(0, 3)
  yield
    val defaultKinds = Vector.tabulate(defaultCount)(i => defaults((firstDefault + i) % defaults.size))
    // ScalaCheck deliberately repeats boundary seeds. A random scalar therefore
    // cannot name independently selected schema combinations. Equal complete
    // constructions may share generated classes; different ones must not.
    val signature = Vector(rule, context, common.schema.toString, aliasRecord.toString,
      aliasField.toString, drop.toString, order.toString, defaultKinds.mkString(",")).mkString("\u0000")
    val digest = MessageDigest.getInstance("SHA-256").digest(signature.getBytes(UTF_8))
    val id = "r" + digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    build(id, rule, context, common, values, aliasRecord, aliasField, drop, order, defaultKinds)

  /** Data-only shrinks keep both schemas; structural shrinks project the outer carrier together. */
  def shrink(c: EvolutionCase): LazyList[EvolutionCase] =
    val data = SchemaCases.shrink(SchemaCase(c.writer, c.values))
      .filter(_.schema.toString == c.writer.toString).map(s => c.copy(values = s.values))
    def projected(schema: Schema, keep: Schema.Field => Boolean): Schema =
      val copy = Schema.createRecord(schema.getName, schema.getDoc, schema.getNamespace, schema.isError)
      schema.getAliases.asScala.foreach(copy.addAlias)
      // Field's copy constructor retains the original JSON default. Rebuilding
      // via defaultVal() would turn Avro byte/fixed strings into byte[] and then
      // Jackson base64 strings, changing their values (including decimals).
      val fields = schema.getFields.asScala.filter(keep).map(original => new Schema.Field(original, original.schema))
      copy.setFields(fields.asJava)
      copy
    def remove(writerField: Option[String], readerField: Option[String]): EvolutionCase =
      val writer = projected(c.writer, f => !writerField.contains(f.name))
      val reader = projected(c.reader, f => !readerField.contains(f.name))
      val values = c.values.map { original =>
        val old = original.asInstanceOf[IndexedRecord]
        datum(writer, writer.getFields.asScala.map(f => old.get(c.writer.getField(f.name).pos).asInstanceOf[AnyRef]).toVector*)
      }
      c.copy(writer = writer, reader = reader, values = values)
    val writerFields = c.writer.getFields.asScala.toVector
    val structural = writerFields.map { old =>
      val matched = c.reader.getFields.asScala.find(f => f.name == old.name || f.aliases.contains(old.name))
      remove(Some(old.name), matched.map(_.name))
    } ++ c.reader.getFields.asScala.filter { f =>
      !writerFields.exists(old => f.name == old.name || f.aliases.contains(old.name))
    }.map(f => remove(None, Some(f.name)))
    // Remove irrelevant carrier structure before spending a budget simplifying
    // arbitrary retained/skipped payloads that may disappear altogether.
    LazyList.from(structural) #::: data
