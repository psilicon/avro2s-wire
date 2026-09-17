package avrogen.properties

import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.{ArrayList, IdentityHashMap, LinkedHashMap}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.scalacheck.{Arbitrary, Gen}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Values use Java Avro's physical representation, independently of generated Scala models. */
final case class SchemaCase(schema: Schema, values: Vector[AnyRef])

object SchemaCases:
  private val primitives = Vector("null", "boolean", "int", "long", "float", "double", "bytes", "string")
  val atoms: Vector[String] = primitives ++ Vector(
    "enum", "fixed", "date", "time-millis", "time-micros", "timestamp-millis", "timestamp-micros",
    "timestamp-nanos", "local-timestamp-millis", "local-timestamp-micros", "local-timestamp-nanos",
    "uuid-string", "uuid-fixed", "decimal-bytes", "decimal-fixed", "duration"
  )
  // Null cannot be combined with another null branch; its two optional contexts are omitted.
  val contexts: Vector[String] = Vector("direct", "array", "map", "null-first", "null-last", "array-map")
  val matrixLabels: Set[String] = (for
    atom <- atoms
    context <- contexts
    if atom != "null" || !context.startsWith("null-")
  yield s"matrix:$atom:$context").toSet

  private final class Builder(namespace: String):
    private var next = 0
    def name(prefix: String): String =
      next += 1
      s"$prefix$next"
    def record(fields: Vector[(String, Schema)], recordName: String = name("Record")): Schema =
      val result = Schema.createRecord(recordName, null, namespace, false)
      result.setFields(fields.map((n, s) => new Schema.Field(n, s, null, null.asInstanceOf[AnyRef])).asJava)
      result
    private def logical(kind: Schema.Type, logicalName: String): Schema =
      val result = Schema.create(kind)
      result.addProp("logicalType", logicalName)
      result
    private def fixed(size: Int, logicalName: Option[String] = None): Schema =
      val result = Schema.createFixed(name("Fixed"), null, namespace, size)
      logicalName.foreach(result.addProp("logicalType", _))
      result
    def atom(kind: String): Schema = kind match
      case "enum" => Schema.createEnum(name("Enum"), null, namespace, Vector("First", "Second", "Third").asJava)
      case "fixed" => fixed(7)
      case "date" => logical(Schema.Type.INT, kind)
      case "time-millis" => logical(Schema.Type.INT, kind)
      case "time-micros" => logical(Schema.Type.LONG, kind)
      case "timestamp-millis" | "timestamp-micros" | "timestamp-nanos" |
          "local-timestamp-millis" | "local-timestamp-micros" | "local-timestamp-nanos" => logical(Schema.Type.LONG, kind)
      case "uuid-string" => logical(Schema.Type.STRING, "uuid")
      case "uuid-fixed" => fixed(16, Some("uuid"))
      case "duration" => fixed(12, Some("duration"))
      case "decimal-bytes" | "decimal-fixed" =>
        val result = if kind == "decimal-bytes" then logical(Schema.Type.BYTES, "decimal") else fixed(16, Some("decimal"))
        result.addProp("precision", Integer.valueOf(38))
        result.addProp("scale", Integer.valueOf(8))
        result
      case primitive => Schema.create(Schema.Type.valueOf(primitive.toUpperCase(java.util.Locale.ROOT)))
    def inContext(value: Schema, context: String): Schema = context match
      case "direct" => value
      case "array" => Schema.createArray(value)
      case "map" => Schema.createMap(value)
      case "null-first" => union(Vector(atom("null"), value))
      case "null-last" => union(Vector(value, atom("null")))
      case "array-map" => Schema.createArray(Schema.createMap(value))
    private def randomAtom(random: Random): Schema =
      val kind = atoms(random.nextInt(atoms.size))
      kind match
        case "fixed" => fixed(select(Vector(0, 1, 2, 7, 16, 32), random.nextInt(6)))
        case "enum" => Schema.createEnum(name("Enum"), null, namespace, Vector.tabulate(1 + random.nextInt(6))(i => s"Symbol$i").asJava)
        case "decimal-bytes" | "decimal-fixed" =>
          val size = select(Vector(1, 2, 4, 8, 16), random.nextInt(5))
          val maximumPrecision = if kind == "decimal-bytes" then 38 else math.floor((size * 8 - 1) * math.log10(2)).toInt
          val precision = 1 + random.nextInt(maximumPrecision)
          val result = if kind == "decimal-bytes" then logical(Schema.Type.BYTES, "decimal") else fixed(size, Some("decimal"))
          result.addProp("precision", Integer.valueOf(precision))
          result.addProp("scale", Integer.valueOf(random.nextInt(precision + 1)))
          result
        case other => atom(other)
    def generalUnion(depth: Int, random: Random): Schema =
      val choices = random.shuffle(Vector("null", "boolean", "int", "long", "float", "double", "bytes", "string", "enum", "fixed", "array", "map", "record"))
      val branches = choices.take(3 + random.nextInt(choices.size - 2)).map {
        case "array" => Schema.createArray(nested(math.max(0, depth - 1), random))
        case "map" => Schema.createMap(nested(math.max(0, depth - 1), random))
        case "record" => record(Vector("child" -> nested(math.max(0, depth - 1), random)))
        case other => atom(other)
      }
      union(branches)
    def nested(depth: Int, random: Random): Schema =
      if depth <= 0 then randomAtom(random)
      else random.nextInt(12) match
        case 0 | 1 => Schema.createArray(nested(depth - 1, random))
        case 2 | 3 => Schema.createMap(nested(depth - 1, random))
        case 4 | 5 =>
          val value = nested(depth - 1, random)
          if value.getType == Schema.Type.UNION || value.getType == Schema.Type.NULL then value
          else inContext(value, if random.nextBoolean() then "null-first" else "null-last")
        case 6 => record(Vector.tabulate(1 + random.nextInt(3))(i => s"field$i" -> nested(depth - 1, random)))
        case 7 => generalUnion(depth - 1, random)
        case _ => randomAtom(random)

  private def union(branches: Vector[Schema]): Schema = Schema.createUnion(branches.asJava)

  /** Every cell is composed from the same atom and context constructors. */
  lazy val mandatoryCases: Vector[SchemaCase] =
    val matrix = (for
      (atom, atomIndex) <- atoms.zipWithIndex
      (context, contextIndex) <- contexts.zipWithIndex
      if atom != "null" || !context.startsWith("null-")
    yield
      val builder = new Builder(s"avrogen.propertymatrix.a${atomIndex}c$contextIndex")
      val schema = builder.record(Vector("payload" -> builder.inContext(builder.atom(atom), context)), "Root")
      schema.addProp("propertyCase", s"matrix:$atom:$context")
      examples(schema, 12)
    )
    val builder = new Builder("avrogen.propertymatrix.composites")
    val allBranches = primitives.map(builder.atom) ++ Vector(
      builder.atom("enum"), builder.atom("fixed"), builder.record(Vector("count" -> builder.atom("long"))),
      Schema.createArray(builder.atom("int")), Schema.createMap(builder.atom("string"))
    )
    def composite(name: String, payload: Schema, count: Int = 8): SchemaCase =
      val schema = builder.record(Vector("payload" -> payload), name)
      schema.addProp("propertyCase", s"composite:$name")
      examples(schema, count)
    val recursive = Schema.createRecord("Recursive", null, "avrogen.propertymatrix.recursive", false)
    recursive.setFields(Vector(
      new Schema.Field("id", builder.atom("long"), null, null.asInstanceOf[AnyRef]),
      new Schema.Field("next", union(Vector(builder.atom("null"), recursive)), null, null.asInstanceOf[AnyRef])
    ).asJava)
    recursive.addProp("propertyCase", "composite:Recursive")
    matrix ++ Vector(
      composite("AllBranches", union(allBranches), allBranches.size * 2),
      composite("CollectionBranches", Schema.createArray(Schema.createMap(union(allBranches))), allBranches.size * 2),
      composite("AmbiguousTimes", union(Vector(builder.atom("time-millis"), builder.atom("time-micros")))),
      composite("NamedLogicalBranches", union(Vector(builder.atom("uuid-fixed"), builder.atom("decimal-fixed"), builder.atom("duration")))),
      composite("NestedOptions", Schema.createMap(Schema.createArray(union(Vector(builder.atom("null"), builder.atom("string")))))),
      examples(recursive, 8),
      examples(builder.record(Vector.empty, "EmptyRecord"), 2)
    )

  def randomCase(maxDepth: Int, valuesPerSchema: Int): Gen[SchemaCase] =
    require(maxDepth >= 0 && maxDepth <= 5, "Schema depth must be between zero and five")
    require(valuesPerSchema > 0, "At least one value is required")
    for
      schemaSeed <- Arbitrary.arbitrary[Long]
      valueSeeds <- Gen.listOfN(valuesPerSchema, Arbitrary.arbitrary[Long])
    yield
      val random = new Random(schemaSeed)
      val builder = new Builder(s"avrogen.propertyrandom.s${java.lang.Long.toUnsignedString(schemaSeed)}")
      val fields = Vector.tabulate(1 + random.nextInt(4))(i => s"field$i" -> builder.nested(maxDepth, random))
      val schema = new Schema.Parser().parse(builder.record(fields, "Root").toString)
      val values = valueSeeds.zipWithIndex.map((seed, index) => datum(schema, index, new Random(seed), 4, randomized = true)).toVector
      SchemaCase(schema, values)

  private def examples(schema: Schema, count: Int): SchemaCase =
    val parsed = new Schema.Parser().parse(schema.toString)
    SchemaCase(parsed, Vector.tabulate(count)(index => datum(parsed, index, new Random(index.toLong * 7919L + 23L), 4)))

  private val ints = Vector(0, -1, 1, -64, 63, 64, -65, Int.MinValue, Int.MaxValue, 8191, 8192)
  private val longs = Vector(0L, -1L, 1L, -64L, 63L, 64L, -65L, Long.MinValue, Long.MaxValue, 8191L, 8192L)
  private val strings = Vector("", "ascii", "\u0000\u007f", "caf\u00e9", "\u6771\u4eac", "\ud83c\udf0d", "e\u0301", "\u0800\uffff", "a" * 128)
  private def select[A](values: Vector[A], index: Int): A = values(Math.floorMod(index, values.size))
  private def bytes(value: AnyRef): Array[Byte] = value match
    case buffer: ByteBuffer =>
      val copy = buffer.duplicate()
      val result = new Array[Byte](copy.remaining())
      copy.get(result)
      result
    case fixed: GenericData.Fixed => fixed.bytes().clone()

  private def randomString(random: Random): String =
    val result = new java.lang.StringBuilder()
    (0 until random.nextInt(65)).foreach { _ =>
      // Draw from Unicode scalar values, excluding the surrogate interval entirely.
      val point = if random.nextInt(4) == 0 then random.nextInt(128) else
        val scalar = random.nextInt(0x110000 - 0x800)
        if scalar >= 0xd800 then scalar + 0x800 else scalar
      result.appendCodePoint(point)
    }
    result.toString

  private def datum(schema: Schema, ordinal: Int, random: Random, depth: Int, randomized: Boolean = false): AnyRef =
    // Mandatory cases keep their complete edge tables; random campaigns mix those
    // same edges with arbitrary scalar values instead of only randomizing schemas.
    val fresh = randomized && random.nextBoolean()
    val logical = Option(schema.getProp("logicalType"))
    logical match
      case Some("uuid") =>
        val uuid = new java.util.UUID(if ordinal == 0 && !fresh then 0L else random.nextLong(), if ordinal == 0 && !fresh then 0L else random.nextLong())
        if schema.getType == Schema.Type.STRING then uuid.toString
        else new GenericData.Fixed(schema, ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits).putLong(uuid.getLeastSignificantBits).array())
      case Some("decimal") =>
        val precision = schema.getObjectProp("precision").asInstanceOf[Number].intValue()
        val limit = BigInteger.TEN.pow(precision)
        val maximum = limit.subtract(BigInteger.ONE)
        val generated = new BigInteger(limit.bitLength(), new java.util.Random(random.nextLong())).mod(limit)
        val unscaled = if fresh then (if random.nextBoolean() then generated.negate() else generated)
          else select(Vector(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(), maximum, maximum.negate(), generated), ordinal)
        val raw = unscaled.toByteArray
        if schema.getType == Schema.Type.BYTES then ByteBuffer.wrap(raw)
        else
          val padded = Array.fill[Byte](schema.getFixedSize)(if unscaled.signum() < 0 then -1.toByte else 0.toByte)
          System.arraycopy(raw, 0, padded, padded.length - raw.length, raw.length)
          new GenericData.Fixed(schema, padded)
      case Some("time-millis") => Integer.valueOf(if fresh then random.nextInt(86400000) else select(Vector(0, 1, 86399999, 43200000), ordinal))
      case Some("time-micros") => java.lang.Long.valueOf(if fresh then Math.floorMod(random.nextLong(), 86400000000L) else select(Vector(0L, 1L, 86399999999L, 43200000000L), ordinal))
      case _ => schema.getType match
        case Schema.Type.NULL => null
        case Schema.Type.BOOLEAN => java.lang.Boolean.valueOf(if fresh then random.nextBoolean() else (ordinal & 1) != 0)
        case Schema.Type.INT => Integer.valueOf(if fresh || ordinal % 12 == 11 then random.nextInt() else select(ints, ordinal))
        case Schema.Type.LONG => java.lang.Long.valueOf(if fresh || ordinal % 12 == 11 then random.nextLong() else select(longs, ordinal))
        case Schema.Type.FLOAT =>
          val number = if fresh then
            val bits = random.nextInt()
            // Keep NaN payloads, but avoid platform-sensitive signaling NaNs.
            val quiet = if (bits & 0x7f800000) == 0x7f800000 && (bits & 0x007fffff) != 0 then bits | 0x00400000 else bits
            java.lang.Float.intBitsToFloat(quiet)
          else select(Vector(0.0f, -0.0f, 1.25f, Float.MinPositiveValue, Float.MaxValue, Float.PositiveInfinity, Float.NegativeInfinity, Float.NaN, java.lang.Float.intBitsToFloat(0x7fc01234), java.lang.Float.intBitsToFloat(0xffc05678)), ordinal)
          java.lang.Float.valueOf(number)
        case Schema.Type.DOUBLE =>
          val number = if fresh then
            val bits = random.nextLong()
            val quiet = if (bits & 0x7ff0000000000000L) == 0x7ff0000000000000L && (bits & 0x000fffffffffffffL) != 0L then bits | 0x0008000000000000L else bits
            java.lang.Double.longBitsToDouble(quiet)
          else select(Vector(0.0d, -0.0d, 1.25d, Double.MinPositiveValue, Double.MaxValue, Double.PositiveInfinity, Double.NegativeInfinity, Double.NaN, java.lang.Double.longBitsToDouble(0x7ff8000012345678L), java.lang.Double.longBitsToDouble(0xfff8000056781234L)), ordinal)
          java.lang.Double.valueOf(number)
        case Schema.Type.STRING =>
          if fresh then randomString(random)
          else if ordinal % 10 == 9 then
            val result = new java.lang.StringBuilder()
            (0 until random.nextInt(32)).foreach { _ =>
              var point = random.nextInt(0x110000)
              if point >= 0xd800 && point <= 0xdfff then point = 'a'.toInt
              result.appendCodePoint(point)
            }
            result.toString
          else select(strings, ordinal)
        case Schema.Type.BYTES =>
          val result = new Array[Byte](if fresh then random.nextInt(129) else select(Vector(0, 1, 2, 7, 16, 64, 128), ordinal))
          random.nextBytes(result)
          ByteBuffer.wrap(result)
        case Schema.Type.FIXED =>
          val result = new Array[Byte](schema.getFixedSize)
          if fresh || ordinal != 0 then random.nextBytes(result)
          new GenericData.Fixed(schema, result)
        case Schema.Type.ENUM => new GenericData.EnumSymbol(schema, schema.getEnumSymbols.get(if fresh then random.nextInt(schema.getEnumSymbols.size()) else Math.floorMod(ordinal, schema.getEnumSymbols.size())))
        case Schema.Type.RECORD =>
          val result = new GenericData.Record(schema)
          schema.getFields.asScala.zipWithIndex.foreach((field, index) => result.put(field.pos(), datum(field.schema(), ordinal + index, random, depth - 1, randomized)))
          result
        case Schema.Type.ARRAY =>
          val size = if depth < 0 then 0 else if fresh then random.nextInt(5) else select(Vector(0, 1, 2, 4), ordinal)
          val result = new ArrayList[AnyRef](size)
          (0 until size).foreach(i => result.add(datum(schema.getElementType, ordinal + i, random, depth - 1, randomized)))
          result
        case Schema.Type.MAP =>
          val size = if depth < 0 then 0 else if fresh then random.nextInt(5) else select(Vector(0, 1, 2, 4), ordinal)
          val result = new LinkedHashMap[String, AnyRef]()
          (0 until size).foreach { i =>
            val suffix = if fresh then randomString(random) else select(strings, ordinal + i)
            result.put(s"key$i-$suffix", datum(schema.getValueType, ordinal + i, random, depth - 1, randomized))
          }
          result
        case Schema.Type.UNION =>
          val branches = schema.getTypes.asScala.toVector
          val terminal = branches.indexWhere(_.getType == Schema.Type.NULL)
          val index = if depth <= 0 && terminal >= 0 then terminal else if fresh then random.nextInt(branches.size) else Math.floorMod(ordinal, branches.size)
          datum(branches(index), ordinal, random, depth, randomized)

  /** Coverage is determined by the actual schema graph, with cycles visited once. */
  def labels(c: SchemaCase): Set[String] =
    val result = mutable.Set.empty[String]
    Option(c.schema.getProp("propertyCase")).foreach(result.add)
    val visited = new IdentityHashMap[Schema, java.lang.Boolean]()
    val active = new IdentityHashMap[Schema, java.lang.Boolean]()
    def visit(schema: Schema): Unit =
      if visited.containsKey(schema) then
        if active.containsKey(schema) then result += "schema:recursive"
        else if schema.getType == Schema.Type.RECORD then result += "schema:shared-reference"
      else
        visited.put(schema, true)
        active.put(schema, true)
        result += s"type:${schema.getType.getName}"
        Option(schema.getProp("logicalType")).foreach(name => result += s"logical:$name:${schema.getType.getName}")
        schema.getType match
          case Schema.Type.RECORD => schema.getFields.asScala.foreach(f => visit(f.schema()))
          case Schema.Type.ARRAY => result += "context:array"; visit(schema.getElementType)
          case Schema.Type.MAP => result += "context:map"; visit(schema.getValueType)
          case Schema.Type.UNION =>
            val branches = schema.getTypes.asScala.toVector
            if branches.size == 2 && branches.exists(_.getType == Schema.Type.NULL) then
              result += (if branches.head.getType == Schema.Type.NULL then "union:null-first" else "union:null-last")
            else result += "union:general"
            branches.foreach(visit)
          case _ => ()
        active.remove(schema)
    visit(c.schema)
    result.toSet

  private def sameBranch(a: Schema, b: Schema): Boolean =
    a.getType == b.getType && (a.getType match
      case Schema.Type.RECORD | Schema.Type.ENUM | Schema.Type.FIXED => a.getFullName == b.getFullName
      case _ => true)

  private def copyRecord(schema: Schema, original: GenericData.Record, changed: Int, replacement: AnyRef): GenericData.Record =
    val result = new GenericData.Record(schema)
    schema.getFields.asScala.foreach(f => result.put(f.pos(), if f.pos() == changed then replacement else original.get(f.pos())))
    result

  private def shrinkDatum(schema: Schema, value: AnyRef): LazyList[AnyRef] =
    val logical = Option(schema.getProp("logicalType"))
    val candidates: LazyList[AnyRef] = schema.getType match
      case Schema.Type.NULL => LazyList.empty
      case Schema.Type.BOOLEAN => LazyList(java.lang.Boolean.FALSE)
      case Schema.Type.INT => LazyList(Integer.valueOf(0), Integer.valueOf(value.asInstanceOf[Number].intValue() / 2))
      case Schema.Type.LONG => LazyList(java.lang.Long.valueOf(0L), java.lang.Long.valueOf(value.asInstanceOf[Number].longValue() / 2))
      case Schema.Type.FLOAT => LazyList(java.lang.Float.valueOf(0f))
      case Schema.Type.DOUBLE => LazyList(java.lang.Double.valueOf(0d))
      case Schema.Type.STRING if logical.contains("uuid") => LazyList("00000000-0000-0000-0000-000000000000")
      case Schema.Type.STRING =>
        val string = value.toString
        val end = string.offsetByCodePoints(0, string.codePointCount(0, string.length) / 2)
        LazyList("", string.substring(0, end))
      case Schema.Type.BYTES if logical.contains("decimal") => LazyList(ByteBuffer.wrap(Array[Byte](0)))
      case Schema.Type.BYTES =>
        val raw = bytes(value)
        LazyList(ByteBuffer.wrap(Array.emptyByteArray), ByteBuffer.wrap(raw.take(raw.length / 2)))
      case Schema.Type.FIXED => LazyList(new GenericData.Fixed(schema, new Array[Byte](schema.getFixedSize)))
      case Schema.Type.ENUM => LazyList(new GenericData.EnumSymbol(schema, schema.getEnumSymbols.get(0)))
      case Schema.Type.RECORD =>
        val record = value.asInstanceOf[GenericData.Record]
        LazyList.from(schema.getFields.asScala).flatMap { field =>
          shrinkDatum(field.schema(), record.get(field.pos())).take(4).map(copyRecord(schema, record, field.pos(), _))
        }
      case Schema.Type.ARRAY =>
        val items = value.asInstanceOf[java.util.Collection[AnyRef]].asScala.toVector
        def array(items: Vector[AnyRef]): AnyRef = new ArrayList[AnyRef](items.asJava)
        LazyList(array(Vector.empty), array(items.take(items.size / 2))) #:::
          items.headOption.to(LazyList).flatMap(first => shrinkDatum(schema.getElementType, first).take(4).map(v => array(items.updated(0, v))))
      case Schema.Type.MAP =>
        val entries = value.asInstanceOf[java.util.Map[String, AnyRef]].asScala.toVector
        def map(entries: Vector[(String, AnyRef)]): AnyRef =
          val result = new LinkedHashMap[String, AnyRef]()
          entries.foreach((k, v) => result.put(k, v))
          result
        LazyList(map(Vector.empty), map(entries.take(entries.size / 2))) #:::
          entries.headOption.to(LazyList).flatMap((key, first) => shrinkDatum(schema.getValueType, first).take(4).map(v => map(entries.updated(0, key -> v))))
      case Schema.Type.UNION =>
        val branch = schema.getTypes.get(GenericData.get().resolveUnion(schema, value))
        shrinkDatum(branch, value)
    candidates.filter(candidate => candidate != value).distinct

  private enum SchemaEdit:
    case KeepFields(target: Schema, names: Set[String])
    case SelectBranch(target: Schema, branch: Schema)

  /** Rebuild named definitions and references together, including recursive definitions. */
  private def editSchema(original: Schema, edit: SchemaEdit): Schema =
    val copied = new IdentityHashMap[Schema, Schema]()
    def copy(schema: Schema): Schema =
      val existing = copied.get(schema)
      if existing != null then existing
      else edit match
        case SchemaEdit.SelectBranch(target, branch) if schema eq target =>
          val result = copy(branch)
          copied.put(schema, result)
          result
        case _ =>
          val result = schema.getType match
            case Schema.Type.RECORD =>
              val result = Schema.createRecord(schema.getName, schema.getDoc, schema.getNamespace, false)
              copied.put(schema, result)
              val fields = edit match
                case SchemaEdit.KeepFields(target, names) if schema eq target => schema.getFields.asScala.filter(f => names(f.name()))
                case _ => schema.getFields.asScala
              result.setFields(fields.map(f => new Schema.Field(f.name(), copy(f.schema()), f.doc(), null.asInstanceOf[AnyRef])).asJava)
              result
            case Schema.Type.ENUM => Schema.createEnum(schema.getName, schema.getDoc, schema.getNamespace, schema.getEnumSymbols)
            case Schema.Type.FIXED => Schema.createFixed(schema.getName, schema.getDoc, schema.getNamespace, schema.getFixedSize)
            case Schema.Type.ARRAY => Schema.createArray(copy(schema.getElementType))
            case Schema.Type.MAP => Schema.createMap(copy(schema.getValueType))
            case Schema.Type.UNION => union(schema.getTypes.asScala.map(copy).toVector)
            case other => Schema.create(other)
          copied.put(schema, result)
          schema.getObjectProps.asScala.filterNot(_._1 == "propertyCase").foreach((key, value) => result.addProp(key, value))
          result
    copy(original)

  private def migrate(old: Schema, updated: Schema, value: AnyRef): AnyRef =
    if old.getType == Schema.Type.UNION then
      val oldBranch = old.getTypes.get(GenericData.get().resolveUnion(old, value))
      if updated.getType == Schema.Type.UNION then
        val selected = updated.getTypes.asScala.find(sameBranch(oldBranch, _)).get
        migrate(oldBranch, selected, value)
      else if sameBranch(oldBranch, updated) then migrate(oldBranch, updated, value)
      else datum(updated, 0, new Random(0), 0)
    else updated.getType match
      case Schema.Type.RECORD =>
        val original = value.asInstanceOf[GenericData.Record]
        val result = new GenericData.Record(updated)
        updated.getFields.asScala.foreach { field =>
          val previous = old.getField(field.name())
          result.put(field.pos(), migrate(previous.schema(), field.schema(), original.get(previous.pos())))
        }
        result
      case Schema.Type.ARRAY =>
        new ArrayList[AnyRef](value.asInstanceOf[java.util.Collection[AnyRef]].asScala.map(v => migrate(old.getElementType, updated.getElementType, v)).toVector.asJava)
      case Schema.Type.MAP =>
        val result = new LinkedHashMap[String, AnyRef]()
        value.asInstanceOf[java.util.Map[String, AnyRef]].asScala.foreach((k, v) => result.put(k, migrate(old.getValueType, updated.getValueType, v)))
        result
      case Schema.Type.ENUM => new GenericData.EnumSymbol(updated, value.toString)
      case Schema.Type.FIXED => new GenericData.Fixed(updated, bytes(value))
      case Schema.Type.BYTES => ByteBuffer.wrap(bytes(value))
      case _ => value

  /** Shrink data first, then schema/value pairs; all candidates remain valid Avro datums. */
  def shrink(c: SchemaCase): LazyList[SchemaCase] =
    val fewerValues = if c.values.size > 1 then LazyList(c.copy(values = c.values.take(1)), c.copy(values = c.values.take(c.values.size / 2))).distinct else LazyList.empty
    val smallerValues = LazyList.from(c.values.indices).flatMap { index =>
      shrinkDatum(c.schema, c.values(index)).map(value => c.copy(values = c.values.updated(index, value)))
    }
    val edits = mutable.ArrayBuffer.empty[SchemaEdit]
    val visited = new IdentityHashMap[Schema, java.lang.Boolean]()
    def collect(schema: Schema): Unit =
      if !visited.containsKey(schema) then
        visited.put(schema, true)
        schema.getType match
          case Schema.Type.RECORD =>
            val fields = schema.getFields.asScala.toVector
            if fields.nonEmpty then
              edits += SchemaEdit.KeepFields(schema, Set.empty)
              fields.foreach(field => edits += SchemaEdit.KeepFields(schema, fields.map(_.name()).filterNot(_ == field.name()).toSet))
            fields.foreach(f => collect(f.schema()))
          case Schema.Type.ARRAY => collect(schema.getElementType)
          case Schema.Type.MAP => collect(schema.getValueType)
          case Schema.Type.UNION =>
            // Terminal choices cannot create an uninhabitable recursive schema or nested union.
            schema.getTypes.asScala.filter(branch => !Set(Schema.Type.RECORD, Schema.Type.ARRAY, Schema.Type.MAP, Schema.Type.UNION)(branch.getType)).foreach { branch =>
              edits += SchemaEdit.SelectBranch(schema, branch)
            }
            schema.getTypes.asScala.foreach(collect)
          case _ => ()
    collect(c.schema)
    val smallerSchemas = LazyList.from(edits).map { edit =>
      val schema = new Schema.Parser().parse(editSchema(c.schema, edit).toString)
      SchemaCase(schema, c.values.map(migrate(c.schema, schema, _)))
    }
    fewerValues #::: smallerValues #::: smallerSchemas
