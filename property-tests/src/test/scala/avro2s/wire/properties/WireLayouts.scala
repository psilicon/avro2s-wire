package avro2s.wire.properties

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, IndexedRecord}
import org.apache.avro.io.{Encoder, EncoderFactory}
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Independent test writer: Java primitives, independently composed collection blocks. */
object WireLayouts:
  enum Layout:
    case Positive, SizedNegative, Mixed

  final case class Site(path: String, kind: String, offset: Int, length: Int, bound: Long = 0L)
  final case class Image(bytes: Array[Byte], sites: Vector[Site], labels: Set[String])
  final case class Mutation(kind: String, path: String = "$", argument: Long = 0L)
  final case class Resources(stringBytes: Int = 0, bytesLength: Int = 0, collectionItems: Long = 0L, nestingDepth: Int = 0):
    def combine(other: Resources): Resources = Resources(
      math.max(stringBytes, other.stringBytes), math.max(bytesLength, other.bytesLength),
      collectionItems + other.collectionItems, math.max(nestingDepth, other.nestingDepth))

  private def primitive(write: Encoder => Unit): Array[Byte] =
    val stream = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().directBinaryEncoder(stream, null)
    write(encoder)
    encoder.flush()
    stream.toByteArray

  def long(value: Long): Array[Byte] = primitive(_.writeLong(value))
  private def int(value: Int): Array[Byte] = primitive(_.writeInt(value))
  private def raw(value: AnyRef): Array[Byte] = value match
    case fixed: GenericData.Fixed => fixed.bytes().clone()
    case buffer: ByteBuffer =>
      val copy = buffer.duplicate()
      val result = new Array[Byte](copy.remaining())
      copy.get(result)
      result

  private def concat(parts: Vector[Image]): Image =
    val stream = new ByteArrayOutputStream()
    val sites = Vector.newBuilder[Site]
    val labels = Set.newBuilder[String]
    parts.foreach { part =>
      val offset = stream.size()
      part.sites.foreach(site => sites += site.copy(offset = site.offset + offset))
      labels ++= part.labels
      stream.write(part.bytes)
    }
    Image(stream.toByteArray, sites.result(), labels.result())

  private def leaf(bytes: Array[Byte], path: String, kind: String, bound: Long = 0L): Image =
    Image(bytes, Vector(Site(path, kind, 0, bytes.length, bound)), Set.empty)

  def encode(schema: Schema, value: AnyRef, layout: Layout, seed: Long): Image =
    val random = new Random(seed)
    var blockOrdinal = 0
    def collection(items: Vector[Image], path: String, kind: String): Image =
      val blocks = Vector.newBuilder[Image]
      var at = 0
      var block = 0
      while at < items.size do
        val count = 1 + random.nextInt(math.min(3, items.size - at))
        val payload = concat(items.slice(at, at + count))
        val sized = layout match
          case Layout.Positive => false
          case Layout.SizedNegative => true
          case Layout.Mixed => (blockOrdinal & 1) == 0
        blockOrdinal += 1
        val location = s"$path/block$block"
        val header = leaf(long(if sized then -count.toLong else count.toLong), location, "collection-count", count)
        val content = if sized then concat(Vector(header, leaf(long(payload.bytes.length), location, "block-size", payload.bytes.length), payload))
          else concat(Vector(header, payload))
        val labels = Set(s"$kind:${if sized then "negative" else "positive"}") ++
          (if payload.bytes.isEmpty then Set(s"$kind:zero-byte-items") else Set.empty[String])
        blocks += content.copy(labels = content.labels ++ labels)
        at += count
        block += 1
      blocks += leaf(long(0), s"$path/end", "collection-count")
      val result = concat(blocks.result())
      result.copy(labels = result.labels ++ Set(s"$kind:${if items.isEmpty then "empty" else "nonempty"}") ++
        (if block > 1 then Set(s"$kind:multiple-blocks") else Set.empty[String]))
    def visit(schema: Schema, datum: AnyRef, path: String): Image = schema.getType match
      case Schema.Type.RECORD =>
        val record = datum.asInstanceOf[IndexedRecord]
        concat(schema.getFields.asScala.map(f => visit(f.schema(), record.get(f.pos()).asInstanceOf[AnyRef], s"$path/${f.name()}")).toVector)
      case Schema.Type.ARRAY =>
        val values = datum.asInstanceOf[java.util.Collection[AnyRef]].asScala.toVector
        collection(values.zipWithIndex.map((v, i) => visit(schema.getElementType, v, s"$path/item$i")), path, "array")
      case Schema.Type.MAP =>
        // GenericDatumReader may return a different map implementation on replay.
        // Stable key order keeps block boundaries and mutation paths reproducible.
        val values = datum.asInstanceOf[java.util.Map[CharSequence, AnyRef]].asScala.toVector.sortBy(_._1.toString)
        collection(values.zipWithIndex.map { case ((key, v), i) =>
          concat(Vector(leaf(primitive(_.writeString(key.toString)), s"$path/key$i", "string"), visit(schema.getValueType, v, s"$path/value$i")))
        }, path, "map")
      case Schema.Type.UNION =>
        val index = GenericData.get().resolveUnion(schema, datum)
        concat(Vector(leaf(long(index), path, "union-index", schema.getTypes.size()), visit(schema.getTypes.get(index), datum, s"$path/branch$index")))
      case Schema.Type.ENUM => leaf(int(schema.getEnumOrdinal(datum.toString)), path, "enum-index", schema.getEnumSymbols.size())
      case Schema.Type.NULL => Image(Array.emptyByteArray, Vector.empty, Set("zero-byte:null"))
      case Schema.Type.BOOLEAN => leaf(primitive(_.writeBoolean(datum.asInstanceOf[java.lang.Boolean].booleanValue())), path, "boolean")
      case Schema.Type.INT => leaf(int(datum.asInstanceOf[Number].intValue()), path, "int")
      case Schema.Type.LONG => leaf(long(datum.asInstanceOf[Number].longValue()), path, "long")
      case Schema.Type.FLOAT => leaf(primitive(_.writeFloat(datum.asInstanceOf[Number].floatValue())), path, "float")
      case Schema.Type.DOUBLE => leaf(primitive(_.writeDouble(datum.asInstanceOf[Number].doubleValue())), path, "double")
      case Schema.Type.STRING => leaf(primitive(_.writeString(datum.toString)), path, "string")
      case Schema.Type.BYTES => leaf(primitive(_.writeBytes(raw(datum))), path, "bytes")
      case Schema.Type.FIXED => leaf(primitive(_.writeFixed(raw(datum))), path, "fixed")
    visit(schema, value, "$")

  /** These change a known grammar element; arbitrary flips are not assumed invalid. */
  def mutations(image: Image): Vector[Mutation] = image.sites.flatMap { site =>
    val kinds = site.kind match
      case "boolean" => Vector("invalid-boolean")
      case "int" => Vector("int-overflow")
      case "long" => Vector("long-overflow")
      case "string" => Vector("negative-length", "oversize-length", "invalid-utf8")
      case "bytes" => Vector("negative-length", "oversize-length")
      case "enum-index" => Vector("negative-enum-index", "enum-index-out-of-range")
      case "union-index" => Vector("negative-union-index", "union-index-out-of-range")
      case "collection-count" => Vector("collection-count-min")
      case "block-size" => Vector("block-size-short", "block-size-long")
      case _ => Vector.empty
    kinds.map(kind => Mutation(kind, site.path, site.bound))
  }

  def mutate(image: Image, mutation: Mutation): Array[Byte] =
    if mutation.kind == "truncate" then
      require(mutation.argument >= 0 && mutation.argument < image.bytes.length, "Truncation must remain a proper prefix while shrinking")
      image.bytes.take(mutation.argument.toInt)
    else if mutation.kind == "trailing" then image.bytes ++ Array(mutation.argument.toByte)
    else
      val siteKind = mutation.kind match
        case "invalid-boolean" => "boolean"
        case "int-overflow" => "int"
        case "long-overflow" => "long"
        case "negative-enum-index" | "enum-index-out-of-range" => "enum-index"
        case "negative-union-index" | "union-index-out-of-range" => "union-index"
        case "collection-count-min" => "collection-count"
        case "block-size-short" | "block-size-long" => "block-size"
        case "invalid-utf8" => "string"
        case "negative-length" | "oversize-length" => "length"
        case other => throw new IllegalArgumentException(s"Unknown wire mutation: $other")
      val site = image.sites.find(s => s.path == mutation.path && (s.kind == siteKind || (siteKind == "length" && Set("string", "bytes")(s.kind))))
        .getOrElse(throw new IllegalArgumentException(s"Mutation site absent: $mutation"))
      val replacement = mutation.kind match
        case "invalid-boolean" => Array[Byte](2)
        case "int-overflow" => Array[Byte](0x80.toByte, 0x80.toByte, 0x80.toByte, 0x80.toByte, 0x10)
        case "long-overflow" => Array.fill[Byte](9)(0x80.toByte) ++ Array[Byte](2)
        case "negative-length" | "negative-union-index" => long(-1)
        case "oversize-length" => long(Long.MaxValue)
        case "invalid-utf8" => long(1) ++ Array[Byte](0x80.toByte)
        case "negative-enum-index" => int(-1)
        case "enum-index-out-of-range" => int(site.bound.toInt)
        case "union-index-out-of-range" => long(site.bound)
        case "collection-count-min" => long(Long.MinValue)
        case "block-size-short" => long(site.bound - 1)
        case "block-size-long" => long(site.bound + 1)
      image.bytes.take(site.offset) ++ replacement ++ image.bytes.drop(site.offset + site.length)

  /** Independent accounting of the native decoder's documented resource policy. */
  def resources(schema: Schema, value: AnyRef): Resources =
    def visit(schema: Schema, datum: AnyRef, depth: Int): Resources = schema.getType match
      case Schema.Type.RECORD =>
        val record = datum.asInstanceOf[IndexedRecord]
        schema.getFields.asScala.foldLeft(Resources(nestingDepth = depth + 1)) { (sum, field) =>
          sum.combine(visit(field.schema(), record.get(field.pos()).asInstanceOf[AnyRef], depth + 1))
        }
      case Schema.Type.UNION => visit(schema.getTypes.get(GenericData.get().resolveUnion(schema, datum)), datum, depth)
      case Schema.Type.ARRAY =>
        val items = datum.asInstanceOf[java.util.Collection[AnyRef]].asScala
        val nextDepth = depth + (if items.isEmpty then 0 else 1)
        items.foldLeft(Resources(collectionItems = items.size, nestingDepth = nextDepth))((sum, v) => sum.combine(visit(schema.getElementType, v, nextDepth)))
      case Schema.Type.MAP =>
        val items = datum.asInstanceOf[java.util.Map[CharSequence, AnyRef]].asScala
        val nextDepth = depth + (if items.isEmpty then 0 else 1)
        items.foldLeft(Resources(collectionItems = items.size, nestingDepth = nextDepth)) { case (sum, (key, v)) =>
          sum.combine(Resources(stringBytes = key.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)).combine(visit(schema.getValueType, v, nextDepth))
        }
      case Schema.Type.STRING => Resources(stringBytes = datum.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, nestingDepth = depth)
      case Schema.Type.BYTES | Schema.Type.FIXED => Resources(bytesLength = raw(datum).length, nestingDepth = depth)
      case _ => Resources(nestingDepth = depth)
    visit(schema, value, 0)
